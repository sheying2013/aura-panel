package socket

import (
	"net"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

func TestWebSocketStartupLogDoesNotExposeNodeSecret(t *testing.T) {
	const secret = "synthetic-only-credential-147"
	if os.Getenv("AURA_TEST_WS_START_LOG") == "1" {
		reporter := StartWebSocketReporterWithConfig("127.0.0.1:1", secret, 0, 0, 0, "test")
		time.Sleep(100 * time.Millisecond)
		reporter.Stop()
		return
	}
	// The child imports service before TestMain, which needs a synthetic config in cwd.
	config, err := os.OpenFile("config.json", os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := config.WriteString("{\"addr\":\"127.0.0.1:0\",\"secret\":\"synthetic\"}"); err != nil {
		t.Fatal(err)
	}
	if err := config.Close(); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(os.Args[0], "-test.run=^TestWebSocketStartupLogDoesNotExposeNodeSecret$")
	cmd.Env = append(os.Environ(), "AURA_TEST_WS_START_LOG=1")
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("child failed: %v", err)
	}
	if strings.Contains(string(output), secret) || strings.Contains(string(output), "secret=") {
		t.Fatal("startup log disclosed credential")
	}
}

func TestWebSocketErrorsDoNotExposeNodeSecret(t *testing.T) {
	const secret = "synthetic-only-credential-147"
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := listener.Addr().String()
	if err := listener.Close(); err != nil {
		t.Fatal(err)
	}
	cases := []struct{ name, address string }{
		{"invalid URL", "[invalid"},
		{"connection failure", address},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			w := &WebSocketReporter{addr: tc.address, secret: secret, version: "test"}
			err := w.connect()
			if err == nil {
				t.Fatal("expected connection failure")
			}
			if strings.Contains(err.Error(), secret) || strings.Contains(err.Error(), "secret=") {
				t.Fatal("connection error disclosed credential")
			}
		})
	}
}
