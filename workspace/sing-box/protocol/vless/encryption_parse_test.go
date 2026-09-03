package vless

import (
	"encoding/base64"
	"strings"
	"testing"
)

func TestParseServerDecryptionShortPaddingBeforeKey(t *testing.T) {
	key := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	cfg, err := parseServerDecryption("mlkem768x25519plus.native.10s.pad." + key)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.padding != "pad" {
		t.Fatalf("padding = %q", cfg.padding)
	}
	if len(cfg.keys) != 1 {
		t.Fatalf("keys = %d", len(cfg.keys))
	}
}

func TestParseClientEncryptionShortPaddingBeforeKey(t *testing.T) {
	key := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	cfg, err := parseClientEncryption("mlkem768x25519plus.native.0rtt.pad." + key)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.padding != "pad" {
		t.Fatalf("padding = %q", cfg.padding)
	}
	if len(cfg.keys) != 1 {
		t.Fatalf("keys = %d", len(cfg.keys))
	}
}

func TestParseClientEncryptionRejectsShortPaddingAfterKey(t *testing.T) {
	key := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	_, err := parseClientEncryption("mlkem768x25519plus.native.0rtt." + key + ".pad")
	if err == nil || !strings.Contains(err.Error(), "invalid encryption key") {
		t.Fatalf("err = %v", err)
	}
}
