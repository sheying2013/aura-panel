package kcp

import (
	"testing"

	"github.com/go-gost/core/listener"
	"github.com/go-gost/core/logger"
	kcp_util "github.com/go-gost/x/internal/util/kcp"
	xmetadata "github.com/go-gost/x/metadata"
)

func newTestListener(t *testing.T, addr string) *kcpListener {
	t.Helper()
	return NewListener(
		listener.AddrOption(addr),
		// logger.Default() is nil unless the daemon installs one.
		listener.LoggerOption(logger.LoggerGroup()),
	).(*kcpListener)
}

// A listener that never completed Init must stay inert; Close used to
// dereference the nil conn and the nil kcp.Listener.
func TestZeroValueListenerIsInert(t *testing.T) {
	l := &kcpListener{}

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

// Regression: parseMetadata stored the package-level DefaultConfig by pointer
// and then overwrote its fields, so every listener silently shared (and
// mutated) one config - two services with different keys ended up with the
// last one parsed.
func TestParseMetadataDoesNotShareDefaultConfig(t *testing.T) {
	l1 := newTestListener(t, "127.0.0.1:0")
	l2 := newTestListener(t, "127.0.0.1:0")

	defaultKey, defaultMTU := kcp_util.DefaultConfig.Key, kcp_util.DefaultConfig.MTU

	if err := l1.parseMetadata(xmetadata.NewMetadata(map[string]any{
		"kcp.key": "key-1",
		"kcp.mtu": 1200,
	})); err != nil {
		t.Fatalf("parseMetadata(l1): %v", err)
	}
	if err := l2.parseMetadata(xmetadata.NewMetadata(map[string]any{
		"kcp.key": "key-2",
		"kcp.mtu": 1400,
	})); err != nil {
		t.Fatalf("parseMetadata(l2): %v", err)
	}

	if l1.md.config == nil || l2.md.config == nil {
		t.Fatal("parseMetadata left config nil")
	}
	if l1.md.config == l2.md.config {
		t.Fatal("both listeners share one *kcp_util.Config")
	}
	if l1.md.config == kcp_util.DefaultConfig || l2.md.config == kcp_util.DefaultConfig {
		t.Fatal("a listener writes into the package-level DefaultConfig")
	}
	if got := l1.md.config.Key; got != "key-1" {
		t.Fatalf("l1 config key = %q, want %q (state bled from l2)", got, "key-1")
	}
	if got := l1.md.config.MTU; got != 1200 {
		t.Fatalf("l1 config MTU = %d, want 1200", got)
	}
	if got := l2.md.config.Key; got != "key-2" {
		t.Fatalf("l2 config key = %q, want %q", got, "key-2")
	}
	if got := kcp_util.DefaultConfig.Key; got != defaultKey {
		t.Fatalf("kcp_util.DefaultConfig.Key = %q, want %q", got, defaultKey)
	}
	if got := kcp_util.DefaultConfig.MTU; got != defaultMTU {
		t.Fatalf("kcp_util.DefaultConfig.MTU = %d, want %d", got, defaultMTU)
	}
}
