//go:build with_adblock

package adblock

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

// Survey: after the type-alias fix, which modifiers still force advanced/companion
// rules to be dropped? Groups by modifier name across the real configured lists.
func TestZZ_SurveyRemainingDropped(t *testing.T) {
	files, err := os.ReadDir("/tmp/adblock-repro")
	if err != nil {
		t.Skipf("no repro dir: %v", err)
	}
	// Mirror the recognized sets AFTER the type-alias fix.
	advRecognized := func(name string) bool {
		switch strings.ToLower(name) {
		case "_", "removeparam", "queryprune", "uritransform", "urltransform", "urlskip",
			"method", "to", "domain", "from", "third-party", "3p", "first-party", "1p",
			"strict1p", "strict3p", "match-case", "important":
			return true
		}
		return applyRequestTypeOption(map[string]bool{}, name, false)
	}
	compRecognized := func(name string) bool {
		switch strings.ToLower(name) {
		case "_", "replace", "permissions", "header", "cookie", "domain", "from",
			"third-party", "3p", "first-party", "1p", "match-case", "important":
			return true
		}
		return applyRequestTypeOption(map[string]bool{}, name, false)
	}
	count := func(recognized func(string) bool) (map[string]int, int, int) {
		unknown := map[string]int{}
		total, withUnknown := 0, 0
		for _, f := range files {
			if !strings.HasSuffix(f.Name(), ".txt") {
				continue
			}
			data, _ := os.ReadFile(filepath.Join("/tmp/adblock-repro", f.Name()))
			for _, line := range strings.Split(string(data), "\n") {
				line = strings.TrimSpace(line)
				if line == "" {
					continue
				}
				if _, ok := parseAdvancedRule(line); ok {
					total++
					l := strings.TrimPrefix(line, "@@")
					_, opts, found := strings.Cut(l, "$")
					if !found {
						continue
					}
					hadUnknown := false
					for _, o := range splitRuleOptions(opts) {
						name, _, _ := strings.Cut(o, "=")
						name = strings.TrimPrefix(strings.TrimSpace(name), "~")
						if !recognized(name) {
							unknown[strings.ToLower(name)]++
							hadUnknown = true
						}
					}
					if hadUnknown {
						withUnknown++
					}
				}
			}
		}
		return unknown, total, withUnknown
	}

	for _, tc := range []struct {
		name       string
		recognized func(string) bool
	}{
		{"advanced", advRecognized},
		{"companion", compRecognized},
	} {
		unknown, total, withUnknown := count(tc.recognized)
		keys := make([]string, 0, len(unknown))
		for k := range unknown {
			keys = append(keys, k)
		}
		sort.Slice(keys, func(i, j int) bool { return unknown[keys[i]] > unknown[keys[j]] })
		t.Logf("[%s] accepted=%d, with-unknown(dropped)=%d", tc.name, total, withUnknown)
		for _, k := range keys {
			t.Logf("  %s -> %d", k, unknown[k])
		}
	}
}
