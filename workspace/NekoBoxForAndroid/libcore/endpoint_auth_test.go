package libcore

import (
	"testing"

	"github.com/sagernet/sing-box/adapter"
)

func TestOpenVPNAuthenticationDetail(t *testing.T) {
	detail, required := openVPNAuthenticationDetail(adapter.OpenVPNStatus{
		State: adapter.OpenVPNStateAuthPending,
		Challenge: &adapter.OpenVPNChallenge{
			Message: "Enter the verification code",
		},
	})
	if !required || detail != "Enter the verification code" {
		t.Fatalf("unexpected authentication result: required=%v detail=%q", required, detail)
	}
	if _, required = openVPNAuthenticationDetail(adapter.OpenVPNStatus{State: adapter.OpenVPNStateConnected}); required {
		t.Fatal("connected endpoint must not request authentication")
	}
}

func TestOpenConnectAuthenticationDetailFallsBackToURL(t *testing.T) {
	detail, required := openConnectAuthenticationDetail(adapter.OpenConnectStatus{
		State: adapter.OpenConnectStateAuthPending,
		AuthChallenge: &adapter.OpenConnectAuthChallenge{
			Browser: &adapter.OpenConnectBrowserRequest{
				URL: "https://login.example.com",
			},
		},
	})
	if !required || detail != "https://login.example.com" {
		t.Fatalf("unexpected authentication result: required=%v detail=%q", required, detail)
	}
}
