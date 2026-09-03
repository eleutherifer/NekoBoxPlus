package libcore

import (
	"net/netip"
	"testing"
)

func TestGeoIPCountryFromDat(t *testing.T) {
	reader := &geoip{countryPrefixes: map[netip.Prefix]string{
		netip.MustParsePrefix("203.0.113.0/24"): "NL",
	}}

	country, err := reader.Country(netip.MustParseAddr("203.0.113.5"))
	if err != nil {
		t.Fatal(err)
	}
	if country != "NL" {
		t.Fatalf("expected NL, got %q", country)
	}
}

func TestGeoIPCountryRejectsPrivateAddress(t *testing.T) {
	reader := &geoip{countryPrefixes: map[netip.Prefix]string{
		netip.MustParsePrefix("10.0.0.0/8"): "US",
	}}

	country, err := reader.Country(netip.MustParseAddr("10.0.0.1"))
	if err != nil {
		t.Fatal(err)
	}
	if country != "" {
		t.Fatalf("expected no country, got %q", country)
	}
}

func TestValidCountryCode(t *testing.T) {
	if code := validCountryCode(" nl "); code != "NL" {
		t.Fatalf("expected NL, got %q", code)
	}
	if code := validCountryCode("private"); code != "" {
		t.Fatalf("expected invalid code to be empty, got %q", code)
	}
}
