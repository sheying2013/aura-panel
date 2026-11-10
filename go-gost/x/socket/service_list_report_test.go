package socket

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// TestParseResourceNames pins the extraction contract: only services[].name and
// chains[].name survive, file order is preserved, unusable entries are skipped.
func TestParseResourceNames(t *testing.T) {
	raw := []byte(`{
		"services": [
			{"name": "16_tls", "addr": ":30201", "handler": {"type": "tls"}},
			{"name": ""},
			{"addr": ":30202"},
			"not-an-object",
			7,
			{"name": " 3_1_0_tcp "}
		],
		"chains": [
			{"name": "chains_16"},
			{"name": "hop_16", "hops": [{"name": "hop-1-inner"}]}
		],
		"limiters": [{"name": "lim-a"}],
		"authers": [{"name": "auth-a"}],
		"resolver": {"name": "resolver-a"}
	}`)

	got, err := parseResourceNames(raw)
	if err != nil {
		t.Fatalf("parseResourceNames: %v", err)
	}
	if want := []string{"16_tls", "3_1_0_tcp"}; !reflect.DeepEqual(got.Services, want) {
		t.Fatalf("services = %v, want %v", got.Services, want)
	}
	if want := []string{"chains_16", "hop_16"}; !reflect.DeepEqual(got.Chains, want) {
		t.Fatalf("chains = %v, want %v", got.Chains, want)
	}
}

// TestParseResourceNamesEmptyArraysKeepsArrays guards against a nil slice
// marshalling as null, which the panel cannot iterate.
func TestParseResourceNamesEmptyArraysKeepsArrays(t *testing.T) {
	got, err := parseResourceNames([]byte(`{"services": [], "chains": []}`))
	if err != nil {
		t.Fatalf("parseResourceNames: %v", err)
	}
	out, err := json.Marshal(got)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	compatAssertJSONEqual(t, []byte(`{"services":[],"chains":[]}`), out)
}

// TestParseResourceNamesRejectsInvalidJSON: an unparsable file must surface an
// error so the caller can skip the report instead of sending an empty snapshot.
func TestParseResourceNamesRejectsInvalidJSON(t *testing.T) {
	if _, err := parseResourceNames([]byte("{not json")); err == nil {
		t.Fatal("parseResourceNames accepted invalid JSON")
	}
}

// TestResolveGostConfigPath pins -C/--config scanning and the fallbacks.
func TestResolveGostConfigPath(t *testing.T) {
	tests := []struct {
		name string
		args []string
		want string
	}{
		{name: "no args", args: nil, want: defaultGostConfigPath},
		{name: "binary only", args: []string{"gost"}, want: defaultGostConfigPath},
		{name: "unrelated flags", args: []string{"gost", "-D", "-test.timeout=10m0s"}, want: defaultGostConfigPath},
		{name: "short -C", args: []string{"gost", "-C", "/app/gost.json"}, want: "/app/gost.json"},
		{name: "long --config", args: []string{"gost", "--config", "/etc/gost/gost.json"}, want: "/etc/gost/gost.json"},
		{name: "-C before --config wins", args: []string{"gost", "-C", "a.json", "--config", "b.json"}, want: "a.json"},
		{name: "-C without value", args: []string{"gost", "-C"}, want: defaultGostConfigPath},
		{name: "-C with empty value", args: []string{"gost", "-C", "  "}, want: defaultGostConfigPath},
		{name: "value trimmed", args: []string{"gost", "--config", "  /app/gost.json  "}, want: "/app/gost.json"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := resolveGostConfigPath(tc.args); got != tc.want {
				t.Fatalf("resolveGostConfigPath(%v) = %q, want %q", tc.args, got, tc.want)
			}
		})
	}
}

// TestGostConfigCandidatePaths: an explicit -C/--config must not fall through to
// a different file, while the default case walks cwd then the container path.
func TestGostConfigCandidatePaths(t *testing.T) {
	if got, want := gostConfigCandidatePaths([]string{"gost"}), []string{defaultGostConfigPath, fallbackGostConfigPath}; !reflect.DeepEqual(got, want) {
		t.Fatalf("default candidates = %v, want %v", got, want)
	}
	if got, want := gostConfigCandidatePaths([]string{"gost", "-C", "/app/gost.json"}), []string{"/app/gost.json"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("explicit candidates = %v, want %v", got, want)
	}
}

// TestLoadLocalResourceNamesReadsExplicitConfig covers the -C path end to end.
func TestLoadLocalResourceNamesReadsExplicitConfig(t *testing.T) {
	path := filepath.Join(t.TempDir(), "gost.json")
	body := `{"services":[{"name":"16_tls","addr":":30201"}],"chains":[{"name":"chains_16"}]}`
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatalf("write fixture: %v", err)
	}

	names, ok := loadLocalResourceNames([]string{"gost", "-C", path})
	if !ok {
		t.Fatal("loadLocalResourceNames reported failure for a readable config")
	}
	if want := []string{"16_tls"}; !reflect.DeepEqual(names.Services, want) {
		t.Fatalf("services = %v, want %v", names.Services, want)
	}
	if want := []string{"chains_16"}; !reflect.DeepEqual(names.Chains, want) {
		t.Fatalf("chains = %v, want %v", names.Chains, want)
	}
}

// TestLoadLocalResourceNamesMissingConfig: no report when the file cannot be
// read, so the panel never receives an empty snapshot it would treat as truth.
func TestLoadLocalResourceNamesMissingConfig(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "absent.json")
	if _, ok := loadLocalResourceNames([]string{"gost", "--config", missing}); ok {
		t.Fatal("loadLocalResourceNames reported success for a missing config")
	}
}

// TestServiceListReportWireFormat pins the upstream message the panel expects:
// type/success/message plus a data object holding only name arrays.
func TestServiceListReportWireFormat(t *testing.T) {
	reporter, server := compatWSPair(t)

	go reporter.sendServiceListReport(ServiceListData{
		Services: []string{"16_tls", "3_1_0_tcp"},
		Chains:   []string{"chains_16", "hop_16"},
	})

	resp := compatReadResponse(t, reporter, server)
	want := `{"type":"ServiceListReport","success":true,"message":"OK","data":{"services":["16_tls","3_1_0_tcp"],"chains":["chains_16","hop_16"]}}`
	got, err := json.Marshal(resp)
	if err != nil {
		t.Fatalf("marshal response: %v", err)
	}
	compatAssertJSONEqual(t, []byte(want), got)
}

// TestCallInfoRequestReportsServiceList: a server call with callType
// "info_request" must push a fresh ServiceListReport read from ./gost.json.
func TestCallInfoRequestReportsServiceList(t *testing.T) {
	body := `{"services":[{"name":"7_tcp","addr":":31234"}],"chains":[]}`
	if err := os.WriteFile(defaultGostConfigPath, []byte(body), 0o644); err != nil {
		t.Fatalf("write ./gost.json fixture: %v", err)
	}

	reporter, server := compatWSPair(t)
	reporter.handleReceivedMessage(websocket.TextMessage, []byte(`{"type":"call","callType":"info_request","requestId":"req-info","data":{}}`))

	resp := compatReadResponse(t, reporter, server)
	if resp["type"] != "ServiceListReport" {
		t.Fatalf("type = %v, want ServiceListReport", resp["type"])
	}
	data, ok := resp["data"].(map[string]any)
	if !ok {
		t.Fatalf("data = %v, want object", resp["data"])
	}
	services, _ := data["services"].([]any)
	if len(services) != 1 || services[0] != "7_tcp" {
		t.Fatalf("data.services = %v, want [7_tcp]", data["services"])
	}
	chains, ok := data["chains"].([]any)
	if !ok || len(chains) != 0 {
		t.Fatalf("data.chains = %v, want []", data["chains"])
	}
	if _, ok := data["ports"]; ok {
		t.Fatalf("data carries extra fields: %v", data)
	}
}

// TestCallWithoutInfoRequestStaysSilent keeps the existing "call is consumed
// without a reply" behaviour for every other call type.
func TestCallWithoutInfoRequestStaysSilent(t *testing.T) {
	reporter, server := compatWSPair(t)

	reporter.handleReceivedMessage(websocket.TextMessage, []byte(`{"type":"call","callType":"command","data":{"cmd":"noop"}}`))

	_ = server.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
	if _, _, err := server.ReadMessage(); err == nil {
		t.Fatal("non-info_request call produced a response")
	}
}
