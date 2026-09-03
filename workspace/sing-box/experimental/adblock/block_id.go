//go:build with_adblock

package adblock

import (
	"strings"
	"sync"

	"github.com/sagernet/sing-box/common/xray/uuid"
)

var (
	runBlockID = sync.OnceValue(func() string {
		v := uuid.New()
		return v.String()
	})
	runBlockHash = sync.OnceValue(func() string {
		return "Q" + strings.ReplaceAll(runBlockID(), "-", "")
	})
)
