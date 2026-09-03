package io.nekohasekai.sagernet.routing

object RoutingProfileMapper {
    fun toImportCandidate(
        format: RoutingProfileFormat,
        profile: ExternalRoutingProfile,
    ): RoutingImportCandidate {
        val settings = mutableListOf<RoutingImportSetting>()
        var omittedXrayValues = false
        val remoteDns = if (format == RoutingProfileFormat.NEKOBOX_PLUS) {
            mapNekoBoxPlusDns(profile.remoteDns)
        } else {
            mapDns(profile.remoteDnsType, profile.remoteDnsDomain, profile.remoteDnsIp, profile.remoteDns)
        }
        remoteDns
            ?.let { settings += RoutingImportSetting(RoutingSettingKind.REMOTE_DNS, it) }
        val directDns = if (format == RoutingProfileFormat.NEKOBOX_PLUS) {
            mapNekoBoxPlusDns(profile.domesticDns)
        } else {
            mapDns(profile.domesticDnsType, profile.domesticDnsDomain, profile.domesticDnsIp, profile.domesticDns)
        }
        directDns
            ?.let { settings += RoutingImportSetting(RoutingSettingKind.DIRECT_DNS, it) }

        val geoip = profile.geoipUrl.clean()
        val geosite = profile.geositeUrl.clean()
        if (geoip != null && geosite != null) {
            val provider = RoutingProviderCatalog.match(geoip, geosite)
            settings += RoutingImportSetting(
                kind = RoutingSettingKind.GEO_ASSETS,
                value = geoip,
                secondaryValue = geosite,
                provider = provider?.id,
            )
        }

        profile.dnsHosts.orEmpty()
            .mapNotNull { (domain, address) ->
                val cleanDomain = domain.trim().trimEnd('.').lowercase()
                val cleanAddress = address.trim()
                if (cleanDomain.isEmpty() || cleanAddress.isEmpty()) null else "$cleanDomain $cleanAddress"
            }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
            ?.let { settings += RoutingImportSetting(RoutingSettingKind.DNS_HOSTS, it) }

        parseBoolean(profile.fakeDns)?.let {
            settings += RoutingImportSetting(RoutingSettingKind.FAKE_DNS, it.toString())
        }
        mapDomainStrategy(profile.domainStrategy)?.let {
            settings += RoutingImportSetting(RoutingSettingKind.DOMAIN_STRATEGY, it.toString())
        }

        fun converted(values: List<String>?, domains: Boolean): List<String> {
            val result = XrayRoutingValueConverter.xrayToSingBox(cleanValues(values), domains)
            omittedXrayValues = omittedXrayValues || result.omitted
            return result.values
        }
        val byCategory = mapOf(
            "direct" to listOf(
                RoutingRuleKind.DIRECT_SITES to converted(profile.directSites, true),
                RoutingRuleKind.DIRECT_IP to converted(profile.directIp, false),
            ),
            "proxy" to listOf(
                RoutingRuleKind.PROXY_SITES to converted(profile.proxySites, true),
                RoutingRuleKind.PROXY_IP to converted(profile.proxyIp, false),
            ),
            "block" to listOf(
                RoutingRuleKind.BLOCK_SITES to converted(profile.blockSites, true),
                RoutingRuleKind.BLOCK_IP to converted(profile.blockIp, false),
            ),
        )
        val order = parseRouteOrder(profile.routeOrder)
        val rules = order.flatMap { category ->
            byCategory.getValue(category).mapNotNull { (kind, values) ->
                values.takeIf { it.isNotEmpty() }?.let { RoutingImportRule(kind, it) }
            }
        }.toMutableList()
        if (parseBoolean(profile.globalProxy) == false) {
            rules += RoutingImportRule(RoutingRuleKind.EVERYTHING_DIRECT)
        }

        return RoutingImportCandidate(
            format = format,
            name = profile.name.clean().orEmpty(),
            settings = settings,
            rules = rules,
            warnings = if (omittedXrayValues) setOf(RoutingImportWarning.UNSUPPORTED_XRAY_VALUES) else emptySet(),
        )
    }

    internal fun parseRouteOrder(value: String?): List<String> {
        val defaults = listOf("block", "proxy", "direct")
        val requested = value.orEmpty().lowercase().split('-')
            .filter { it in defaults }
            .distinct()
        return requested + defaults.filterNot(requested::contains)
    }

    private fun mapDns(type: String?, domain: String?, ip: String?, legacy: String?): String? {
        val cleanDomain = domain.clean()
        val cleanIp = ip.clean()
        val cleanLegacy = legacy.clean()
        return when (type.clean()?.uppercase()) {
            "DOH" -> cleanDomain ?: cleanIp?.let { "https://$it/dns-query" } ?: cleanLegacy
            "DOU" -> cleanIp ?: cleanLegacy ?: cleanDomain
            else -> cleanLegacy ?: cleanDomain ?: cleanIp
        }
    }

    internal fun mapNekoBoxPlusDns(value: String?): String? = value.orEmpty()
        .split(',', '\n', '\r')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n")

    private fun mapDomainStrategy(value: String?): Boolean? = when (value.clean()?.lowercase()) {
        "asis" -> false
        "ipifnonmatch", "ipondemand" -> true
        else -> null
    }

    internal fun parseBoolean(value: String?): Boolean? = when (value.clean()?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    private fun cleanValues(values: List<String>?): List<String> = values.orEmpty()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)
}
