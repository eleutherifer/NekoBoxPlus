package io.nekohasekai.sagernet.fmt

/**
 * In-TUN address literals for the generated sing-box configuration.
 *
 * These used to live on [io.nekohasekai.sagernet.bg.VpnService], but since the
 * Android side now consumes the authoritative payload produced by libcore, the
 * VpnService no longer hardcodes any address. They are owned by the config
 * builder, which is the only place that still writes them into the generated
 * sing-box TUN inbound; libcore then reads them back and builds the payload.
 */
internal object TunAddresses {
    /** TUN IPv4 interface address (control subnet is a single /30). */
    const val INET4_CLIENT = "172.19.0.1"

    /** In-TUN IPv4 DNS server / gateway (next address in the /30). */
    const val INET4_ROUTER = "172.19.0.2"

    /** FakeDNS IPv4 range base, claimed as a route in bypass-LAN mode. */
    const val FAKEDNS_V4 = "198.18.0.0"

    /** TUN IPv6 interface address (control subnet is a single /126). */
    const val INET6_CLIENT = "fdfe:dcba:9876::1"

    /** In-TUN IPv6 DNS server / gateway (next address in the /126). */
    const val INET6_ROUTER = "fdfe:dcba:9876::2"
}
