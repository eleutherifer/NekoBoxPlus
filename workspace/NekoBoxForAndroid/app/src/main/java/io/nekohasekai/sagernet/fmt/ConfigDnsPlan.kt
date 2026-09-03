package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.routing.RoutingSettingKind
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun parseDnsDomainOverrides(raw: String): Map<String, List<String>> {
    val overrides = linkedMapOf<String, MutableList<String>>()
    raw.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter(String::isNotBlank)
        .forEach { line ->
            val tokens = line.split("\\s+".toRegex())
            if (tokens.size < 2) return@forEach
            val domain = tokens.first().trim().trimEnd('.').lowercase()
                .takeIf(String::isNotBlank) ?: return@forEach
            val addresses = tokens.drop(1).filter(String::isIpAddress)
            if (addresses.isNotEmpty()) overrides.getOrPut(domain) { mutableListOf() }.addAll(addresses)
        }
    return overrides
}

internal fun connectionIpResolveHost(url: String): String? =
    url.toHttpUrlOrNull()?.host?.takeUnless(String::isIpAddress)

internal data class ConfigDnsPreferences(
    val remoteDns: String,
    val directDns: String,
    val domainOverrides: String,
    val fakeDns: Boolean,
    val resolveDestination: Boolean,
    val ipv6Mode: Int,
)

internal data class ConfigDnsPlan(
    val directDns: String,
    val remoteServers: List<String>,
    val directServers: List<String>,
    val domainOverrides: Map<String, List<String>>,
    val useFakeDns: Boolean,
    val resolveDestination: Boolean,
    val ipv6Mode: Int,
)

internal object ConfigDnsPlanner {
    fun plan(
        preferences: ConfigDnsPreferences,
        routingOverrides: Map<RoutingSettingKind, String>,
        forTest: Boolean,
    ): ConfigDnsPlan {
        val remoteDns = routingOverrides[RoutingSettingKind.REMOTE_DNS] ?: preferences.remoteDns
        val directDns = routingOverrides[RoutingSettingKind.DIRECT_DNS] ?: preferences.directDns
        val rawOverrides = routingOverrides[RoutingSettingKind.DNS_HOSTS] ?: preferences.domainOverrides
        val fakeDns = routingOverrides[RoutingSettingKind.FAKE_DNS]?.toBooleanStrictOrNull()
            ?: preferences.fakeDns
        val resolveDestination = routingOverrides[RoutingSettingKind.DOMAIN_STRATEGY]
            ?.toBooleanStrictOrNull()
            ?: preferences.resolveDestination
        return ConfigDnsPlan(
            directDns = directDns,
            remoteServers = activeDnsLines(remoteDns),
            directServers = activeDnsLines(directDns),
            domainOverrides = if (forTest) emptyMap() else parseDnsDomainOverrides(rawOverrides),
            useFakeDns = fakeDns && !forTest,
            resolveDestination = resolveDestination,
            ipv6Mode = if (forTest) IPv6Mode.ENABLE else preferences.ipv6Mode,
        )
    }

    private fun activeDnsLines(value: String): List<String> = value.lineSequence()
        .map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith('#') }
        .toList()
}
