package socket

import (
	"testing"

	"github.com/go-gost/x/registry"
)

// TestResumeRebuildsServiceSkippedByLoader reproduces the cold-start state left
// by loader.Load when a service was paused: the config entry survives with
// metadata.paused, the registry is empty. Resume must rebuild from the config
// alone and not depend on an instance left in the registry.
func TestResumeRebuildsServiceSkippedByLoader(t *testing.T) {
	registerParseFixtures(t)

	const name = "paused-after-restart"
	cfg := fixtureServiceConfig(name, fixtureListenerType)
	cfg.Metadata = map[string]any{"paused": true}
	useServiceConfig(t, cfg)

	// What loader.Load leaves behind for a paused service: no parse, no register.
	if got := registry.ServiceRegistry().Get(name); got != nil {
		t.Fatalf("precondition: registry already holds %v", got)
	}

	if err := resumeServices(resumeServicesRequest{Services: []string{name}}); err != nil {
		t.Fatalf("resume after cold start: %v", err)
	}
	t.Cleanup(func() { registry.ServiceRegistry().Unregister(name) })

	rebuilt := registry.ServiceRegistry().Get(name)
	if rebuilt == nil {
		t.Fatal("resume did not rebuild the service that the loader skipped")
	}
	if pausedFlagFromConfig(t, name) {
		t.Fatal("resume left the paused flag behind")
	}
}

// TestResumeOfRunningServiceStillFails keeps the skip from widening: a service
// that is not marked paused and is missing from the registry is still an error,
// not a silent rebuild.
func TestResumeOfRunningServiceStillFails(t *testing.T) {
	registerParseFixtures(t)

	const name = "not-paused-not-registered"
	useServiceConfig(t, fixtureServiceConfig(name, fixtureListenerType))

	if err := resumeServices(resumeServicesRequest{Services: []string{name}}); err == nil {
		t.Fatal("resume rebuilt a service that was never marked paused")
	}
	if got := registry.ServiceRegistry().Get(name); got != nil {
		t.Fatalf("failed resume left %v registered", got)
	}
}
