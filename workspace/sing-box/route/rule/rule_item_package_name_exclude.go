package rule

import (
	"strings"

	"github.com/sagernet/sing-box/adapter"
)

var _ RuleItem = (*PackageNameExcludeItem)(nil)

type PackageNameExcludeItem struct {
	packageNames []string
	packageMap   map[string]bool
	excludeNone  bool // Matches if package_name has any value.
}

func NewPackageNameExcludeItem(packageNameList []string) *PackageNameExcludeItem {
	if len(packageNameList) == 1 && packageNameList[0] == "none" {
		return &PackageNameExcludeItem{packageNames: packageNameList, excludeNone: true}
	}

	rule := &PackageNameExcludeItem{
		packageNames: packageNameList,
		packageMap:   make(map[string]bool),
	}
	for _, packageName := range packageNameList {
		rule.packageMap[packageName] = true
	}
	return rule
}

func (r *PackageNameExcludeItem) Match(metadata *adapter.InboundContext) bool {
	if metadata.ProcessInfo == nil || len(metadata.ProcessInfo.AndroidPackageNames) == 0 {
		return false
	}
	for _, packageName := range metadata.ProcessInfo.AndroidPackageNames {
		if r.excludeNone {
			return true
		}
		if r.packageMap[packageName] {
			return false
		}
	}
	return true
}

func (r *PackageNameExcludeItem) String() string {
	var description string
	pLen := len(r.packageNames)
	if pLen == 1 {
		description = "package_name_exclude=" + r.packageNames[0]
	} else {
		description = "package_name_exclude=[" + strings.Join(r.packageNames, " ") + "]"
	}
	return description
}
