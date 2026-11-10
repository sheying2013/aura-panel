package socket

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// compatSecret mirrors the production path where a non-empty secret makes the
// reporter wrap every outgoing message in an encrypted envelope.
const compatSecret = "compat-test-secret"

// TestMain isolates relative config writes under an explicitly selected local
// test root. Generated fixtures are retained for inspection.
func TestMain(m *testing.M) {
	root := os.Getenv("FLUX_COMPAT_TEST_ROOT")
	if root == "" {
		panic("FLUX_COMPAT_TEST_ROOT must point to a directory in the local repository")
	}
	dir, err := os.MkdirTemp(root, "gost-socket-compat-")
	if err != nil {
		panic(err)
	}
	wd, _ := os.Getwd()
	if err := os.Chdir(dir); err != nil {
		panic(err)
	}

	code := m.Run()

	if wd != "" {
		_ = os.Chdir(wd)
	}
	os.Exit(code)
}

// compatWSPair wires a WebSocketReporter to an in-process loopback peer over
// httptest. Nothing leaves 127.0.0.1 and no real gost node is involved.
func compatWSPair(t *testing.T) (*WebSocketReporter, *websocket.Conn) {
	t.Helper()

	peers := make(chan *websocket.Conn, 1)
	upgrader := websocket.Upgrader{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		peers <- c
	}))
	t.Cleanup(srv.Close)

	wsURL := "ws" + strings.TrimPrefix(srv.URL, "http")
	client, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("dial loopback websocket: %v", err)
	}
	t.Cleanup(func() { client.Close() })

	select {
	case server := <-peers:
		t.Cleanup(func() { server.Close() })

		reporter := NewWebSocketReporter(wsURL, compatSecret)
		reporter.connMutex.Lock()
		reporter.conn = client
		reporter.connected = true
		reporter.connMutex.Unlock()

		return reporter, server
	case <-time.After(5 * time.Second):
		t.Fatal("loopback websocket handshake timed out")
	}

	return nil, nil
}

// compatReadResponse reads one reporter message, unwrapping the AES envelope
// the reporter applies whenever a secret is configured.
func compatReadResponse(t *testing.T, reporter *WebSocketReporter, server *websocket.Conn) map[string]any {
	t.Helper()

	_ = server.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, data, err := server.ReadMessage()
	if err != nil {
		t.Fatalf("read response: %v", err)
	}

	var envelope struct {
		Encrypted bool   `json:"encrypted"`
		Data      string `json:"data"`
	}
	if err := json.Unmarshal(data, &envelope); err == nil && envelope.Encrypted {
		if reporter.aesCrypto == nil {
			t.Fatalf("encrypted response received but reporter has no cipher")
		}
		plain, err := reporter.aesCrypto.Decrypt(envelope.Data)
		if err != nil {
			t.Fatalf("decrypt response: %v", err)
		}
		data = plain
	}

	var out map[string]any
	if err := json.Unmarshal(data, &out); err != nil {
		t.Fatalf("decode response %s: %v", data, err)
	}
	return out
}

// compatAssertJSONEqual compares two JSON documents semantically so key order
// and whitespace never decide the result.
func compatAssertJSONEqual(t *testing.T, want, got []byte) {
	t.Helper()

	var w, g any
	if err := json.Unmarshal(want, &w); err != nil {
		t.Fatalf("decode want %s: %v", want, err)
	}
	if err := json.Unmarshal(got, &g); err != nil {
		t.Fatalf("decode got %s: %v", got, err)
	}
	if !reflect.DeepEqual(w, g) {
		t.Fatalf("json mismatch\nwant: %s\ngot: %s", want, got)
	}
}
