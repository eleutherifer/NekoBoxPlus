package libcore

import (
	"errors"
	"net"
	"os"
	"sort"
	"strings"

	"github.com/oschwald/maxminddb-golang"
	geosites "github.com/sagernet/sing-box/common/geosite"
)

func ListGeositeCodes(path string) (result string, err error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer func() {
		err = errors.Join(err, file.Close())
	}()

	_, codes, err := geosites.NewReader(file)
	if err == nil {
		sort.Strings(codes)
		return strings.Join(codes, "\n"), nil
	}

	_, codes, datErr := loadV2GeoSite(path)
	if datErr != nil {
		return "", err
	}
	sort.Strings(codes)
	return strings.Join(codes, "\n"), nil
}

func ListGeoipCodes(path string) (result string, err error) {
	reader, err := maxminddb.Open(path)
	if err != nil {
		_, codes, datErr := loadV2GeoIP(path)
		if datErr != nil {
			return "", err
		}
		return strings.Join(codes, "\n"), nil
	}
	defer func() {
		err = errors.Join(err, reader.Close())
	}()

	networks := reader.Networks(maxminddb.SkipAliasedNetworks)
	seen := make(map[string]struct{})
	codes := make([]string, 0)
	var (
		ipNet       *net.IPNet
		countryCode string
	)

	for networks.Next() {
		ipNet, err = networks.Network(&countryCode)
		if err != nil {
			return "", err
		}
		if ipNet == nil {
			continue
		}
		countryCode = strings.ToLower(strings.TrimSpace(countryCode))
		if countryCode == "" {
			continue
		}
		if _, exists := seen[countryCode]; exists {
			continue
		}
		seen[countryCode] = struct{}{}
		codes = append(codes, countryCode)
	}

	sort.Strings(codes)
	return strings.Join(codes, "\n"), nil
}
