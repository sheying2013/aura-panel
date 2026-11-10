package service

import (
	"context"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/go-gost/core/handler"
	"github.com/go-gost/core/listener"
	"github.com/go-gost/core/logger"
	"github.com/go-gost/core/metadata"
	xudp "github.com/go-gost/x/internal/net/udp"
)

// wrappedListener adapts a plain net.Listener (the UDP listener returns one) to
// the go-gost listener interface the service expects.
type wrappedListener struct {
	net.Listener
}

func (l wrappedListener) Init(metadata.Metadata) error { return nil }

var _ listener.Listener = wrappedListener{}

// fakeListener hands out pre-built connections, so a test drives the service
// without binding a real port.
type fakeListener struct {
	addr    net.Addr
	conns   chan net.Conn
	closed  chan struct{}
	once    sync.Once
	closes  int32
	accepts int32
}

func newFakeListener() *fakeListener {
	return &fakeListener{
		addr:   &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)},
		conns:  make(chan net.Conn, 16),
		closed: make(chan struct{}),
	}
}

func (l *fakeListener) Init(metadata.Metadata) error { return nil }

func (l *fakeListener) Accept() (net.Conn, error) {
	atomic.AddInt32(&l.accepts, 1)
	select {
	case conn := <-l.conns:
		return conn, nil
	case <-l.closed:
		return nil, net.ErrClosed
	}
}

func (l *fakeListener) Addr() net.Addr { return l.addr }

func (l *fakeListener) Close() error {
	l.once.Do(func() {
		atomic.AddInt32(&l.closes, 1)
		close(l.closed)
	})
	return nil
}

func (l *fakeListener) deliver(conn net.Conn) { l.conns <- conn }

func (l *fakeListener) closeCount() int32 { return atomic.LoadInt32(&l.closes) }

// blockingHandler behaves like a proxy handler: it sits in Read (io.CopyBuffer
// in production) until the connection itself is closed.
type blockingHandler struct {
	started chan struct{}
	ended   chan struct{}
}

func newBlockingHandler() *blockingHandler {
	return &blockingHandler{
		started: make(chan struct{}, 8),
		ended:   make(chan struct{}, 8),
	}
}

func (h *blockingHandler) Init(metadata.Metadata) error { return nil }

func (h *blockingHandler) Handle(ctx context.Context, conn net.Conn, _ ...handler.HandleOption) error {
	h.started <- struct{}{}
	defer func() { h.ended <- struct{}{} }()

	buf := make([]byte, 512)
	for {
		if _, err := conn.Read(buf); err != nil {
			return err
		}
	}
}

func waitSignal(t *testing.T, ch chan struct{}, what string) {
	t.Helper()

	select {
	case <-ch:
	case <-time.After(3 * time.Second):
		t.Fatalf("timed out waiting for %s", what)
	}
}

func newTestService(t *testing.T, name string, ln *fakeListener, h handler.Handler) *defaultService {
	t.Helper()

	s, ok := NewService(name, ln, h, LoggerOption(logger.LoggerGroup())).(*defaultService)
	if !ok {
		t.Fatal("NewService did not return the default service implementation")
	}
	t.Cleanup(func() { s.Close() })
	return s
}

// TestCloseDropsInFlightConnection is the regression for defect B: closing the
// service used to leave the handler blocked in Read forever, so a proxied TCP
// session kept forwarding after the service was paused.
func TestCloseDropsInFlightConnection(t *testing.T) {
	ln := newFakeListener()
	h := newBlockingHandler()
	s := newTestService(t, "close-drops-conn", ln, h)

	go s.Serve()

	server, client := net.Pipe()
	defer client.Close()
	ln.deliver(server)

	waitSignal(t, h.started, "handler to start")

	if err := s.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	waitSignal(t, h.ended, "handler to return after Close")

	// The peer must observe the connection going away, not just the listener.
	client.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := client.Read(make([]byte, 1)); err == nil {
		t.Fatal("client side was left open after Close")
	}
}

// TestCloseActiveConnsKeepsListener mirrors the pause path: connections die but
// the service can still accept.
func TestCloseActiveConnsKeepsListener(t *testing.T) {
	ln := newFakeListener()
	h := newBlockingHandler()
	s := newTestService(t, "close-active-conns", ln, h)

	go s.Serve()

	first, firstPeer := net.Pipe()
	defer firstPeer.Close()
	ln.deliver(first)
	waitSignal(t, h.started, "first handler to start")

	s.CloseActiveConns()
	waitSignal(t, h.ended, "first handler to return")

	second, secondPeer := net.Pipe()
	defer secondPeer.Close()
	ln.deliver(second)
	waitSignal(t, h.started, "second handler to start")

	if got := ln.closeCount(); got != 0 {
		t.Fatalf("CloseActiveConns closed the listener %d times", got)
	}
}

// TestCloseIsIdempotent pins the sync.Once contract: repeated and concurrent
// Close calls must be no-ops, and the listener must be closed exactly once.
func TestCloseIsIdempotent(t *testing.T) {
	ln := newFakeListener()
	h := newBlockingHandler()
	s := newTestService(t, "close-idempotent", ln, h)

	go s.Serve()

	server, client := net.Pipe()
	defer client.Close()
	ln.deliver(server)
	waitSignal(t, h.started, "handler to start")

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := s.Close(); err != nil {
				t.Errorf("Close: %v", err)
			}
		}()
	}
	wg.Wait()

	if err := s.Close(); err != nil {
		t.Fatalf("Close after concurrent close: %v", err)
	}
	if got := ln.closeCount(); got != 1 {
		t.Fatalf("listener closed %d times, want 1", got)
	}
}

// TestTrackConnRefusedAfterClose covers the accept/Close race deterministically:
// a connection arriving after Close must be rejected, not silently tracked and
// leaked.
func TestTrackConnRefusedAfterClose(t *testing.T) {
	ln := newFakeListener()
	s := newTestService(t, "track-after-close", ln, newBlockingHandler())

	if err := s.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	conn, peer := net.Pipe()
	defer peer.Close()
	defer conn.Close()

	if s.trackConn(conn) {
		t.Fatal("trackConn accepted a connection after Close")
	}
}

// TestCloseDropsUDPConversation runs the same teardown over a real UDP listener,
// because the UDP path hands the handler a virtual conn, not a TCP socket.
func TestCloseDropsUDPConversation(t *testing.T) {
	pc, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen udp: %v", err)
	}

	ln := wrappedListener{xudp.NewListener(pc, &xudp.ListenConfig{
		Backlog:        8,
		ReadQueueSize:  8,
		ReadBufferSize: 2048,
		Keepalive:      true,
		TTL:            time.Minute,
		Logger:         logger.LoggerGroup(),
	})}
	h := newBlockingHandler()
	s := NewService("close-drops-udp", ln, h, LoggerOption(logger.LoggerGroup()))

	go s.Serve()
	defer s.Close()

	client, err := net.Dial("udp", pc.LocalAddr().String())
	if err != nil {
		t.Fatalf("dial udp: %v", err)
	}
	defer client.Close()

	if _, err := client.Write([]byte("ping")); err != nil {
		t.Fatalf("write udp: %v", err)
	}
	waitSignal(t, h.started, "udp handler to start")

	if err := s.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	waitSignal(t, h.ended, "udp handler to return after Close")
}
