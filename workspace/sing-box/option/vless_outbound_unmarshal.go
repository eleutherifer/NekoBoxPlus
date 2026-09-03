package option

import (
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing/common/json"
)

type _VLESSOutboundOptions VLESSOutboundOptions

// UnmarshalJSON implements a dirty hack. It works when uTLS is enabled and the only ALPN is h3.
// transport/v2rayxhttp/client.go cannot reliably rebuild STDConfig for TLS from uTLS config in
// that case because uTLS doesn't expose enough info. Incorporating a rebuild into v2rayxhttp would
// require broader changes inside common/tls and merges are already a nightmare.
// This shit works so it stays for now.
func (c *VLESSOutboundOptions) UnmarshalJSON(bytes []byte) error {
	err := json.Unmarshal(bytes, (*_VLESSOutboundOptions)(c))
	if err != nil {
		return err
	}
	if c.Transport == nil ||
		c.Transport.Type != C.V2RayTransportTypeXHTTP ||
		c.TLS == nil ||
		c.TLS.UTLS == nil ||
		!c.TLS.UTLS.Enabled ||
		c.TLS.Reality != nil && c.TLS.Reality.Enabled ||
		len(c.TLS.ALPN) > 1 {
		return nil
	}
	if c.TLS.UTLS.Enabled && len(c.TLS.ALPN) == 1 && (c.TLS.ALPN[0] == "h3" || c.TLS.ALPN[0] == "quic") {
		c.TLS.UTLS = &OutboundUTLSOptions{}
	}
	return nil
}
