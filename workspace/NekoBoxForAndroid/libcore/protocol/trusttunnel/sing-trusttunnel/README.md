# sing-trusttunnel

# Source

Repository: https://github.com/xchacha20-poly1305/sing-trusttunnel
Commit: f604faf14c23198ddf2873610a0900966e7d2dcb

[![Go Reference](https://pkg.go.dev/badge/github.com/xchacha20-poly1305/sing-trusttunnel.svg)](https://pkg.go.dev/github.com/xchacha20-poly1305/sing-trusttunnel)

A sing style [TrustTunnel](https://trusttunnel.org/) implementation.

API reference: [sing-trusttunnel on pkg.go.dev](https://pkg.go.dev/github.com/xchacha20-poly1305/sing-trusttunnel)

## CLI

### Build

```bash
make build
```

### Usage

```
sing-trusttunnel [options] <command> [arguments]

Options:
  -v              Show version
  -c <path>       Config file path (default: config.json)

Commands:
  client          Run as client mode
  server          Run as server mode
  config-to-url   Convert client config to TrustTunnel URL
  url-to-config   Convert TrustTunnel URL to config
```

### Config

**Server**

```json
{
  "listen": "::",
  "listenPort": 443,
  "cert": "/path/to/cert.pem",
  "key": "/path/to/key.key",
  "serverName": "example.com",
  "users": [
    {
      "username": "trust",
      "password": "tunnel"
    }
  ],
  "listenQuic": true,
  "authFailureStatusCode": 407,
  "nonConnectAuthFailureStatusCode": 407
}
```

**Client**

```json5
{
  "server": "server.example.com",
  "serverPort": 443,
  "username": "trust",
  "password": "tunnel",
  "serverName": "example.com",
  "allowInsecure": true,
  "healthCheck": true,
  "quic": true,
  "clientRandomPrefix": "aabbcc",
  "name": "Example TrustTunnel",
  "dnsUpstreams": [
    "1.1.1.1",
    "tls://dns.example.com"
  ],
  
  // Socks listen
  "listen": "127.0.0.1",
  "listenPort": 1080,
  "auth": [
    {
      "username": "socks",
      "password": "5"
    }
  ]
}
```

### Client random

`clientRandomPrefix` controls the TLS ClientHello random sent to the TrustTunnel
endpoint. It supports two formats:

- `"aabbcc"`: overwrite the leading random bytes with the given hex prefix.
- `"a0b0/f0f0"`: apply a hex mask, so only masked bits are fixed.

The value must decode to at most 32 bytes. Masked values must use equal-length
prefix and mask parts. A fresh 32-byte random is generated for each new upstream
TLS or QUIC session, then the configured prefix or mask is applied.

For HTTP/2, sing-trusttunnel uses uTLS when it needs to set the ClientHello
random. If the caller provides a uTLS-enabled sing-box TLS config, the existing
uTLS fingerprint is kept and only the ClientHello random is injected. For HTTP/3,
the same generated random is supplied through the QUIC TLS config entropy source.

When `quic` is enabled, the client first probes the HTTP/3 endpoint with a short
timeout. If the QUIC probe fails, it falls back to HTTP/2 using a cloned TLS
config with only ALPN changed to `h2`, matching the official TrustTunnel
client's QUIC-to-TLS fallback behavior.

### Authentication failure status

Servers return `407 Proxy Authentication Required` by default, with
`Proxy-Authenticate: Basic realm=Authorization Required`. `authFailureStatusCode`
and `nonConnectAuthFailureStatusCode` can be set to `407`, `405`, `404`, or
`403` to match TrustTunnel endpoint behavior.

### URL conversion

`config-to-url` and `url-to-config` preserve TrustTunnel deep-link fields:
`clientRandomPrefix`, `name`, `dnsUpstreams`, `certificate`,
`serverName`, `allowInsecure`, and the upstream protocol selected by `quic`.
