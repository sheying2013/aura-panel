package socket

import (
	"encoding/json"
	"net"
	"os"
	"testing"

	"github.com/gorilla/websocket"
)

// TestTcpPingDoesNotWriteConfig pins the daily node probe: TcpPing is a
// read-only diagnostic, so routeCommand must answer without rewriting gost.json
// on either the success or the failure branch, while an unknown command keeps
// the original persist behaviour.
func TestTcpPingDoesNotWriteConfig(t *testing.T) {
	oldWD, err := os.Getwd()
	if err != nil {
		t.Fatalf("getwd: %v", err)
	}
	workDir, err := os.MkdirTemp(".", "tcpping-config-")
	if err != nil {
		t.Fatalf("create work dir: %v", err)
	}
	if err := os.Chdir(workDir); err != nil {
		t.Fatalf("chdir %s: %v", workDir, err)
	}
	t.Cleanup(func() { _ = os.Chdir(oldWD) })

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen loopback: %v", err)
	}
	t.Cleanup(func() { listener.Close() })
	openPort := listener.Addr().(*net.TCPAddr).Port

	reporter, server := compatWSPair(t)

	send := func(cmd CommandMessage) map[string]any {
		t.Helper()
		payload, err := json.Marshal(cmd)
		if err != nil {
			t.Fatalf("marshal command: %v", err)
		}
		reporter.handleReceivedMessage(websocket.TextMessage, payload)
		return compatReadResponse(t, reporter, server)
	}
	configWritten := func() bool {
		_, err := os.Stat(defaultGostConfigPath)
		return err == nil
	}

	ok := send(CommandMessage{Type: "TcpPing", RequestId: "req-ok", Data: TcpPingRequest{
		IP: "127.0.0.1", Port: openPort, Count: 1, Timeout: 500, RequestId: "req-ok",
	}})
	if ok["type"] != "TcpPingResponse" || ok["success"] != true {
		t.Fatalf("TcpPing success response = %v", ok)
	}
	if configWritten() {
		t.Fatalf("successful TcpPing wrote %s", defaultGostConfigPath)
	}

	bad := send(CommandMessage{Type: "TcpPing", RequestId: "req-bad", Data: TcpPingRequest{
		IP: "127.0.0.1", Port: 1, Count: 1, Timeout: 200, RequestId: "req-bad",
	}})
	if bad["type"] != "TcpPingResponse" {
		t.Fatalf("TcpPing failure response = %v", bad)
	}
	if configWritten() {
		t.Fatalf("failed TcpPing wrote %s", defaultGostConfigPath)
	}

	// The health scheduler uses port zero to exercise the control channel without dialing.
	probe := send(CommandMessage{Type: "TcpPing", RequestId: "req-probe", Data: TcpPingRequest{
		IP: "127.0.0.1", Port: 0, Count: 1, Timeout: 200, RequestId: "req-probe",
	}})
	data, okData := probe["data"].(map[string]any)
	if probe["type"] != "TcpPingResponse" || probe["success"] != true || !okData || data["success"] != false || data["errorMessage"] != "无效的端口号，范围应为1-65535" {
		t.Fatalf("zero-port control probe response = %v", probe)
	}
	if configWritten() {
		t.Fatalf("zero-port control probe wrote %s", defaultGostConfigPath)
	}

	unknown := send(CommandMessage{Type: "__flush_unknown__", RequestId: "req-unknown"})
	if unknown["type"] != "UnknownCommandResponse" || unknown["success"] != false {
		t.Fatalf("unknown command response = %v", unknown)
	}
	if !configWritten() {
		t.Fatalf("unknown command did not write %s", defaultGostConfigPath)
	}
}
