package libcore

const (
	geoipDat             = "geoip.db"
	geositeDat           = "geosite.db"
	geoipVersion         = "geoip.version.txt"
	geositeVersion       = "geosite.version.txt"
	throneRulesetDat     = "throne-ruleset-srslist.h"
	throneRulesetVersion = "throne-ruleset.version.txt"
	itdogRulesetDat      = "itdog-ruleset.json"
	itdogRulesetVersion  = "itdog-ruleset.version.txt"

	metacubexdDstFolder = "metacubexd"
	metacubexdVersion   = "metacubexd.version.txt"
)

var apkAssetPrefixSingBox = "sing-box/"
var internalAssetsPath string
var externalAssetsPath string

func ResetPanelAssets() error {
	return resetPanelAssets()
}
