package rule

import (
	"testing"

	"github.com/sagernet/sing-box/adapter"
)

func TestPackageNameExcludeNone(t *testing.T) {
	item := NewPackageNameExcludeItem([]string{"none"})
	if item.String() != "package_name_exclude=none" {
		t.Fatalf("unexpected string: %s", item.String())
	}
	if item.Match(&adapter.InboundContext{}) {
		t.Fatal("none sentinel matched missing process info")
	}
	if item.Match(&adapter.InboundContext{ProcessInfo: &adapter.ConnectionOwner{}}) {
		t.Fatal("none sentinel matched empty package names")
	}
	if !item.Match(&adapter.InboundContext{ProcessInfo: &adapter.ConnectionOwner{
		AndroidPackageNames: []string{"com.example.app"},
	}}) {
		t.Fatal("none sentinel did not match non-empty package names")
	}
}

func TestPackageNameExcludeList(t *testing.T) {
	item := NewPackageNameExcludeItem([]string{"com.example.excluded"})
	if item.Match(&adapter.InboundContext{ProcessInfo: &adapter.ConnectionOwner{
		AndroidPackageNames: []string{"com.example.excluded"},
	}}) {
		t.Fatal("package_name_exclude matched excluded package")
	}
	if !item.Match(&adapter.InboundContext{ProcessInfo: &adapter.ConnectionOwner{
		AndroidPackageNames: []string{"com.example.allowed"},
	}}) {
		t.Fatal("package_name_exclude did not match allowed package")
	}
}
