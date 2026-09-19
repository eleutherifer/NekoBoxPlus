//go:build with_quic

package trusttunnel

import (
	stdtls "crypto/tls"
	"errors"
	"net"
	"testing"

	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/tls"
	"github.com/stretchr/testify/require"
	"golang.org/x/net/http2"
)

type stdConfigErrorTLSConfig struct {
	config *stdtls.Config
	err    error
}

func (c *stdConfigErrorTLSConfig) ServerName() string              { return c.config.ServerName }
func (c *stdConfigErrorTLSConfig) SetServerName(serverName string) { c.config.ServerName = serverName }
func (c *stdConfigErrorTLSConfig) NextProtos() []string            { return c.config.NextProtos }
func (c *stdConfigErrorTLSConfig) SetNextProtos(p []string)        { c.config.NextProtos = p }
func (c *stdConfigErrorTLSConfig) STDConfig() (*stdtls.Config, error) {
	return nil, c.err
}
func (c *stdConfigErrorTLSConfig) Client(conn net.Conn) (tls.Conn, error) {
	return stdtls.Client(conn, c.config), nil
}
func (c *stdConfigErrorTLSConfig) Clone() tls.Config {
	return &stdConfigErrorTLSConfig{
		config: c.config.Clone(),
		err:    c.err,
	}
}

func TestNewClientQUICUsesSeparateStandardTLSConfig(t *testing.T) {
	t.Parallel()

	fallbackTLSConfig := &stdConfigErrorTLSConfig{
		config: &stdtls.Config{
			NextProtos: []string{"http/1.1"},
		},
		err: errors.New("unsupported usage for uTLS"),
	}
	quicTLSConfig := &testClientTLSConfig{
		config: &stdtls.Config{
			NextProtos: []string{http2.NextProtoTLS},
		},
	}
	logger := &captureLogger{}

	client, err := NewClient(ClientOptions{
		Ctx:           t.Context(),
		Logger:        logger,
		TLSConfig:     fallbackTLSConfig,
		QUICTLSConfig: quicTLSConfig,
		QUIC:          true,
	})
	require.NoError(t, err)
	require.NotNil(t, client)
	require.Equal(t, []string{"http/1.1", http3.NextProtoH3}, fallbackTLSConfig.NextProtos())
	require.Equal(t, []string{http2.NextProtoTLS, http3.NextProtoH3}, quicTLSConfig.NextProtos())
	require.Len(t, logger.warnings, 2)
	require.Contains(t, logger.warnings[0], "missing required ALPN "+http3.NextProtoH3)
	require.Contains(t, logger.warnings[1], "missing required ALPN "+http3.NextProtoH3)
	require.NotNil(t, client.fallbackRoundTrip)
}

func TestNewClientForceQUICSkipsFallbackRoundTripper(t *testing.T) {
	t.Parallel()

	tlsConfig := &testClientTLSConfig{
		config: &stdtls.Config{
			NextProtos: []string{http2.NextProtoTLS},
		},
	}

	client, err := NewClient(ClientOptions{
		Ctx:       t.Context(),
		TLSConfig: tlsConfig,
		QUIC:      true,
		ForceQUIC: true,
	})

	require.NoError(t, err)
	require.NotNil(t, client)
	require.Nil(t, client.fallbackRoundTrip)
	require.Equal(t, []string{http3.NextProtoH3}, tlsConfig.NextProtos())
}
