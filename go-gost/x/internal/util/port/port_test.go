package port

import (
	"sync"
	"sync/atomic"
	"testing"
)

type fakeTracker struct {
	calls int32
}

func (f *fakeTracker) CloseActiveConns() { atomic.AddInt32(&f.calls, 1) }

func (f *fakeTracker) count() int32 { return atomic.LoadInt32(&f.calls) }

// TestForceClosePortConnectionsClosesTrackers is the regression for the tcpkill
// dependency: an in-flight session must be dropped in-process, with no external
// binary involved.
func TestForceClosePortConnectionsClosesTrackers(t *testing.T) {
	const addr = "0.0.0.0:39101"

	a, b := &fakeTracker{}, &fakeTracker{}
	RegisterConnTracker(addr, a)
	RegisterConnTracker("127.0.0.1:39101", b) // same port, different host
	defer UnregisterConnTracker(addr, a)
	defer UnregisterConnTracker(addr, b)

	if err := ForceClosePortConnections(addr); err != nil {
		t.Fatalf("ForceClosePortConnections: %v", err)
	}

	if got := a.count(); got != 1 {
		t.Fatalf("tracker a closed %d times, want 1", got)
	}
	if got := b.count(); got != 1 {
		t.Fatalf("tracker closed by host variant %d times, want 1", got)
	}
}

// TestForceClosePortConnectionsOtherPortUntouched guards against a sweep that
// hits every service instead of the one on that port.
func TestForceClosePortConnectionsOtherPortUntouched(t *testing.T) {
	tracker := &fakeTracker{}
	RegisterConnTracker("0.0.0.0:39102", tracker)
	defer UnregisterConnTracker("0.0.0.0:39102", tracker)

	if err := ForceClosePortConnections("0.0.0.0:39103"); err != nil {
		t.Fatalf("ForceClosePortConnections: %v", err)
	}
	if got := tracker.count(); got != 0 {
		t.Fatalf("tracker on another port was closed %d times", got)
	}
}

func TestUnregisterConnTrackerStopsTeardown(t *testing.T) {
	const addr = "0.0.0.0:39104"

	tracker := &fakeTracker{}
	RegisterConnTracker(addr, tracker)
	UnregisterConnTracker(addr, tracker)

	if err := ForceClosePortConnections(addr); err != nil {
		t.Fatalf("ForceClosePortConnections: %v", err)
	}
	if got := tracker.count(); got != 0 {
		t.Fatalf("unregistered tracker was closed %d times", got)
	}
}

// TestForceClosePortConnectionsReportsBadAddr replaces the old silent failure:
// an unusable address is now an error the caller can surface.
func TestForceClosePortConnectionsReportsBadAddr(t *testing.T) {
	for _, addr := range []string{"", "localhost", "127.0.0.1:not-a-port"} {
		if err := ForceClosePortConnections(addr); err == nil {
			t.Fatalf("ForceClosePortConnections(%q) returned nil error", addr)
		}
	}
}

// TestTrackerIndexIsRaceFree exercises registration against teardown, which is
// what happens when a service is created or closed while a pause sweeps.
func TestTrackerIndexIsRaceFree(t *testing.T) {
	const addr = "0.0.0.0:39105"

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		tracker := &fakeTracker{}
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 50; j++ {
				RegisterConnTracker(addr, tracker)
				ForceClosePortConnections(addr)
				UnregisterConnTracker(addr, tracker)
			}
		}()
	}
	wg.Wait()

	if err := ForceClosePortConnections(addr); err != nil {
		t.Fatalf("ForceClosePortConnections after race: %v", err)
	}
}
