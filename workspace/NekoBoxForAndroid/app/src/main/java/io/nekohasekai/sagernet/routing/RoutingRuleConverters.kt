package io.nekohasekai.sagernet.routing

import io.nekohasekai.sagernet.database.CustomDnsServerEntity
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleType

data class RoutingValueConversion(
    val values: List<String>,
    val omitted: Boolean,
)

/** Converts only values crossing the Xray-based Happ/Incy boundary. */
object XrayRoutingValueConverter {
    fun xrayToSingBox(values: List<String>, domains: Boolean): RoutingValueConversion =
        convert(values) { value ->
            if (domains) xrayDomainToSingBox(value) else xrayIpToSingBox(value)
        }

    fun singBoxToXray(values: List<String>, domains: Boolean): RoutingValueConversion =
        convert(values) { value ->
            if (domains) singBoxDomainToXray(value) else singBoxIpToXray(value)
        }

    private fun convert(values: List<String>, mapper: (String) -> String?): RoutingValueConversion {
        var omitted = false
        val converted = values.mapNotNull { raw ->
            val value = raw.trim()
            if (value.isEmpty()) return@mapNotNull null
            mapper(value).also { if (it == null) omitted = true }
        }.distinct()
        return RoutingValueConversion(converted, omitted)
    }

    private fun xrayDomainToSingBox(value: String): String? = when {
        value.startsWith("ext:geosite.dat:", ignoreCase = true) ->
            "geosite:${value.substringAfterLast(':')}"
        value.startsWith("ext:", ignoreCase = true) ||
            value.startsWith("dotless:", ignoreCase = true) || value.startsWith('!') -> null
        hasDomainPrefix(value) -> value
        else -> "keyword:$value"
    }

    private fun singBoxDomainToXray(value: String): String? = when {
        value.startsWith("ext:", ignoreCase = true) ||
            value.startsWith("dotless:", ignoreCase = true) || value.startsWith('!') -> null
        hasDomainPrefix(value) -> value
        else -> "domain:$value"
    }

    private fun xrayIpToSingBox(value: String): String? = when {
        value.startsWith('!') -> null
        value.startsWith("ext:geoip.dat:", ignoreCase = true) ->
            "geoip:${value.substringAfterLast(':')}"
        value.startsWith("ext:", ignoreCase = true) -> null
        else -> value
    }

    private fun singBoxIpToXray(value: String): String? = when {
        value.startsWith('!') || value.startsWith("ext:", ignoreCase = true) -> null
        else -> value
    }

    private fun hasDomainPrefix(value: String) = listOf(
        "full:", "domain:", "keyword:", "regexp:", "geosite:",
    ).any { value.startsWith(it, ignoreCase = true) }
}

data class StableRuleExport(
    val rule: StableRoutingRule,
    val customOutboundFallback: Boolean,
)

object StableRoutingRuleMapper {
    fun export(
        rule: RuleEntity,
        customDnsServerId: (String) -> Long? = { null },
        customOutboundHash: (Long) -> String? = { outbound ->
            runCatching { ProfileManager.getProfile(outbound)?.requireBean()?.hash }.getOrNull()
        },
    ): StableRuleExport {
        val isDnsRule = RuleType.fromValue(rule.type) == RuleType.DNS
        var fallback = false
        val (outbound, outboundHash) = if (isDnsRule) {
            StableRoutingOutbound.PROXY to null
        } else when (rule.outbound) {
            0L -> StableRoutingOutbound.PROXY to null
            -1L -> StableRoutingOutbound.DIRECT to null
            -2L -> StableRoutingOutbound.BLOCK to null
            else -> {
                val hash = customOutboundHash(rule.outbound)
                if (hash == null) {
                    fallback = true
                    StableRoutingOutbound.PROXY to null
                } else {
                    StableRoutingOutbound.CUSTOM to hash
                }
            }
        }
        return StableRuleExport(
            StableRoutingRule(
                type = rule.type,
                name = rule.name,
                config = rule.config,
                enabled = rule.enabled,
                domains = rule.domains,
                ip = rule.ip,
                port = rule.port,
                sourcePort = rule.sourcePort,
                networkType = rule.networkType,
                wifiSsid = rule.wifiSsid,
                wifiBssid = rule.wifiBssid,
                network = rule.network,
                source = rule.source,
                protocol = rule.protocol,
                ruleset = rule.ruleset,
                clashMode = rule.clashMode,
                outbound = outbound,
                outboundHash = outboundHash,
                packages = rule.packages,
                createDnsRule = if (isDnsRule) true else rule.createDnsRule,
                dnsAction = if (isDnsRule) rule.dnsAction else "route",
                dnsServer = if (isDnsRule) {
                    customDnsServerId(rule.dnsServer)?.let(StableDnsServerReference::id)
                        ?: StableDnsServerReference.tag(rule.dnsServer)
                } else {
                    StableDnsServerReference.tag("")
                },
                // Kept in StableRoutingRule for compatibility with existing links, but sing-box
                // 1.14 no longer exposes a per-rule DNS strategy.
                dnsStrategy = "",
                dnsDisableCache = isDnsRule && rule.dnsDisableCache,
                dnsRewriteTtl = if (isDnsRule) rule.dnsRewriteTtl else 0,
                dnsClientSubnet = if (isDnsRule) rule.dnsClientSubnet else "",
                dnsRcode = if (isDnsRule) rule.dnsRcode else "NOERROR",
                dnsRejectMethod = if (isDnsRule) rule.dnsRejectMethod else "",
                dnsPredefinedAnswer = if (isDnsRule) rule.dnsPredefinedAnswer else "",
                dnsPredefinedNs = if (isDnsRule) rule.dnsPredefinedNs else "",
                dnsPredefinedExtra = if (isDnsRule) rule.dnsPredefinedExtra else "",
            ),
            fallback,
        )
    }

    fun import(
        rule: StableRoutingRule,
        resolvedOutbound: Long,
        resolvedDnsServer: String? = rule.dnsServer.tag,
    ): RuleEntity {
        val isDnsRule = RuleType.fromValue(rule.type) == RuleType.DNS
        return RuleEntity(
            type = if (isDnsRule) RuleType.DNS.value else RuleType.NORMAL.value,
            name = rule.name,
            config = rule.config,
            enabled = rule.enabled,
            domains = rule.domains,
            ip = rule.ip,
            port = rule.port,
            sourcePort = rule.sourcePort,
            networkType = rule.networkType,
            wifiSsid = rule.wifiSsid,
            wifiBssid = rule.wifiBssid,
            network = rule.network,
            source = rule.source,
            protocol = rule.protocol,
            ruleset = rule.ruleset,
            clashMode = rule.clashMode,
            outbound = if (isDnsRule) 0L else resolvedOutbound,
            packages = rule.packages,
            createDnsRule = if (isDnsRule) true else rule.createDnsRule,
            dnsAction = if (isDnsRule) rule.dnsAction else "route",
            dnsServer = if (isDnsRule) resolvedDnsServer.orEmpty() else "",
            // Ignore the legacy wire value instead of restoring a setting removed in 1.14.
            dnsStrategy = "",
            dnsDisableCache = isDnsRule && rule.dnsDisableCache,
            dnsRewriteTtl = if (isDnsRule) rule.dnsRewriteTtl else 0,
            dnsClientSubnet = if (isDnsRule) rule.dnsClientSubnet else "",
            dnsRcode = if (isDnsRule) rule.dnsRcode else "NOERROR",
            dnsRejectMethod = if (isDnsRule) rule.dnsRejectMethod else "",
            dnsPredefinedAnswer = if (isDnsRule) rule.dnsPredefinedAnswer else "",
            dnsPredefinedNs = if (isDnsRule) rule.dnsPredefinedNs else "",
            dnsPredefinedExtra = if (isDnsRule) rule.dnsPredefinedExtra else "",
        )
    }

    fun builtinOutbound(rule: StableRoutingRule): Long? = when (rule.outbound.lowercase()) {
        StableRoutingOutbound.PROXY -> 0L
        StableRoutingOutbound.DIRECT -> -1L
        StableRoutingOutbound.BLOCK -> -2L
        else -> null
    }
}

object StableCustomDnsServerMapper {
    fun export(server: CustomDnsServerEntity, exportId: Long) = StableCustomDnsServer(
        id = exportId,
        tag = server.tag,
        type = server.type,
        enabled = server.enabled,
        server = server.server,
        serverPort = server.serverPort,
        path = server.path,
        method = server.method,
        headers = server.headers,
        domainResolver = server.domainResolver,
        domainStrategy = server.domainStrategy,
        disableCache = server.disableCache,
        rewriteTtl = server.rewriteTtl,
        clientSubnet = server.clientSubnet,
        detour = server.detour,
        bindInterface = server.bindInterface,
        inet4BindAddress = server.inet4BindAddress,
        inet6BindAddress = server.inet6BindAddress,
        connectTimeout = server.connectTimeout,
        tcpFastOpen = server.tcpFastOpen,
        tcpMultiPath = server.tcpMultiPath,
        udpFragment = server.udpFragment,
        tlsServerName = server.tlsServerName,
        tlsInsecure = server.tlsInsecure,
        tlsAlpn = server.tlsAlpn,
        tlsCertificates = server.tlsCertificates,
        localPreferGo = server.localPreferGo,
    )

    fun import(server: StableCustomDnsServer, localId: Long) = CustomDnsServerEntity(
        id = localId,
        tag = server.tag,
        type = server.type,
        userOrder = localId,
        enabled = server.enabled,
        server = server.server,
        serverPort = server.serverPort,
        path = server.path,
        method = server.method,
        headers = server.headers,
        domainResolver = server.domainResolver,
        domainStrategy = server.domainStrategy,
        disableCache = server.disableCache,
        rewriteTtl = server.rewriteTtl,
        clientSubnet = server.clientSubnet,
        detour = server.detour,
        bindInterface = server.bindInterface,
        inet4BindAddress = server.inet4BindAddress,
        inet6BindAddress = server.inet6BindAddress,
        connectTimeout = server.connectTimeout,
        tcpFastOpen = server.tcpFastOpen,
        tcpMultiPath = server.tcpMultiPath,
        udpFragment = server.udpFragment,
        tlsServerName = server.tlsServerName,
        tlsInsecure = server.tlsInsecure,
        tlsAlpn = server.tlsAlpn,
        tlsCertificates = server.tlsCertificates,
        localPreferGo = server.localPreferGo,
    )
}

object RoutingOutboundHashResolver {
    data class Match(val id: Long, val name: String)

    fun resolve(
        hashes: Set<String>,
        profiles: List<ProxyEntity>,
        hashOf: (ProxyEntity) -> String? = { profile ->
            runCatching { profile.requireBean().hash }.getOrNull()
        },
    ): Map<String, Match> {
        if (hashes.isEmpty()) return emptyMap()
        val remaining = hashes.toMutableSet()
        val result = linkedMapOf<String, Match>()
        for (profile in profiles.sortedBy(ProxyEntity::id)) {
            val hash = hashOf(profile) ?: continue
            if (hash !in remaining) continue
            result[hash] = Match(profile.id, runCatching(profile::displayName).getOrDefault(""))
            remaining -= hash
            if (remaining.isEmpty()) break
        }
        return result
    }
}
