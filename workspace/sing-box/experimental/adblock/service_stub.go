//go:build !with_adblock

package adblock

import (
	"context"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
)

func New(ctx context.Context, logger log.ContextLogger, options option.AdblockOptions, logLevels ...log.Level) (adapter.AdblockService, error) {
	if options.Enabled {
		return nil, E.New("adblock is not included in this build, rebuild with -tags with_adblock")
	}
	return nil, nil
}
