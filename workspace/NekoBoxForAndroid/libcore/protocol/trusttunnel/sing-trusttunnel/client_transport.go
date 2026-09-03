package trusttunnel

import (
	"github.com/sagernet/sing/common/baderror"
	"github.com/sagernet/sing/common/tls"
)

type HTTPSClientInterface interface {
	NewHTTPSRoundTripper(client *Client, options ClientOptions, tlsConfig tls.Config) (RoundTripper, func(error) error, error)
}

type QUICClientInterface interface {
	NewQUICRoundTripper(client *Client, options ClientOptions, tlsConfig tls.Config) error
}

type standardHTTPSClient struct{}

type standardQUICClient struct{}

type cronetHTTPSClient struct{}

type cronetQUICClient struct{}

func newHTTPSClientInterface(options ClientOptions) HTTPSClientInterface {
	if options.UseCronetHTTPS {
		return cronetHTTPSClient{}
	}
	return standardHTTPSClient{}
}

func newQUICClientInterface(options ClientOptions) QUICClientInterface {
	if options.UseCronetQUIC {
		return cronetQUICClient{}
	}
	return standardQUICClient{}
}

func (standardHTTPSClient) NewHTTPSRoundTripper(client *Client, options ClientOptions, tlsConfig tls.Config) (RoundTripper, func(error) error, error) {
	roundTripper, wrapError := client.newH2RoundTripper(tlsConfig)
	return roundTripper, wrapError, nil
}

func (cronetHTTPSClient) NewHTTPSRoundTripper(client *Client, options ClientOptions, tlsConfig tls.Config) (RoundTripper, func(error) error, error) {
	roundTripper, err := client.newCronetRoundTripper(options, false)
	return roundTripper, baderror.WrapH2, err
}

func (standardQUICClient) NewQUICRoundTripper(client *Client, options ClientOptions, tlsConfig tls.Config) error {
	return client.quicRoundTripper(tlsConfig, options.QUICCongestionControl)
}

func (cronetQUICClient) NewQUICRoundTripper(client *Client, options ClientOptions, tlsConfig tls.Config) error {
	roundTripper, err := client.newCronetRoundTripper(options, true)
	if err != nil {
		return err
	}
	client.roundTripper = roundTripper
	client.wrapError = func(err error) error { return err }
	return nil
}
