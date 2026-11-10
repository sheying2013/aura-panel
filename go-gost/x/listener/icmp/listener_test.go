// The surrounding package is declared "quic" upstream (copy of listener/quic).
package quic

import (
	"crypto/tls"
	"testing"

	"github.com/go-gost/core/listener"
	"github.com/go-gost/core/logger"
	xmetadata "github.com/go-gost/x/metadata"
)

func newTestListener(t *testing.T, addr string, tlsCfg *tls.Config) *icmpListener {
	t.Helper()
	return NewListener(
		listener.AddrOption(addr),
		listener.TLSConfigOption(tlsCfg),
		// logger.Default() is nil unless the daemon installs one.
		listener.LoggerOption(logger.LoggerGroup()),
	).(*icmpListener)
}

// A listener that never completed Init must stay inert: the zero value of
// quic.EarlyListener has a nil baseServer, so Close/Addr/Accept used to panic.
func TestZeroValueListenerIsInert(t *testing.T) {
	l := &icmpListener{}

	if err := l.Close(); err != nil {
		t.Fatalf("Close on a never initialised listener: %v", err)
	}
	if addr := l.Addr(); addr != nil {
		t.Fatalf("Addr on a never initialised listener = %v, want nil", addr)
	}
	if _, err := l.Accept(); err != listener.ErrClosed {
		t.Fatalf("Accept on a never initialised listener = %v, want %v", err, listener.ErrClosed)
	}
}

// Regression: Close dereferenced the zero-valued quic.EarlyListener whenever
// Init returned early - the normal case for an unprivileged process, since
// opening a raw ICMP socket needs CAP_NET_RAW.
func TestCloseAfterFailedInit(t *testing.T) {
	l := newTestListener(t, "127.0.0.1:0", nil)
	err := l.Init(xmetadata.NewMetadata(nil))
	if err == nil {
		defer l.Close()
		if l.Addr() == nil {
			t.Fatal("Addr() is nil after a successful Init")
		}
		t.Log("raw ICMP socket opened, privileged path exercised")
		return
	}
	t.Logf("Init failed as expected without CAP_NET_RAW: %v", err)

	if cerr := l.Close(); cerr != nil {
		t.Fatalf("Close after a failed Init: %v", cerr)
	}
	if addr := l.Addr(); addr != nil {
		t.Fatalf("Addr after a failed Init = %v, want nil", addr)
	}
	if _, aerr := l.Accept(); aerr != listener.ErrClosed {
		t.Fatalf("Accept after a failed Init = %v, want %v", aerr, listener.ErrClosed)
	}
}

// Regression: Init assigned NextProtos on options.TLSConfig without checking
// for nil. The assignment also leaked into the *tls.Config shared with the
// handler, because the same pointer is passed to both.
func TestInitWithoutTLSConfig(t *testing.T) {
	cfg := &tls.Config{}
	l := newTestListener(t, "127.0.0.1:0", cfg)
	if err := l.Init(xmetadata.NewMetadata(nil)); err == nil {
		defer l.Close()
	}

	if len(cfg.NextProtos) != 0 {
		t.Fatalf("Init mutated the caller's tls.Config: NextProtos=%v", cfg.NextProtos)
	}

	nilCfg := newTestListener(t, "127.0.0.1:0", nil)
	if err := nilCfg.Init(xmetadata.NewMetadata(nil)); err == nil {
		defer nilCfg.Close()
	}
}
