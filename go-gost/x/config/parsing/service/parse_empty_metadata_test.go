package service

import (
	"testing"

	"github.com/go-gost/x/config"
)

// metadata.NewMetadata returns a nil interface for an empty map, so any direct
// method call on it panics. A service config that carries "metadata": {} (for
// example after the paused marker is removed by a resume that leaves the map
// empty on disk) used to crash the whole agent on startup at parse.go's
// md.IsExists calls. Parsing must tolerate it.
func TestParseServiceToleratesEmptyMetadata(t *testing.T) {
	addr := freeLoopbackAddr(t)

	svc, err := ParseService(&config.ServiceConfig{
		Name:     "parse-test-empty-metadata",
		Addr:     addr,
		Metadata: map[string]any{},
		Listener: &config.ListenerConfig{Type: "tcp"},
		Handler:  &config.HandlerConfig{Type: "parse-test-stub"},
	})
	if err != nil {
		t.Fatalf("ParseService failed on empty metadata: %v", err)
	}
	defer svc.Close()

	if svc == nil {
		t.Fatal("ParseService returned no service for empty metadata")
	}
}
