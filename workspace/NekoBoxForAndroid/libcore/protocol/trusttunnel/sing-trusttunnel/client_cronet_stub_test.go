//go:build !with_trusttunnel_cronet

package trusttunnel

import (
	stdtls "crypto/tls"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestNewClientCronetHTTPSRequiresBuildTag(t *testing.T) {
	t.Parallel()

	client, err := NewClient(ClientOptions{
		Ctx:            t.Context(),
		TLSConfig:      &testClientTLSConfig{config: &stdtls.Config{}},
		UseCronetHTTPS: true,
	})

	require.Nil(t, client)
	require.Error(t, err)
	require.Contains(t, err.Error(), "with_trusttunnel_cronet")
}

func TestNewClientCronetQUICRequiresBuildTag(t *testing.T) {
	t.Parallel()

	client, err := NewClient(ClientOptions{
		Ctx:           t.Context(),
		TLSConfig:     &testClientTLSConfig{config: &stdtls.Config{}},
		QUIC:          true,
		UseCronetQUIC: true,
	})

	require.Nil(t, client)
	require.Error(t, err)
	require.Contains(t, err.Error(), "with_trusttunnel_cronet")
}
