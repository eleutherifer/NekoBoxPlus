//go:build !re2_cgo && !re2_wazero && (amd64 || arm64 || loong64 || mips64 || mips64le || ppc64 || ppc64le || riscv64 || s390x)

package regexpr

import (
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
)

const logLevelNone log.Level = ^log.Level(0)

func CalculateLogLevels(input log.Level, logOptionsPtr *option.LogOptions) []log.Level {
	levels := []log.Level{input}
	var logOptions option.LogOptions
	if logOptionsPtr != nil {
		logOptions = *logOptionsPtr
	}
	if logOptions.Disabled || logOptions.Level == "panic" || logOptions.Level == "fatal" {
		levels = append(levels, logLevelNone)
	}
	return levels
}

func SetLogLevel(levels ...log.Level) {
	for _, level := range levels {
		if level == logLevelNone {
			configureLogging(true)
			return
		}
	}
	configureLogging(false)
}
