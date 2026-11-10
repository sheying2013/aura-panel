package loader

import (
	"context"
	"net"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/go-gost/core/handler"
	"github.com/go-gost/core/listener"
	"github.com/go-gost/core/metadata"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/registry"
)

const (
	loaderFixtureListenerType = "loader-fixture-listener"
	loaderFixtureHandlerType  = "loader-fixture-handler"
)

var (
	loaderFixturesOnce sync.Once
	loaderListeners    int32
)

// registerLoaderFixtures installs stand-ins for a real listener/handler so the
// loader can build services without binding a port.
func registerLoaderFixtures(t *testing.T) {
	t.Helper()

	loaderFixturesOnce.Do(func() {
		// Load installs the default logger itself; setting one here would make
		// logger.SetDefault store a second concrete type and panic.
		if err := registry.ListenerRegistry().Register(loaderFixtureListenerType,
			func(...listener.Option) listener.Listener {
				atomic.AddInt32(&loaderListeners, 1)
				return &loaderFixtureListener{
					addr:   &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)},
					closed: make(chan struct{}),
				}
			}); err != nil {
			t.Fatalf("register fixture listener: %v", err)
		}
		if err := registry.HandlerRegistry().Register(loaderFixtureHandlerType,
			func(...handler.Option) handler.Handler {
				return &loaderFixtureHandler{}
			}); err != nil {
			t.Fatalf("register fixture handler: %v", err)
		}
	})
}

type loaderFixtureListener struct {
	addr   net.Addr
	closed chan struct{}
	once   sync.Once
}

func (l *loaderFixtureListener) Init(metadata.Metadata) error { return nil }

func (l *loaderFixtureListener) Accept() (net.Conn, error) {
	<-l.closed
	return nil, net.ErrClosed
}

func (l *loaderFixtureListener) Addr() net.Addr { return l.addr }

func (l *loaderFixtureListener) Close() error {
	l.once.Do(func() { close(l.closed) })
	return nil
}

type loaderFixtureHandler struct{}

func (h *loaderFixtureHandler) Init(metadata.Metadata) error { return nil }

func (h *loaderFixtureHandler) Handle(_ context.Context, _ net.Conn, _ ...handler.HandleOption) error {
	return nil
}

func loaderServiceConfig(name string, paused bool) *config.ServiceConfig {
	cfg := &config.ServiceConfig{
		Name:     name,
		Listener: &config.ListenerConfig{Type: loaderFixtureListenerType},
		Handler:  &config.HandlerConfig{Type: loaderFixtureHandlerType},
	}
	if paused {
		cfg.Metadata = map[string]any{"paused": true}
	}
	return cfg
}

// TestLoadSkipsPausedService is the regression for defect A: a paused service
// must survive in the configuration but stay out of the registry, otherwise
// program.go serves it on the next cold start.
func TestLoadSkipsPausedService(t *testing.T) {
	registerLoaderFixtures(t)

	const (
		live   = "loader-live-service"
		paused = "loader-paused-service"
	)
	cfg := &config.Config{
		Services: []*config.ServiceConfig{
			loaderServiceConfig(live, false),
			loaderServiceConfig(paused, true),
		},
	}
	t.Cleanup(func() {
		registry.ServiceRegistry().Unregister(live)
		registry.ServiceRegistry().Unregister(paused)
	})

	before := atomic.LoadInt32(&loaderListeners)
	if err := Load(cfg); err != nil {
		t.Fatalf("Load: %v", err)
	}
	built := atomic.LoadInt32(&loaderListeners) - before

	if got := registry.ServiceRegistry().Get(live); got == nil {
		t.Fatal("running service was not registered")
	}
	if got := registry.ServiceRegistry().Get(paused); got != nil {
		t.Fatalf("paused service was loaded into the registry: %v", got)
	}
	if built != 1 {
		t.Fatalf("parsed %d services, want 1 (paused service must not be parsed)", built)
	}
	if len(cfg.Services) != 2 {
		t.Fatalf("config dropped paused service: %d entries left", len(cfg.Services))
	}
	if !isServicePaused(cfg.Services[1]) {
		t.Fatal("config no longer carries the paused marker")
	}
}

// TestLoadRegistersServiceWithoutPausedFlag keeps the skip narrow: only the
// explicit paused marker is honoured.
func TestLoadRegistersServiceWithoutPausedFlag(t *testing.T) {
	registerLoaderFixtures(t)

	const name = "loader-unflagged-service"
	cfg := &config.Config{
		Services: []*config.ServiceConfig{loaderServiceConfig(name, false)},
	}
	cfg.Services[0].Metadata = map[string]any{"paused": "true"} // wrong type on purpose
	t.Cleanup(func() { registry.ServiceRegistry().Unregister(name) })

	if err := Load(cfg); err != nil {
		t.Fatalf("Load: %v", err)
	}
	if got := registry.ServiceRegistry().Get(name); got == nil {
		t.Fatal("service with a non-bool paused marker was skipped")
	}
}
