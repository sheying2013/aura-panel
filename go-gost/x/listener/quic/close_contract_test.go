package quic

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/go-gost/core/listener"
	"github.com/go-gost/core/logger"
	xmetadata "github.com/go-gost/x/metadata"
	qgo "github.com/quic-go/quic-go"
)

// Close used to read and write l.conn without any synchronisation, so two
// Closes racing each other (or a Close racing Init/Accept) tripped the race
// detector, and every caller past the first closed an already released socket.
func TestConcurrentCloseIsIdempotent(t *testing.T) {
	const (
		iterations = 20
		closers    = 8
	)
	for i := 0; i < iterations; i++ {
		l := newTestListener(t, "127.0.0.1:0", nil)
		if err := l.Init(xmetadata.NewMetadata(nil)); err != nil {
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
	for i := 0; i < 20; i++ {
		l := newTestListener(t, "127.0.0.1:0", nil)

		var (
			wg                sync.WaitGroup
			initErr, closeErr error
		)
		wg.Add(2)
		go func() {
			defer wg.Done()
			initErr = l.Init(xmetadata.NewMetadata(nil))
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
	l := &quicListener{conn: pc}

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

// Accept parked on an empty queue has to return once Close stops the listener.
func TestAcceptUnblocksOnClose(t *testing.T) {
	l := newTestListener(t, "127.0.0.1:0", nil)
	if err := l.Init(xmetadata.NewMetadata(nil)); err != nil {
		t.Fatalf("Init: %v", err)
	}

	done := make(chan error, 1)
	go func() {
		_, err := l.Accept()
		done <- err
	}()
	time.Sleep(50 * time.Millisecond)

	if err := l.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("Accept returned a nil error after Close")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Accept did not return after Close")
	}
}

// Lifecycle: a real loopback session is up, then Close runs with that session
// live. It must tear the session down, return cleanly and hand the UDP port
// back to the OS.
func TestCloseWhileSessionActive(t *testing.T) {
	l := newTestListener(t, "127.0.0.1:0", &tls.Config{
		Certificates: []tls.Certificate{testCertificate(t)},
	})
	if err := l.Init(xmetadata.NewMetadata(nil)); err != nil {
		t.Fatalf("Init: %v", err)
	}
	addr := l.Addr().String()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	client, err := qgo.DialAddr(ctx, addr, &tls.Config{
		InsecureSkipVerify: true,
		NextProtos:         []string{"quic/v1"},
	}, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer client.CloseWithError(0, "")

	stream, err := client.OpenStreamSync(ctx)
	if err != nil {
		t.Fatalf("open stream: %v", err)
	}
	if _, err := stream.Write([]byte("ping")); err != nil {
		t.Fatalf("write: %v", err)
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
	case <-ctx.Done():
		t.Fatal("Accept timed out")
	}

	if err := l.Close(); err != nil {
		t.Fatalf("Close with a live session: %v", err)
	}
	if got := l.Addr(); got != nil {
		t.Fatalf("Addr after Close = %v, want nil", got)
	}

	pc, err := net.ListenPacket("udp", addr)
	if err != nil {
		t.Fatalf("socket still bound on %s after Close: %v", addr, err)
	}
	pc.Close()
}

// QA finding: Init on a listener that already owns a bound socket used to
// overwrite ln/conn silently. The first socket stayed bound with nothing left
// to close it, and its accept loop kept reporting into the state of the new
// one. Init must refuse instead, and the one socket it did bind must still be
// the one Close releases.
func TestDuplicateInitIsRefused(t *testing.T) {
	l := newTestListener(t, "127.0.0.1:0", nil)
	if err := l.Init(xmetadata.NewMetadata(nil)); err != nil {
		t.Fatalf("Init: %v", err)
	}
	first := l.Addr().String()

	if err := l.Init(xmetadata.NewMetadata(nil)); !errors.Is(err, ErrAlreadyInitialized) {
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
	l := &quicListener{conn: pc, inited: true, logger: logger.LoggerGroup()}

	if err := l.Close(); !errors.Is(err, errFakeClose) {
		t.Fatalf("first Close = %v, want %v", err, errFakeClose)
	}
	if err := l.Init(xmetadata.NewMetadata(nil)); !errors.Is(err, listener.ErrClosed) {
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
	if err := l.Init(xmetadata.NewMetadata(nil)); !errors.Is(err, listener.ErrClosed) {
		t.Fatalf("Init after the retry = %v, want %v", err, listener.ErrClosed)
	}
}

// A retried Close reports the fresh outcome of each attempt instead of caching
// the first error, and degrades into a no-op once the socket is released.
func TestCloseRetryReportsFreshOutcome(t *testing.T) {
	pc := &failingPacketConn{fails: 2}
	l := &quicListener{conn: pc}

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
	if err := l.Init(xmetadata.NewMetadata(nil)); !errors.Is(err, listener.ErrClosed) {
		t.Fatalf("Init after Close = %v, want %v", err, listener.ErrClosed)
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

	l := newTestListener(t, addr, nil)
	if err := l.Init(xmetadata.NewMetadata(nil)); err == nil {
		l.Close()
		t.Fatalf("Init succeeded on %s, which the blocker holds", addr)
	}
	blocker.Close()

	if err := l.Init(xmetadata.NewMetadata(nil)); err != nil {
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
		l := newTestListener(t, "127.0.0.1:0", nil)

		var wg sync.WaitGroup
		errs := make([]error, 2)
		wg.Add(4)
		for j := 0; j < 2; j++ {
			go func(j int) {
				defer wg.Done()
				errs[j] = l.Init(xmetadata.NewMetadata(nil))
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
