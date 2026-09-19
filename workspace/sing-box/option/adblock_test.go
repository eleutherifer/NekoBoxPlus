package option

import (
	"github.com/goccy/go-json"
	"testing"
	"time"

	badjson "github.com/sagernet/sing/common/json"
)

func TestAdblockProtocolFilteringOptions(t *testing.T) {
	options := AdblockOptions{
		Filtering: AdblockFiltering{
			DNS:   true,
			HTTP:  true,
			HTTPS: true,
			QUIC:  true,
		},
	}

	if !options.FilterDNS() {
		t.Fatal("expected DNS filtering to be enabled")
	}
	if !options.FilterHTTP() {
		t.Fatal("expected HTTP filtering to be enabled")
	}
	if options.FilterHTTPS() {
		t.Fatal("expected HTTPS filtering to require TLS")
	}
	if options.FilterQUIC() {
		t.Fatal("expected QUIC filtering to require TLS")
	}

	options.TLS = &AdblockTLSOptions{}
	if options.FilterHTTPS() {
		t.Fatal("expected disabled TLS to disable HTTPS filtering")
	}
	if options.FilterQUIC() {
		t.Fatal("expected disabled TLS to disable QUIC filtering")
	}

	options.TLS.Enabled = true
	if !options.FilterHTTPS() {
		t.Fatal("expected HTTPS filtering to be enabled with TLS")
	}
	if !options.FilterQUIC() {
		t.Fatal("expected QUIC filtering to be enabled with TLS")
	}

	options.Filtering.HTTPS = false
	options.Filtering.QUIC = false
	if options.FilterHTTPS() {
		t.Fatal("expected HTTPS filtering flag to disable HTTPS")
	}
	if options.FilterQUIC() {
		t.Fatal("expected QUIC filtering flag to disable QUIC")
	}
}

func TestAdblockFilterListTrustUnmarshal(t *testing.T) {
	var options AdblockOptions
	if err := json.Unmarshal([]byte(`{
		"filters": {
			"lists": [
				"https://example.com/easylist.txt",
				{
					"url": "https://example.com/ublock.txt",
					"trust": true,
					"format": "standard"
				}
			]
		}
	}`), &options); err != nil {
		t.Fatal(err)
	}

	if len(options.Filters.FilterLists) != 2 {
		t.Fatalf("expected 2 filter lists, got %d", len(options.Filters.FilterLists))
	}
	if options.Filters.FilterLists[0].URL != "https://example.com/easylist.txt" {
		t.Fatalf("unexpected string filter URL: %q", options.Filters.FilterLists[0].URL)
	}
	if options.Filters.FilterLists[0].Trust {
		t.Fatal("expected string filter list to default to untrusted")
	}
	if options.Filters.FilterLists[1].URL != "https://example.com/ublock.txt" {
		t.Fatalf("unexpected object filter URL: %q", options.Filters.FilterLists[1].URL)
	}
	if !options.Filters.FilterLists[1].Trust {
		t.Fatal("expected object filter trust to be preserved")
	}
}

func TestAdblockTLSCronetUnmarshal(t *testing.T) {
	var options AdblockOptions
	if err := json.Unmarshal([]byte(`{
		"tls": {
			"enabled": true,
			"utls": "chrome",
			"cronet": true
		}
	}`), &options); err != nil {
		t.Fatal(err)
	}
	if options.TLS == nil {
		t.Fatal("expected TLS options")
	}
	if !options.TLS.Cronet {
		t.Fatal("expected cronet option to be preserved")
	}
	if options.TLS.UTLS == nil || *options.TLS.UTLS != "chrome" {
		t.Fatalf("expected utls value to still parse, got %#v", options.TLS.UTLS)
	}
}

func TestAdblockFilteringNewOptions(t *testing.T) {
	var options AdblockOptions
	if err := json.Unmarshal([]byte(`{
		"tags": ["mobile", "trusted"],
		"environment": {
			"chromium": true,
			"ubo": true,
			"html_filtering": true,
			"ipaddress": true,
			"user_stylesheet": true
		},
		"filtering": {
			"dns_block_mode": "nxdomain",
			"dns_block_ttl": "5m",
			"cname_infrastructure_suffixes": ["customcdn.test"],
			"replace_max_body": 1024
		}
	}`), &options); err != nil {
		t.Fatal(err)
	}
	if options.Filtering.DNSBlockMode != AdblockDNSBlockModeNXDOMAIN {
		t.Fatalf("unexpected DNS block mode: %q", options.Filtering.DNSBlockMode)
	}
	if options.Filtering.DNSBlockTTLValue() != uint32((5*time.Minute)/time.Second) {
		t.Fatalf("unexpected DNS block TTL: %d", options.Filtering.DNSBlockTTLValue())
	}
	if options.Filtering.ReplaceMaxBodyValue() != 1024 {
		t.Fatalf("unexpected replace max body: %d", options.Filtering.ReplaceMaxBodyValue())
	}
	if len(options.Tags) != 2 || options.Tags[0] != "mobile" || options.Tags[1] != "trusted" {
		t.Fatalf("unexpected tags: %#v", options.Tags)
	}
	if options.Environment == nil || !options.Environment.Chromium || !options.Environment.UBO || !options.Environment.HTMLFiltering || !options.Environment.IPAddress || !options.Environment.UserStylesheet {
		t.Fatalf("unexpected environment: %#v", options.Environment)
	}
	var invalid AdblockDNSBlockMode
	if err := json.Unmarshal([]byte(`"invalid"`), &invalid); err != nil {
		t.Fatal(err)
	}
	if invalid != AdblockDNSBlockModeZeroIP {
		t.Fatalf("invalid mode should default to zero_ip, got %q", invalid)
	}
}

func TestAdblockConstraintsUnmarshal(t *testing.T) {
	for _, test := range []struct {
		name string
		data string
		want int
	}{
		{name: "legacy object", data: "{\"process_name\":\"browser\"}", want: 1},
		{name: "array", data: "[{\"inbound\":\"lan\"},{\"source_ip_is_not_loopback\":true}]", want: 2},
		{name: "null", data: "null", want: 0},
	} {
		t.Run(test.name, func(t *testing.T) {
			var constraints AdblockConstraints
			if err := badjson.Unmarshal([]byte(test.data), &constraints); err != nil {
				t.Fatal(err)
			}
			if len(constraints) != test.want {
				t.Fatalf("len(constraints) = %d, want %d", len(constraints), test.want)
			}
		})
	}
}
