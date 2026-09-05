package libcore

import (
	"path/filepath"
	"testing"

	"github.com/sagernet/bbolt"
)

func TestCanonicalClashModeMatchesCaseInsensitive(t *testing.T) {
	mode, ok := canonicalClashMode([]string{"Rule", "Streaming"}, "streaming")
	if !ok {
		t.Fatal("expected mode to match")
	}
	if mode != "Streaming" {
		t.Fatalf("expected canonical mode Streaming, got %q", mode)
	}
}

func TestCanonicalClashModeRejectsUnknownMode(t *testing.T) {
	_, ok := canonicalClashMode([]string{"Rule", "Streaming"}, "Unknown")
	if ok {
		t.Fatal("expected unknown mode to be rejected")
	}
}

func TestNilBoxClashModeMethodsAreEmptyNoOps(t *testing.T) {
	currentMode, err := CurrentClashMode(nil)
	if err != nil {
		t.Fatal(err)
	}
	if currentMode != "" {
		t.Fatalf("expected empty current mode, got %q", currentMode)
	}

	modeList, err := ClashModeList(nil)
	if err != nil {
		t.Fatal(err)
	}
	if modeList != "[]" {
		t.Fatalf("expected empty mode list, got %q", modeList)
	}

	if err := SetClashMode(nil, "Rule"); err != nil {
		t.Fatal(err)
	}
}

func TestLoadClashModeFromCacheReturnsPersistedMode(t *testing.T) {
	cachePath := filepath.Join(t.TempDir(), "cache.db")
	database, err := bbolt.Open(cachePath, 0o600, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := database.Update(func(transaction *bbolt.Tx) error {
		bucket, err := transaction.CreateBucket([]byte("clash_mode"))
		if err != nil {
			return err
		}
		return bucket.Put([]byte("default"), []byte("Streaming"))
	}); err != nil {
		database.Close()
		t.Fatal(err)
	}
	if err := database.Close(); err != nil {
		t.Fatal(err)
	}

	mode, err := LoadClashModeFromCache(cachePath)
	if err != nil {
		t.Fatal(err)
	}
	if mode != "Streaming" {
		t.Fatalf("expected persisted mode Streaming, got %q", mode)
	}
}

func TestLoadClashModeFromCacheAllowsMissingCache(t *testing.T) {
	mode, err := LoadClashModeFromCache(filepath.Join(t.TempDir(), "missing.db"))
	if err != nil {
		t.Fatal(err)
	}
	if mode != "" {
		t.Fatalf("expected empty mode for missing cache, got %q", mode)
	}
}
