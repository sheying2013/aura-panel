package crypto

import (
	"encoding/base64"
	"testing"
)

// This vector was generated independently with Node.js crypto using a fixed
// nonce; production encryption must continue to generate a fresh nonce.
const compatibilitySecret = "fixture-secret-兼容"
const compatibilityPlaintext = `{"type":"AddService","requestId":"fixture-001"}`
const compatibilityCiphertext = "AAECAwQFBgcICQoLIjM7VBZ59/ESDHlfckTlMvBrpShjcG5XQdA/SAoCM1s9TlTPZxZPuaT3I7TILtoxdn/Q15aEg06arONnjBwC"

func TestDecryptIndependentAES256GCMVector(t *testing.T) {
	c, err := NewAESCrypto(compatibilitySecret)
	if err != nil {
		t.Fatal(err)
	}
	got, err := c.DecryptString(compatibilityCiphertext)
	if err != nil {
		t.Fatal(err)
	}
	if got != compatibilityPlaintext {
		t.Fatalf("got %q, want %q", got, compatibilityPlaintext)
	}
}

func TestAESGCMRejectsWrongKeyAndTampering(t *testing.T) {
	wrongKey, err := NewAESCrypto("wrong-secret")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := wrongKey.Decrypt(compatibilityCiphertext); err == nil {
		t.Fatal("wrong key accepted")
	}
	c, err := NewAESCrypto(compatibilitySecret)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := base64.StdEncoding.DecodeString(compatibilityCiphertext)
	if err != nil {
		t.Fatal(err)
	}
	raw[len(raw)-1] ^= 1
	if _, err := c.Decrypt(base64.StdEncoding.EncodeToString(raw)); err == nil {
		t.Fatal("tampered authentication tag accepted")
	}
}

func TestAESGCMRoundTripAndInputValidation(t *testing.T) {
	if _, err := NewAESCrypto(""); err == nil {
		t.Fatal("empty secret accepted")
	}
	c, err := NewAESCrypto(compatibilitySecret)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.Encrypt(nil); err == nil {
		t.Fatal("empty plaintext accepted")
	}
	for _, invalid := range []string{"", "not-base64!", "AA=="} {
		if _, err := c.Decrypt(invalid); err == nil {
			t.Fatalf("invalid ciphertext %q accepted", invalid)
		}
	}
	ciphertext, err := c.EncryptString(compatibilityPlaintext)
	if err != nil {
		t.Fatal(err)
	}
	got, err := c.DecryptString(ciphertext)
	if err != nil {
		t.Fatal(err)
	}
	if got != compatibilityPlaintext {
		t.Fatalf("got %q, want %q", got, compatibilityPlaintext)
	}
}
