package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.IPv6Mode
import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions

internal fun dnsStrategyForIpv6Mode(configured: String, ipv6Mode: Int): String? =
    when (ipv6Mode) {
        IPv6Mode.DISABLE -> "ipv4_only"
        IPv6Mode.ONLY -> "ipv6_only"
        IPv6Mode.PREFER -> configured.ifBlank { "prefer_ipv6" }
        IPv6Mode.ENABLE -> configured.ifBlank { "prefer_ipv4" }
        else -> configured.ifBlank { null }
    }

internal fun destinationStrategyForIpv6Mode(resolveDestination: Boolean, ipv6Mode: Int): String =
    when {
        ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
        ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
        !resolveDestination -> ""
        ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
        else -> "prefer_ipv4"
    }

internal fun strictDnsStrategyForIpv6Mode(ipv6Mode: Int): String? =
    when (ipv6Mode) {
        IPv6Mode.DISABLE -> "ipv4_only"
        IPv6Mode.ONLY -> "ipv6_only"
        else -> null
    }

internal fun tunDnsAddressesForIpv6Mode(
    ipv6Mode: Int,
    ipv4Address: String,
    ipv6Address: String,
): List<String> =
    when (ipv6Mode) {
        IPv6Mode.DISABLE -> listOf(ipv4Address)
        IPv6Mode.ONLY -> listOf(ipv6Address)
        else -> listOf(ipv4Address, ipv6Address)
    }

internal fun buildAddressFamilyFilterDnsRule(ipv6Mode: Int): DNSRule_DefaultOptions? {
    val blockedIpVersion =
        when (ipv6Mode) {
            IPv6Mode.DISABLE -> 6
            IPv6Mode.ONLY -> 4
            else -> return null
        }
    return DNSRule_DefaultOptions().apply {
        ip_version = blockedIpVersion
        action = "predefined"
        rcode = "NOERROR"
    }
}
