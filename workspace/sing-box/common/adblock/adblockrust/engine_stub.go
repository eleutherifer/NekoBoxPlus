//go:build !with_adblock

package adblockrust

import E "github.com/sagernet/sing/common/exceptions"

func newEngine(ruleSets []RuleSet, adblockResources string) (Engine, error) {
	return nil, E.New("adblock is not included in this build, rebuild with -tags with_adblock")
}
