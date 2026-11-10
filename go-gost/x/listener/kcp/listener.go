package kcp

import (
	"errors"
	"net"
	"sync"
	"time"

	"github.com/go-gost/core/limiter"
	"github.com/go-gost/core/listener"
	"github.com/go-gost/core/logger"
	md "github.com/go-gost/core/metadata"
	admission "github.com/go-gost/x/admission/wrapper"
	xnet "github.com/go-gost/x/internal/net"
	kcp_util "github.com/go-gost/x/internal/util/kcp"
	traffic_limiter "github.com/go-gost/x/limiter/traffic"
	limiter_wrapper "github.com/go-gost/x/limiter/traffic/wrapper"
	metrics "github.com/go-gost/x/metrics/wrapper"
	stats "github.com/go-gost/x/observer/stats/wrapper"
	"github.com/go-gost/x/registry"
	"github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"
	"github.com/xtaci/tcpraw"
)

func init() {
	registry.ListenerRegistry().Register("kcp", NewListener)
}

// ErrAlreadyInitialized is returned by Init when the listener already owns a
// bound socket. Init used to overwrite ln/conn silently: the previous socket
// stayed bound with nothing left to close it, and its accept loop kept
// reporting errors into the state of the new one.
var ErrAlreadyInitialized = errors.New("listener already initialized")

type kcpListener struct {
	// mu guards inited, ln, conn and closed; see the contract documented on Close.
	mu      sync.Mutex
	conn    net.PacketConn
	ln      *kcp.Listener
	inited  bool
	closed  bool
	cqueue  chan net.Conn
	errChan chan error
	logger  logger.Logger
	md      metadata
	options listener.Options
}

func NewListener(opts ...listener.Option) listener.Listener {
	options := listener.Options{}
	for _, opt := range opts {
		opt(&options)
	}
	return &kcpListener{
		logger:  options.Logger,
		options: options,
	}
}

func (l *kcpListener) Init(md md.Metadata) (err error) {
	l.mu.Lock()
	defer l.mu.Unlock()

	if l.closed {
		return listener.ErrClosed
	}
	if l.inited {
		return ErrAlreadyInitialized
	}

	if err = l.parseMetadata(md); err != nil {
		return
	}

	config := l.md.config
	config.Init()

	var conn net.PacketConn
	if config.TCP {
		network := "tcp"
		if xnet.IsIPv4(l.options.Addr) {
			network = "tcp4"
		}
		conn, err = tcpraw.Listen(network, l.options.Addr)
	} else {
		network := "udp"
		if xnet.IsIPv4(l.options.Addr) {
			network = "udp4"
		}
		var udpAddr *net.UDPAddr
		udpAddr, err = net.ResolveUDPAddr(network, l.options.Addr)
		if err != nil {
			return
		}
		conn, err = net.ListenUDP(network, udpAddr)
	}
	if err != nil {
		return
	}

	conn = metrics.WrapUDPConn(l.options.Service, conn)
	conn = stats.WrapUDPConn(conn, l.options.Stats)
	conn = admission.WrapUDPConn(l.options.Admission, conn)
	conn = limiter_wrapper.WrapUDPConn(
		conn,
		l.options.TrafficLimiter,
		traffic_limiter.ServiceLimitKey,
		limiter.ScopeOption(limiter.ScopeService),
		limiter.ServiceOption(l.options.Service),
		limiter.NetworkOption(conn.LocalAddr().Network()),
	)

	ln, err := kcp.ServeConn(
		kcp_util.BlockCrypt(config.Key, config.Crypt, kcp_util.DefaultSalt),
		config.DataShard, config.ParityShard, conn)
	if err != nil {
		// l.conn/l.ln are only set once ServeConn succeeded, so the wrapper
		// chain above stays unreachable and has to be released here.
		conn.Close()
		return
	}

	if config.DSCP > 0 {
		if er := ln.SetDSCP(config.DSCP); er != nil {
			l.logger.Warn(er)
		}
	}
	if er := ln.SetReadBuffer(config.SockBuf); er != nil {
		l.logger.Warn(er)
	}
	if er := ln.SetWriteBuffer(config.SockBuf); er != nil {
		l.logger.Warn(er)
	}

	l.ln = ln
	l.conn = conn
	l.inited = true
	l.cqueue = make(chan net.Conn, l.md.backlog)
	l.errChan = make(chan error, 1)

	go l.listenLoop(ln)

	return
}

func (l *kcpListener) Accept() (conn net.Conn, err error) {
	l.mu.Lock()
	ln, cqueue, errChan := l.ln, l.cqueue, l.errChan
	l.mu.Unlock()

	if ln == nil {
		return nil, listener.ErrClosed
	}

	var ok bool
	select {
	case conn = <-cqueue:
		conn = limiter_wrapper.WrapConn(
			conn,
			l.options.TrafficLimiter,
			conn.RemoteAddr().String(),
			limiter.ScopeOption(limiter.ScopeConn),
			limiter.ServiceOption(l.options.Service),
			limiter.NetworkOption(conn.LocalAddr().Network()),
			limiter.SrcOption(conn.RemoteAddr().String()),
		)
	case err, ok = <-errChan:
		if !ok {
			err = listener.ErrClosed
		}
	}
	return
}

// Close stops the accept loop and releases the socket. It is idempotent and
// safe to call concurrently with itself, Init, Accept and Addr:
//
//   - Close and Init are serialised by mu. A Close that arrives while Init is
//     still binding waits for Init to publish the listener and then releases
//     it; an Init that starts after Close returns listener.ErrClosed. Init
//     never waits for Close, so the two cannot deadlock.
//   - Release order is preserved: ln first, so AcceptKCP stops handing out
//     sessions, then conn. kcp.Listener.Close only signals (ServeConn sets
//     ownConn=false), so the socket is ours to close and its error is
//     reported as well instead of being dropped.
//   - Close is terminal: closed is set before anything is released, so an Init
//     that arrives once Close has started is refused with listener.ErrClosed
//     even when a release below fails and has to be retried.
//   - ln is released even when its Close reports an error: kcp closes its die
//     channel before returning and answers every later call with
//     io.ErrClosedPipe, so a retained handle would replace the real failure
//     with that terminal error on the next Close.
//   - conn is kept when its Close fails, so the next Close calls the socket
//     again and reports the fresh outcome instead of a cached error. Once conn
//     is released every further call is a nil-returning no-op.
func (l *kcpListener) Close() error {
	l.mu.Lock()
	defer l.mu.Unlock()

	l.closed = true

	if l.ln == nil && l.conn == nil {
		return nil
	}

	var err error
	if ln := l.ln; ln != nil {
		l.ln = nil
		if er := ln.Close(); er != nil {
			err = er
		}
	}
	if conn := l.conn; conn != nil {
		l.conn = nil
		if er := conn.Close(); er != nil {
			if err == nil {
				err = er
			}
			l.conn = conn
		}
	}
	return err
}

func (l *kcpListener) Addr() net.Addr {
	l.mu.Lock()
	ln := l.ln
	l.mu.Unlock()
	if ln == nil {
		return nil
	}
	return ln.Addr()
}

func (l *kcpListener) listenLoop(ln *kcp.Listener) {
	for {
		conn, err := ln.AcceptKCP()
		if err != nil {
			l.logger.Error("accept:", err)
			l.errChan <- err
			close(l.errChan)
			return
		}

		conn.SetStreamMode(true)
		conn.SetWriteDelay(false)
		conn.SetNoDelay(
			l.md.config.NoDelay,
			l.md.config.Interval,
			l.md.config.Resend,
			l.md.config.NoCongestion,
		)
		conn.SetMtu(l.md.config.MTU)
		conn.SetWindowSize(l.md.config.SndWnd, l.md.config.RcvWnd)
		conn.SetACKNoDelay(l.md.config.AckNodelay)
		go l.mux(conn)
	}
}

func (l *kcpListener) mux(conn net.Conn) {
	defer conn.Close()

	smuxConfig := smux.DefaultConfig()
	smuxConfig.Version = l.md.config.SmuxVer
	smuxConfig.MaxReceiveBuffer = l.md.config.SmuxBuf
	smuxConfig.MaxStreamBuffer = l.md.config.StreamBuf
	if l.md.config.KeepAlive > 0 {
		smuxConfig.KeepAliveInterval = time.Duration(l.md.config.KeepAlive) * time.Second
	}

	if !l.md.config.NoComp {
		conn = kcp_util.CompStreamConn(conn)
	}

	mux, err := smux.Server(conn, smuxConfig)
	if err != nil {
		l.logger.Error(err)
		return
	}
	defer mux.Close()

	for {
		stream, err := mux.AcceptStream()
		if err != nil {
			l.logger.Error("accept stream: ", err)
			return
		}

		select {
		case l.cqueue <- stream:
		case <-stream.GetDieCh():
			stream.Close()
		default:
			stream.Close()
			l.logger.Warnf("connection queue is full, client %s discarded", stream.RemoteAddr())
		}
	}
}
