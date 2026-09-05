# MASQUE

MASQUE tunnels complete IP packets through Cloudflare WARP using CONNECT-IP over HTTP/3 or HTTP/2.

### Structure

```json
{
  "type": "masque",
  "tag": "warp",
  "transport": "auto",
  "h3_fallback_timeout": "5s",
  "mtu": 1280,
  "profile": {},
  "tls": {}
}
```

The existing `profile` object controls Cloudflare enrollment and cached credentials. The existing `config` object can still provide enrolled key material and endpoints directly.

### Transport fields

#### transport

CONNECT-IP transport: `auto`, `h3`, or `h2`. The default is `auto`: try HTTP/3 first, then use HTTP/2 when the QUIC handshake or H3 capability negotiation fails.

The legacy `use_http2: true` setting remains supported and selects `h2`. Do not combine it with `transport: auto` or `transport: h3`.

#### h3_fallback_timeout

Maximum time given to the complete HTTP/3 setup in automatic mode before HTTP/2 is attempted. The default is `5s`.

Authentication, certificate-pinning, configuration, and HTTP 4xx failures do not trigger fallback.

#### mtu

IP MTU used by the userspace or system tunnel. The default is `1280`. HTTP/2 transports accept values up to `16000`.

#### udp_initial_packet_size

Initial QUIC packet size. The default is `1242`. Path MTU discovery remains enabled when this field is set.

#### disable_path_mtu_discovery

Disables QUIC path MTU discovery when explicitly set to `true`. The default is `false`.

All existing dialer, endpoint-family, system-interface, allowed-IP, reconnect, UDP timeout, keepalive, and TLS fields remain supported.
