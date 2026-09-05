package libcore

import (
	"strings"
	"testing"
)

func TestVersionModulesIncludesBothAdblockResourceSets(t *testing.T) {
	oldAdblockResources := VersionAdblockResources
	oldUBlock := VersionUBlock
	VersionAdblockResources = "brave-test"
	VersionUBlock = "ublock-test"
	t.Cleanup(func() {
		VersionAdblockResources = oldAdblockResources
		VersionUBlock = oldUBlock
	})

	versions := VersionModules()
	for _, expected := range []string{
		"adblock-resources: brave-test",
		"uBlock: ublock-test",
	} {
		if !strings.Contains(versions, expected) {
			t.Fatalf("module versions are missing %q: %s", expected, versions)
		}
	}
}
