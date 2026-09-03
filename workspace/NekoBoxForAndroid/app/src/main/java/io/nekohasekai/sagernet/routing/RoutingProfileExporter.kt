package io.nekohasekai.sagernet.routing

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.CustomDnsServerStore
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleType
import io.nekohasekai.sagernet.fmt.parseDnsDomainOverrides
import io.nekohasekai.sagernet.fmt.parseDnsEndpoint
import io.nekohasekai.sagernet.ktx.isIpAddress
import moe.matsuri.nb4a.utils.listByLineOrComma

object RoutingProfileExporter {
    fun export(
        format: RoutingProfileFormat,
        name: String,
        rules: List<RuleEntity>,
    ): RoutingExportResult {
        if (format == RoutingProfileFormat.NEKOBOX_PLUS) return exportNekoBoxPlus(name, rules)
        val warnings = linkedSetOf<RoutingExportWarning>()
        val analysis = analyzeRules(rules)
        val representable = analysis.rules
        if (analysis.unsupportedRules) warnings += RoutingExportWarning.UNSUPPORTED_RULES
        if (analysis.simplifiedOrder) warnings += RoutingExportWarning.SIMPLIFIED_ORDER

        fun values(outbound: Long, domains: Boolean): List<String> {
            val raw = representable
            .filter { it.outbound == outbound }
            .flatMap { if (domains) it.domains.listByLineOrComma() else it.ip.listByLineOrComma() }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            val converted = XrayRoutingValueConverter.singBoxToXray(raw, domains)
            if (converted.omitted) warnings += RoutingExportWarning.UNSUPPORTED_RULES
            return converted.values
        }

        val profile = ExternalRoutingProfile(
            name = name.trim(),
            globalProxy = (!analysis.everythingDirect).toString(),
            geoipUrl = effectiveGeoipUrl(),
            geositeUrl = effectiveGeositeUrl(),
            directSites = values(-1L, true),
            directIp = values(-1L, false),
            proxySites = values(0L, true),
            proxyIp = values(0L, false),
            blockSites = values(-2L, true),
            blockIp = values(-2L, false),
            domainStrategy = if (DataStore.resolveDestination) "IPIfNonMatch" else "AsIs",
            fakeDns = DataStore.enableFakeDns.toString(),
            routeOrder = analysis.categoryOrder.joinToString("-"),
        )
        exportSharedSettings(profile, warnings)

        return RoutingExportResult(RoutingProfileCodec.encode(format, profile), warnings)
    }

    private fun exportNekoBoxPlus(name: String, rules: List<RuleEntity>): RoutingExportResult {
        val warnings = linkedSetOf<RoutingExportWarning>()
        val customDnsServers = CustomDnsServerStore.allServers().mapIndexed { index, server ->
            StableCustomDnsServerMapper.export(server, index + 1L)
        }
        val customDnsServerIds = customDnsServers.associate { it.tag to it.id }
        val outboundHashes = mutableMapOf<Long, String?>()
        fun outboundHash(outbound: Long): String? {
            if (outbound in outboundHashes) return outboundHashes[outbound]
            return runCatching {
                io.nekohasekai.sagernet.database.ProfileManager.getProfile(outbound)
                    ?.requireBean()?.hash
            }.getOrNull().also { outboundHashes[outbound] = it }
        }
        val exportedRules = rules.map { rule ->
            StableRoutingRuleMapper.export(
                rule,
                customDnsServerId = customDnsServerIds::get,
                customOutboundHash = ::outboundHash,
            ).also {
                if (it.customOutboundFallback) warnings += RoutingExportWarning.CUSTOM_OUTBOUND_FALLBACK
            }.rule
        }
        val profile = ExternalRoutingProfile(
            name = name.trim(),
            globalProxy = (!analyzeRules(rules).everythingDirect).toString(),
            geoipUrl = effectiveGeoipUrl(),
            geositeUrl = effectiveGeositeUrl(),
            domainStrategy = if (DataStore.resolveDestination) "IPIfNonMatch" else "AsIs",
            fakeDns = DataStore.enableFakeDns.toString(),
            rules = exportedRules,
            customDnsServers = customDnsServers,
            lastUpdated = System.currentTimeMillis() / 1000L,
        )
        profile.remoteDns = encodeNekoBoxPlusDns(DataStore.remoteDns)
        profile.domesticDns = encodeNekoBoxPlusDns(DataStore.directDns)
        exportDnsHosts(profile, warnings)
        return RoutingExportResult(
            RoutingProfileCodec.encode(RoutingProfileFormat.NEKOBOX_PLUS, profile),
            warnings,
        )
    }

    private fun exportSharedSettings(
        profile: ExternalRoutingProfile,
        warnings: MutableSet<RoutingExportWarning>,
    ) {
        exportDns(DataStore.remoteDns, remote = true, profile, warnings)
        exportDns(DataStore.directDns, remote = false, profile, warnings)
        exportDnsHosts(profile, warnings)
    }

    private fun exportDnsHosts(
        profile: ExternalRoutingProfile,
        warnings: MutableSet<RoutingExportWarning>,
    ) {
        val hosts = parseDnsDomainOverrides(DataStore.dnsDomainOverrides)
        if (hosts.values.any { it.size > 1 }) warnings += RoutingExportWarning.DNS_HOST_VALUES_OMITTED
        profile.dnsHosts = hosts.mapValues { it.value.first() }
    }

    internal fun encodeNekoBoxPlusDns(raw: String): String? = raw.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .toList()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(",")

    private fun exportDns(
        raw: String,
        remote: Boolean,
        profile: ExternalRoutingProfile,
        warnings: MutableSet<RoutingExportWarning>,
    ) {
        val values = raw.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        if (values.size > 1) warnings += RoutingExportWarning.DNS_VALUES_OMITTED
        val address = values.firstOrNull() ?: return
        val endpoint = parseDnsEndpoint(address) ?: run {
            warnings += RoutingExportWarning.DNS_VALUES_OMITTED
            return
        }
        val doh = endpoint.scheme == "https" || endpoint.scheme == "h3"
        if (!doh && !endpoint.host.isIpAddress()) {
            warnings += RoutingExportWarning.DNS_VALUES_OMITTED
            return
        }
        val type = if (doh) "DoH" else "DoU"
        val domain = if (doh) {
            buildString {
                append("https://")
                append(if (endpoint.host.contains(':')) "[${endpoint.host}]" else endpoint.host)
                endpoint.port?.let { append(":$it") }
                append(endpoint.path ?: "/dns-query")
            }
        } else ""
        val ip = endpoint.host.takeIf { it.isIpAddress() }.orEmpty()
        if (remote) {
            profile.remoteDnsType = type
            profile.remoteDnsDomain = domain
            profile.remoteDnsIp = ip
            profile.remoteDns = ip
        } else {
            profile.domesticDnsType = type
            profile.domesticDnsDomain = domain
            profile.domesticDnsIp = ip
            profile.domesticDns = ip
        }
    }

    internal data class RuleAnalysis(
        val rules: List<RuleEntity>,
        val everythingDirect: Boolean,
        val categoryOrder: List<String>,
        val unsupportedRules: Boolean,
        val simplifiedOrder: Boolean,
    )

    internal fun analyzeRules(rules: List<RuleEntity>): RuleAnalysis {
        val enabled = rules.filter(RuleEntity::enabled)
        val catchAll = enabled.lastOrNull()?.takeIf(::isEverythingDirect)
        val candidates = if (catchAll == null) enabled else enabled.dropLast(1)
        val representable = candidates.filter(::isRepresentable)
        val categorySequence = representable.map(::category)
        val collapsed = categorySequence.fold(mutableListOf<String>()) { result, value ->
            if (result.lastOrNull() != value) result += value
            result
        }
        return RuleAnalysis(
            rules = representable,
            everythingDirect = catchAll != null,
            categoryOrder = categorySequence.distinct() +
                listOf("block", "proxy", "direct").filterNot(categorySequence::contains),
            unsupportedRules = representable.size != candidates.size,
            simplifiedOrder = collapsed.size != collapsed.distinct().size,
        )
    }

    internal fun isEverythingDirect(rule: RuleEntity): Boolean =
        rule.type == RuleType.NORMAL.value && rule.outbound == -1L &&
            rule.port.replace("-", ":").trim() == "0:65535" &&
            rule.domains.isBlank() && rule.ip.isBlank() &&
            rule.copy(name = "", port = "").hasNoMatchers()

    internal fun isRepresentable(rule: RuleEntity): Boolean =
        rule.type == RuleType.NORMAL.value && rule.outbound in setOf(-2L, -1L, 0L) &&
            (rule.domains.isNotBlank() || rule.ip.isNotBlank()) &&
            rule.copy(name = "", domains = "", ip = "").hasNoMatchers()

    private fun RuleEntity.hasNoMatchers(): Boolean =
        config.isBlank() && port.isBlank() && sourcePort.isBlank() && networkType.isEmpty() &&
            wifiSsid.isBlank() && wifiBssid.isBlank() && network.isBlank() && source.isBlank() &&
            protocol.isBlank() && ruleset.isBlank() && clashMode.isBlank() && packages.isEmpty()

    private fun category(rule: RuleEntity): String = when (rule.outbound) {
        -2L -> "block"
        -1L -> "direct"
        else -> "proxy"
    }

    private fun effectiveGeoipUrl(): String =
        RoutingProviderCatalog.byId(DataStore.rulesProvider)?.geoipUrl ?: DataStore.rulesGeoipUrl

    private fun effectiveGeositeUrl(): String =
        RoutingProviderCatalog.byId(DataStore.rulesProvider)?.geositeUrl ?: DataStore.rulesGeositeUrl
}
