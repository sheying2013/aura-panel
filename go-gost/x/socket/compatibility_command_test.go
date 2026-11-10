package socket

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// TestCommandMessageRequestIDParsing pins the wire contract the panel relies
// on: type, requestId and data survive a decode/encode cycle.
func TestCommandMessageRequestIDParsing(t *testing.T) {
	raw := `{"type":"AddService","requestId":"req-123","data":[{"name":"tcp-1","addr":":18080"},{"name":"udp-1","addr":":18081"}]}`

	var cmd CommandMessage
	if err := json.Unmarshal([]byte(raw), &cmd); err != nil {
		t.Fatalf("unmarshal command: %v", err)
	}
	if cmd.Type != "AddService" {
		t.Fatalf("type = %q, want AddService", cmd.Type)
	}
	if cmd.RequestId != "req-123" {
		t.Fatalf("requestId = %q, want req-123", cmd.RequestId)
	}

	out, err := json.Marshal(cmd)
	if err != nil {
		t.Fatalf("marshal command: %v", err)
	}
	compatAssertJSONEqual(t, []byte(raw), out)
}

// TestCommandResponseRequestIDContract checks the response field is echoed when
// present and omitted when the command carried none.
func TestCommandResponseRequestIDContract(t *testing.T) {
	withID, err := json.Marshal(CommandResponse{
		Type:      "AddServiceResponse",
		Success:   true,
		Message:   "OK",
		RequestId: "req-9",
	})
	if err != nil {
		t.Fatalf("marshal response with requestId: %v", err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(withID, &decoded); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if decoded["requestId"] != "req-9" {
		t.Fatalf("requestId = %v, want req-9", decoded["requestId"])
	}

	withoutID, err := json.Marshal(CommandResponse{Type: "AddServiceResponse", Success: true, Message: "OK"})
	if err != nil {
		t.Fatalf("marshal response without requestId: %v", err)
	}
	decoded = nil
	if err := json.Unmarshal(withoutID, &decoded); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if _, ok := decoded["requestId"]; ok {
		t.Fatalf("requestId present for empty value: %s", withoutID)
	}
}

// TestRequestIDSurvivesReceivePath drives handleReceivedMessage -> routeCommand
// -> sendResponse and asserts the requestId comes back unchanged.
func TestRequestIDSurvivesReceivePath(t *testing.T) {
	tests := []struct {
		name        string
		payload     string
		wantType    string
		wantRequest string
		wantSuccess bool
		check       func(t *testing.T, resp map[string]any)
	}{
		{
			name:        "unknown command",
			payload:     `{"type":"NoSuchCommand","requestId":"req-unknown","data":{}}`,
			wantType:    "UnknownCommandResponse",
			wantRequest: "req-unknown",
			wantSuccess: false,
		},
		{
			name:        "tcp ping rejects invalid target without dialing",
			payload:     `{"type":"TcpPing","requestId":"req-tcp","data":{"ip":"bad_host!","port":80,"count":1,"requestId":"req-tcp"}}`,
			wantType:    "TcpPingResponse",
			wantRequest: "req-tcp",
			wantSuccess: true,
			check: func(t *testing.T, resp map[string]any) {
				data, ok := resp["data"].(map[string]any)
				if !ok {
					t.Fatalf("data = %v, want object", resp["data"])
				}
				if data["requestId"] != "req-tcp" {
					t.Fatalf("data.requestId = %v, want req-tcp", data["requestId"])
				}
				if data["success"] != false {
					t.Fatalf("data.success = %v, want false", data["success"])
				}
				msg, _ := data["errorMessage"].(string)
				if !strings.Contains(msg, "无效") {
					t.Fatalf("errorMessage = %q, want invalid target hint", msg)
				}
			},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			reporter, server := compatWSPair(t)

			reporter.handleReceivedMessage(websocket.TextMessage, []byte(tc.payload))
			resp := compatReadResponse(t, reporter, server)

			if resp["type"] != tc.wantType {
				t.Fatalf("type = %v, want %s", resp["type"], tc.wantType)
			}
			if resp["requestId"] != tc.wantRequest {
				t.Fatalf("requestId = %v, want %s", resp["requestId"], tc.wantRequest)
			}
			if resp["success"] != tc.wantSuccess {
				t.Fatalf("success = %v, want %v", resp["success"], tc.wantSuccess)
			}
			if tc.check != nil {
				tc.check(t, resp)
			}
		})
	}
}

// TestResponseOmitsEmptyRequestID covers a command sent without requestId.
func TestResponseOmitsEmptyRequestID(t *testing.T) {
	reporter, server := compatWSPair(t)

	reporter.handleReceivedMessage(websocket.TextMessage, []byte(`{"type":"NoSuchCommand","data":{}}`))
	resp := compatReadResponse(t, reporter, server)

	if _, ok := resp["requestId"]; ok {
		t.Fatalf("requestId present for command without one: %v", resp["requestId"])
	}
	if resp["type"] != "UnknownCommandResponse" {
		t.Fatalf("type = %v, want UnknownCommandResponse", resp["type"])
	}
}

// TestCallMessageProducesNoResponse pins the "call" filter in
// handleReceivedMessage: callbacks are consumed without a reply.
func TestCallMessageProducesNoResponse(t *testing.T) {
	reporter, server := compatWSPair(t)

	reporter.handleReceivedMessage(websocket.TextMessage, []byte(`{"type":"call","requestId":"req-call","data":{"type":"ping"}}`))

	_ = server.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
	if _, _, err := server.ReadMessage(); err == nil {
		t.Fatal("call message produced a response")
	}
}

// TestCompressedEnvelopeReportsDecompressError documents the current behaviour
// of the compressed branch: a payload that is not raw gzip is answered with a
// DecompressError instead of a panic or a silent drop.
func TestCompressedEnvelopeReportsDecompressError(t *testing.T) {
	reporter, server := compatWSPair(t)

	reporter.handleReceivedMessage(websocket.TextMessage, []byte(`{"type":"AddService","compressed":true,"requestId":"req-zip","data":"not-gzip-bytes"}`))
	resp := compatReadResponse(t, reporter, server)

	if resp["type"] != "DecompressError" {
		t.Fatalf("type = %v, want DecompressError", resp["type"])
	}
	if resp["success"] != false {
		t.Fatalf("success = %v, want false", resp["success"])
	}
}
