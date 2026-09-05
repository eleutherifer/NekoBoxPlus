package mobile

import "testing"

func TestWaitReadyFailsWhenNotRunning(t *testing.T) {
	Stop()

	ok, err := WaitReady(10)
	if err == nil {
		t.Fatal("expected error when runner is not active")
	}
	if ok {
		t.Fatal("expected ready result to be false")
	}
}

func TestActiveSocksPortIsZeroWhenStopped(t *testing.T) {
	Stop()

	if got := ActiveSocksPort(); got != 0 {
		t.Fatalf("unexpected active socks port: %d", got)
	}
}

func TestSetBoundAddressRoundtrips(t *testing.T) {
	SetBoundAddress("", "")
	SetBoundAddress("192.0.2.5", "2001:db8::5")
	t.Cleanup(func() {
		SetBoundAddress("", "")
	})

	if got := BoundIPv4(); got != "192.0.2.5" {
		t.Fatalf("BoundIPv4() = %q, want 192.0.2.5", got)
	}
	if got := BoundIPv6(); got != "2001:db8::5" {
		t.Fatalf("BoundIPv6() = %q, want 2001:db8::5", got)
	}
}
