package cachefile

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/sagernet/bbolt"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
)

// newStartedCache opens a read-write cache file at path, starts it and returns
// the started instance. The caller is responsible for closing it.
func newStartedCache(t *testing.T, path string) *CacheFile {
	t.Helper()
	cacheFile := New(context.Background(), logger.NOP(), option.CacheFileOptions{
		Enabled: true,
		Path:    path,
	})
	if err := cacheFile.Start(adapter.StartStateInitialize); err != nil {
		t.Fatalf("start rw cache: %v", err)
	}
	return cacheFile
}

func TestReadOnlyLoadsMASQUEConfig(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cache.db")

	writer := newStartedCache(t, path)
	tag := "masque-out"
	masqueConfig := &adapter.SavedBinary{
		Content:     []byte(`{"client_id":"masque-register"}`),
		LastUpdated: time.Unix(42, 0),
		LastEtag:    "etag",
	}
	if err := writer.SaveMASQUEConfig(tag, masqueConfig); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	reader := NewReadOnly(context.Background(), path)
	if err := reader.Start(adapter.StartStateInitialize); err != nil {
		t.Fatalf("start ro cache: %v", err)
	}
	defer reader.Close()

	saved := reader.LoadMASQUEConfig(tag)
	if saved == nil {
		t.Fatal("missing MASQUE config from read-only cache")
	}
	if string(saved.Content) != string(masqueConfig.Content) {
		t.Fatalf("unexpected content: %q", saved.Content)
	}
}

// TestReadOnlyDoesNotMutateDatabase is the regression test for the
// "page N already freed" panic.
//
// The panic happened because LoadMASQUEConfigFromCache (libcore) opened the
// shared cache.db with a second read-write CacheFile while the box was still
// running. Start() of a read-write CacheFile commits a housekeeping
// transaction (it drops buckets it does not recognize); doing that from a
// second handle racing with the box diverges the in-memory freelist and
// corrupts the database.
//
// A read-only CacheFile must never write. We prove that by leaving an
// "unknown" bucket on disk (one that a read-write Start would delete) and
// asserting the file is byte-identical before and after a read-only open, and
// that the unknown bucket survives.
func TestReadOnlyDoesNotMutateDatabase(t *testing.T) {
	const unknownBucket = "legacy_unknown_bucket"
	dir := t.TempDir()
	mainPath := filepath.Join(dir, "cache.db")

	// Build a database holding a MASQUE config plus a top-level bucket that
	// read-write Start() would remove during housekeeping.
	writer := newStartedCache(t, mainPath)
	tag := "masque-out"
	if err := writer.SaveMASQUEConfig(tag, &adapter.SavedBinary{
		Content:     []byte(`{"client_id":"masque-register"}`),
		LastUpdated: time.Unix(42, 0),
		LastEtag:    "etag",
	}); err != nil {
		t.Fatal(err)
	}
	if err := writer.DB.Update(func(tx *bbolt.Tx) error {
		b, err := tx.CreateBucketIfNotExists([]byte(unknownBucket))
		if err != nil {
			return err
		}
		return b.Put([]byte("k"), []byte("v"))
	}); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	dirty, err := os.ReadFile(mainPath)
	if err != nil {
		t.Fatal(err)
	}

	// Sanity check the premise: a read-write re-open must mutate the file by
	// running housekeeping (deleting the unknown bucket). Verified on a copy so
	// the main file stays pristine for the read-only check below.
	rwPath := filepath.Join(dir, "rw-copy.db")
	if err := os.WriteFile(rwPath, dirty, 0o666); err != nil {
		t.Fatal(err)
	}
	rw := newStartedCache(t, rwPath)
	if err := rw.Close(); err != nil {
		t.Fatal(err)
	}
	if afterRW, _ := os.ReadFile(rwPath); string(afterRW) == string(dirty) {
		t.Fatal("expected read-write Start to mutate the database via housekeeping")
	}

	// The actual fix: a read-only open must not touch the file at all.
	reader := NewReadOnly(context.Background(), mainPath)
	if err := reader.Start(adapter.StartStateInitialize); err != nil {
		t.Fatalf("start ro cache: %v", err)
	}
	if saved := reader.LoadMASQUEConfig(tag); saved == nil {
		t.Fatal("missing MASQUE config from read-only cache")
	}
	if err := reader.Close(); err != nil {
		t.Fatal(err)
	}

	afterRO, err := os.ReadFile(mainPath)
	if err != nil {
		t.Fatal(err)
	}
	if string(afterRO) != string(dirty) {
		t.Fatalf("read-only cache mutated the database (%d -> %d bytes)", len(dirty), len(afterRO))
	}

	// The unknown bucket must still be present, proving no housekeeping ran.
	inspect := NewReadOnly(context.Background(), mainPath)
	if err := inspect.Start(adapter.StartStateInitialize); err != nil {
		t.Fatalf("start ro inspect cache: %v", err)
	}
	var unknownSeen bool
	if err := inspect.DB.View(func(tx *bbolt.Tx) error {
		if b := tx.Bucket([]byte(unknownBucket)); b != nil {
			unknownSeen = true
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	inspect.Close()
	if !unknownSeen {
		t.Fatal("read-only Start removed an unknown bucket; it must never run housekeeping")
	}
}

// TestReadOnlyMissingFile reports os.ErrNotExist so callers can treat a missing
// cache (first run, nothing cached yet) as "no config" instead of a hard error.
func TestReadOnlyMissingFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "does-not-exist.db")
	reader := NewReadOnly(context.Background(), path)
	err := reader.Start(adapter.StartStateInitialize)
	if err == nil {
		t.Fatal("expected an error for a missing cache file")
	}
	if !os.IsNotExist(err) {
		t.Fatalf("expected os.ErrNotExist, got %v", err)
	}
}
