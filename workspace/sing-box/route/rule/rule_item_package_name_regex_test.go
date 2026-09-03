package rule

import (
	"testing"

	"github.com/sagernet/sing-box/adapter"
)

func TestPackageNameRegexCatchAll(t *testing.T) {
	item, err := NewPackageNameRegexItem([]string{".+"})
	if err != nil {
		t.Fatal(err)
	}
	if item.Match(&adapter.InboundContext{}) {
		t.Fatal("catch-all regex matched missing process info")
	}
	if item.Match(&adapter.InboundContext{ProcessInfo: &adapter.ConnectionOwner{}}) {
		t.Fatal("catch-all regex matched empty package names")
	}
	if !item.Match(&adapter.InboundContext{ProcessInfo: &adapter.ConnectionOwner{
		AndroidPackageNames: []string{"com.example.app"},
	}}) {
		t.Fatal("catch-all regex did not match package name")
	}
}

func TestPackageNameRegexPattern(t *testing.T) {
	item, err := NewPackageNameRegexItem([]string{`^com\.example\.`})
	if err != nil {
		t.Fatal(err)
	}
	if !item.Match(&adapter.InboundContext{ProcessInfo: &adapter.ConnectionOwner{
		AndroidPackageNames: []string{"com.example.app"},
	}}) {
		t.Fatal("regex did not match expected package")
	}
	if item.Match(&adapter.InboundContext{ProcessInfo: &adapter.ConnectionOwner{
		AndroidPackageNames: []string{"org.example.app"},
	}}) {
		t.Fatal("regex matched unexpected package")
	}
}
