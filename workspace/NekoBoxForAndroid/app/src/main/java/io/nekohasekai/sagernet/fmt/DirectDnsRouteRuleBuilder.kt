package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import moe.matsuri.nb4a.utils.NGUtil.isIpv4Address
import moe.matsuri.nb4a.utils.NGUtil.isPureIpAddress

internal fun buildDirectDnsRouteRule(rawDirectDns: String): Rule_DefaultOptions? {
    val endpoints = rawDirectDns
        .lineSequence()
        .mapNotNull { parseDnsEndpoint(it) }
        .toList()
    if (endpoints.isEmpty()) return null

    val ipCidrs = mutableListOf<String>()
    val domains = mutableListOf<String>()
    val networks = linkedSetOf<String>()
    val ports = linkedSetOf<Int>()

    endpoints.forEach { endpoint ->
        val isIp = isPureIpAddress(endpoint.host)
        if (isIp) {
            ipCidrs += if (isIpv4Address(endpoint.host)) {
                "${endpoint.host}/32"
            } else {
                "${endpoint.host}/128"
            }
        } else {
            domains += endpoint.host.lowercase()
        }

        networks += directDnsRouteNetworks(endpoint.scheme)
        ports += endpoint.port ?: directDnsRoutePort(endpoint.scheme)
    }

    return Rule_DefaultOptions().apply {
        inbound = listOf(TAG_TUN)
        if (ipCidrs.isNotEmpty()) ip_cidr = ipCidrs.distinct()
        if (domains.isNotEmpty()) domain = domains.distinct()
        network = networks.toList()
        port = ports.toList()
        outbound = TAG_BYPASS
    }
}

private fun directDnsRouteNetworks(scheme: String?): List<String> =
    when (scheme) {
        "https", "tls", "tcp" -> listOf("tcp")
        "quic" -> listOf("udp")
        else -> listOf("tcp", "udp")
    }

private fun directDnsRoutePort(scheme: String?): Int =
    when (scheme) {
        "https", "h3" -> 443
        "tls", "quic" -> 853
        else -> 53
    }
