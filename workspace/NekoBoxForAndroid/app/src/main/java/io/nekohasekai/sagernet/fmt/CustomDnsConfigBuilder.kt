package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.CustomDnsServerEntity
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleType
import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions
import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import moe.matsuri.nb4a.SingBoxOptions.DomainResolveOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundTLSOptions
import moe.matsuri.nb4a.checkEmpty
import moe.matsuri.nb4a.makeSingBoxRule
import moe.matsuri.nb4a.utils.NGUtil.isPureIpAddress
import moe.matsuri.nb4a.utils.listByLineOrComma

private val SUPPORTED_CUSTOM_DNS_TYPES = setOf("udp", "tcp", "tls", "quic", "https", "h3", "local")
private val BUILTIN_DOMAIN_RESOLVERS = setOf("dns-direct", "dns-remote")

fun normalizeCustomDnsDetour(detour: String): String =
    if (detour.trim() == TAG_PROXY) TAG_PROXY else TAG_DIRECT

fun validateCustomDnsServer(
    server: CustomDnsServerEntity,
    existing: List<CustomDnsServerEntity>,
): String? {
    val tag = server.tag.trim()
    if (tag.isBlank()) return "DNS server name is required"
    if (tag in CustomDnsServerEntity.RESERVED_TAGS) return "DNS server name is reserved"
    if (server.type !in SUPPORTED_CUSTOM_DNS_TYPES) return "Unsupported DNS server type"
    if (server.type != "local" && server.server.trim().isBlank()) return "DNS server address is required"
    if (server.domainResolver.isNotBlank() && server.domainResolver !in BUILTIN_DOMAIN_RESOLVERS) {
        return "Domain resolver is invalid"
    }
    if (server.type != "local" && !isPureIpAddress(server.server.trim()) && server.domainResolver.isBlank()) {
        return "Domain resolver is required for domain server addresses"
    }
    if (server.serverPort !in 0..65535) return "DNS server port is invalid"
    if (existing.any { it.id != server.id && it.tag == tag }) return "DNS server name already exists"
    return null
}

fun buildCustomDnsServerOptions(
    server: CustomDnsServerEntity,
    detourTag: String,
): DNSServerOptions {
    return DNSServerOptions().apply {
        type = server.type
        tag = server.tag.trim()
        if (server.type != "local") {
            this.server = server.server.trim()
            if (server.serverPort > 0) server_port = server.serverPort
        }
        if ((server.type == "https" || server.type == "h3") && server.path.isNotBlank()) {
            path = server.path.trim()
        }
        if ((server.type == "https" || server.type == "h3") && server.method.isNotBlank()) {
            method = server.method.trim()
        }
        parseHeaders(server.headers).takeIf { it.isNotEmpty() }?.let { headers = it }
        if (server.domainResolver.isNotBlank()) {
            domain_resolver = DomainResolveOptions().apply {
                this.server = server.domainResolver.trim()
                if (server.domainStrategy.isNotBlank() && server.domainStrategy != "auto") strategy = server.domainStrategy.trim()
                if (server.disableCache) disable_cache = true
                if (server.rewriteTtl > 0) rewrite_ttl = server.rewriteTtl
                if (server.clientSubnet.isNotBlank()) client_subnet = server.clientSubnet.trim()
            }
        }
        if (detourTag.isNotEmpty()) detour = detourTag
        if (server.bindInterface.isNotBlank()) bind_interface = server.bindInterface.trim()
        if (server.inet4BindAddress.isNotBlank()) inet4_bind_address = server.inet4BindAddress.trim()
        if (server.inet6BindAddress.isNotBlank()) inet6_bind_address = server.inet6BindAddress.trim()
        if (server.connectTimeout > 0) connect_timeout = server.connectTimeout
        if (server.tcpFastOpen) tcp_fast_open = true
        if (server.tcpMultiPath) tcp_multi_path = true
        when (server.udpFragment.lowercase()) {
            "true" -> udp_fragment = true
            "false" -> udp_fragment = false
        }
        if (server.localPreferGo && server.type == "local") prefer_go = true
        if (server.type in setOf("tls", "quic", "https", "h3") && hasTlsOptions(server)) {
            tls = OutboundTLSOptions().apply {
                enabled = true
                if (server.tlsServerName.isNotBlank()) server_name = server.tlsServerName.trim()
                if (server.tlsInsecure) insecure = true
                if (server.tlsAlpn.isNotBlank()) alpn = server.tlsAlpn.listByLineOrComma()
                if (server.tlsCertificates.isNotBlank()) certificate = server.tlsCertificates.listByLineOrComma()
            }
        }
    }
}

fun buildStandaloneDnsRule(
    rule: RuleEntity,
    customServerTags: Set<String>,
): DNSRule_DefaultOptions? {
    if (RuleType.fromValue(rule.type) != RuleType.DNS) return null
    if (rule.dnsAction == "route" && rule.dnsServer !in builtinDnsServerTags && rule.dnsServer !in customServerTags) {
        return null
    }

    return DNSRule_DefaultOptions().apply {
        if (rule.domains.isNotBlank()) makeSingBoxRule(rule.domains.listByLineOrComma())
        if (rule.ruleset.isNotBlank()) {
            val tags = rule.ruleset.listByLineOrComma()
            rule_set = (rule_set.orEmpty() + tags).distinct()
        }
        if (rule.ip.isNotBlank()) {
            ip_cidr = mutableListOf()
            rule.ip.listByLineOrComma().forEach {
                if (it == "geoip:private") ip_is_private = true else ip_cidr!!.add(it)
            }
            if (ip_cidr?.isEmpty() == true) ip_cidr = null
        }
        if (rule.source.isNotBlank()) source_ip_cidr = rule.source.listByLineOrComma()
        if (rule.port.isNotBlank()) {
            port = mutableListOf()
            port_range = mutableListOf()
            rule.port.listByLineOrComma().forEach {
                if (it.contains(":")) port_range!!.add(it) else it.toIntOrNull()?.let { value -> port!!.add(value) }
            }
            if (port?.isEmpty() == true) port = null
            if (port_range?.isEmpty() == true) port_range = null
        }
        if (rule.sourcePort.isNotBlank()) {
            source_port = mutableListOf()
            source_port_range = mutableListOf()
            rule.sourcePort.listByLineOrComma().forEach {
                if (it.contains(":")) source_port_range!!.add(it) else it.toIntOrNull()?.let { value -> source_port!!.add(value) }
            }
            if (source_port?.isEmpty() == true) source_port = null
            if (source_port_range?.isEmpty() == true) source_port_range = null
        }
        if (rule.network.isNotBlank()) network = rule.network.listByLineOrComma()
        if (rule.protocol.isNotBlank()) protocol = rule.protocol.listByLineOrComma()
        if (rule.packages.isNotEmpty()) package_name = rule.packages.toList()
        if (rule.networkType.isNotEmpty()) network_type = rule.networkType.toList()
        if (RuleEntity.isWifiIdentityVisible(rule.networkType)) {
            RuleEntity.normalizeWifiSsidList(rule.wifiSsid).takeIf { it.isNotEmpty() }?.let { wifi_ssid = it }
            RuleEntity.normalizeWifiBssidList(rule.wifiBssid).takeIf { it.isNotEmpty() }?.let { wifi_bssid = it }
        }
        if (rule.clashMode.isNotBlank()) clash_mode = rule.clashMode
        if (rule.config.isNotBlank()) _hack_custom_config = rule.config

        when (rule.dnsAction.ifBlank { "route" }) {
            "predefined" -> {
                action = "predefined"
                rcode = rule.dnsRcode.ifBlank { "NOERROR" }
                rule.dnsPredefinedAnswer.listByLineOrComma().takeIf { it.isNotEmpty() }?.let { answer = it }
                rule.dnsPredefinedNs.listByLineOrComma().takeIf { it.isNotEmpty() }?.let { ns = it }
                rule.dnsPredefinedExtra.listByLineOrComma().takeIf { it.isNotEmpty() }?.let { extra = it }
            }
            "reject" -> {
                action = "reject"
                if (rule.dnsRejectMethod.isNotBlank()) method = rule.dnsRejectMethod
            }
            "route-options" -> {
                action = "route-options"
                applyDnsRouteOptions(rule)
            }
            else -> {
                server = rule.dnsServer.ifBlank { "dns-remote" }
                applyDnsRouteOptions(rule)
            }
        }
    }.takeUnless { it.checkEmpty() }
}

private val builtinDnsServerTags = setOf("dns-direct", "dns-remote", "dns-local", "dns-fake")

private fun DNSRule_DefaultOptions.applyDnsRouteOptions(rule: RuleEntity) {
    if (rule.dnsDisableCache) disable_cache = true
    if (rule.dnsRewriteTtl > 0) rewrite_ttl = rule.dnsRewriteTtl
    if (rule.dnsClientSubnet.isNotBlank()) client_subnet = rule.dnsClientSubnet
}

private fun hasTlsOptions(server: CustomDnsServerEntity): Boolean {
    return server.tlsServerName.isNotBlank() ||
        server.tlsInsecure ||
        server.tlsAlpn.isNotBlank() ||
        server.tlsCertificates.isNotBlank()
}

private fun parseHeaders(raw: String): Map<String, String> {
    return raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull {
            val separator = it.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            it.substring(0, separator).trim().takeIf(String::isNotEmpty)
                ?.let { key -> key to it.substring(separator + 1).trim() }
        }
        .toMap()
}
