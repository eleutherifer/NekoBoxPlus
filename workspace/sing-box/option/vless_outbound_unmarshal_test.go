package option

import (
	"testing"

	"github.com/sagernet/sing/common/json"
)

func TestVLESSOutboundOptionsUnmarshalDisablesUTLSForXHTTPQUICOnly(t *testing.T) {
	for _, alpn := range []string{"h3", "quic"} {
		t.Run(alpn, func(t *testing.T) {
			var options VLESSOutboundOptions
			err := json.Unmarshal([]byte(`{
				"transport": {"type": "xhttp"},
				"tls": {
					"enabled": true,
					"alpn": ["`+alpn+`"],
					"utls": {"enabled": true, "fingerprint": "chrome"}
				}
			}`), &options)
			if err != nil {
				t.Fatal(err)
			}
			if options.TLS == nil || options.TLS.UTLS == nil {
				t.Fatal("TLS uTLS options are nil")
			}
			if options.TLS.UTLS.Enabled {
				t.Fatal("uTLS was not disabled")
			}
			if options.TLS.UTLS.Fingerprint != "" {
				t.Fatalf("uTLS fingerprint = %q, want empty", options.TLS.UTLS.Fingerprint)
			}
		})
	}
}

func TestVLESSOutboundOptionsUnmarshalPreservesUTLSOutsideXHTTPQUICOnly(t *testing.T) {
	testCases := map[string]string{
		"non-XHTTP transport": `{
			"transport": {"type": "http"},
			"tls": {"enabled": true, "alpn": ["h3"], "utls": {"enabled": true, "fingerprint": "chrome"}}
		}`,
		"Reality": `{
			"transport": {"type": "xhttp"},
			"tls": {
				"enabled": true,
				"alpn": ["h3"],
				"utls": {"enabled": true, "fingerprint": "chrome"},
				"reality": {"enabled": true}
			}
		}`,
		"TCP ALPN": `{
			"transport": {"type": "xhttp"},
			"tls": {"enabled": true, "alpn": ["h2"], "utls": {"enabled": true, "fingerprint": "chrome"}}
		}`,
		"multiple ALPNs": `{
			"transport": {"type": "xhttp"},
			"tls": {"enabled": true, "alpn": ["h3", "h2"], "utls": {"enabled": true, "fingerprint": "chrome"}}
		}`,
	}
	for name, input := range testCases {
		t.Run(name, func(t *testing.T) {
			var options VLESSOutboundOptions
			if err := json.Unmarshal([]byte(input), &options); err != nil {
				t.Fatal(err)
			}
			if options.TLS == nil || options.TLS.UTLS == nil {
				t.Fatal("TLS uTLS options are nil")
			}
			if !options.TLS.UTLS.Enabled {
				t.Fatal("uTLS was unexpectedly disabled")
			}
			if options.TLS.UTLS.Fingerprint != "chrome" {
				t.Fatalf("uTLS fingerprint = %q, want chrome", options.TLS.UTLS.Fingerprint)
			}
		})
	}
}
