package libcore

import (
	"fmt"

	"libcore/protocol/byedpi"

	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

func validateByeDPIOptions(options option.Options) error {
	byedpiTags := make(map[string]struct{})
	for index, outbound := range options.Outbounds {
		if outbound.Type != byedpi.TypeByeDPI {
			continue
		}
		tag := outbound.Tag
		if tag == "" {
			tag = fmt.Sprint(index)
		}
		byedpiTags[tag] = struct{}{}
		if outboundOptions, ok := outbound.Options.(*byedpi.OutboundOptions); ok && outboundOptions.Detour != "" {
			return fmt.Errorf("byedpi outbound %q cannot be used with detour", tag)
		}
	}
	if len(byedpiTags) == 0 {
		return nil
	}
	for index, outbound := range options.Outbounds {
		tag := outbound.Tag
		if tag == "" {
			tag = fmt.Sprint(index)
		}
		switch outbound.Type {
		case C.TypeSelector:
			selectorOptions := outbound.Options.(*option.SelectorOutboundOptions)
			for _, outboundTag := range selectorOptions.Outbounds {
				if _, ok := byedpiTags[outboundTag]; ok {
					return fmt.Errorf("selector outbound %q cannot reference byedpi outbound %q", tag, outboundTag)
				}
			}
		case C.TypeURLTest:
			urlTestOptions := outbound.Options.(*option.URLTestOutboundOptions)
			for _, outboundTag := range urlTestOptions.Outbounds {
				if _, ok := byedpiTags[outboundTag]; ok {
					return fmt.Errorf("urltest outbound %q cannot reference byedpi outbound %q", tag, outboundTag)
				}
			}
		}
	}
	return nil
}
