package port

import (
	"fmt"
	"net"
	"strconv"
	"sync"
)

// ConnTracker is implemented by a running service: CloseActiveConns closes every
// connection that service is currently serving and leaves its listener alone.
type ConnTracker interface {
	CloseActiveConns()
}

// trackers indexes the running services by listening port. It replaces the old
// tcpkill invocation: the binary is absent from most containers, and
// exec'ing it failed silently, so an in-flight TCP session kept forwarding after
// its service was paused.
var (
	trackerMux sync.Mutex
	trackers   = make(map[int]map[ConnTracker]struct{})
)

// RegisterConnTracker lets ForceClosePortConnections reach the connections of a
// running service. addr is the service listen address.
func RegisterConnTracker(addr string, t ConnTracker) {
	p, err := portOf(addr)
	if err != nil || t == nil {
		return
	}

	trackerMux.Lock()
	defer trackerMux.Unlock()

	set := trackers[p]
	if set == nil {
		set = make(map[ConnTracker]struct{}, 1)
		trackers[p] = set
	}
	set[t] = struct{}{}
}

// UnregisterConnTracker drops a service from the index. A service that is gone
// must not be kept reachable, otherwise the index holds it alive.
func UnregisterConnTracker(addr string, t ConnTracker) {
	p, err := portOf(addr)
	if err != nil || t == nil {
		return
	}

	trackerMux.Lock()
	defer trackerMux.Unlock()

	set := trackers[p]
	delete(set, t)
	if len(set) == 0 {
		delete(trackers, p)
	}
}

// ForceClosePortConnections closes the connections currently forwarded on the
// listening port of addr. The teardown happens in-process through the service'
// own connection table; nothing is exec'ed, so a missing tcpkill binary can no
// longer turn this into a silent no-op. The listener is not touched.
func ForceClosePortConnections(addr string) error {
	p, err := portOf(addr)
	if err != nil {
		return err
	}

	trackerMux.Lock()
	targets := make([]ConnTracker, 0, len(trackers[p]))
	for t := range trackers[p] {
		targets = append(targets, t)
	}
	trackerMux.Unlock()

	for _, t := range targets {
		t.CloseActiveConns()
	}
	return nil
}

// portOf extracts the port number of a listen address. The host part is ignored
// on purpose: tcpkill was told "port N" and the service may have been configured
// with "" / "0.0.0.0" / the concrete interface address.
func portOf(addr string) (int, error) {
	if addr == "" {
		return 0, fmt.Errorf("port: empty address")
	}
	_, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return 0, fmt.Errorf("port: parse %q: %w", addr, err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return 0, fmt.Errorf("port: parse %q: %w", addr, err)
	}
	return port, nil
}
