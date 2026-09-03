package option

import (
	"encoding/base64"
	"testing"

	"github.com/sagernet/sing/common/json"
	"github.com/stretchr/testify/require"
)

func TestXrayCertificateSHA256Unmarshal(t *testing.T) {
	pin := make([]byte, 32)
	for index := range pin {
		pin[index] = byte(index)
	}
	var options OutboundTLSOptions
	err := json.Unmarshal([]byte(`{"xray_certificate_sha256":["`+base64.StdEncoding.EncodeToString(pin)+`"]}`), &options)
	require.NoError(t, err)
	require.Equal(t, [][]byte{pin}, [][]byte(options.XrayCertificateSHA256))

	err = json.Unmarshal([]byte(`{"xray_certificate_sha256":["not-base64"]}`), &options)
	require.Error(t, err)
}
