//go:build !with_adblock

package resources

import E "github.com/sagernet/sing/common/exceptions"

func GetBundledAssets(_ string) (string, error) {
	return "", E.New("adblock resources are not included in this build, rebuild with -tags with_adblock")
}

func GetWebAccessibleResource(_ string) ([]byte, string, bool) {
	return nil, "", false
}
