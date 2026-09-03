//go:build with_adblock

package adblock

import (
	"path/filepath"
	"regexp"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
)

type Constraints struct {
	list                 []Constraint
	hasProcessConstraint bool
}

type Constraint struct {
	srcIsNotLoopback     bool
	inbound              map[string]bool
	processNames         map[string]bool
	processPaths         map[string]bool
	processPathRegex     []*regexp.Regexp
	packageNames         map[string]bool
	packageNameExclude   map[string]bool
	packageNameRegex     []*regexp.Regexp
	hasProcessConstraint bool
}

func compileConstraints(options option.AdblockConstraints) (*Constraints, error) {
	if len(options) == 0 {
		return nil, nil
	}

	result := &Constraints{}

	for _, rule := range options {
		item := Constraint{
			srcIsNotLoopback: rule.SourceIPIsNotLoopback,
		}

		if len(rule.Inbound) > 0 {
			item.inbound = makeStringSet(rule.Inbound)
		}
		if len(rule.ProcessName) > 0 {
			item.processNames = makeStringSet(rule.ProcessName)
			item.hasProcessConstraint = true
			result.hasProcessConstraint = true
		}
		if len(rule.ProcessPath) > 0 {
			item.processPaths = makeStringSet(rule.ProcessPath)
			item.hasProcessConstraint = true
			result.hasProcessConstraint = true
		}
		for _, expression := range rule.ProcessPathRegex {
			matcher, err := regexp.Compile(expression)
			if err != nil {
				return nil, E.Cause(err, "process_path_regex")
			}
			item.processPathRegex = append(item.processPathRegex, matcher)
			item.hasProcessConstraint = true
			result.hasProcessConstraint = true
		}
		if len(rule.PackageName) > 0 {
			item.packageNames = makeStringSet(rule.PackageName)
			item.hasProcessConstraint = true
			result.hasProcessConstraint = true
		}
		if len(rule.PackageNameExclude) > 0 {
			item.packageNameExclude = makeStringSet(rule.PackageNameExclude)
			item.hasProcessConstraint = true
			result.hasProcessConstraint = true
		}
		for _, expression := range rule.PackageNameRegex {
			matcher, err := regexp.Compile(expression)
			if err != nil {
				return nil, E.Cause(err, "package_name_regex")
			}
			item.packageNameRegex = append(item.packageNameRegex, matcher)
			item.hasProcessConstraint = true
			result.hasProcessConstraint = true
		}

		result.list = append(result.list, item)
	}

	return result, nil
}

func (c *Constraints) Match(metadata *adapter.InboundContext) bool {
	if len(c.list) == 0 {
		return true
	}

	for _, item := range c.list {
		if item.Match(metadata) {
			return true
		}
	}

	return false
}

func (c *Constraint) Match(metadata *adapter.InboundContext) bool {
	if len(c.inbound) > 0 && !c.inbound[metadata.Inbound] {
		return false
	}

	if c.srcIsNotLoopback {
		sourceAddress := metadata.Source.Addr.Unmap()
		if !sourceAddress.IsValid() || sourceAddress.IsLoopback() {
			return false
		}
	}

	if !c.hasProcessConstraint {
		return true
	}

	info := metadata.ProcessInfo
	if info == nil {
		return false
	}
	if len(c.processNames) > 0 && info.ProcessPath != "" && c.processNames[filepath.Base(info.ProcessPath)] {
		return true
	}
	if len(c.processPaths) > 0 {
		if info.ProcessPath != "" && c.processPaths[info.ProcessPath] {
			return true
		}
		if C.IsAndroid && common.Any(info.AndroidPackageNames, func(packageName string) bool { return c.processPaths[packageName] }) {
			return true
		}
	}
	for _, matcher := range c.processPathRegex {
		if info.ProcessPath != "" && matcher.MatchString(info.ProcessPath) {
			return true
		}
	}
	if len(c.packageNames) > 0 && common.Any(info.AndroidPackageNames, func(packageName string) bool { return c.packageNames[packageName] }) {
		return true
	}
	if len(c.packageNameExclude) > 0 && len(info.AndroidPackageNames) > 0 && !common.Any(info.AndroidPackageNames, func(packageName string) bool { return c.packageNameExclude[packageName] }) {
		return true
	}
	for _, matcher := range c.packageNameRegex {
		if common.Any(info.AndroidPackageNames, matcher.MatchString) {
			return true
		}
	}
	return false
}
