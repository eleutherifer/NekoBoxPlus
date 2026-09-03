package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.IPv6Mode
import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import moe.matsuri.nb4a.utils.NGUtil.isIpv4Address
import moe.matsuri.nb4a.utils.NGUtil.isPureIpAddress

private const val ANDROID_DNS_UID = 1051

internal fun buildTunUnrecognizedTrafficRule(
    tunMode: String,
    bypassMode: Boolean,
    mainProxyTag: String,
): Rule_DefaultOptions? {
    if (tunMode == "insecure") return null
    return Rule_DefaultOptions().apply {
        inbound = listOf(TAG_TUN)
        package_name = mutableListOf("android")
        outbound = when (tunMode) {
            "block" -> TAG_BLOCK
            "normal-direct-bypass-block" -> if (bypassMode) TAG_BLOCK else TAG_DIRECT
            "direct" -> TAG_DIRECT
            "proxy" -> mainProxyTag
            else -> TAG_BLOCK
        }
        replaceBlockOutboundWithRejectAction()
    }
}

internal fun buildTunSystemDnsRouteRules(
    dnsWhitelist: String,
    dotWhitelist: String,
    dohWhitelist: String,
    outboundTag: String,
): List<Rule_DefaultOptions> {
    fun entries(raw: String) = raw.split('\n', ',').map(String::trim).filter(String::isNotEmpty)
    fun ipCidrs(raw: String) = entries(raw).filter { isPureIpAddress(it) }
        .map { if (isIpv4Address(it)) "$it/32" else "$it/128" }
    fun newRule() = Rule_DefaultOptions().apply {
        inbound = listOf(TAG_TUN)
        user_id = listOf(ANDROID_DNS_UID)
        outbound = outboundTag
    }
    fun encryptedDnsRule(whitelist: String, portNumber: Int): Rule_DefaultOptions? {
        val whitelistEntries = entries(whitelist)
        val ips = whitelistEntries.filter { isPureIpAddress(it) }
            .map { if (isIpv4Address(it)) "$it/32" else "$it/128" }
        val domains = whitelistEntries.filterNot { isPureIpAddress(it) }.map { it.lowercase() }
        if (ips.isEmpty() && domains.isEmpty()) return null
        return newRule().apply {
            domain = domains.toMutableList()
            ip_cidr = ips.toMutableList()
            network = listOf("tcp", "udp")
            protocol = listOf("tls", "quic")
            port = listOf(portNumber)
        }
    }

    return buildList {
        ipCidrs(dnsWhitelist).takeIf { it.isNotEmpty() }?.let { ips ->
            add(
                newRule().apply {
                    ip_cidr = ips.toMutableList()
                    network = listOf("udp")
                    port = listOf(53)
                },
            )
        }
        encryptedDnsRule(dotWhitelist, 853)?.let(::add)
        encryptedDnsRule(dohWhitelist, 443)?.let(::add)
    }
}

internal fun buildBypassLanRouteAddress(
    ipv6Mode: Int,
    publicRoutes: List<String>,
): List<String> = buildList {
    // Keep the router and FakeDNS ranges reachable through TUN while excluding private LAN ranges.
    if (ipv6Mode != IPv6Mode.ONLY) {
        publicRoutes.map(String::trim).filter(String::isNotBlank).forEach(::add)
        add(TunAddresses.INET4_ROUTER + "/32")
        add(TunAddresses.FAKEDNS_V4 + "/15")
    }
    if (ipv6Mode != IPv6Mode.DISABLE) add("2000::/3")
}
