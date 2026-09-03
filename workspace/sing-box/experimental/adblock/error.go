package adblock

import (
	"strings"

	"github.com/sagernet/sing-box/adapter"
	F "github.com/sagernet/sing/common/format"
)

type blockedError struct {
	metadata adapter.InboundContext
}

func (e *blockedError) Error() string {
	var builder strings.Builder
	builder.WriteString("blocked by adblock")
	writeMetadata := func(name string, value any) {
		builder.WriteString(", ")
		builder.WriteString(name)
		builder.WriteString("=")
		builder.WriteString(F.ToString(value))
	}
	if e.metadata.Network != "" {
		writeMetadata("network", e.metadata.Network)
	}
	if e.metadata.Source.IsValid() {
		writeMetadata("source", e.metadata.Source)
	}
	if e.metadata.Destination.IsValid() {
		writeMetadata("destination", e.metadata.Destination)
	}
	if e.metadata.Domain != "" {
		writeMetadata("domain", e.metadata.Domain)
	}
	if e.metadata.Protocol != "" {
		writeMetadata("protocol", e.metadata.Protocol)
	}
	if e.metadata.Inbound != "" {
		writeMetadata("inbound", e.metadata.Inbound)
	}
	if e.metadata.InboundType != "" {
		writeMetadata("inbound_type", e.metadata.InboundType)
	}
	if e.metadata.User != "" {
		writeMetadata("user", e.metadata.User)
	}
	if processInfo := e.metadata.ProcessInfo; processInfo != nil {
		if processInfo.ProcessID != 0 {
			writeMetadata("process_id", processInfo.ProcessID)
		}
		if processInfo.UserName != "" {
			writeMetadata("process_user", processInfo.UserName)
		}
		if processInfo.ProcessPath != "" {
			writeMetadata("process_path", processInfo.ProcessPath)
		}
		if len(processInfo.AndroidPackageNames) > 0 {
			writeMetadata("packages", strings.Join(processInfo.AndroidPackageNames, ","))
		}
	}
	return builder.String()
}

func (e *blockedError) IsAdblockBlocked() {}
