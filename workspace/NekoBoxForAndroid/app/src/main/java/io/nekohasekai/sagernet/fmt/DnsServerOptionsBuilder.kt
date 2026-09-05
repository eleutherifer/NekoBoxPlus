package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.ktx.isIpAddress
import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions
import moe.matsuri.nb4a.SingBoxOptions.DomainResolveOptions

internal fun buildDomainResolverConfig(
    server: String,
    strategy: String = "",
): DomainResolveOptions =
    DomainResolveOptions().apply {
        this.server = server
        if (strategy.isNotBlank()) {
            this.strategy = strategy
        }
    }

internal fun buildProfileServerDomainResolverConfig(
    forTest: Boolean,
    remoteStrategy: String,
    directStrategy: String,
): DomainResolveOptions =
    buildDomainResolverConfig(
        server = if (forTest) "dns-local" else "dns-direct",
        strategy = if (forTest) remoteStrategy else directStrategy,
    )

internal fun buildDnsServerOptions(
    rawAddress: String,
    tagValue: String,
    detourValue: String? = null,
    domainResolver: DomainResolveOptions? = null,
): DNSServerOptions {
    val endpoint = parseDnsEndpoint(rawAddress) ?: error("Invalid DNS server: $rawAddress")

    fun defaultPortFor(type: String): Int =
        when (type) {
            "tls", "quic" -> 853
            "https", "h3" -> 443
            else -> 53
        }

    fun DNSServerOptions.configureRemote(typeValue: String) {
        type = typeValue
        server = endpoint.host
        endpoint.port?.takeIf { it != defaultPortFor(typeValue) }?.let { server_port = it }
        if ((typeValue == "https" || typeValue == "h3") && endpoint.path != null && endpoint.path != "/dns-query") {
            path = endpoint.path
        }
        if (!endpoint.host.isIpAddress()) {
            this.domain_resolver = domainResolver
        }
    }

    return DNSServerOptions().apply {
        tag = tagValue
        if (detourValue != TAG_DIRECT) {
            detour = detourValue
        }
        when (endpoint.scheme) {
            null -> {
                if (endpoint.host == "local") {
                    type = "local"
                } else {
                    configureRemote("udp")
                }
            }
            "local" -> type = "local"
            "udp", "tcp", "tls", "quic", "https", "h3" -> configureRemote(endpoint.scheme)
            "dhcp" -> {
                type = "dhcp"
                if (endpoint.host != "auto") {
                    interface_ = endpoint.host
                }
            }
            "mdns" -> {
                type = "mdns"
                if (endpoint.host != "auto") {
                    interface_ = endpoint.host
                }
            }
            else -> error("Unsupported DNS server scheme '${endpoint.scheme}' in $rawAddress")
        }
    }
}

internal fun buildUrlTestDnsServers(
    rawAddresses: List<String>,
    queryDeadline: String,
    detourValue: String?,
): List<DNSServerOptions> =
    listOf(
        buildDnsServerOptions(
            rawAddress = "local",
            tagValue = "dns-local",
            detourValue = TAG_DIRECT,
        ),
        buildDnsServerOptions(
            rawAddresses = rawAddresses,
            tagValue = "dns-remote",
            detourValue = detourValue,
            domainResolver = buildDomainResolverConfig("dns-local"),
            queryDeadline = queryDeadline,
        ),
    )

internal fun injectUrlTestDnsIfMissing(
    config: MutableMap<String, Any?>,
    dns: Map<String, Any?>,
): Boolean {
    if (config.containsKey("dns") && config["dns"] != null) return false
    config["dns"] = dns
    return true
}

internal fun buildDnsServerOptions(
    rawAddresses: List<String>,
    tagValue: String,
    detourValue: String? = null,
    domainResolver: DomainResolveOptions? = null,
    queryDeadline: String = "",
): DNSServerOptions {
    if (rawAddresses.isEmpty()) {
        error("No DNS server, check your settings!")
    }
    val expandedAddresses =
        rawAddresses.flatMap { address ->
            val endpoint = parseDnsEndpoint(address) ?: error("Invalid DNS server: $address")
            if (endpoint.scheme == null && endpoint.host.isIpAddress()) {
                listOf(address to "udp", address to "tcp")
            } else {
                listOf(address to null)
            }
        }
    if (expandedAddresses.size == 1) {
        return buildDnsServerOptions(expandedAddresses.single().first, tagValue, detourValue, domainResolver)
    }
    return DNSServerOptions().apply {
        type = "balancer"
        tag = tagValue
        if (queryDeadline.isNotBlank()) {
            query_deadline = queryDeadline.trim()
        }
        servers = expandedAddresses.mapIndexed { index, (address, transportType) ->
            buildDnsServerOptions(
                rawAddress = address,
                tagValue = "$tagValue-${index + 1}",
                detourValue = detourValue,
                domainResolver = domainResolver,
            ).apply { transportType?.let { type = it } }
        }
    }
}
