package device

import (
	"encoding/binary"
	"testing"
)

func TestDetermineTransportPacketRequiresS4Padding(t *testing.T) {
	const (
		padding   = 12
		header    = 0x10203040
		packetLen = padding + MessageTransportHeaderSize
	)

	device := new(Device)
	var headerRange UintRange
	headerRange.FromUint32(header, header)
	device.headers.transport.Store(headerRange)
	device.paddings.transport.Store(padding)

	padded := make([]byte, packetLen)
	binary.LittleEndian.PutUint32(padded[padding:], header)
	packetType, detectedPadding := device.DeterminePacketTypeAndPadding(
		padded,
		MessageTransportType,
		make([]byte, 4),
	)
	if packetType != MessageTransportType || detectedPadding != padding {
		t.Fatalf("padded packet detected as type %d with padding %d", packetType, detectedPadding)
	}

	unpadded := make([]byte, packetLen)
	binary.LittleEndian.PutUint32(unpadded, header)
	packetType, detectedPadding = device.DeterminePacketTypeAndPadding(
		unpadded,
		MessageTransportType,
		make([]byte, 4),
	)
	if packetType != MessageUnknownType || detectedPadding != 0 {
		t.Fatalf("unpadded packet detected as type %d with padding %d", packetType, detectedPadding)
	}
}
