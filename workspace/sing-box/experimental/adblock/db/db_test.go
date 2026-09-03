//go:build with_adblock

package db

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/sagernet/bbolt"
	bboltErrors "github.com/sagernet/bbolt/errors"
	"github.com/sagernet/sing-box/adapter"
)

func TestAdblockStatsPersist(t *testing.T) {
	path := filepath.Join(t.TempDir(), "adblock.db")
	dbInstance := New(context.Background(), path)
	if err := dbInstance.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	if err := dbInstance.StoreAdblockStats(42, 7); err != nil {
		t.Fatal(err)
	}
	if err := dbInstance.Close(); err != nil {
		t.Fatal(err)
	}

	reopened := New(context.Background(), path)
	if err := reopened.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	total, blocked, loaded := reopened.LoadAdblockStats()
	if !loaded {
		t.Fatal("missing adblock stats")
	}
	if total != 42 || blocked != 7 {
		t.Fatalf("unexpected adblock stats: total=%d blocked=%d", total, blocked)
	}
}

func TestReadOnlyAdblockStatsLoad(t *testing.T) {
	path := filepath.Join(t.TempDir(), "adblock.db")
	dbInstance := New(context.Background(), path)
	if err := dbInstance.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	if err := dbInstance.StoreAdblockStats(42, 7); err != nil {
		t.Fatal(err)
	}
	if err := dbInstance.Close(); err != nil {
		t.Fatal(err)
	}

	reader := NewReadOnly(context.Background(), path)
	if err := reader.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	total, blocked, loaded := reader.LoadAdblockStats()
	if !loaded {
		t.Fatal("missing adblock stats")
	}
	if total != 42 || blocked != 7 {
		t.Fatalf("unexpected adblock stats: total=%d blocked=%d", total, blocked)
	}
}

func TestReadOnlyAdblockDBMissingFile(t *testing.T) {
	reader := NewReadOnly(context.Background(), filepath.Join(t.TempDir(), "missing.db"))
	if err := reader.Start(adapter.StartStateInitialize); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("expected missing file error, got %v", err)
	}
}

func TestReadOnlyAdblockDBDoesNotMutateDatabase(t *testing.T) {
	const unknownBucket = "legacy_unknown_bucket"
	dir := t.TempDir()
	path := filepath.Join(dir, "adblock.db")

	writer := New(context.Background(), path)
	if err := writer.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	if err := writer.StoreAdblockStats(42, 7); err != nil {
		t.Fatal(err)
	}
	if err := writer.DB.Update(func(tx *bbolt.Tx) error {
		bucket, err := tx.CreateBucketIfNotExists([]byte(unknownBucket))
		if err != nil {
			return err
		}
		return bucket.Put([]byte("k"), []byte("v"))
	}); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}

	reader := NewReadOnly(context.Background(), path)
	if err := reader.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	total, blocked, loaded := reader.LoadAdblockStats()
	if err := reader.Close(); err != nil {
		t.Fatal(err)
	}
	if !loaded || total != 42 || blocked != 7 {
		t.Fatalf("unexpected read-only stats: loaded=%v total=%d blocked=%d", loaded, total, blocked)
	}

	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != string(before) {
		t.Fatalf("read-only adblock DB mutated the database (%d -> %d bytes)", len(before), len(after))
	}

	inspect, err := bbolt.Open(path, 0o666, &bbolt.Options{Timeout: time.Second, ReadOnly: true})
	if err != nil {
		t.Fatal(err)
	}
	defer inspect.Close()
	if err := inspect.View(func(tx *bbolt.Tx) error {
		if tx.Bucket([]byte(unknownBucket)) == nil {
			t.Fatal("read-only Start removed an unknown bucket")
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}

func TestReadOnlyAdblockDBRejectsWrites(t *testing.T) {
	path := filepath.Join(t.TempDir(), "adblock.db")
	writer := New(context.Background(), path)
	if err := writer.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	reader := NewReadOnly(context.Background(), path)
	if err := reader.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	if err := reader.StoreAdblockStats(1, 1); !errors.Is(err, bboltErrors.ErrDatabaseReadOnly) {
		t.Fatalf("expected read-only write error, got %v", err)
	}
}
