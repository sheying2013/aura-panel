package quic

import (
	"errors"
	"net"
	"sync"
	"testing"

	"github.com/go-gost/core/listener"
	"github.com/go-gost/core/logger"
	xmetadata "github.com/go-gost/x/metadata"
)

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

// Close used to read and write l.conn without any synchronisation, so two
// Closes racing each other (or a Close racing Init/Accept) tripped the race
// detector, and every caller past the first closed an already released socket.
//
// The raw ICMP socket needs CAP_NET_RAW, so this test drives the socket branch
// with a fake packet conn and stays runnable unprivileged.
func TestConcurrentCloseIsIdempotent(t *testing.T) {
	const closers = 8

	pc := &failingPacketConn{}
	l := &icmpListener{conn: pc}

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
			t.Fatalf("Close #%d = %v, want nil", j, err)
		}
	}
	if n := pc.closeCount(); n != 1 {
		t.Fatalf("socket Close calls = %d, want 1", n)
	}
	if addr := l.Addr(); addr != nil {
		t.Fatalf("Addr after Close = %v, want nil", addr)
	}
	if _, err := l.Accept(); err != listener.ErrClosed {
		t.Fatalf("Accept after Close = %v, want %v", err, listener.ErrClosed)
	}
}

// The unprivileged path is the one that runs without CAP_NET_RAW: Init fails
// and the listener has to stay inert and concurrency safe.
func TestConcurrentCloseAfterFailedInit(t *testing.T) {
	l := newTestListener(t, "127.0.0.1:0", nil)
	err := l.Init(xmetadata.NewMetadata(nil))
	if err == nil {
		t.Log("raw ICMP socket opened, privileged path exercised")
	} else {
		t.Logf("raw ICMP unavailable, as expected without CAP_NET_RAW: %v", err)
	}

	const closers = 8
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

	for j, cerr := range errs {
		if cerr != nil {
			t.Fatalf("Close #%d = %v, want nil", j, cerr)
		}
	}
	if addr := l.Addr(); addr != nil {
		t.Fatalf("Addr after Close = %v, want nil", addr)
	}
	if _, aerr := l.Accept(); aerr != listener.ErrClosed {
		t.Fatalf("Accept after Close = %v, want %v", aerr, listener.ErrClosed)
	}
}

// A Close that failed to release the socket must not look finished: the handle
// stays owned so the next Close really retries it, and only once the release
// succeeded does Close degrade into a no-op.
func TestCloseRetriesAfterFailedRelease(t *testing.T) {
	pc := &failingPacketConn{fails: 1}
	l := &icmpListener{conn: pc}

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
	l := newTestListener(t, "127.0.0.1:0", nil)
	if err := l.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if err := l.Init(xmetadata.NewMetadata(nil)); !errors.Is(err, listener.ErrClosed) {
		t.Fatalf("Init after Close = %v, want %v", err, listener.ErrClosed)
	}
}

// QA finding: Init on a listener that already owns a bound socket used to
// overwrite conn silently, leaving the first raw socket bound with nothing left
// to close it. The raw socket needs CAP_NET_RAW, so the published state is
// injected and Init must be refused before it touches anything.
func TestDuplicateInitIsRefused(t *testing.T) {
	pc := &failingPacketConn{}
	l := &icmpListener{conn: pc, inited: true, logger: logger.LoggerGroup()}

	if err := l.Init(xmetadata.NewMetadata(nil)); !errors.Is(err, ErrAlreadyInitialized) {
		t.Fatalf("Init on an initialized listener = %v, want %v", err, ErrAlreadyInitialized)
	}
	if l.conn != pc {
		t.Fatal("the refused Init replaced the owned socket")
	}
	if n := pc.closeCount(); n != 0 {
		t.Fatalf("socket Close calls = %d, want 0", n)
	}
	if err := l.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if n := pc.closeCount(); n != 1 {
		t.Fatalf("socket Close calls after Close = %d, want 1", n)
	}
}

// QA finding: Close released what it could and then reset closed to false, so a
// later Init bound a second socket on top of a half-closed listener. Close is
// terminal from its first call, while the handle whose release failed is still
// retried.
func TestInitAfterFailedCloseIsRefused(t *testing.T) {
	pc := &failingPacketConn{fails: 1}
	l := &icmpListener{conn: pc, inited: true, logger: logger.LoggerGroup()}

	if err := l.Close(); !errors.Is(err, errFakeClose) {
		t.Fatalf("first Close = %v, want %v", err, errFakeClose)
	}
	if err := l.Init(xmetadata.NewMetadata(nil)); !errors.Is(err, listener.ErrClosed) {
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
	l := &icmpListener{conn: pc}

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
}

// Regression, run under -race: Init, a duplicate Init, Close and Accept hitting
// the same listener must leave a closed listener and no data race on
// conn/inited/closed.
func TestInitCloseAcceptRace(t *testing.T) {
	for i := 0; i < 50; i++ {
		l := &icmpListener{inited: true, logger: logger.LoggerGroup()}

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
