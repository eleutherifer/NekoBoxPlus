package libcore

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"strings"

	"github.com/sagernet/bbolt"
	"libcore/device"
)

const (
	storageMaintenanceCompactSuffix = ".maintenance-compact"
	storageMaintenanceTxMaxSize     = 1 << 20
)

var (
	adblockFiltersBucket = []byte("filters")
)

// PerformStorageMaintenance removes obsolete cached data. It must only be
// called while the box service is stopped, because routing cache compaction
// replaces its database file atomically.
func PerformStorageMaintenance(adblockEnabled bool, joinedAdblockURLs string, joinedRoutingRuleKeys string) (err error) {
	defer device.DeferPanicToError("PerformStorageMaintenance", func(err_ error) { err = err_ })

	adblockPath := filepath.Join(tempPath, "adblock.db")
	if !adblockEnabled {
		if err := os.Remove(adblockPath); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
	} else if err := pruneAndCompactAdblock(adblockPath, parseMaintenanceURLs(joinedAdblockURLs)); err != nil {
		return err
	}

	if err := pruneAndCompactRoutingRulesCache(filepath.Join(tempPath, routingRulesCacheFileName), parseRoutingRuleKeys(joinedRoutingRuleKeys)); err != nil {
		return err
	}
	if err := os.Remove(filepath.Join(tempPath, "cache.db")); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

func parseMaintenanceURLs(joined string) map[string]struct{} {
	urls := make(map[string]struct{})
	for url := range strings.Lines(joined) {
		url = strings.TrimSpace(url)
		if url != "" {
			urls[adblockCacheTag(url)] = struct{}{}
		}
	}
	return urls
}

func adblockCacheTag(url string) string {
	sum := sha256.Sum256([]byte(url))
	return "adblock:" + hex.EncodeToString(sum[:])
}

func parseRoutingRuleKeys(joined string) routingRuleRequests {
	requests := routingRuleRequests{
		routingRuleGeoIP:   make(map[string]struct{}),
		routingRuleGeosite: make(map[string]struct{}),
	}
	for key := range strings.Lines(joined) {
		kind, normalizedKey, valid := parseRoutingRuleKey(strings.TrimSpace(key))
		if valid {
			requests[kind][normalizedKey] = struct{}{}
		}
	}
	return requests
}

func pruneAndCompactAdblock(path string, enabled map[string]struct{}) error {
	if _, err := os.Stat(path); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}
	database, err := bbolt.Open(path, 0o600, nil)
	if err != nil {
		return err
	}
	err = database.Update(func(transaction *bbolt.Tx) error {
		bucket := transaction.Bucket(adblockFiltersBucket)
		if bucket == nil {
			return nil
		}
		var staleKeys [][]byte
		if err := bucket.ForEach(func(key, _ []byte) error {
			if _, enabled := enabled[string(key)]; !enabled {
				staleKeys = append(staleKeys, append([]byte(nil), key...))
			}
			return nil
		}); err != nil {
			return err
		}
		for _, key := range staleKeys {
			if err := bucket.Delete(key); err != nil {
				return err
			}
		}
		return nil
	})
	closeErr := database.Close()
	if err != nil || closeErr != nil {
		return errors.Join(err, closeErr)
	}
	return compactBoltDatabase(path)
}

func pruneAndCompactRoutingRulesCache(path string, requested routingRuleRequests) error {
	routingRulesCacheAccess.Lock()
	defer routingRulesCacheAccess.Unlock()
	if len(requested[routingRuleGeoIP]) == 0 && len(requested[routingRuleGeosite]) == 0 {
		if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		return nil
	}
	if _, err := os.Stat(path); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}

	database, err := bbolt.Open(path, 0o600, nil)
	if err != nil {
		return err
	}
	err = database.Update(func(transaction *bbolt.Tx) error {
		for _, kind := range []routingRuleKind{routingRuleGeoIP, routingRuleGeosite} {
			bucket := transaction.Bucket(routingRuleBucketName(kind))
			if bucket == nil {
				continue
			}
			var staleKeys [][]byte
			if err := bucket.ForEach(func(key, _ []byte) error {
				if _, kept := requested[kind][string(key)]; !kept {
					staleKeys = append(staleKeys, bytes.Clone(key))
				}
				return nil
			}); err != nil {
				return err
			}
			for _, key := range staleKeys {
				if err := bucket.Delete(key); err != nil {
					return err
				}
			}
		}
		return nil
	})
	closeErr := database.Close()
	if err != nil || closeErr != nil {
		return errors.Join(err, closeErr)
	}
	return compactBoltDatabase(path)
}

func compactBoltDatabase(path string) error {
	if _, err := os.Stat(path); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}
	temporaryPath := path + storageMaintenanceCompactSuffix
	if err := os.Remove(temporaryPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	source, err := bbolt.Open(path, 0o600, &bbolt.Options{ReadOnly: true})
	if err != nil {
		return err
	}
	destination, err := bbolt.Open(temporaryPath, 0o600, nil)
	if err != nil {
		return errors.Join(err, source.Close())
	}
	err = bbolt.Compact(destination, source, storageMaintenanceTxMaxSize)
	err = errors.Join(err, destination.Close(), source.Close())
	if err != nil {
		_ = os.Remove(temporaryPath)
		return err
	}
	if err := os.Rename(temporaryPath, path); err != nil {
		_ = os.Remove(temporaryPath)
		return err
	}
	directory, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	return errors.Join(directory.Sync(), directory.Close())
}
