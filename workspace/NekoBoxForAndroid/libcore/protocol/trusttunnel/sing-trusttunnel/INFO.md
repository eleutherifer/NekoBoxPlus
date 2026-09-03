# Vendored sing-trusttunnel

Upstream repository: https://github.com/xchacha20-poly1305/sing-trusttunnel

Base commit: https://github.com/xchacha20-poly1305/sing-trusttunnel/commit/2bb5a0ce5fa6689e6c3c4226055d4e94af2ae779

The vendored version adds Cronet HTTPS/QUIC transports and forced QUIC,
custom TLS ClientHello random support with HTTP/3-to-HTTP/2 fallback,
configurable TLS identity and trusted roots, extended URL/config and
authentication-status handling, and connection reset, recovery, and cleanup
for HTTP/2, QUIC, and Cronet transports. Its Go import paths are rewritten from
the upstream module path to the libcore package path.
