package socket

import (
	"strings"
	"testing"
	"time"

	"github.com/go-gost/core/service"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/registry"
)

// 本文件只盯跨 CRUD/pause 的一致性缺口，复现的都是独立审计点名的四个问题：
//  1. deleteServices 在 Unregister 之后再 Close 一次（registry.Unregister 自己就会关）；
//  2. 同一批次里重复出现的名字被当成两个不同的操作；
//  3. rollbackResumedServices 在名字已被别人替换时仍然把配置标成暂停（配置污染）；
//  4. create/update/delete 与 pause/resume 不共用 serviceMutationMux。
//
// 第 8 个用例是「现状钉子」，不是缺陷修复：update 的中途失败没有回滚，
// 未验证「先解析后释放」的端口行为之前不动它。

// TestDeleteClosesServiceExactlyOnce pins the double-close: registry.Unregister
// already closes the instance, so deleteServices must not close it again.
func TestDeleteClosesServiceExactlyOnce(t *testing.T) {
	registerParseFixtures(t)

	const name = "delete-single-close"
	useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))
	instance := registerFakeService(t, name)

	if err := deleteServices(deleteServicesRequest{Services: []string{name}}); err != nil {
		t.Fatalf("deleteServices: %v", err)
	}

	if got := instance.closeCount(); got != 1 {
		t.Fatalf("deleted instance was closed %d times, want exactly 1 (Unregister already closes it)", got)
	}
	if got := registry.ServiceRegistry().Get(name); got != nil {
		t.Fatalf("deleted name is still registered as %v", got)
	}
	if configHasService(t, name) {
		t.Fatal("delete left the config entry behind")
	}
}

// TestDeleteRepeatedNameInOneBatchClosesOnce covers the same double-close through
// a repeated name: the batch names are trimmed and each name is acted on once.
func TestDeleteRepeatedNameInOneBatchClosesOnce(t *testing.T) {
	registerParseFixtures(t)

	const name = "delete-duplicate-batch"
	useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))
	instance := registerFakeService(t, name)

	if err := deleteServices(deleteServicesRequest{Services: []string{name, "  " + name + "  "}}); err != nil {
		t.Fatalf("delete with a repeated name: %v", err)
	}

	if got := instance.closeCount(); got != 1 {
		t.Fatalf("instance was closed %d times, want exactly 1", got)
	}
	if got := countConfigEntries(t, name); got != 0 {
		t.Fatalf("config still holds %d entries for %s", got, name)
	}
}

// TestPauseRepeatedNameInOneBatchPausesOnce: the second look at the same name saw
// a released registry slot and reported "changed while pausing", so a batch that
// merely listed a name twice failed and rolled itself back.
func TestPauseRepeatedNameInOneBatchPausesOnce(t *testing.T) {
	registerParseFixtures(t)

	const name = "pause-duplicate-batch"
	useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))
	instance := registerFakeService(t, name)

	err := pauseServices(pauseServicesRequest{Services: []string{name, name}})
	if err != nil {
		t.Fatalf("pause with a repeated name: %v (a repeated name is not a concurrent change)", err)
	}

	if got := instance.closeCount(); got != 1 {
		t.Fatalf("paused instance was closed %d times, want exactly 1", got)
	}
	if got := registry.ServiceRegistry().Get(name); got != nil {
		t.Fatalf("pause left the name registered as %v", got)
	}
	if !pausedFlagFromConfig(t, name) {
		t.Fatal("pause did not mark the config as paused")
	}
}

// TestResumeRepeatedNameInOneBatchRebuildsOnce: the repeated name used to rebuild
// twice, hit "already exists" on the second register and roll the whole batch back.
func TestResumeRepeatedNameInOneBatchRebuildsOnce(t *testing.T) {
	registerTrackedFixtures(t)

	const name = "resume-duplicate-batch"
	useServiceConfig(t, fixtureServiceConfig(name, trackedListenerType))
	instance := registerFakeService(t, name)

	if err := pauseServices(pauseServicesRequest{Services: []string{name}}); err != nil {
		t.Fatalf("pause before resume: %v", err)
	}

	mark := trackedMark()
	err := resumeServices(resumeServicesRequest{Services: []string{name, name}})
	if err != nil {
		t.Fatalf("resume with a repeated name: %v", err)
	}
	if got := len(trackedSince(mark)); got != 1 {
		t.Fatalf("resume built %d services, want exactly 1", got)
	}

	rebuilt := registry.ServiceRegistry().Get(name)
	if rebuilt == nil {
		t.Fatal("resume left the name unregistered")
	}
	if rebuilt == service.Service(instance) {
		t.Fatal("resume kept the closed instance instead of rebuilding")
	}
	if pausedFlagFromConfig(t, name) {
		t.Fatal("resume left the paused flag behind")
	}
}

// TestUpdateRepeatedNameInOneBatchIsRejected: two configs for one name are
// contradictory, so update must refuse the batch before touching the registry
// instead of tearing the service down twice and letting the last one win.
func TestUpdateRepeatedNameInOneBatchIsRejected(t *testing.T) {
	registerTrackedFixtures(t)

	const name = "update-duplicate-batch"
	useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))
	instance := registerFakeService(t, name)

	updated := *fixtureServiceConfig(name, trackedListenerType)
	updated.Addr = "127.0.0.1:0"

	mark := trackedMark()
	err := updateServices(updateServicesRequest{Data: []config.ServiceConfig{updated, updated}})
	if err == nil {
		t.Fatal("update accepted two configs for the same name: which one wins is undefined")
	}
	if !strings.Contains(err.Error(), "more than once") {
		t.Fatalf("error = %v, want an explicit duplicate-name cause", err)
	}
	if got := len(trackedSince(mark)); got != 0 {
		t.Fatalf("rejected update built %d services, want 0", got)
	}
	if got := registry.ServiceRegistry().Get(name); got != service.Service(instance) {
		t.Fatalf("rejected update changed the registry entry to %v", got)
	}
	if instance.closed() {
		t.Fatal("rejected update closed the running instance")
	}
	if entry := serviceConfigByName(config.Global(), name); entry == nil || entry.Addr != "" {
		t.Fatalf("rejected update wrote the config: %+v", entry)
	}
}

// TestRollbackResumedServicesKeepsConfigCleanWhenReleaseFails is the config
// pollution case: when the name already belongs to another instance, the rollback
// cannot take the service down, so it must not claim the service is paused either.
func TestRollbackResumedServicesKeepsConfigCleanWhenReleaseFails(t *testing.T) {
	registerParseFixtures(t)

	const name = "resume-rollback-replaced"
	useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))
	replacement := registerFakeService(t, name)
	stale := &fakeService{}

	err := rollbackResumedServices([]resumedService{{
		name:          name,
		service:       stale,
		serviceConfig: fixtureServiceConfig(name, fixtureListenerType),
	}})
	if err == nil {
		t.Fatal("rollback hid the ownership mismatch")
	}
	if !strings.Contains(err.Error(), "replaced concurrently") {
		t.Fatalf("error = %v, want an ownership-mismatch cause", err)
	}

	// 实例在跑，配置就不能说它暂停：否则 status 报 paused，服务其实在收流量。
	if pausedFlagFromConfig(t, name) {
		t.Fatal("rollback marked a running replacement as paused: config now disagrees with the registry")
	}
	if replacement.closed() {
		t.Fatal("rollback closed an instance it does not own")
	}
	if got := registry.ServiceRegistry().Get(name); got != service.Service(replacement) {
		t.Fatalf("rollback replaced the registry entry with %v", got)
	}
}

// assertTakesMutationLock fails when call finishes while serviceMutationMux is
// held, which is exactly what a mutation path that ignores the shared lock does.
func assertTakesMutationLock(t *testing.T, label string, call func() error) {
	t.Helper()

	serviceMutationMux.Lock()
	done := make(chan error, 1)
	go func() { done <- call() }()

	select {
	case err := <-done:
		serviceMutationMux.Unlock()
		t.Fatalf("%s finished (%v) while serviceMutationMux was held: it does not share the pause/resume lock", label, err)
	case <-time.After(250 * time.Millisecond):
	}

	serviceMutationMux.Unlock()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("%s after the lock was released: %v", label, err)
		}
	case <-time.After(10 * time.Second):
		t.Fatalf("%s never returned after the lock was released", label)
	}
}

// TestMutationsShareServiceMutationLock: create/update/delete must serialize with
// pause/resume, otherwise one batch can unregister a replacement the other just
// registered. 外部 API (x/api) 和 loader 仍然直接操作 registry，这条锁管不到它们。
func TestMutationsShareServiceMutationLock(t *testing.T) {
	registerParseFixtures(t)

	t.Run("create", func(t *testing.T) {
		const name = "lock-create"
		useServiceConfig(t)
		t.Cleanup(func() { registry.ServiceRegistry().Unregister(name) })

		assertTakesMutationLock(t, "createServices", func() error {
			return createServices(createServicesRequest{Data: []config.ServiceConfig{
				*fixtureServiceConfig(name, fixtureListenerType),
			}})
		})
	})

	t.Run("update", func(t *testing.T) {
		const name = "lock-update"
		useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))
		registerFakeService(t, name)

		assertTakesMutationLock(t, "updateServices", func() error {
			return updateServices(updateServicesRequest{Data: []config.ServiceConfig{
				*fixtureServiceConfig(name, fixtureListenerType),
			}})
		})
	})

	t.Run("delete", func(t *testing.T) {
		const name = "lock-delete"
		useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))
		registerFakeService(t, name)

		assertTakesMutationLock(t, "deleteServices", func() error {
			return deleteServices(deleteServicesRequest{Services: []string{name}})
		})
	})

	// 对照组：pause 本来就持锁，证明这套探针能看出持锁与不持锁的区别。
	t.Run("pause", func(t *testing.T) {
		const name = "lock-pause"
		useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))
		registerFakeService(t, name)
		t.Cleanup(func() { registry.ServiceRegistry().Unregister(name) })

		assertTakesMutationLock(t, "pauseServices", func() error {
			return pauseServices(pauseServicesRequest{Services: []string{name}})
		})
	})
}

// TestUpdateParseFailureLeavesAffectedServiceDown pins today's behavior instead of
// changing it: updateServices has no rollback, so a batch that fails mid-way leaves
// the earlier services rebuilt and the failing one closed and unregistered while its
// config survives. 回滚要先验证「先解析后释放」的端口占用行为，未验证前保持现状。
func TestUpdateParseFailureLeavesAffectedServiceDown(t *testing.T) {
	registerParseFixtures(t)

	const (
		okName  = "update-no-rollback-ok"
		badName = "update-no-rollback-bad"
	)
	originalBad := fixtureServiceConfig(badName, absentListenerType)
	originalBad.Addr = "127.0.0.1:0"
	useServiceConfig(t,
		fixtureServiceConfig(okName, fixtureListenerType),
		originalBad,
	)
	oldOK := registerFakeService(t, okName)
	oldBad := registerFakeService(t, badName)

	updatedBad := *fixtureServiceConfig(badName, absentListenerType)
	updatedBad.Addr = "127.0.0.1:9"

	err := updateServices(updateServicesRequest{Data: []config.ServiceConfig{
		*fixtureServiceConfig(okName, fixtureListenerType),
		updatedBad,
	}})
	if err == nil {
		t.Fatal("update succeeded although the second listener type is unknown")
	}
	if !strings.Contains(err.Error(), "unknown listener") {
		t.Fatalf("error = %v, want the parse cause", err)
	}

	// 第一个：已经重建成功，旧实例已释放。
	if rebuilt := registry.ServiceRegistry().Get(okName); rebuilt == nil || rebuilt == service.Service(oldOK) {
		t.Fatalf("first service = %v, want a rebuilt instance", rebuilt)
	}
	if !oldOK.closed() {
		t.Fatal("first service was not released before rebuilding")
	}

	// 第二个：实例已关、名字已空，配置还在 —— 这是没有回滚的代价，先钉住。
	if got := registry.ServiceRegistry().Get(badName); got != nil {
		t.Fatalf("failed service is still registered as %v", got)
	}
	if !oldBad.closed() {
		t.Fatal("failed service kept running after its replacement failed to parse")
	}
	entry := serviceConfigByName(config.Global(), badName)
	if entry == nil {
		t.Fatal("update dropped the config of the service it failed to rebuild")
	}
	if entry.Addr != "127.0.0.1:0" {
		t.Fatalf("config addr = %q, want the untouched original (phase three never ran)", entry.Addr)
	}
}
