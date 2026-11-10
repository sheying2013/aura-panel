package quic

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"net"
	"testing"
	"time"

	"github.com/go-gost/core/listener"
	"github.com/go-gost/core/logger"
	xmetadata "github.com/go-gost/x/metadata"
	qgo "github.com/quic-go/quic-go"
)

func newTestListener(t *testing.T, addr string, tlsCfg *tls.Config) *quicListener {
	t.Helper()
	return NewListener(
		listener.AddrOption(addr),
		listener.TLSConfigOption(tlsCfg),
		// logger.Default() is nil unless the daemon installs one.
		listener.LoggerOption(logger.LoggerGroup()),
	).(*quicListener)
}

// A listener that never completed Init must stay inert: the zero value of
// quic.EarlyListener has a nil baseServer, so Close/Addr/Accept used to panic.
func TestZeroValueListenerIsInert(t *testing.T) {
	l := &quicListener{}

	if err := l.Close(); err != nil {
		t.Fatalf("Close on a never initialised listener: %v", err)
	}
	if addr := l.Addr(); addr != nil {
		t.Fatalf("Addr on a never initialised listener = %v, want nil", addr)
	}
	if _, err := l.Accept(); err != listener.ErrClosed {
		t.Fatalf("Accept on a never initialised listener = %v, want %v", err, listener.ErrClosed)
	}
}

// Regression: Init assigned NextProtos on options.TLSConfig without checking
// for nil, panicking after the socket had already been allocated.
func TestInitWithoutTLSConfig(t *testing.T) {
	l := newTestListener(t, "127.0.0.1:0", nil)
	if err := l.Init(xmetadata.NewMetadata(nil)); err != nil {
		t.Fatalf("Init without TLSConfig: %v", err)
	}
	defer l.Close()

	if l.Addr() == nil {
		t.Fatal("Addr() is nil after a successful Init")
	}
}

// Init must not write to the *tls.Config it was handed: the service parser
// shares that pointer with the handler.
func TestInitDoesNotMutateCallerTLSConfig(t *testing.T) {
	cfg := &tls.Config{}
	l := newTestListener(t, "127.0.0.1:0", cfg)
	if err := l.Init(xmetadata.NewMetadata(nil)); err == nil {
		defer l.Close()
	}

	if len(cfg.NextProtos) != 0 {
		t.Fatalf("Init mutated the caller's tls.Config: NextProtos=%v", cfg.NextProtos)
	}
}

// A failed Init must leave a listener that is still safe to Close, and the
// socket it already holds must be released.
func TestCloseAfterFailedInit(t *testing.T) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("ListenPacket: %v", err)
	}
	defer pc.Close()

	l := newTestListener(t, pc.LocalAddr().String(), nil)
	if err := l.Init(xmetadata.NewMetadata(nil)); err == nil {
		l.Close()
		t.Fatal("Init succeeded on an already bound address")
	}

	// Regression: Close dereferenced the zero-valued quic.EarlyListener.
	if err := l.Close(); err != nil {
		t.Fatalf("Close after a failed Init: %v", err)
	}
	if addr := l.Addr(); addr != nil {
		t.Fatalf("Addr after a failed Init = %v, want nil", addr)
	}
}

// Regression: quic-go only closes the PacketConn it created itself, so Close
// left the UDP socket bound and a restart could not reuse the port.
func TestCloseReleasesSocket(t *testing.T) {
	l := newTestListener(t, "127.0.0.1:0", nil)
	if err := l.Init(xmetadata.NewMetadata(nil)); err != nil {
		t.Fatalf("Init: %v", err)
	}
	addr := l.Addr().String()

	if err := l.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	pc, err := net.ListenPacket("udp", addr)
	if err != nil {
		t.Fatalf("socket still bound on %s after Close: %v", addr, err)
	}
	pc.Close()
}

// The accepted stream must still reach the caller after the ownership rework.
func TestAcceptStreamAfterHandshake(t *testing.T) {
	l := newTestListener(t, "127.0.0.1:0", &tls.Config{
		Certificates: []tls.Certificate{testCertificate(t)},
	})
	if err := l.Init(xmetadata.NewMetadata(nil)); err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer l.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	client, err := qgo.DialAddr(ctx, l.Addr().String(), &tls.Config{
		InsecureSkipVerify: true,
		NextProtos:         []string{"quic/v1"},
	}, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer client.CloseWithError(0, "")

	stream, err := client.OpenStreamSync(ctx)
	if err != nil {
		t.Fatalf("open stream: %v", err)
	}
	if _, err := stream.Write([]byte("ping")); err != nil {
		t.Fatalf("write: %v", err)
	}

	type result struct {
		conn net.Conn
		err  error
	}
	ch := make(chan result, 1)
	go func() {
		conn, err := l.Accept()
		ch <- result{conn, err}
	}()

	select {
	case r := <-ch:
		if r.err != nil {
			t.Fatalf("Accept: %v", r.err)
		}
		defer r.conn.Close()
		if r.conn.RemoteAddr() == nil || r.conn.LocalAddr() == nil {
			t.Fatal("accepted conn has no addresses")
		}
		buf := make([]byte, 4)
		if _, err := r.conn.Read(buf); err != nil {
			t.Fatalf("read: %v", err)
		}
		if string(buf) != "ping" {
			t.Fatalf("read %q, want %q", buf, "ping")
		}
	case <-ctx.Done():
		t.Fatal("Accept timed out")
	}
}

func testCertificate(t *testing.T) tls.Certificate {
	t.Helper()

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "gost-quic-test"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IPAddresses:           []net.IP{net.ParseIP("127.0.0.1")},
		IsCA:                  true,
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("create certificate: %v", err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}
