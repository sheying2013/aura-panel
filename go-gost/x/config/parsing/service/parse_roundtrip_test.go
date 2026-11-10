package service

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/go-gost/core/handler"
	"github.com/go-gost/core/listener"
	"github.com/go-gost/core/logger"
	"github.com/go-gost/core/metadata"
	"github.com/go-gost/x/config"
	_ "github.com/go-gost/x/listener/tcp"
	"github.com/go-gost/x/registry"
)

// These tests bind real loopback ports: a listener that Init created must not
// survive a failed ParseService, and it must survive a successful one.

var errStubInit = errors.New("stub handler init failed")

type stubHandler struct {
	initErr error
}

func (h *stubHandler) Init(md metadata.Metadata) error { return h.initErr }

func (h *stubHandler) Handle(ctx context.Context, conn net.Conn, opts ...handler.HandleOption) error {
	return conn.Close()
}

func newStubHandler(opts ...handler.Option) handler.Handler {
	return &stubHandler{}
}

func newStubInitErrorHandler(opts ...handler.Option) handler.Handler {
	return &stubHandler{initErr: errStubInit}
}

// HandlerRegistry().Register exits the process when a name is registered twice,
// so the stubs are registered once for the whole test binary.
func init() {
	logger.SetDefault(logger.LoggerGroup())
	registry.HandlerRegistry().Register("parse-test-stub", newStubHandler)
	registry.HandlerRegistry().Register("parse-test-init-error", newStubInitErrorHandler)
	registry.ListenerRegistry().Register("parse-test-probe", newProbeListener)
}

// probeListener records every Close ParseService performs on it. It is handed
// out by the listener registry, so a test decides its behaviour by setting
// probeNext before calling ParseService.
type probeListener struct {
	initErr    error
	panicClose bool
	closes     int
}

var probeNext *probeListener

func newProbeListener(opts ...listener.Option) listener.Listener {
	if probeNext == nil {
		probeNext = &probeListener{}
	}
	return probeNext
}

func (l *probeListener) Init(md metadata.Metadata) error { return l.initErr }

func (l *probeListener) Accept() (net.Conn, error) { return nil, listener.ErrClosed }

func (l *probeListener) Addr() net.Addr { return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)} }

func (l *probeListener) Close() error {
	l.closes++
	if l.panicClose {
		panic("probe listener has no socket to close")
	}
	return nil
}

// TestParseServiceReleasesListenerOnPostInitError fails while ParseService
// leaves the listener of a failed parse open: the port stays bound.
func TestParseServiceReleasesListenerOnPostInitError(t *testing.T) {
	addr := freeLoopbackAddr(t)

	// The listener is created and bound by Init, then the handler lookup fails.
	svc, err := ParseService(&config.ServiceConfig{
		Name:     "parse-test-post-init",
		Addr:     addr,
		Listener: &config.ListenerConfig{Type: "tcp"},
		Handler:  &config.HandlerConfig{Type: "parse-test-missing"},
	})
	if err == nil {
		svc.Close()
		t.Fatal("ParseService succeeded with an unregistered handler type")
	}

	assertAddrFree(t, addr)
}

// TestParseServiceReleasesListenerOnHandlerInitError covers a handler that is
// found but fails to initialize, which happens after the listener is bound.
func TestParseServiceReleasesListenerOnHandlerInitError(t *testing.T) {
	addr := freeLoopbackAddr(t)

	svc, err := ParseService(&config.ServiceConfig{
		Name:     "parse-test-handler-init",
		Addr:     addr,
		Listener: &config.ListenerConfig{Type: "tcp"},
		Handler:  &config.HandlerConfig{Type: "parse-test-init-error"},
	})
	if err == nil {
		svc.Close()
		t.Fatal("ParseService succeeded with a handler whose Init failed")
	}

	assertAddrFree(t, addr)
}

// TestParseServiceReleasesListenerOnHandlerTLSError covers the handler TLS
// step, which runs after the listener is bound and before the handler exists.
func TestParseServiceReleasesListenerOnHandlerTLSError(t *testing.T) {
	addr := freeLoopbackAddr(t)

	svc, err := ParseService(&config.ServiceConfig{
		Name:     "parse-test-handler-tls",
		Addr:     addr,
		Listener: &config.ListenerConfig{Type: "tcp"},
		Handler: &config.HandlerConfig{
			Type: "parse-test-stub",
			TLS: &config.TLSConfig{
				CertFile: "missing-cert.pem",
				KeyFile:  "missing-key.pem",
			},
		},
	})
	if err == nil {
		svc.Close()
		t.Fatal("ParseService succeeded with an unreadable handler certificate")
	}

	assertAddrFree(t, addr)
}

// TestParseServiceKeepsListenerOnSuccess guards the other direction: the
// listener belongs to the returned service and must still be listening.
func TestParseServiceKeepsListenerOnSuccess(t *testing.T) {
	addr := freeLoopbackAddr(t)

	svc, err := ParseService(&config.ServiceConfig{
		Name:     "parse-test-success",
		Addr:     addr,
		Listener: &config.ListenerConfig{Type: "tcp"},
		Handler:  &config.HandlerConfig{Type: "parse-test-stub"},
	})
	if err != nil {
		t.Fatalf("ParseService: %v", err)
	}

	conn, err := net.DialTimeout("tcp4", addr, time.Second)
	if err != nil {
		svc.Close()
		t.Fatalf("listener was released on the success path: %v", err)
	}
	conn.Close()

	if err := svc.Close(); err != nil {
		t.Fatalf("close service: %v", err)
	}
	assertAddrFree(t, addr)
}

// TestParseServiceInitFailureDoesNotPanic covers Init failing before the
// listener holds a socket (address in use here): releasing it must not panic,
// and the listener that owns the address must be left alone.
func TestParseServiceInitFailureDoesNotPanic(t *testing.T) {
	addr := freeLoopbackAddr(t)

	occupied, err := net.Listen("tcp4", addr)
	if err != nil {
		t.Fatalf("occupy %s: %v", addr, err)
	}
	defer occupied.Close()

	svc, err := ParseService(&config.ServiceConfig{
		Name:     "parse-test-init-failure",
		Addr:     addr,
		Listener: &config.ListenerConfig{Type: "tcp"},
		Handler:  &config.HandlerConfig{Type: "parse-test-stub"},
	})
	if err == nil {
		svc.Close()
		t.Fatal("ParseService succeeded on an address that is already in use")
	}

	conn, err := net.DialTimeout("tcp4", addr, time.Second)
	if err != nil {
		t.Fatalf("listener of another owner was closed: %v", err)
	}
	conn.Close()
}

func freeLoopbackAddr(t *testing.T) string {
	t.Helper()

	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("reserve loopback address: %v", err)
	}
	addr := ln.Addr().String()
	if err := ln.Close(); err != nil {
		t.Fatalf("release loopback address: %v", err)
	}
	return addr
}

func assertAddrFree(t *testing.T, addr string) {
	t.Helper()

	deadline := time.Now().Add(2 * time.Second)
	for {
		ln, err := net.Listen("tcp4", addr)
		if err == nil {
			ln.Close()
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("listener leaked: %s is still bound after ParseService returned: %v", addr, err)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func setProbe(t *testing.T, p *probeListener) {
	t.Helper()

	probeNext = p
	t.Cleanup(func() { probeNext = nil })
}

// TestParseServiceDoesNotCloseListenerWhoseInitFailed pins the ownership rule:
// a listener that failed to initialize owns no socket, so it must be left
// alone. The probe panics from Close, exactly like the stock listeners do when
// their socket field was never set, and it counts the attempt first so the test
// reports the ownership violation instead of the swallowed panic.
func TestParseServiceDoesNotCloseListenerWhoseInitFailed(t *testing.T) {
	probe := &probeListener{initErr: errStubInit, panicClose: true}
	setProbe(t, probe)

	svc, err := ParseService(&config.ServiceConfig{
		Name:     "parse-test-probe-init-error",
		Addr:     "127.0.0.1:0",
		Listener: &config.ListenerConfig{Type: "parse-test-probe"},
		Handler:  &config.HandlerConfig{Type: "parse-test-stub"},
	})
	if err == nil {
		svc.Close()
		t.Fatal("ParseService succeeded with a listener whose Init failed")
	}
	if probe.closes != 0 {
		t.Fatalf("listener whose Init failed was closed %d time(s)", probe.closes)
	}
}

// TestParseServiceClosesListenerOnceOnPostInitError covers the release of a
// listener that did initialize: exactly one Close, so the socket is freed and
// nothing is released twice.
func TestParseServiceClosesListenerOnceOnPostInitError(t *testing.T) {
	probe := &probeListener{}
	setProbe(t, probe)

	svc, err := ParseService(&config.ServiceConfig{
		Name:     "parse-test-probe-post-init",
		Addr:     "127.0.0.1:0",
		Listener: &config.ListenerConfig{Type: "parse-test-probe"},
		Handler:  &config.HandlerConfig{Type: "parse-test-missing"},
	})
	if err == nil {
		svc.Close()
		t.Fatal("ParseService succeeded with an unregistered handler type")
	}
	if probe.closes != 1 {
		t.Fatalf("listener was closed %d time(s) on a post-init error, want 1", probe.closes)
	}
}

// TestParseServiceLeavesOwnedListenerToService guards the success path: the
// listener belongs to the returned service, so ParseService must not close it
// and service.Close must reach it exactly once.
func TestParseServiceLeavesOwnedListenerToService(t *testing.T) {
	probe := &probeListener{}
	setProbe(t, probe)

	svc, err := ParseService(&config.ServiceConfig{
		Name:     "parse-test-probe-success",
		Addr:     "127.0.0.1:0",
		Listener: &config.ListenerConfig{Type: "parse-test-probe"},
		Handler:  &config.HandlerConfig{Type: "parse-test-stub"},
	})
	if err != nil {
		t.Fatalf("ParseService: %v", err)
	}
	if probe.closes != 0 {
		t.Fatalf("listener was closed %d time(s) before the service existed", probe.closes)
	}
	if err := svc.Close(); err != nil {
		t.Fatalf("close service: %v", err)
	}
	if probe.closes != 1 {
		t.Fatalf("service closed the listener %d time(s), want 1", probe.closes)
	}
}
