package device

import (
	"encoding/binary"
	"testing"
)

func TestCounterObfUsesHandshakeCounter(t *testing.T) {
	chain, err := newObfChain("<b 0xaa><c><c>")
	if err != nil {
		t.Fatal(err)
	}

	packet := make([]byte, chain.ObfuscatedLen(0))
	chain.ObfuscateWithCounter(packet, nil, 0x10203040)

	if packet[0] != 0xaa {
		t.Fatalf("static byte = %#x", packet[0])
	}
	for _, offset := range []int{1, 5} {
		if counter := binary.BigEndian.Uint32(packet[offset:]); counter != 0x10203040 {
			t.Fatalf("counter at offset %d = %#x", offset, counter)
		}
	}
}
