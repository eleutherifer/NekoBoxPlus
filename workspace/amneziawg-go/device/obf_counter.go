package device

import (
	"encoding/binary"
)

// newCounterObf creates a packet counter obfuscator.
// The <c> tag outputs a 4-byte counter in network byte order (big-endian).
func newCounterObf(_ string) (obf, error) {
	return &counterObf{}, nil
}

type counterObf struct{}

func (o *counterObf) Obfuscate(dst, src []byte) {
	o.obfuscateCounter(dst, 0)
}

func (o *counterObf) obfuscateCounter(dst []byte, counter uint32) {
	binary.BigEndian.PutUint32(dst, counter)
}

func (o *counterObf) Deobfuscate(dst, src []byte) bool {
	return true
}

func (o *counterObf) ObfuscatedLen(n int) int {
	return 4
}

func (o *counterObf) DeobfuscatedLen(n int) int {
	return 0
}
