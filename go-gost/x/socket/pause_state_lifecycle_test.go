package socket

import (
	"errors"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/go-gost/core/listener"
	"github.com/go-gost/core/metadata"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/registry"
)

// 本文件专门盯暂停态引入的几处 socket 层缺口：重复 pause 的幂等、暂停态下的删除与
// 更新、同名创建、以及 createServices 批量解析失败时的资源回收。

const (
	trackedListenerType        = "socket-fixture-listener-tracked"
	trackedFailingListenerType = "socket-fixture-listener-close-fails"
	absentListenerType         = "socket-fixture-listener-absent"
)

var (
	registerTrackedOnce sync.Once
	trackedMu           sync.Mutex
	trackedCreated      []*trackedListener
)

// trackedListener records creation and shutdown, so a test can tell whether the
// service built around it was released or merely abandoned.
type trackedListener struct {
	closed   chan struct{}
	once     sync.Once
	closeErr error
	closes   int32
}

func (l *trackedListener) Init(metadata.Metadata) error { return nil }

func (l *trackedListener) Accept() (net.Conn, error) {
	<-l.closed
	return nil, net.ErrClosed
}

func (l *trackedListener) Addr() net.Addr { return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)} }

func (l *trackedListener) Close() error {
	atomic.AddInt32(&l.closes, 1)
	l.once.Do(func() { close(l.closed) })
	return l.closeErr
}

func (l *trackedListener) closeCount() int32 { return atomic.LoadInt32(&l.closes) }

// registerTrackedFixtures installs the recording listener types on top of the
// shared parse fixtures.
func registerTrackedFixtures(t *testing.T) {
	t.Helper()

	registerParseFixtures(t)

	registerTrackedOnce.Do(func() {
		for _, fixture := range []struct {
			listenerType string
			closeErr     error
		}{
			{listenerType: trackedListenerType},
			{listenerType: trackedFailingListenerType, closeErr: errors.New("listener close failed")},
		} {
			if err := registry.ListenerRegistry().Register(fixture.listenerType,
				func(...listener.Option) listener.Listener {
					l := &trackedListener{closed: make(chan struct{}), closeErr: fixture.closeErr}
					trackedMu.Lock()
					trackedCreated = append(trackedCreated, l)
					trackedMu.Unlock()
					return l
				}); err != nil {
				t.Fatalf("register tracked listener %s: %v", fixture.listenerType, err)
			}
		}
	})
}

// trackedMark snapshots how many tracked listeners already exist.
func trackedMark() int {
	trackedMu.Lock()
	defer trackedMu.Unlock()
	return len(trackedCreated)
}

// trackedSince returns the listeners created after mark.
func trackedSince(mark int) []*trackedListener {
	trackedMu.Lock()
	defer trackedMu.Unlock()

	out := make([]*trackedListener, len(trackedCreated)-mark)
	copy(out, trackedCreated[mark:])
	return out
}

func configHasService(t *testing.T, name string) bool {
	t.Helper()
	return serviceConfigByName(config.Global(), name) != nil
}

func countConfigEntries(t *testing.T, name string) int {
	t.Helper()

	count := 0
	for _, s := range config.Global().Services {
		if s.Name == name {
			count++
		}
	}
	return count
}

// TestPauseAlreadyPausedIsIdempotent covers a panel retry: the second pause of a
// paused service must succeed and must not touch anything.
func TestPauseAlreadyPausedIsIdempotent(t *testing.T) {
	registerParseFixtures(t)

	const name = "pause-idempotent"
	useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))
	original := registerFakeService(t, name)

	if err := pauseServices(pauseServicesRequest{Services: []string{name}}); err != nil {
		t.Fatalf("first pause: %v", err)
	}
	if err := pauseServices(pauseServicesRequest{Services: []string{name}}); err != nil {
		t.Fatalf("second pause of a paused service: %v", err)
	}

	if got := original.closeCount(); got != 1 {
		t.Fatalf("paused instance was closed %d times, want exactly 1", got)
	}
	if got := registry.ServiceRegistry().Get(name); got != nil {
		t.Fatalf("repeat pause resurrected the name as %v", got)
	}
	if !pausedFlagFromConfig(t, name) {
		t.Fatal("repeat pause dropped the paused flag")
	}
}

// TestPauseSkipsAlreadyPausedAndPausesTheRest keeps a mixed batch useful: the
// already paused entry is skipped, the running one is really paused.
func TestPauseSkipsAlreadyPausedAndPausesTheRest(t *testing.T) {
	registerParseFixtures(t)

	const (
		pausedName  = "pause-batch-already-paused"
		runningName = "pause-batch-running"
	)
	useServiceConfig(t,
		fixtureServiceConfig(pausedName, fixtureListenerType),
		fixtureServiceConfig(runningName, fixtureListenerType),
	)
	registerFakeService(t, pausedName)
	running := registerFakeService(t, runningName)

	if err := pauseServices(pauseServicesRequest{Services: []string{pausedName}}); err != nil {
		t.Fatalf("pause %s: %v", pausedName, err)
	}
	if err := pauseServices(pauseServicesRequest{Services: []string{pausedName, runningName}}); err != nil {
		t.Fatalf("mixed batch pause: %v", err)
	}

	if !running.closed() {
		t.Fatal("the running service in the batch was never closed")
	}
	if got := registry.ServiceRegistry().Get(runningName); got != nil {
		t.Fatalf("the running service kept its registry name as %v", got)
	}
	if !pausedFlagFromConfig(t, runningName) {
		t.Fatal("the running service was not marked paused")
	}
	if !pausedFlagFromConfig(t, pausedName) {
		t.Fatal("the skipped service lost its paused flag")
	}
}

// TestPauseUnknownStateStillReportsNotFound keeps the third state distinct: a
// config without the paused marker and without a live instance is not paused.
func TestPauseUnknownStateStillReportsNotFound(t *testing.T) {
	registerParseFixtures(t)

	const name = "pause-unknown-state"
	useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))

	err := pauseServices(pauseServicesRequest{Services: []string{name}})
	if err == nil {
		t.Fatal("pause treated a service with no instance as already paused")
	}
	if !strings.Contains(err.Error(), "not found") {
		t.Fatalf("error = %v, want a not-found cause", err)
	}
	if pausedFlagFromConfig(t, name) {
		t.Fatal("a service that was never running got marked paused")
	}
}

// TestDeletePausedServiceRemovesConfigOnly deletes what a paused service still
// has: its config. The other paused service stays untouched.
func TestDeletePausedServiceRemovesConfigOnly(t *testing.T) {
	registerParseFixtures(t)

	const (
		name  = "delete-paused"
		other = "delete-paused-other"
	)
	useServiceConfig(t,
		fixtureServiceConfig(name, fixtureListenerType),
		fixtureServiceConfig(other, fixtureListenerType),
	)
	registerFakeService(t, name)
	registerFakeService(t, other)

	if err := pauseServices(pauseServicesRequest{Services: []string{name, other}}); err != nil {
		t.Fatalf("pause before delete: %v", err)
	}

	if err := deleteServices(deleteServicesRequest{Services: []string{name}}); err != nil {
		t.Fatalf("delete of a paused service: %v", err)
	}

	if configHasService(t, name) {
		t.Fatal("delete left the paused config behind")
	}
	if got := registry.ServiceRegistry().Get(name); got != nil {
		t.Fatalf("deleted name is still registered as %v", got)
	}
	if !configHasService(t, other) {
		t.Fatal("delete removed an unrelated paused service")
	}
	if !pausedFlagFromConfig(t, other) {
		t.Fatal("delete cleared the paused flag of an unrelated service")
	}
	if got := registry.ServiceRegistry().Get(other); got != nil {
		t.Fatalf("delete started an unrelated paused service: %v", got)
	}
}

// TestDeleteUnknownStateStillReportsNotFound pins the third state for delete.
func TestDeleteUnknownStateStillReportsNotFound(t *testing.T) {
	registerParseFixtures(t)

	const name = "delete-unknown-state"
	useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))

	err := deleteServices(deleteServicesRequest{Services: []string{name}})
	if err == nil {
		t.Fatal("delete accepted a config without instance and without paused flag")
	}
	if !strings.Contains(err.Error(), "not found") {
		t.Fatalf("error = %v, want a not-found cause", err)
	}
	if !configHasService(t, name) {
		t.Fatal("a failed delete dropped the config entry")
	}
}

// TestUpdatePausedServiceKeepsItPaused rewrites a paused config without starting
// it, and keeps the paused flag so resume still finds the service.
func TestUpdatePausedServiceKeepsItPaused(t *testing.T) {
	registerParseFixtures(t)

	const name = "update-paused"
	original := fixtureServiceConfig(name, fixtureListenerType)
	original.Metadata = map[string]any{"label": "edge"}
	useServiceConfig(t, original)
	instance := registerFakeService(t, name)

	if err := pauseServices(pauseServicesRequest{Services: []string{name}}); err != nil {
		t.Fatalf("pause before update: %v", err)
	}

	updated := *fixtureServiceConfig(name, fixtureListenerType)
	updated.Addr = "127.0.0.1:0"
	updated.Metadata = map[string]any{"label": "edge-2"}

	if err := updateServices(updateServicesRequest{Data: []config.ServiceConfig{updated}}); err != nil {
		t.Fatalf("update of a paused service: %v", err)
	}

	if got := registry.ServiceRegistry().Get(name); got != nil {
		t.Fatalf("updating a paused service started it: %v", got)
	}
	entry := serviceConfigByName(config.Global(), name)
	if entry == nil {
		t.Fatal("update dropped the paused config")
	}
	if entry.Addr != "127.0.0.1:0" {
		t.Fatalf("config addr = %q, want the updated value", entry.Addr)
	}
	if entry.Metadata["label"] != "edge-2" {
		t.Fatalf("config metadata = %v, want the updated label", entry.Metadata)
	}
	if !isPaused(entry) {
		t.Fatal("update dropped the paused flag: resume would now report not found")
	}
	if _, mutated := updated.Metadata["paused"]; mutated {
		t.Fatal("update wrote the paused flag into the caller's request document")
	}
	if got := instance.closeCount(); got != 1 {
		t.Fatalf("paused instance was closed %d times, want exactly 1", got)
	}

	if err := resumeServices(resumeServicesRequest{Services: []string{name}}); err != nil {
		t.Fatalf("resume after a paused update: %v", err)
	}
	if got := registry.ServiceRegistry().Get(name); got == nil {
		t.Fatal("resume did not rebuild the updated paused service")
	}
	if isPaused(serviceConfigByName(config.Global(), name)) {
		t.Fatal("resume left the paused flag on the rebuilt service")
	}
}

// TestUpdateUnknownStateStillReportsNotFound pins the third state for update.
func TestUpdateUnknownStateStillReportsNotFound(t *testing.T) {
	registerParseFixtures(t)

	const name = "update-unknown-state"
	useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))

	updated := *fixtureServiceConfig(name, fixtureListenerType)
	err := updateServices(updateServicesRequest{Data: []config.ServiceConfig{updated}})
	if err == nil {
		t.Fatal("update accepted a config without instance and without paused flag")
	}
	if !strings.Contains(err.Error(), "not found") {
		t.Fatalf("error = %v, want a not-found cause", err)
	}
	if pausedFlagFromConfig(t, name) {
		t.Fatal("a failed update marked the service paused")
	}
}

// TestCreateRejectsNameOfPausedService is the duplicate-config guard: pausing
// frees the registry name but not the config, so a create must not reuse it.
func TestCreateRejectsNameOfPausedService(t *testing.T) {
	registerParseFixtures(t)

	const name = "create-over-paused"
	useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))
	registerFakeService(t, name)

	if err := pauseServices(pauseServicesRequest{Services: []string{name}}); err != nil {
		t.Fatalf("pause before create: %v", err)
	}

	err := createServices(createServicesRequest{Data: []config.ServiceConfig{
		*fixtureServiceConfig(name, fixtureListenerType),
	}})
	if err == nil {
		t.Fatal("create accepted the name of a paused service")
	}
	if !strings.Contains(err.Error(), "already exists") {
		t.Fatalf("error = %v, want a duplicate-name cause", err)
	}
	if got := registry.ServiceRegistry().Get(name); got != nil {
		t.Fatalf("rejected create registered %v anyway", got)
	}
	if got := countConfigEntries(t, name); got != 1 {
		t.Fatalf("config holds %d entries for %s, want the original paused one", got, name)
	}
	if !pausedFlagFromConfig(t, name) {
		t.Fatal("rejected create disturbed the paused config")
	}
}

// TestCreateParseFailureReleasesEarlierServices is the resource leak regression:
// a batch that fails on parse must close the services it already built.
func TestCreateParseFailureReleasesEarlierServices(t *testing.T) {
	registerTrackedFixtures(t)
	useServiceConfig(t)

	const (
		goodName = "create-cleanup-good"
		badName  = "create-cleanup-bad"
	)
	mark := trackedMark()

	err := createServices(createServicesRequest{Data: []config.ServiceConfig{
		*fixtureServiceConfig(goodName, trackedListenerType),
		*fixtureServiceConfig(badName, absentListenerType),
	}})
	if err == nil {
		t.Fatal("create succeeded although the second listener type is unknown")
	}
	if !strings.Contains(err.Error(), "unknown listener") {
		t.Fatalf("error = %v, want the parse cause", err)
	}
	if strings.Contains(err.Error(), "cleanup incomplete") {
		t.Fatalf("cleanup reported a failure it could not have had: %v", err)
	}

	built := trackedSince(mark)
	if len(built) != 1 {
		t.Fatalf("created %d services, want only the one that parsed", len(built))
	}
	if built[0].closeCount() == 0 {
		t.Fatal("the service parsed before the failure kept its listener open")
	}
	if got := registry.ServiceRegistry().Get(goodName); got != nil {
		t.Fatalf("failed create registered %v", got)
	}
	if configHasService(t, goodName) {
		t.Fatal("failed create wrote a config entry")
	}
}

// TestCreateDuplicateNameRollsBackOwnInstances covers the register-phase failure:
// the registered instance and the never-registered remainder both go away.
func TestCreateDuplicateNameRollsBackOwnInstances(t *testing.T) {
	registerTrackedFixtures(t)
	useServiceConfig(t)

	const name = "create-rollback-duplicate"
	mark := trackedMark()

	err := createServices(createServicesRequest{Data: []config.ServiceConfig{
		*fixtureServiceConfig(name, trackedListenerType),
		*fixtureServiceConfig(name, trackedListenerType),
	}})
	if err == nil {
		t.Fatal("create accepted two services with the same name")
	}
	if !strings.Contains(err.Error(), "already exists") {
		t.Fatalf("error = %v, want a duplicate-name cause", err)
	}
	if strings.Contains(err.Error(), "cleanup incomplete") {
		t.Fatalf("rollback reported a failure it could not have had: %v", err)
	}

	if got := registry.ServiceRegistry().Get(name); got != nil {
		t.Fatalf("failed create left the name registered as %v", got)
	}
	built := trackedSince(mark)
	if len(built) != 2 {
		t.Fatalf("created %d services, want 2", len(built))
	}
	for i, l := range built {
		if l.closeCount() == 0 {
			t.Fatalf("instance %d was never released", i)
		}
	}
	if configHasService(t, name) {
		t.Fatal("failed create wrote a config entry")
	}
}

// TestCreateCleanupFailureIsReported keeps a failing rollback visible and keeps
// the original cause in front of it.
func TestCreateCleanupFailureIsReported(t *testing.T) {
	registerTrackedFixtures(t)
	useServiceConfig(t)

	const goodName = "create-cleanup-fails"

	err := createServices(createServicesRequest{Data: []config.ServiceConfig{
		*fixtureServiceConfig(goodName, trackedFailingListenerType),
		*fixtureServiceConfig("create-cleanup-fails-bad", absentListenerType),
	}})
	if err == nil {
		t.Fatal("create succeeded although the second listener type is unknown")
	}
	if !strings.Contains(err.Error(), "unknown listener") {
		t.Fatalf("cleanup replaced the original cause: %v", err)
	}
	if !strings.Contains(err.Error(), "cleanup incomplete") {
		t.Fatalf("close failure was swallowed: %v", err)
	}
	if !strings.Contains(err.Error(), goodName) {
		t.Fatalf("cleanup failure does not name the service: %v", err)
	}
	if !strings.Contains(err.Error(), "listener close failed") {
		t.Fatalf("cleanup failure dropped the close error: %v", err)
	}
	if got := registry.ServiceRegistry().Get(goodName); got != nil {
		t.Fatalf("failed create registered %v", got)
	}
}
