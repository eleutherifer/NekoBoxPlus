//go:build with_adblock

package adblock

import (
	"bytes"
	"testing"
)

// TestDocumentClassIDsCorrectness pins the extraction contract that the Rust
// HiddenClassIDSelectors query relies on: unique class names and element ids,
// case-preserved, deduplicated, ignoring unquoted values and empty ids.
//
// Note: the scan is a substring match for "class="/"id=" in the lowercased
// buffer, so a longer attribute name such as "data-class=" also matches. This
// is the pre-existing behavior and is acceptable for cosmetic selector lookup
// (an extra, harmless selector string is simply passed to the engine).
func TestDocumentClassIDsCorrectness(t *testing.T) {
	content := []byte(`<html><body>
<div class="hero main" id="top"><span class="hero">x</span></div>
<a class=unquoted href="#">no class</a>
<p id='single'>s</p>
<div class="  multi   spaces  ">m</div>
<input type="text" id="">
</body></html>`)
	lower := bytes.ToLower(content)

	classes, ids := documentClassIDs(content, lower)

	classSet := sliceToSet(classes)
	idSet := sliceToSet(ids)

	for _, want := range []string{"hero", "main", "multi", "spaces"} {
		if !classSet[want] {
			t.Errorf("missing class %q in %v", want, classes)
		}
	}
	for _, unwanted := range []string{"unquoted", ""} {
		if classSet[unwanted] {
			t.Errorf("unexpected class %q in %v", unwanted, classes)
		}
	}
	for _, want := range []string{"top", "single"} {
		if !idSet[want] {
			t.Errorf("missing id %q in %v", want, ids)
		}
	}
	if idSet[""] {
		t.Errorf("empty id must not be emitted: %v", ids)
	}
	// "hero" appears twice but must be emitted once.
	count := 0
	for _, c := range classes {
		if c == "hero" {
			count++
		}
	}
	if count != 1 {
		t.Errorf("class hero emitted %d times, want 1", count)
	}
}

func sliceToSet(values []string) map[string]bool {
	out := make(map[string]bool, len(values))
	for _, v := range values {
		out[v] = true
	}
	return out
}
