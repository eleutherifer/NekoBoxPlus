//go:build re2_cgo || re2_wazero || (!amd64 && !arm64 && !loong64 && !mips64 && !mips64le && !ppc64 && !ppc64le && !riscv64 && !s390x)

package regexpr

import (
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
)

func CalculateLogLevels(input log.Level, _ *option.LogOptions) []log.Level {
	return []log.Level{input}
}

func SetLogLevel(...log.Level) {
}
