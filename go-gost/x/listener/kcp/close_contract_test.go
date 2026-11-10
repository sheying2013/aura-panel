package kcp

import (
	"errors"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/go-gost/core/listener"
	"github.com/go-gost/core/logger"
	coreMetadata "github.com/go-gost/core/metadata"
	kcp_util "github.com/go-gost/x/internal/util/kcp"
	xmetadata "github.com/go-gost/x/metadata"
	"github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"
)

func loopbackMetadata() coreMetadata.Metadata {
	return xmetadata.NewMetadata(map[string]any{
		"kcp.key":    "gost-listener-loopback",
		"kcp.mtu":    1350,
		"kcp.sndwnd": 128,
		"kcp.rcvwnd": 128,
		"kcp.mode":   "fast",
	})
}

// dialLoopback mirrors the wiring of x/dialer/kcp on top of a real UDP socket
// so the tests exercise the actual KCP handshake, not a stub.
func dialLoopback(t *testing.T, addr string, cfg *kcp_util.Config) (*smux.Session, net.PacketConn) {
	t.Helper()

	pc, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("client socket: %v", err)
	}

	conn, err := kcp.NewConn(addr,
		kcp_util.BlockCrypt(cfg.Key, cfg.Crypt, kcp_util.DefaultSalt),
		cfg.DataShard, cfg.ParityShard, pc)
	if err != nil {
		pc.Close()
		t.Fatalf("kcp.NewConn: %v", err)
	}

	conn.SetStreamMode(true)
	conn.SetWriteDelay(false)
	conn.SetNoDelay(cfg.NoDelay, cfg.Interval, cfg.Resend, cfg.NoCongestion)
	conn.SetWindowSize(cfg.SndWnd, cfg.RcvWnd)
	conn.SetMtu(cfg.MTU)
	conn.SetACKNoDelay(cfg.AckNodelay)

	smuxCfg := smux.DefaultConfig()
	smuxCfg.Version = cfg.SmuxVer
	smuxCfg.MaxReceiveBuffer = cfg.SmuxBuf
	smuxCfg.MaxStreamBuffer = cfg.StreamBuf

	var cc net.Conn = conn
	if !cfg.NoComp {
		cc = kcp_util.CompStreamConn(conn)
	}
	session, err := smux.Client(cc, smuxCfg)
	if err != nil {
		pc.Close()
		t.Fatalf("smux.Client: %v", err)
	}
	return session, pc
}

// Feasibility: a real KCP listener on loopback completes the handshake and
// hands the accepted smux stream to the caller, in both directions.
func TestLoopbackHandshakeAndStream(t *testing.T) {
	l := newTestListener(t, "127.0.0.1:0")
	if err := l.Init(loopbackMetadata()); err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer l.Close()

	client, pc := dialLoopback(t, l.Addr().String(), l.md.config)
	defer pc.Close()
	defer client.Close()

	stream, err := client.OpenStream()
	if err != nil {
		t.Fatalf("open stream: %v", err)
	}
	defer stream.Close()

	type result struct {
		conn net.Conn
		err  error
	}
	accepted := make(chan result, 1)
	go func() {
		conn, err := l.Accept()
		accepted <- result{conn, err}
	}()

	select {
	case r := <-accepted:
		if r.err != nil {
			t.Fatalf("Accept: %v", r.err)
		}
		defer r.conn.Close()

		stream.SetDeadline(time.Now().Add(10 * time.Second))
		r.conn.SetDeadline(time.Now().Add(10 * time.Second))

		if _, err := stream.Write([]byte("ping")); err != nil {
			t.Fatalf("client write: %v", err)
		}
		buf := make([]byte, 4)
		if _, err := io.ReadFull(r.conn, buf); err != nil {
			t.Fatalf("server read: %v", err)
		}
		if string(buf) != "ping" {
			t.Fatalf("server read %q, want %q", buf, "ping")
		}

		if _, err := r.conn.Write([]byte("pong")); err != nil {
			t.Fatalf("server write: %v", err)
		}
		if _, err := io.ReadFull(stream, buf); err != nil {
			t.Fatalf("client read: %v", err)
		}
		if string(buf) != "pong" {
			t.Fatalf("client read %q, want %q", buf, "pong")
		}
	case <-time.After(15 * time.Second):
		t.Fatal("Accept timed out: no stream reached the connection queue")
	}
}

// Close used to read and write l.conn without any synchronisation, so two
// Closes racing each other (or a Close racing Init/Accept) tripped the race
// detector, and every caller past the first closed an already released socket.
func TestConcurrentCloseIsIdempotent(t *testing.T) {
	const (
		iterations = 15
		closers    = 8
	)
	for i := 0; i < iterations; i++ {
		l := newTestListener(t, "127.0.0.1:0")
		if err := l.Init(loopbackMetadata()); err != nil {
			t.Fatalf("Init: %v", err)
		}

		var wg sync.WaitGroup
		errs := make([]error, closers)
		wg.Add(closers)
		for j := 0; j < closers; j++ {
			go func(j int) {
				defer wg.Done()
				errs[j] = l.Close()
			}(j)
		}
		wg.Wait()

		for j, err := range errs {
			if err != nil {
				t.Fatalf("iteration %d: Close #%d = %v, want nil", i, j, err)
			}
		}
		if addr := l.Addr(); addr != nil {
			t.Fatalf("iteration %d: Addr after Close = %v, want nil", i, addr)
		}
		if _, err := l.Accept(); err != listener.ErrClosed {
			t.Fatalf("iteration %d: Accept after Close = %v, want %v", i, err, listener.ErrClosed)
		}
	}
}

// Contract: a Close overlapping Init waits for it and then releases the socket,
// an Init overlapping Close is refused instead of resurrecting a closed
// listener, and neither ordering leaks the UDP socket.
func TestInitAndCloseConcurrently(t *testing.T) {
	for i := 0; i < 15; i++ {
		l := newTestListener(t, "127.0.0.1:0")

		var (
			wg                sync.WaitGroup
			initErr, closeErr error
		)
		wg.Add(2)
		go func() {
			defer wg.Done()
			initErr = l.Init(loopbackMetadata())
		}()
		go func() {
			defer wg.Done()
			closeErr = l.Close()
		}()
		wg.Wait()

		if closeErr != nil {
			t.Fatalf("iteration %d: Close = %v, want nil", i, closeErr)
		}
		if initErr != nil && !errors.Is(initErr, listener.ErrClosed) {
			t.Fatalf("iteration %d: Init = %v, want nil or %v", i, initErr, listener.ErrClosed)
		}
		if err := l.Close(); err != nil {
			t.Fatalf("iteration %d: final Close = %v, want nil", i, err)
		}
		if addr := l.Addr(); addr != nil {
			t.Fatalf("iteration %d: Addr after Close = %v, want nil", i, addr)
		}
	}
}

// Lifecycle: a real loopback session is up, then Close runs with that session
// live. kcp.Listener does not own the socket (ServeConn passes ownConn=false),
// so Close must release it, return cleanly and hand the port back to the OS.
func TestCloseWhileSessionActive(t *testing.T) {
	l := newTestListener(t, "127.0.0.1:0")
	if err := l.Init(loopbackMetadata()); err != nil {
		t.Fatalf("Init: %v", err)
	}
	addr := l.Addr().String()

	client, pc := dialLoopback(t, addr, l.md.config)
	defer pc.Close()
	defer client.Close()

	stream, err := client.OpenStream()
	if err != nil {
		t.Fatalf("open stream: %v", err)
	}
	defer stream.Close()
	if _, err := stream.Write([]byte("ping")); err != nil {
		t.Fatalf("client write: %v", err)
	}

	type result struct {
		conn net.Conn
		err  error
	}
	accepted := make(chan result, 1)
	go func() {
		conn, err := l.Accept()
		accepted <- result{conn, err}
	}()

	select {
	case r := <-accepted:
		if r.err != nil {
			t.Fatalf("Accept: %v", r.err)
		}
		defer r.conn.Close()
	case <-time.After(15 * time.Second):
		t.Fatal("Accept timed out before Close")
	}

	if err := l.Close(); err != nil {
		t.Fatalf("Close with a live session: %v", err)
	}
	if got := l.Addr(); got != nil {
		t.Fatalf("Addr after Close = %v, want nil", got)
	}

	pc2, err := net.ListenPacket("udp", addr)
	if err != nil {
		t.Fatalf("socket still bound on %s after Close: %v", addr, err)
	}
	pc2.Close()
}

var errFakeClose = errors.New("fake close failure")

type failingPacketConn struct {
	net.PacketConn

	mu     sync.Mutex
	closes int
	fails  int
}

func (c *failingPacketConn) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.closes++
	if c.closes <= c.fails {
		return errFakeClose
	}
	return nil
}

func (c *failingPacketConn) closeCount() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.closes
}

// A Close that failed to release the socket must not look finished: the handle
// stays owned so the next Close really retries it, and only once the release
// succeeded does Close degrade into a no-op.
func TestCloseRetriesAfterFailedRelease(t *testing.T) {
	pc := &failingPacketConn{fails: 1}
	l := &kcpListener{conn: pc}

	if err := l.Close(); !errors.Is(err, errFakeClose) {
		t.Fatalf("first Close = %v, want %v", err, errFakeClose)
	}
	if n := pc.closeCount(); n != 1 {
		t.Fatalf("socket Close calls after the failure = %d, want 1", n)
	}
	if err := l.Close(); err != nil {
		t.Fatalf("retry Close = %v, want nil", err)
	}
	if err := l.Close(); err != nil {
		t.Fatalf("third Close = %v, want nil", err)
	}
	if n := pc.closeCount(); n != 2 {
		t.Fatalf("socket Close calls in total = %d, want 2 (the retry must reach the socket)", n)
	}
}

// An Init that starts after Close must be refused instead of reviving the
// listener behind a closed state.
func TestInitAfterCloseIsRefused(t *testing.T) {
	l := newTestListener(t, "127.0.0.1:0")
	if err := l.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if err := l.Init(loopbackMetadata()); !errors.Is(err, listener.ErrClosed) {
		t.Fatalf("Init after Close = %v, want %v", err, listener.ErrClosed)
	}
}

// QA finding: Init on a listener that already owns a bound socket used to
// overwrite ln/conn silently. The first socket stayed bound with nothing left
// to close it, and its accept loop kept reporting into the state of the new
// one. Init must refuse instead, and the one socket it did bind must still be
// the one Close releases.
func TestDuplicateInitIsRefused(t *testing.T) {
	l := newTestListener(t, "127.0.0.1:0")
	if err := l.Init(loopbackMetadata()); err != nil {
		t.Fatalf("Init: %v", err)
	}
	first := l.Addr().String()

	if err := l.Init(loopbackMetadata()); !errors.Is(err, ErrAlreadyInitialized) {
		t.Fatalf("second Init = %v, want %v", err, ErrAlreadyInitialized)
	}
	if got := l.Addr(); got == nil || got.String() != first {
		t.Fatalf("Addr after the refused Init = %v, want %s", got, first)
	}

	if err := l.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	// Exactly one bind happened, so the port must be free again; a socket left
	// behind by an overwritten Init would still hold it.
	pc, err := net.ListenPacket("udp", first)
	if err != nil {
		t.Fatalf("socket %s still bound after Close: %v", first, err)
	}
	pc.Close()
}

// QA finding: Close released what it could and then reset closed to false, so
// a later Init bound a second socket on top of a half-closed listener. Close is
// terminal from its first call, while the handle whose release failed is still
// retried.
func TestInitAfterFailedCloseIsRefused(t *testing.T) {
	pc := &failingPacketConn{fails: 1}
	l := &kcpListener{conn: pc, inited: true, logger: logger.LoggerGroup()}

	if err := l.Close(); !errors.Is(err, errFakeClose) {
		t.Fatalf("first Close = %v, want %v", err, errFakeClose)
	}
	if err := l.Init(loopbackMetadata()); !errors.Is(err, listener.ErrClosed) {
		if err == nil {
			l.Close()
		}
		t.Fatalf("Init after a failed Close = %v, want %v", err, listener.ErrClosed)
	}
	if addr := l.Addr(); addr != nil {
		t.Fatalf("Addr after a failed Close = %v, want nil", addr)
	}
	if err := l.Close(); err != nil {
		t.Fatalf("retry Close = %v, want nil", err)
	}
	if n := pc.closeCount(); n != 2 {
		t.Fatalf("socket Close calls = %d, want 2 (the retry must reach the socket)", n)
	}
	if err := l.Init(loopbackMetadata()); !errors.Is(err, listener.ErrClosed) {
		t.Fatalf("Init after the retry = %v, want %v", err, listener.ErrClosed)
	}
}

// A retried Close reports the fresh outcome of each attempt instead of caching
// the first error, and degrades into a no-op once the socket is released.
func TestCloseRetryReportsFreshOutcome(t *testing.T) {
	pc := &failingPacketConn{fails: 2}
	l := &kcpListener{conn: pc}

	for i := 1; i <= 2; i++ {
		if err := l.Close(); !errors.Is(err, errFakeClose) {
			t.Fatalf("Close #%d = %v, want %v", i, err, errFakeClose)
		}
	}
	if n := pc.closeCount(); n != 2 {
		t.Fatalf("socket Close calls after two failures = %d, want 2", n)
	}
	if err := l.Close(); err != nil {
		t.Fatalf("Close after the socket was released = %v, want nil", err)
	}
	if n := pc.closeCount(); n != 3 {
		t.Fatalf("socket Close calls = %d, want 3 (the successful close is the last attempt)", n)
	}
	if err := l.Close(); err != nil {
		t.Fatalf("Close past the released socket = %v, want nil", err)
	}
	if n := pc.closeCount(); n != 3 {
		t.Fatalf("socket Close calls = %d, want 3 (no further attempt)", n)
	}
	if err := l.Init(loopbackMetadata()); !errors.Is(err, listener.ErrClosed) {
		t.Fatalf("Init after Close = %v, want %v", err, listener.ErrClosed)
	}
}

// kcp.Listener.Close is terminal: the first call closes the die channel and
// every later call answers io.ErrClosedPipe. Close must drop the handle instead
// of retrying it, otherwise the next Close would replace the real failure with
// that terminal error.
func TestCloseDoesNotReplayTerminalListenerError(t *testing.T) {
	l := newTestListener(t, "127.0.0.1:0")
	if err := l.Init(loopbackMetadata()); err != nil {
		t.Fatalf("Init: %v", err)
	}
	ln := l.ln

	if err := l.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if err := ln.Close(); !errors.Is(err, io.ErrClosedPipe) {
		t.Fatalf("second kcp.Listener.Close = %v, want %v", err, io.ErrClosedPipe)
	}
	if err := l.Close(); err != nil {
		t.Fatalf("Close replayed the terminal listener error: %v", err)
	}
}

// Only Close is terminal. An Init that failed before it published a socket -
// here because the address is taken - must stay retryable.
func TestInitRetryAfterFailedBind(t *testing.T) {
	blocker, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("blocker: %v", err)
	}
	addr := blocker.LocalAddr().String()

	l := newTestListener(t, addr)
	if err := l.Init(loopbackMetadata()); err == nil {
		l.Close()
		t.Fatalf("Init succeeded on %s, which the blocker holds", addr)
	}
	blocker.Close()

	if err := l.Init(loopbackMetadata()); err != nil {
		t.Fatalf("Init retry after a failed bind: %v", err)
	}
	if l.Addr() == nil {
		t.Fatal("Addr is nil after the successful retry")
	}
	if err := l.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
}

// Regression, run under -race: Init, a duplicate Init, Close and Accept hitting
// the same listener must leave a closed listener, never a second bind, and no
// data race on ln/conn/inited/closed.
func TestInitCloseAcceptRace(t *testing.T) {
	for i := 0; i < 50; i++ {
		l := newTestListener(t, "127.0.0.1:0")

		var wg sync.WaitGroup
		errs := make([]error, 2)
		wg.Add(4)
		for j := 0; j < 2; j++ {
			go func(j int) {
				defer wg.Done()
				errs[j] = l.Init(loopbackMetadata())
			}(j)
		}
		go func() {
			defer wg.Done()
			_ = l.Close()
		}()
		go func() {
			defer wg.Done()
			_, _ = l.Accept()
		}()
		wg.Wait()

		for j, err := range errs {
			if err != nil && !errors.Is(err, ErrAlreadyInitialized) && !errors.Is(err, listener.ErrClosed) {
				t.Fatalf("iteration %d: Init #%d = %v, want nil, %v or %v", i, j, err, ErrAlreadyInitialized, listener.ErrClosed)
			}
		}
		if err := l.Close(); err != nil {
			t.Fatalf("iteration %d: final Close = %v, want nil", i, err)
		}
		if addr := l.Addr(); addr != nil {
			t.Fatalf("iteration %d: Addr after Close = %v, want nil", i, addr)
		}
		if _, err := l.Accept(); err != listener.ErrClosed {
			t.Fatalf("iteration %d: Accept after Close = %v, want %v", i, err, listener.ErrClosed)
		}
	}
}
