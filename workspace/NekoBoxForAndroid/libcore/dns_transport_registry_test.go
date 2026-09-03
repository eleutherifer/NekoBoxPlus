package libcore

import (
	"testing"

	"github.com/sagernet/sing-box/option"
)

func TestEndpointDNSTransportsRegistered(t *testing.T) {
	registry := nekoboxAndroidDNSTransportRegistry(nil)

	openVPNOptions, loaded := registry.CreateOptions("openvpn")
	if !loaded {
		t.Fatal("OpenVPN DNS transport is not registered")
	}
	if _, ok := openVPNOptions.(*option.OpenVPNDNSServerOptions); !ok {
		t.Fatalf("unexpected OpenVPN DNS options type: %T", openVPNOptions)
	}

	openConnectOptions, loaded := registry.CreateOptions("openconnect")
	if !loaded {
		t.Fatal("OpenConnect DNS transport is not registered")
	}
	if _, ok := openConnectOptions.(*option.OpenConnectDNSServerOptions); !ok {
		t.Fatalf("unexpected OpenConnect DNS options type: %T", openConnectOptions)
	}
}
