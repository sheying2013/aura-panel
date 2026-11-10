package socket

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/go-gost/x/config"
)

const compatTCPFixture = `[{"name":"tcp-forward","addr":":18080","interface":"eth0","sockopts":{"mark":4},"climiter":"cl1","rlimiter":"rl1","admission":"a1","admissions":["a1","a2"],"bypass":"b1","bypasses":["b1","b2"],"handler":{"type":"tcp","retries":2,"chain":"c1","metadata":{"dialTimeout":"5s"}},"listener":{"type":"tcp","chain":"c1"},"forwarder":{"nodes":[{"name":"node-1","addr":"10.10.10.10:443","network":"tcp","host":"example.com","path":"/ws"}]},"metadata":{"port":18080,"mark":3,"enabled":true,"label":"edge","ratio":0.25}}]`

const compatUDPFixture = `[{"name":"udp-forward","addr":":18081","handler":{"type":"udp"},"listener":{"type":"udp"},"forwarder":{"nodes":[{"name":"dns","addr":"1.1.1.1:53","network":"udp"}]},"metadata":{"udp":true,"ttl":64}}]`

// TestServiceConfigJSONRoundTripPreserved asserts that a panel-supplied service
// document comes back out of config.ServiceConfig unchanged.
func TestServiceConfigJSONRoundTripPreserved(t *testing.T) {
	tests := []struct {
		name        string
		fixture     string
		handlerType string
		listenerTyp string
		nodeAddr    string
	}{
		{name: "tcp forwarder", fixture: compatTCPFixture, handlerType: "tcp", listenerTyp: "tcp", nodeAddr: "10.10.10.10:443"},
		{name: "udp forwarder", fixture: compatUDPFixture, handlerType: "udp", listenerTyp: "udp", nodeAddr: "1.1.1.1:53"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			var services []config.ServiceConfig
			if err := json.Unmarshal([]byte(tc.fixture), &services); err != nil {
				t.Fatalf("unmarshal services: %v", err)
			}
			if len(services) != 1 {
				t.Fatalf("len(services) = %d, want 1", len(services))
			}

			svc := services[0]
			if svc.Handler == nil || svc.Handler.Type != tc.handlerType {
				t.Fatalf("handler = %+v, want type %s", svc.Handler, tc.handlerType)
			}
			if svc.Listener == nil || svc.Listener.Type != tc.listenerTyp {
				t.Fatalf("listener = %+v, want type %s", svc.Listener, tc.listenerTyp)
			}
			if svc.Forwarder == nil || len(svc.Forwarder.Nodes) != 1 || svc.Forwarder.Nodes[0].Addr != tc.nodeAddr {
				t.Fatalf("forwarder = %+v, want node addr %s", svc.Forwarder, tc.nodeAddr)
			}

			out, err := json.Marshal(services)
			if err != nil {
				t.Fatalf("marshal services: %v", err)
			}
			compatAssertJSONEqual(t, []byte(tc.fixture), out)
		})
	}
}

// TestServiceConfigKeepsTCPAndUDPDistinct guards against any future defaulting
// that would collapse the two protocol families.
func TestServiceConfigKeepsTCPAndUDPDistinct(t *testing.T) {
	payload := `[{"name":"tcp-1","addr":":18080","handler":{"type":"tcp"},"listener":{"type":"tcp"}},{"name":"udp-1","addr":":18081","handler":{"type":"udp"},"listener":{"type":"udp"}}]`

	var services []config.ServiceConfig
	if err := json.Unmarshal([]byte(payload), &services); err != nil {
		t.Fatalf("unmarshal services: %v", err)
	}
	if len(services) != 2 {
		t.Fatalf("len(services) = %d, want 2", len(services))
	}
	if services[0].Handler.Type != "tcp" || services[0].Listener.Type != "tcp" {
		t.Fatalf("tcp service types = %s/%s", services[0].Handler.Type, services[0].Listener.Type)
	}
	if services[1].Handler.Type != "udp" || services[1].Listener.Type != "udp" {
		t.Fatalf("udp service types = %s/%s", services[1].Handler.Type, services[1].Listener.Type)
	}

	out, err := json.Marshal(services)
	if err != nil {
		t.Fatalf("marshal services: %v", err)
	}
	compatAssertJSONEqual(t, []byte(payload), out)
}

// TestServiceConfigDoesNotInjectDefaults keeps bare service documents bare: the
// panel decides handler/listener types, not the decoder.
func TestServiceConfigDoesNotInjectDefaults(t *testing.T) {
	payload := `[{"name":"bare","addr":":9000"}]`

	var services []config.ServiceConfig
	if err := json.Unmarshal([]byte(payload), &services); err != nil {
		t.Fatalf("unmarshal services: %v", err)
	}
	if len(services) != 1 {
		t.Fatalf("len(services) = %d, want 1", len(services))
	}
	if services[0].Handler != nil || services[0].Listener != nil || services[0].Forwarder != nil {
		t.Fatalf("decoder injected defaults: handler=%+v listener=%+v forwarder=%+v",
			services[0].Handler, services[0].Listener, services[0].Forwarder)
	}

	out, err := json.Marshal(services)
	if err != nil {
		t.Fatalf("marshal services: %v", err)
	}
	compatAssertJSONEqual(t, []byte(payload), out)
}

// TestPreprocessDurationFieldsPreservesServiceConfig is the compatibility core
// for AddService/UpdateService: the duration pass runs before decoding, so it
// must not rewrite, drop or retype anything else in a TCP/UDP service document.
func TestPreprocessDurationFieldsPreservesServiceConfig(t *testing.T) {
	reporter := &WebSocketReporter{}

	for name, fixture := range map[string]string{"tcp": compatTCPFixture, "udp": compatUDPFixture} {
		t.Run(name, func(t *testing.T) {
			processed, err := reporter.preprocessDurationFields([]byte(fixture))
			if err != nil {
				t.Fatalf("preprocess: %v", err)
			}
			compatAssertJSONEqual(t, []byte(fixture), processed)

			var services []config.ServiceConfig
			if err := json.Unmarshal(processed, &services); err != nil {
				t.Fatalf("unmarshal processed services: %v", err)
			}
			svc := services[0]
			if svc.Handler == nil || svc.Listener == nil || svc.Handler.Type != svc.Listener.Type {
				t.Fatalf("protocol family not preserved: %+v / %+v", svc.Handler, svc.Listener)
			}
			if svc.Addr == "" || svc.Forwarder == nil || len(svc.Forwarder.Nodes) == 0 {
				t.Fatalf("service body not preserved: %+v", svc)
			}
		})
	}
}

// TestPreprocessDurationFieldsConvertsSelectorFailTimeout covers the one field
// the pass is allowed to touch, and the behaviour for unparsable values.
func TestPreprocessDurationFieldsConvertsSelectorFailTimeout(t *testing.T) {
	reporter := &WebSocketReporter{}

	t.Run("string duration becomes nanoseconds", func(t *testing.T) {
		processed, err := reporter.preprocessDurationFields([]byte(`[{"name":"hop-1","selector":{"strategy":"round","failTimeout":"5s","maxFails":2}}]`))
		if err != nil {
			t.Fatalf("preprocess: %v", err)
		}

		var hops []map[string]any
		if err := json.Unmarshal(processed, &hops); err != nil {
			t.Fatalf("unmarshal: %v", err)
		}
		selector, ok := hops[0]["selector"].(map[string]any)
		if !ok {
			t.Fatalf("selector = %v, want object", hops[0]["selector"])
		}
		if selector["failTimeout"] != float64(int64(5*time.Second)) {
			t.Fatalf("failTimeout = %v, want %d", selector["failTimeout"], int64(5*time.Second))
		}
		if selector["strategy"] != "round" || selector["maxFails"] != float64(2) {
			t.Fatalf("selector siblings changed: %v", selector)
		}
	})

	t.Run("invalid string is left untouched", func(t *testing.T) {
		processed, err := reporter.preprocessDurationFields([]byte(`{"selector":{"failTimeout":"later"}}`))
		if err != nil {
			t.Fatalf("preprocess: %v", err)
		}

		var decoded map[string]map[string]any
		if err := json.Unmarshal(processed, &decoded); err != nil {
			t.Fatalf("unmarshal: %v", err)
		}
		if decoded["selector"]["failTimeout"] != "later" {
			t.Fatalf("failTimeout = %v, want later", decoded["selector"]["failTimeout"])
		}
	})

	t.Run("numeric duration is left untouched", func(t *testing.T) {
		processed, err := reporter.preprocessDurationFields([]byte(`{"selector":{"failTimeout":1500}}`))
		if err != nil {
			t.Fatalf("preprocess: %v", err)
		}

		var decoded map[string]map[string]any
		if err := json.Unmarshal(processed, &decoded); err != nil {
			t.Fatalf("unmarshal: %v", err)
		}
		if decoded["selector"]["failTimeout"] != float64(1500) {
			t.Fatalf("failTimeout = %v, want 1500", decoded["selector"]["failTimeout"])
		}
	})
}

// TestPreprocessDurationFieldsKeepsLargeMetadataInts is a scope guard: metadata
// integers used by gost (ports, marks) must stay exact through the pass.
func TestPreprocessDurationFieldsKeepsLargeMetadataInts(t *testing.T) {
	reporter := &WebSocketReporter{}
	payload := `[{"name":"svc","addr":":18080","metadata":{"port":65535,"mark":4294967295,"flags":9007199254740991}}]`

	processed, err := reporter.preprocessDurationFields([]byte(payload))
	if err != nil {
		t.Fatalf("preprocess: %v", err)
	}
	compatAssertJSONEqual(t, []byte(payload), processed)
}
