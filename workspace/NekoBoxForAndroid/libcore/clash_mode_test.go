package libcore

import "testing"

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
