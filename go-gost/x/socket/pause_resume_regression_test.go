package socket

import (
	"context"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/go-gost/core/handler"
	"github.com/go-gost/core/listener"
	"github.com/go-gost/core/logger"
	"github.com/go-gost/core/metadata"
	"github.com/go-gost/core/service"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/registry"
)

// Fixture listener/handler types let parser.ParseService build real
// *xservice.defaultService instances without binding a port.
const (
	fixtureListenerType = "socket-fixture-listener"
	fixtureHandlerType  = "socket-fixture-handler"
)

var registerParseFixturesOnce sync.Once

func registerParseFixtures(done *testing.T) {
	done.Helper()

	registerParseFixturesOnce.Do(func() {
		// ParseService needs a default logger; an empty group is a silent no-op
		// that also turns registry Fatal calls into no-ops.
		logger.SetDefault(logger.LoggerGroup())

		if err := registry.ListenerRegistry().Register(fixtureListenerType,
			func(...listener.Option) listener.Listener {
				return &fixtureListener{
					addr:   &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)},
					closed: make(chan struct{}),
				}
			}); err != nil {
			done.Fatalf("register fixture listener: %v", err)
		}
		if err := registry.HandlerRegistry().Register(fixtureHandlerType,
			func(...handler.Option) handler.Handler {
				return &fixtureHandler{}
			}); err != nil {
			done.Fatalf("register fixture handler: %v", err)
		}
	})
}

// fixtureListener blocks in Accept until closed, so a rebuilt service never
// touches the network and shut down deterministically.
type fixtureListener struct {
	addr   net.Addr
	closed chan struct{}
	once   sync.Once
}

func (l *fixtureListener) Init(metadata.Metadata) error { return nil }

func (l *fixtureListener) Accept() (net.Conn, error) {
	<-l.closed
	return nil, net.ErrClosed
}

func (l *fixtureListener) Addr() net.Addr { return l.addr }

func (l *fixtureListener) Close() error {
	l.once.Do(func() { close(l.closed) })
	return nil
}

type fixtureHandler struct{}

func (h *fixtureHandler) Init(metadata.Metadata) error { return nil }

func (h *fixtureHandler) Handle(context.Context, net.Conn, ...handler.HandleOption) error {
	return nil
}

// fakeService stands in for a running service already in the registry. It only
// records how often it was closed.
type fakeService struct {
	closeCalls int32
}

func (s *fakeService) Serve() error { return nil }

func (s *fakeService) Addr() net.Addr { return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)} }

func (s *fakeService) Close() error {
	atomic.AddInt32(&s.closeCalls, 1)
	return nil
}

func (s *fakeService) closed() bool { return atomic.LoadInt32(&s.closeCalls) > 0 }

func (s *fakeService) closeCount() int32 { return atomic.LoadInt32(&s.closeCalls) }

// useServiceConfig installs cfg as the global configuration for one test and
// restores the previous one afterwards.
func useServiceConfig(t *testing.T, services ...*config.ServiceConfig) {
	t.Helper()

	prev := config.Global()
	config.Set(&config.Config{Services: services})
	t.Cleanup(func() { config.Set(prev) })
}

func fixtureServiceConfig(name, listenerType string) *config.ServiceConfig {
	return &config.ServiceConfig{
		Name:     name,
		Listener: &config.ListenerConfig{Type: listenerType},
		Handler:  &config.HandlerConfig{Type: fixtureHandlerType},
	}
}

// registerFakeService puts svc in the registry and removes it after the test.
func registerFakeService(t *testing.T, name string) *fakeService {
	t.Helper()

	svc := &fakeService{}
	if err := registry.ServiceRegistry().Register(name, svc); err != nil {
		t.Fatalf("register fixture service %s: %v", name, err)
	}
	t.Cleanup(func() { registry.ServiceRegistry().Unregister(name) })
	return svc
}

// pausedFlagFromConfig reports what the product itself would observe.
func pausedFlagFromConfig(t *testing.T, name string) bool {
	t.Helper()

	for _, s := range config.Global().Services {
		if s.Name != name {
			continue
		}
		paused, _ := s.Metadata["paused"].(bool)
		return paused
	}
	t.Fatalf("service %s vanished from config", name)
	return false
}

// TestPauseReleasesRegistryName is the core regression: pausing used to leave
// the registry name occupied by the closed instance, so every later rebuild
// (resume, rollback, recreate) hit "already exists".
func TestPauseReleasesRegistryName(t *testing.T) {
	registerParseFixtures(t)

	const name = "pause-regression-single"
	cfg := fixtureServiceConfig(name, fixtureListenerType)
	useServiceConfig(t, cfg)
	original := registerFakeService(t, name)

	if err := pauseServices(pauseServicesRequest{Services: []string{name}}); err != nil {
		t.Fatalf("pauseServices: %v", err)
	}

	if got := registry.ServiceRegistry().Get(name); got != nil {
		t.Fatalf("registry still holds %v after pause: a rebuild would fail with duplicate", got)
	}
	if !original.closed() {
		t.Fatal("paused service was never closed")
	}
	if !pausedFlagFromConfig(t, name) {
		t.Fatal("pause did not mark the config as paused")
	}
}

// TestResumeRebuildsAfterPause drives the recovery path end to end.
func TestResumeRebuildsAfterPause(t *testing.T) {
	registerParseFixtures(t)

	const name = "pause-regression-resume"
	cfg := fixtureServiceConfig(name, fixtureListenerType)
	useServiceConfig(t, cfg)
	original := registerFakeService(t, name)

	if err := pauseServices(pauseServicesRequest{Services: []string{name}}); err != nil {
		t.Fatalf("pauseServices: %v", err)
	}
	if err := resumeServices(resumeServicesRequest{Services: []string{name}}); err != nil {
		t.Fatalf("resumeServices after pause: %v", err)
	}

	rebuilt := registry.ServiceRegistry().Get(name)
	if rebuilt == nil {
		t.Fatal("resume left the name unregistered")
	}
	if rebuilt == service.Service(original) {
		t.Fatal("resume kept the closed instance instead of rebuilding")
	}
	if pausedFlagFromConfig(t, name) {
		t.Fatal("resume left the paused flag behind")
	}
}

// TestPauseRollbackRestoresEarlierService covers a mid-transaction pause
// failure: the service paused before the failure must come back running.
func TestPauseRollbackRestoresEarlierService(t *testing.T) {
	registerParseFixtures(t)

	const (
		pausedName  = "pause-regression-rollback-ok"
		missingName = "pause-regression-rollback-missing"
	)
	useServiceConfig(t, fixtureServiceConfig(pausedName, fixtureListenerType))
	pausedService := registerFakeService(t, pausedName)
	untouched := registerFakeService(t, missingName)

	err := pauseServices(pauseServicesRequest{Services: []string{pausedName, missingName}})
	if err == nil {
		t.Fatal("pauseServices succeeded although the second service has no config")
	}
	if !strings.Contains(err.Error(), "configuration not found") {
		t.Fatalf("error = %v, want the original cause", err)
	}
	if strings.Contains(err.Error(), "rollback incomplete") {
		t.Fatalf("rollback claimed to fail while it could rebuild the service: %v", err)
	}

	restored := registry.ServiceRegistry().Get(pausedName)
	if restored == nil {
		t.Fatal("rollback lost the paused service: its name stayed free")
	}
	if restored == service.Service(pausedService) {
		t.Fatal("rollback did not rebuild the closed instance")
	}
	if !pausedService.closed() {
		t.Fatal("the paused service was never closed")
	}
	if pausedFlagFromConfig(t, pausedName) {
		t.Fatal("rollback left the paused flag on the restored service")
	}

	if got := registry.ServiceRegistry().Get(missingName); got != service.Service(untouched) {
		t.Fatalf("untouched service = %v, want the original instance", got)
	}
	if untouched.closed() {
		t.Fatal("a service without config was closed although it was never paused")
	}
}

// TestPauseRollbackFailureIsVisible keeps rollback failures distinguishable
// from the pause failure that triggered them.
func TestPauseRollbackFailureIsVisible(t *testing.T) {
	registerParseFixtures(t)

	const (
		brokenName  = "pause-regression-rollback-broken"
		missingName = "pause-regression-rollback-missing-b"
	)
	// This listener type is not registered, so the rollback cannot rebuild.
	useServiceConfig(t, fixtureServiceConfig(brokenName, "socket-fixture-listener-absent"))
	registerFakeService(t, brokenName)
	registerFakeService(t, missingName)

	err := pauseServices(pauseServicesRequest{Services: []string{brokenName, missingName}})
	if err == nil {
		t.Fatal("pauseServices succeeded although the second service has no config")
	}
	if !strings.Contains(err.Error(), "configuration not found") {
		t.Fatalf("error = %v, want the original cause", err)
	}
	if !strings.Contains(err.Error(), "rollback incomplete") {
		t.Fatalf("rollback failure is invisible: %v", err)
	}
	if !strings.Contains(err.Error(), brokenName) {
		t.Fatalf("rollback failure does not name the service: %v", err)
	}
	if !strings.Contains(err.Error(), "unknown listener") {
		t.Fatalf("rollback failure dropped the parse error: %v", err)
	}
	if got := registry.ServiceRegistry().Get(brokenName); got != nil {
		t.Fatalf("broken service is still registered as %v", got)
	}
}

// TestReleaseServiceSkipsConcurrentReplacement pins the ownership check: a
// release must never close or drop an instance registered by someone else.
func TestReleaseServiceSkipsConcurrentReplacement(t *testing.T) {
	const name = "release-regression-replaced"
	replacement := registerFakeService(t, name)
	stale := &fakeService{}

	if releaseService(name, stale) {
		t.Fatal("releaseService claimed a service it does not own")
	}
	if got := registry.ServiceRegistry().Get(name); got != service.Service(replacement) {
		t.Fatalf("registry entry = %v, want the replacement", got)
	}
	if replacement.closed() || stale.closed() {
		t.Fatal("releaseService closed an instance it must leave alone")
	}

	if !releaseService(name, replacement) {
		t.Fatal("releaseService refused its own instance")
	}
	if got := registry.ServiceRegistry().Get(name); got != nil {
		t.Fatalf("released name still registered as %v", got)
	}
	if !replacement.closed() {
		t.Fatal("releaseService did not close the released instance")
	}
}

// TestConcurrentPauseResumeLeavesUsableName runs both directions at once: the
// name must never stay blocked by an instance nobody owns any more.
func TestConcurrentPauseResumeLeavesUsableName(t *testing.T) {
	if testing.Short() {
		t.Skip("concurrency regression needs the 100ms port-release wait")
	}
	registerParseFixtures(t)

	const name = "pause-regression-concurrent"
	useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))
	registerFakeService(t, name)

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		for i := 0; i < 5; i++ {
			_ = pauseServices(pauseServicesRequest{Services: []string{name}})
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < 5; i++ {
			_ = resumeServices(resumeServicesRequest{Services: []string{name}})
		}
	}()
	wg.Wait()

	if err := resumeServices(resumeServicesRequest{Services: []string{name}}); err != nil {
		t.Fatalf("resume after concurrent pause/resume: %v", err)
	}
	if registry.ServiceRegistry().Get(name) == nil {
		t.Fatal("service is neither registered nor resumable after the race")
	}
}
