package adapter

import (
	"context"
	"net"
	"testing"
)

type testV2RayClientTransport struct {
	closed bool
}

func (t *testV2RayClientTransport) DialContext(context.Context) (net.Conn, error) {
	return nil, nil
}

func (t *testV2RayClientTransport) Close() error {
	t.closed = true
	return nil
}

type testResettableV2RayClientTransport struct {
	testV2RayClientTransport
	reset bool
}

func (t *testResettableV2RayClientTransport) Reset() error {
	t.reset = true
	return nil
}

func TestResetV2RayClientTransportPrefersReset(t *testing.T) {
	transport := &testResettableV2RayClientTransport{}

	if err := ResetV2RayClientTransport(transport); err != nil {
		t.Fatal(err)
	}
	if !transport.reset {
		t.Fatal("resettable transport was not reset")
	}
	if transport.closed {
		t.Fatal("resettable transport was closed")
	}
}

func TestResetV2RayClientTransportFallsBackToClose(t *testing.T) {
	transport := &testV2RayClientTransport{}

	if err := ResetV2RayClientTransport(transport); err != nil {
		t.Fatal(err)
	}
	if !transport.closed {
		t.Fatal("non-resettable transport was not closed")
	}
}
