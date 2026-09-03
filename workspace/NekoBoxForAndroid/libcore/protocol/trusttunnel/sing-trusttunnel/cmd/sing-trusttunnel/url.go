package main

import (
	"cmp"
	"encoding/json"
	"os"

	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"

	"libcore/protocol/trusttunnel/sing-trusttunnel/tturl"
)

func runConfigToURL() error {
	options, err := parseConfig[ClientOptions](configPath)
	if err != nil {
		return E.Cause(err, "parse config")
	}

	url := &tturl.URL{
		Hostname: options.Server,
		Addresses: []M.Socksaddr{
			M.ParseSocksaddrHostPort(options.Server, options.ServerPort),
		},
		Username:           options.Username,
		Password:           options.Password,
		CustomSNI:          options.ServerName,
		SkipVerification:   options.AllowInsecure,
		ClientRandomPrefix: options.ClientRandomPrefix,
		Name:               options.Name,
		DNSUpstreams:       options.DNSUpstreams,
		Certificate:        options.Certificate,
	}
	if options.QUIC {
		url.UpstreamProtocol = tturl.UpstreamProtocolHTTP3
	} else {
		url.UpstreamProtocol = tturl.UpstreamProtocolHTTP2
	}
	link, err := url.Build()
	if err != nil {
		return E.Cause(err, "build url")
	}
	_, err = os.Stdout.WriteString(link)
	if err != nil {
		return E.Cause(err, "write link")
	}
	return nil
}

func runURLToConfig(configType string, link string) error {
	url, err := tturl.Parse(link)
	if err != nil {
		return E.Cause(err, "parse url")
	}

	var config any
	switch configType {
	case "client":
		options := &ClientOptions{
			Server:             url.Hostname,
			Username:           url.Username,
			Password:           url.Password,
			ServerName:         url.CustomSNI,
			AllowInsecure:      url.SkipVerification,
			QUIC:               url.UpstreamProtocol == tturl.UpstreamProtocolHTTP3,
			ClientRandomPrefix: url.ClientRandomPrefix,
			Name:               url.Name,
			DNSUpstreams:       url.DNSUpstreams,
			Certificate:        url.Certificate,
		}
		if len(url.Addresses) > 0 {
			addr := url.Addresses[0]
			if addr.Port != 0 {
				options.ServerPort = addr.Port
			}
		}
		config = options
	case "server":
		options := &ServerOptions{
			ServerName: cmp.Or(url.CustomSNI, url.Hostname),
			ListenQUIC: url.UpstreamProtocol == tturl.UpstreamProtocolHTTP3,
		}
		if len(url.Addresses) > 0 {
			addr := url.Addresses[0]
			if addr.Port != 0 {
				options.ListenPort = addr.Port
			}
		}
		config = options
	default:
		return E.New("invalid config type: ", configType, ", must be 'client' or 'server'")
	}

	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	err = encoder.Encode(config)
	if err != nil {
		return E.Cause(err, "encode config")
	}
	return nil
}
