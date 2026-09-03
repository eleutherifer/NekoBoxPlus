//go:build re2_cgo || re2_wazero || (!amd64 && !arm64 && !loong64 && !mips64 && !mips64le && !ppc64 && !ppc64le && !riscv64 && !s390x)

package regexpr

import (
	"testing"

	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
)

func TestCalculateLogLevelsStub(t *testing.T) {
	levels := CalculateLogLevels(log.LevelInfo, &option.LogOptions{Disabled: true})
	if len(levels) != 1 || levels[0] != log.LevelInfo {
		t.Fatalf("CalculateLogLevels() = %#v", levels)
	}
}
