package libcore

import (
	"bytes"
	"context"
	"errors"
	"net"
	"os"
	"path/filepath"
	"testing"

	geosites "github.com/sagernet/sing-box/common/geosite"
)

func TestMatchGeoFilesV2RayGeosite(t *testing.T) {
	path := writeGeositeDat(t, &v2geoSiteList{
		Entry: []*v2geoSite{
			{
				CountryCode: "SECOND",
				Domain: []*v2geoDomain{
					{Type: v2geoDomainRootDomain, Value: "example.com"},
				},
			},
			{
				CountryCode: "FIRST",
				Domain: []*v2geoDomain{
					{Type: v2geoDomainFull, Value: "www.example.com"},
				},
			},
			{
				CountryCode: "UNUSED",
				Domain: []*v2geoDomain{
					{Type: v2geoDomainFull, Value: "unused.example"},
				},
			},
		},
	})

	result, err := matchGeoFiles(t.Context(), "WWW.EXAMPLE.COM.", path, "unused")
	if err != nil {
		t.Fatal(err)
	}
	assertStrings(t, result, []string{"geosite:first", "geosite:second"})
}

func TestMatchGeoFilesV2RayGeoIP(t *testing.T) {
	path := writeGeoIPDat(t, &v2geoIPList{
		Entry: []*v2geoIP{
			{
				CountryCode: "PRIVATE",
				CIDR:        []*v2geoCIDR{{IP: []byte{10, 0, 0, 0}, Prefix: 8}},
			},
			{
				CountryCode: "CN",
				CIDR:        []*v2geoCIDR{{IP: []byte{10, 10, 0, 0}, Prefix: 16}},
			},
			{
				CountryCode: "V6",
				CIDR:        []*v2geoCIDR{{IP: net.ParseIP("2001:db8::").To16(), Prefix: 32}},
			},
		},
	})

	result, err := matchGeoFiles(t.Context(), "10.10.1.2", "unused", path)
	if err != nil {
		t.Fatal(err)
	}
	assertStrings(t, result, []string{"geoip:cn", "geoip:private"})

	result, err = matchGeoFiles(t.Context(), "2001:db8::1", "unused", path)
	if err != nil {
		t.Fatal(err)
	}
	assertStrings(t, result, []string{"geoip:v6"})
}

func TestMatchGeoFilesGeositeDB(t *testing.T) {
	path := filepath.Join(t.TempDir(), "geosite.db")
	var buffer bytes.Buffer
	if err := geosites.Write(&buffer, map[string][]geosites.Item{
		"test": {{Type: geosites.RuleTypeDomain, Value: "example.com"}},
	}); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, buffer.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}

	result, err := matchGeoFiles(t.Context(), "example.com", path, "unused")
	if err != nil {
		t.Fatal(err)
	}
	assertStrings(t, result, []string{"geosite:test"})
}

func TestMatchGeoFilesRejectsEmptyInput(t *testing.T) {
	if _, err := matchGeoFiles(t.Context(), "  ", "unused", "unused"); err == nil {
		t.Fatal("expected empty input error")
	}
}

func TestRuleSetMatchSessionCloseCancelsRun(t *testing.T) {
	session := NewRuleSetMatchSession()
	started := make(chan struct{})
	session.run = func(ctx context.Context, _ string) ([]string, error) {
		close(started)
		<-ctx.Done()
		return nil, context.Cause(ctx)
	}
	done := make(chan error, 1)
	go func() {
		_, err := session.Run("example.com")
		done <- err
	}()
	<-started
	session.Close()
	if err := <-done; !errors.Is(err, errRuleSetMatchCancelled) {
		t.Fatalf("unexpected cancellation error: %v", err)
	}
}
