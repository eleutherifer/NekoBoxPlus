//go:build with_adblock

package resources

import (
	"io/fs"
	"strings"
	"testing"
)

func TestBundledAssetsContainOnlyGeneratedResources(t *testing.T) {
	allowed := []string{
		"files/placeholder.txt",
		"files/dist/",
		"files/resources/",
		"files/src/js/redirect-resources.js",
		"files/src/web_accessible_resources/",
	}
	err := fs.WalkDir(bundledAdblockResources, "files", func(path string, entry fs.DirEntry, err error) error {
		if err != nil || entry.IsDir() {
			return err
		}
		for _, prefix := range allowed {
			if path == prefix || strings.HasPrefix(path, prefix) {
				return nil
			}
		}
		t.Errorf("unexpected embedded adblock resource: %s", path)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, loaded := GetWebAccessibleResource("noop.js"); !loaded {
		t.Fatal("expected bundled noop.js WAR")
	}
	if _, _, loaded := GetWebAccessibleResource("README.txt"); loaded {
		t.Fatal("undeclared uBlock files must not be bundled")
	}
}
