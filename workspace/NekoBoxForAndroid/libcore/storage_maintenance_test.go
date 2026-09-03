package libcore

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/sagernet/bbolt"
)

func TestPerformStorageMaintenancePrunesAdblockFilters(t *testing.T) {
	cacheDirectory := t.TempDir()
	previousTempPath := tempPath
	tempPath = cacheDirectory
	t.Cleanup(func() { tempPath = previousTempPath })

	adblockPath := filepath.Join(cacheDirectory, "adblock.db")
	database, err := bbolt.Open(adblockPath, 0o600, nil)
	if err != nil {
		t.Fatal(err)
	}
	err = database.Update(func(transaction *bbolt.Tx) error {
		bucket, err := transaction.CreateBucket(adblockFiltersBucket)
		if err != nil {
			return err
		}
		if err := bucket.Put([]byte(adblockCacheTag("https://enabled.example/filter")), []byte("enabled")); err != nil {
			return err
		}
		return bucket.Put([]byte(adblockCacheTag("https://stale.example/filter")), []byte("stale"))
	})
	if closeErr := database.Close(); err != nil || closeErr != nil {
		t.Fatal(errors.Join(err, closeErr))
	}

	if err := PerformStorageMaintenance(true, "https://enabled.example/filter", ""); err != nil {
		t.Fatal(err)
	}

	database, err = bbolt.Open(adblockPath, 0o600, &bbolt.Options{ReadOnly: true})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = database.Close() })
	err = database.View(func(transaction *bbolt.Tx) error {
		bucket := transaction.Bucket(adblockFiltersBucket)
		if bucket.Get([]byte(adblockCacheTag("https://enabled.example/filter"))) == nil {
			t.Error("enabled filter was removed")
		}
		if bucket.Get([]byte(adblockCacheTag("https://stale.example/filter"))) != nil {
			t.Error("stale filter was retained")
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestPerformStorageMaintenanceDeletesDisabledAdblockDatabase(t *testing.T) {
	cacheDirectory := t.TempDir()
	previousTempPath := tempPath
	tempPath = cacheDirectory
	t.Cleanup(func() { tempPath = previousTempPath })
	adblockPath := filepath.Join(cacheDirectory, "adblock.db")
	if err := os.WriteFile(adblockPath, []byte("cached"), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := PerformStorageMaintenance(false, "", ""); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(adblockPath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("adblock database still exists: %v", err)
	}
}

func TestPerformStorageMaintenancePrunesRoutingRulesCache(t *testing.T) {
	cacheDirectory := t.TempDir()
	previousTempPath := tempPath
	tempPath = cacheDirectory
	t.Cleanup(func() { tempPath = previousTempPath })

	cachePath := filepath.Join(cacheDirectory, routingRulesCacheFileName)
	database, err := bbolt.Open(cachePath, 0o600, nil)
	if err != nil {
		t.Fatal(err)
	}
	err = database.Update(func(transaction *bbolt.Tx) error {
		geoIP, err := transaction.CreateBucket(geoIPBucketName)
		if err != nil {
			return err
		}
		if err := geoIP.Put([]byte("geoip:us"), []byte("kept")); err != nil {
			return err
		}
		if err := geoIP.Put([]byte("geoip:ru"), []byte("stale")); err != nil {
			return err
		}
		geosite, err := transaction.CreateBucket(geositeBucketName)
		if err != nil {
			return err
		}
		return geosite.Put([]byte("geosite:google"), []byte("kept"))
	})
	if closeErr := database.Close(); err != nil || closeErr != nil {
		t.Fatal(errors.Join(err, closeErr))
	}

	if err := PerformStorageMaintenance(false, "", "geoip:US\ngeosite:google"); err != nil {
		t.Fatal(err)
	}

	database, err = bbolt.Open(cachePath, 0o600, &bbolt.Options{ReadOnly: true})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = database.Close() })
	err = database.View(func(transaction *bbolt.Tx) error {
		if transaction.Bucket(geoIPBucketName).Get([]byte("geoip:us")) == nil {
			t.Error("requested geoip rule was removed")
		}
		if transaction.Bucket(geoIPBucketName).Get([]byte("geoip:ru")) != nil {
			t.Error("stale geoip rule was retained")
		}
		if transaction.Bucket(geositeBucketName).Get([]byte("geosite:google")) == nil {
			t.Error("requested geosite rule was removed")
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestPerformStorageMaintenanceDeletesEmptyRoutingRulesCache(t *testing.T) {
	cacheDirectory := t.TempDir()
	previousTempPath := tempPath
	tempPath = cacheDirectory
	t.Cleanup(func() { tempPath = previousTempPath })
	cachePath := filepath.Join(cacheDirectory, routingRulesCacheFileName)
	if err := os.WriteFile(cachePath, []byte("cached"), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := PerformStorageMaintenance(false, "", ""); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(cachePath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("routing rules cache still exists: %v", err)
	}
}

func TestPerformStorageMaintenanceDeletesSingBoxCache(t *testing.T) {
	cacheDirectory := t.TempDir()
	previousTempPath := tempPath
	tempPath = cacheDirectory
	t.Cleanup(func() { tempPath = previousTempPath })
	cachePath := filepath.Join(cacheDirectory, "cache.db")
	if err := os.WriteFile(cachePath, []byte("cached"), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := PerformStorageMaintenance(false, "", ""); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(cachePath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("sing-box cache still exists: %v", err)
	}
}
