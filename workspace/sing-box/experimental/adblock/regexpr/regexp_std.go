//go:build re2_cgo || re2_wazero || (!amd64 && !arm64 && !loong64 && !mips64 && !mips64le && !ppc64 && !ppc64le && !riscv64 && !s390x)

package regexpr

import "regexp"

type Regexp = regexp.Regexp

func Compile(expr string) (*Regexp, error) {
	return regexp.Compile(expr)
}

func MustCompile(expr string) *Regexp {
	return regexp.MustCompile(expr)
}

func QuoteMeta(s string) string {
	return regexp.QuoteMeta(s)
}
