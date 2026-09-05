//go:build !with_trusttunnel_cronet

package trusttunnel

import E "github.com/sagernet/sing/common/exceptions"

func checkCronetAvailable(options ClientOptions) error {
	if options.UseCronetQUIC || options.UseCronetHTTPS {
		return E.New(`TrustTunnel Cronet is not included in this build, rebuild with -tags with_trusttunnel_cronet`)
	}
	return nil
}

func (c *Client) newCronetRoundTripper(options ClientOptions, quic bool) (RoundTripper, error) {
	return nil, E.New(`TrustTunnel Cronet is not included in this build, rebuild with -tags with_trusttunnel_cronet`)
}
