package trusttunnel

import (
	"context"
	stdtls "crypto/tls"
	"fmt"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
	"golang.org/x/net/http2"
)

type captureLogger struct {
	warnings []string
}

func (l *captureLogger) Trace(args ...any) {}
func (l *captureLogger) Debug(args ...any) {}
func (l *captureLogger) Info(args ...any)  {}
func (l *captureLogger) Warn(args ...any)  { l.WarnContext(context.Background(), args...) }
func (l *captureLogger) Error(args ...any) {}
func (l *captureLogger) Fatal(args ...any) {}
func (l *captureLogger) Panic(args ...any) {}

func (l *captureLogger) TraceContext(ctx context.Context, args ...any) {}
func (l *captureLogger) DebugContext(ctx context.Context, args ...any) {}
func (l *captureLogger) InfoContext(ctx context.Context, args ...any)  {}
func (l *captureLogger) WarnContext(ctx context.Context, args ...any) {
	var builder strings.Builder
	for _, arg := range args {
		builder.WriteString(toString(arg))
	}
	l.warnings = append(l.warnings, builder.String())
}
func (l *captureLogger) ErrorContext(ctx context.Context, args ...any) {}
func (l *captureLogger) FatalContext(ctx context.Context, args ...any) {}
func (l *captureLogger) PanicContext(ctx context.Context, args ...any) {}

func toString(value any) string {
	return fmt.Sprint(value)
}

func TestNormalizeClientALPNDefaultsEmptyConfig(t *testing.T) {
	t.Parallel()

	config := &testClientTLSConfig{config: &stdtls.Config{}}
	logger := &captureLogger{}

	normalizeClientALPN(t.Context(), logger, config, "h3")

	require.Equal(t, []string{"h3"}, config.NextProtos())
	require.Empty(t, logger.warnings)
}

func TestNormalizeClientALPNAppendsMissingRequiredProtocolAndWarns(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name     string
		initial  []string
		required string
		expected []string
	}{
		{
			name:     "h3",
			initial:  []string{http2.NextProtoTLS},
			required: "h3",
			expected: []string{http2.NextProtoTLS, "h3"},
		},
		{
			name:     "h2",
			initial:  []string{"http/1.1"},
			required: http2.NextProtoTLS,
			expected: []string{"http/1.1", http2.NextProtoTLS},
		},
	}
	for _, testCase := range tests {
		t.Run(testCase.name, func(t *testing.T) {
			t.Parallel()

			config := &testClientTLSConfig{config: &stdtls.Config{}}
			config.SetNextProtos(testCase.initial)
			logger := &captureLogger{}

			normalizeClientALPN(t.Context(), logger, config, testCase.required)

			require.Equal(t, testCase.expected, config.NextProtos())
			require.Len(t, logger.warnings, 1)
			require.Contains(t, logger.warnings[0], "missing required ALPN "+testCase.required)
		})
	}
}

func TestNormalizeClientALPNKeepsExistingRequiredProtocol(t *testing.T) {
	t.Parallel()

	config := &testClientTLSConfig{config: &stdtls.Config{}}
	config.SetNextProtos([]string{"http/1.1", "h3"})
	logger := &captureLogger{}

	normalizeClientALPN(t.Context(), logger, config, "h3")

	require.Equal(t, []string{"http/1.1", "h3"}, config.NextProtos())
	require.Empty(t, logger.warnings)
}

func TestNewClientAppendsH2WhenHTTP2ALPNMissing(t *testing.T) {
	t.Parallel()

	config := &testClientTLSConfig{config: &stdtls.Config{}}
	config.SetNextProtos([]string{"http/1.1"})
	logger := &captureLogger{}

	client, err := NewClient(ClientOptions{
		Ctx:       t.Context(),
		Logger:    logger,
		TLSConfig: config,
	})
	require.NoError(t, err)
	require.NotNil(t, client)
	require.Equal(t, []string{"http/1.1", http2.NextProtoTLS}, config.NextProtos())
	require.Len(t, logger.warnings, 1)
	require.Contains(t, logger.warnings[0], "missing required ALPN "+http2.NextProtoTLS)
}

func TestNewRequestSeparatesOriginAndConnectAuthority(t *testing.T) {
	t.Parallel()

	request := newRequest("origin.example:443", "_check", nil)

	require.Equal(t, "origin.example:443", request.URL.Host)
	require.Equal(t, "_check", request.Host)
}
