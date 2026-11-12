package service

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	xcrypto "github.com/go-gost/x/internal/util/crypto"
)

// 自造测试凭据，仅用于本地断言，不是任何真实节点的 secret。
const fixtureReportSecret = "fixture-report-secret-not-real"

var errFixtureTransport = errors.New("fixture transport failure")

type fixtureTimeoutError struct{}

func (fixtureTimeoutError) Error() string   { return "fixture timeout" }
func (fixtureTimeoutError) Timeout() bool   { return true }
func (fixtureTimeoutError) Temporary() bool { return false }

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

// setReporterState 替换上报器全局状态，并在测试结束后恢复。
func setReporterState(t *testing.T, uploadURL, configURL string, aes *xcrypto.AESCrypto) {
	t.Helper()
	oldUpload, oldConfig, oldAES := httpReportURL, configReportURL, httpAESCrypto
	httpReportURL, configReportURL, httpAESCrypto = uploadURL, configURL, aes
	t.Cleanup(func() {
		httpReportURL, configReportURL, httpAESCrypto = oldUpload, oldConfig, oldAES
	})
}

// failTransport 让该测试内的 http.Client 使用必然失败的 transport，
// 不发起任何真实网络请求。
func failTransport(t *testing.T, err error) {
	t.Helper()
	old := http.DefaultTransport
	http.DefaultTransport = roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, err
	})
	t.Cleanup(func() { http.DefaultTransport = old })
}

func assertNoReportSecret(t *testing.T, where, text string) {
	t.Helper()
	if strings.Contains(text, fixtureReportSecret) {
		t.Fatalf("%s leaks the report secret", where)
	}
	if strings.Contains(text, "secret=") {
		t.Fatalf("%s leaks the secret query", where)
	}
}

func TestSendBatchTrafficReportTransportErrorHidesSecret(t *testing.T) {
	setReporterState(t, "http://report.invalid/flow/upload?secret="+fixtureReportSecret, "", nil)
	failTransport(t, errFixtureTransport)

	ok, err := sendBatchTrafficReport(context.Background(), []TrafficReportItem{{N: "svc", U: 10, D: 20}})
	if ok || err == nil {
		t.Fatalf("expected failure, got ok=%v err=%v", ok, err)
	}
	if !errors.Is(err, errFixtureTransport) {
		t.Fatalf("transport error identity lost: %v", err)
	}
	if !strings.Contains(err.Error(), "发送HTTP请求失败") {
		t.Fatalf("unexpected error text: %v", err)
	}
	assertNoReportSecret(t, "batch transport error", err.Error())
}

func TestSendBatchTrafficReportPreservesUnderlyingErrorType(t *testing.T) {
	setReporterState(t, "http://report.invalid/flow/upload?secret="+fixtureReportSecret, "", nil)
	failTransport(t, fixtureTimeoutError{})

	_, err := sendBatchTrafficReport(context.Background(), []TrafficReportItem{{N: "svc", U: 1, D: 2}})
	if err == nil {
		t.Fatal("expected failure")
	}
	if !errors.Is(err, fixtureTimeoutError{}) {
		t.Fatalf("errors.Is lost the underlying error: %v", err)
	}
	var timeoutErr interface{ Timeout() bool }
	if !errors.As(err, &timeoutErr) || !timeoutErr.Timeout() {
		t.Fatalf("errors.As lost the timeout semantics: %v", err)
	}
	assertNoReportSecret(t, "typed transport error", err.Error())
}

func TestSendConfigReportTransportErrorHidesSecret(t *testing.T) {
	setReporterState(t, "", "http://report.invalid/flow/config?secret="+fixtureReportSecret, nil)
	failTransport(t, errFixtureTransport)

	ok, err := sendConfigReport(context.Background())
	if ok || err == nil {
		t.Fatalf("expected failure, got ok=%v err=%v", ok, err)
	}
	if !errors.Is(err, errFixtureTransport) {
		t.Fatalf("transport error identity lost: %v", err)
	}
	assertNoReportSecret(t, "config transport error", err.Error())
}

func TestSendBatchTrafficReportInvalidURLHidesSecret(t *testing.T) {
	setReporterState(t, "http://[::1]:badport/flow/upload?secret="+fixtureReportSecret, "", nil)

	ok, err := sendBatchTrafficReport(context.Background(), []TrafficReportItem{{N: "svc", U: 1, D: 2}})
	if ok || err == nil {
		t.Fatalf("expected failure, got ok=%v err=%v", ok, err)
	}
	if !strings.Contains(err.Error(), "创建HTTP请求失败") {
		t.Fatalf("unexpected error text: %v", err)
	}
	assertNoReportSecret(t, "invalid URL error", err.Error())
}

type capturedReport struct {
	path   string
	secret string
	body   []byte
}

func TestSendBatchTrafficReportSuccessUnchanged(t *testing.T) {
	gotCh := make(chan capturedReport, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		gotCh <- capturedReport{path: r.URL.Path, secret: r.URL.Query().Get("secret"), body: body}
		fmt.Fprint(w, "ok")
	}))
	defer srv.Close()

	setReporterState(t, srv.URL+"/flow/upload?secret="+fixtureReportSecret, "", nil)

	ok, err := sendBatchTrafficReport(context.Background(), []TrafficReportItem{{N: "svc", U: 10, D: 20}})
	if !ok || err != nil {
		t.Fatalf("expected success, got ok=%v err=%v", ok, err)
	}

	got := <-gotCh
	if got.path != "/flow/upload" {
		t.Fatalf("unexpected path %q", got.path)
	}
	if got.secret != fixtureReportSecret {
		t.Fatalf("secret query not sent as before")
	}
	var items []TrafficReportItem
	if err := json.Unmarshal(got.body, &items); err != nil {
		t.Fatalf("decode report body: %v", err)
	}
	if len(items) != 1 || items[0].N != "svc" || items[0].U != 10 || items[0].D != 20 {
		t.Fatalf("unexpected report body: %+v", items)
	}
}

func TestSendBatchTrafficReportEncryptedPayloadUnchanged(t *testing.T) {
	aes, err := xcrypto.NewAESCrypto(fixtureReportSecret)
	if err != nil {
		t.Fatalf("create fixture AES: %v", err)
	}
	gotCh := make(chan capturedReport, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		gotCh <- capturedReport{body: body}
		fmt.Fprint(w, "ok")
	}))
	defer srv.Close()

	setReporterState(t, srv.URL+"/flow/upload?secret="+fixtureReportSecret, "", aes)

	ok, err := sendBatchTrafficReport(context.Background(), []TrafficReportItem{{N: "svc", U: 3, D: 4}})
	if !ok || err != nil {
		t.Fatalf("expected success, got ok=%v err=%v", ok, err)
	}

	var envelope struct {
		Encrypted bool   `json:"encrypted"`
		Data      string `json:"data"`
		Timestamp int64  `json:"timestamp"`
	}
	if err := json.Unmarshal((<-gotCh).body, &envelope); err != nil {
		t.Fatalf("decode encrypted envelope: %v", err)
	}
	if !envelope.Encrypted || envelope.Data == "" || envelope.Timestamp == 0 {
		t.Fatalf("unexpected encrypted envelope: %+v", envelope)
	}
	if plain, err := aes.Decrypt(envelope.Data); err != nil || !strings.Contains(string(plain), "\"n\":\"svc\"") {
		t.Fatalf("encrypted payload does not round-trip: %v", err)
	}
}

func TestStartConfigReporterLogHidesSecret(t *testing.T) {
	setReporterState(t, "", "http://report.invalid/flow/config?secret="+fixtureReportSecret, nil)
	failTransport(t, errFixtureTransport)

	oldStdout := os.Stdout
	pr, pw, err := os.Pipe()
	if err != nil {
		t.Fatalf("create pipe: %v", err)
	}
	os.Stdout = pw
	defer func() { os.Stdout = oldStdout }()
	defer pw.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	lines := make(chan string, 32)
	go func() {
		sc := bufio.NewScanner(pr)
		for sc.Scan() {
			lines <- sc.Text()
		}
		close(lines)
	}()

	done := make(chan struct{})
	go func() {
		StartConfigReporter(ctx)
		close(done)
	}()

	var out strings.Builder
	deadline := time.After(5 * time.Second)
	found := false
	for !found {
		select {
		case line, ok := <-lines:
			if !ok {
				t.Fatal("reporter output ended before the initial failure log")
			}
			out.WriteString(line)
			out.WriteString("\n")
			if strings.Contains(line, "初始配置上报失败") {
				found = true
			}
		case <-deadline:
			t.Fatal("timed out waiting for the initial config report log")
		}
	}

	cancel()
	<-done
	os.Stdout = oldStdout
	pw.Close()
	for line := range lines {
		out.WriteString(line)
		out.WriteString("\n")
	}

	if !strings.Contains(out.String(), "发送HTTP请求失败") {
		t.Fatalf("failure log missing request error: %q", out.String())
	}
	assertNoReportSecret(t, "config reporter log", out.String())
}
