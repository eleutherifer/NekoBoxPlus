//go:build !re2_cgo && !re2_wazero && (amd64 || arm64 || loong64 || mips64 || mips64le || ppc64 || ppc64le || riscv64 || s390x)

package regexpr

import re2 "github.com/wasilibs/go-re2"

type Regexp = re2.Regexp

func Compile(expr string) (*Regexp, error) {
	return re2.Compile(expr)
}

func MustCompile(expr string) *Regexp {
	compiled, err := Compile(expr)
	if err != nil {
		panic(`regexp: Compile(` + expr + `): ` + err.Error())
	}
	return compiled
}

func QuoteMeta(s string) string {
	return re2.QuoteMeta(s)
}
