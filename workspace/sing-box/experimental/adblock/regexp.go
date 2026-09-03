//go:build with_adblock

package adblock

import (
	"github.com/sagernet/sing-box/experimental/adblock/regexpr"
	"github.com/sagernet/sing-box/log"
)

type adblockRegexp = regexpr.Regexp

func setAdblockRegexpLogLevel(levels ...log.Level) {
	regexpr.SetLogLevel(levels...)
}

func compileAdblockRegexp(expr string) (*adblockRegexp, error) {
	return regexpr.Compile(expr)
}

func mustCompileAdblockRegexp(expr string) *adblockRegexp {
	return regexpr.MustCompile(expr)
}

func quoteAdblockRegexpMeta(s string) string {
	return regexpr.QuoteMeta(s)
}
