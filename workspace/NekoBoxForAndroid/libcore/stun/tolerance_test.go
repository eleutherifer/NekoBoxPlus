package stun

import (
	"encoding/binary"
	"net"
	"testing"
	"time"
)

type mismatchResponseConn struct {
	request []byte
	read    bool
}

func (c *mismatchResponseConn) ReadFrom(destination []byte) (int, net.Addr, error) {
	if c.read {
		return 0, nil, mismatchTimeoutError{}
	}
	request, err := newPacketFromBytes(c.request)
	if err != nil {
		return 0, nil, err
	}
	response := &packet{
		types:      typeBindingResponse,
		transID:    append([]byte(nil), request.transID...),
		attributes: make([]attribute, 0, 1),
	}
	mappedValue := make([]byte, 8)
	mappedValue[1] = attributeFamilyIPv4
	binary.BigEndian.PutUint16(mappedValue[2:4], 40000)
	copy(mappedValue[4:], net.ParseIP("192.0.2.20").To4())
	mapped, err := newAttribute(attributeMappedAddress, mappedValue)
	if err != nil {
		return 0, nil, err
	}
	if err := response.addAttribute(*mapped); err != nil {
		return 0, nil, err
	}
	c.read = true
	wire := response.bytes()
	return copy(destination, wire), &net.UDPAddr{
		IP:   net.ParseIP("203.0.113.2"),
		Port: 3479,
	}, nil
}

func (c *mismatchResponseConn) WriteTo(source []byte, _ net.Addr) (int, error) {
	c.request = append(c.request[:0], source...)
	return len(source), nil
}

func (*mismatchResponseConn) Close() error                     { return nil }
func (*mismatchResponseConn) LocalAddr() net.Addr              { return new(net.UDPAddr) }
func (*mismatchResponseConn) SetDeadline(time.Time) error      { return nil }
func (*mismatchResponseConn) SetReadDeadline(time.Time) error  { return nil }
func (*mismatchResponseConn) SetWriteDeadline(time.Time) error { return nil }

type mismatchTimeoutError struct{}

func (mismatchTimeoutError) Error() string   { return "timeout" }
func (mismatchTimeoutError) Timeout() bool   { return true }
func (mismatchTimeoutError) Temporary() bool { return true }

func TestResponseAddressMismatchIsStrictByDefault(t *testing.T) {
	client := NewClient()
	_, err := client.test(
		new(mismatchResponseConn),
		&net.UDPAddr{IP: net.ParseIP("198.51.100.1"), Port: 3478},
	)
	if err == nil || err.Error() != "server error: response IP/port" {
		t.Fatalf("unexpected error: %v", err)
	}
	if !client.ResponseAddressMismatch() {
		t.Fatal("mismatch was not recorded")
	}
}

func TestResponseAddressMismatchCanBeTolerated(t *testing.T) {
	client := NewClient()
	client.SetResponseAddressMismatchAllowed(true)
	response, err := client.test(
		new(mismatchResponseConn),
		&net.UDPAddr{IP: net.ParseIP("198.51.100.1"), Port: 3478},
	)
	if err != nil {
		t.Fatal(err)
	}
	if response == nil || response.mappedAddr == nil {
		t.Fatal("tolerated response did not produce a mapped address")
	}
	if !client.ResponseAddressMismatch() {
		t.Fatal("mismatch was not recorded")
	}
}
