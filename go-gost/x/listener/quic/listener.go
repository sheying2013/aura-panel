package quic

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"strings"
	"sync"

	"github.com/go-gost/core/limiter"
	"github.com/go-gost/core/listener"
	"github.com/go-gost/core/logger"
	md "github.com/go-gost/core/metadata"
	admission "github.com/go-gost/x/admission/wrapper"
	xnet "github.com/go-gost/x/internal/net"
	quic_util "github.com/go-gost/x/internal/util/quic"
	traffic_limiter "github.com/go-gost/x/limiter/traffic"
	limiter_wrapper "github.com/go-gost/x/limiter/traffic/wrapper"
	metrics "github.com/go-gost/x/metrics/wrapper"
	stats "github.com/go-gost/x/observer/stats/wrapper"
	"github.com/go-gost/x/registry"
	"github.com/quic-go/quic-go"
)

func init() {
	registry.ListenerRegistry().Register("quic", NewListener)
}

// ErrAlreadyInitialized is returned by Init when the listener already owns a
// bound socket. Init used to overwrite ln/conn silently: the previous socket
// stayed bound with nothing left to close it, and its accept loop kept
// reporting errors into the state of the new one.
var ErrAlreadyInitialized = errors.New("listener already initialized")

type quicListener struct {
	// mu guards inited, ln, conn and closed; see the contract documented on Close.
	mu      sync.Mutex
	ln      *quic.EarlyListener
	conn    net.PacketConn // owned by the listener, quic-go never closes it
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
	return &quicListener{
		logger:  options.Logger,
		options: options,
	}
}

func (l *quicListener) Init(md md.Metadata) (err error) {
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

	addr := l.options.Addr
	if _, _, err := net.SplitHostPort(addr); err != nil {
		addr = net.JoinHostPort(strings.Trim(addr, "[]"), "0")
	}

	network := "udp"
	if xnet.IsIPv4(addr) {
		network = "udp4"
	}
	laddr, err := net.ResolveUDPAddr(network, addr)
	if err != nil {
		return
	}

	var conn net.PacketConn
	conn, err = net.ListenUDP(network, laddr)
	if err != nil {
		return
	}
	if l.md.cipherKey != nil {
		conn = quic_util.CipherPacketConn(conn, l.md.cipherKey)
	}

	conn = metrics.WrapPacketConn(l.options.Service, conn)
	conn = stats.WrapPacketConn(conn, l.options.Stats)
	conn = admission.WrapPacketConn(l.options.Admission, conn)
	conn = limiter_wrapper.WrapPacketConn(
		conn,
		l.options.TrafficLimiter,
		traffic_limiter.ServiceLimitKey,
		limiter.ScopeOption(limiter.ScopeService),
		limiter.ServiceOption(l.options.Service),
		limiter.NetworkOption(conn.LocalAddr().Network()),
	)

	config := &quic.Config{
		KeepAlivePeriod:      l.md.keepAlivePeriod,
		HandshakeIdleTimeout: l.md.handshakeTimeout,
		MaxIdleTimeout:       l.md.maxIdleTimeout,
		Versions: []quic.Version{
			quic.Version1,
			quic.Version2,
		},
		MaxIncomingStreams: int64(l.md.maxStreams),
		EnableDatagrams:    l.md.enableDatagram,
	}

	tlsCfg := l.options.TLSConfig
	if tlsCfg == nil {
		tlsCfg = &tls.Config{}
	} else {
		// NextProtos must not leak into the config shared with the handler.
		tlsCfg = tlsCfg.Clone()
	}
	tlsCfg.NextProtos = []string{"h3", "quic/v1"}

	ln, err := quic.ListenEarly(conn, tlsCfg, config)
	if err != nil {
		// Callers do not Close a listener whose Init failed.
		conn.Close()
		return
	}

	l.conn = conn
	l.ln = ln
	l.inited = true
	l.cqueue = make(chan net.Conn, l.md.backlog)
	l.errChan = make(chan error, 1)

	go l.listenLoop(ln)

	return
}

func (l *quicListener) Accept() (conn net.Conn, err error) {
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
//     still binding waits for Init to publish the socket and then releases it;
//     an Init that starts after Close returns listener.ErrClosed. Init never
//     waits for Close, so the two cannot deadlock.
//   - Release order is preserved: ln first, because it stops accepting and
//     tears the live sessions down, then conn, because quic-go only closes
//     sockets it created itself (createdConn) and reads packets from ours. The
//     first error in that order is returned; the second release still runs.
//   - Close is terminal: closed is set before anything is released, so an Init
//     that arrives once Close has started is refused with listener.ErrClosed
//     even when a release below fails and has to be retried.
//   - ln is released even when its Close reports an error: quic-go marks the
//     server closed before returning (baseServer.close sets closeErr first), so
//     a retained handle could only replay that terminal state instead of
//     retrying anything.
//   - conn is kept when its Close fails, so the next Close calls the socket
//     again and reports the fresh outcome instead of a cached error. Such a
//     retry only helps wrappers that can fail transiently: a real UDP socket
//     whose close already happened answers the repeat call with net.ErrClosed,
//     and that is what Close then returns. Once conn is released every further
//     call is a nil-returning no-op.
func (l *quicListener) Close() error {
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

func (l *quicListener) Addr() net.Addr {
	l.mu.Lock()
	ln := l.ln
	l.mu.Unlock()
	if ln == nil {
		return nil
	}
	return ln.Addr()
}

func (l *quicListener) listenLoop(ln *quic.EarlyListener) {
	for {
		ctx := context.Background()
		session, err := ln.Accept(ctx)
		if err != nil {
			l.logger.Error("accept:", err)
			l.errChan <- err
			close(l.errChan)
			return
		}
		go l.mux(ctx, session)
	}
}

func (l *quicListener) mux(ctx context.Context, session quic.Connection) {
	defer session.CloseWithError(0, "closed")

	for {
		stream, err := session.AcceptStream(ctx)
		if err != nil {
			l.logger.Error("accept stream:", err)
			return
		}

		conn := &quicConn{
			Stream: stream,
			laddr:  session.LocalAddr(),
			raddr:  session.RemoteAddr(),
		}
		select {
		case l.cqueue <- conn:
		case <-stream.Context().Done():
			stream.Close()
		default:
			stream.Close()
			l.logger.Warnf("connection queue is full, client %s discarded", session.RemoteAddr())
		}
	}
}
