//go:build with_adblock

package adblock

import (
	"crypto/sha256"
	"encoding/hex"
	"path/filepath"
)

func makeStringSet(values []string) map[string]bool {
	set := make(map[string]bool, len(values))
	for _, value := range values {
		set[value] = true
	}
	return set
}

func cacheTag(rawURL string) string {
	sum := sha256.Sum256([]byte(rawURL))
	return "adblock:" + hex.EncodeToString(sum[:])
}

func sameCachePath(left string, right string) bool {
	leftAbs, leftErr := filepath.Abs(left)
	rightAbs, rightErr := filepath.Abs(right)
	if leftErr == nil && rightErr == nil {
		return leftAbs == rightAbs
	}
	return filepath.Clean(left) == filepath.Clean(right)
}
