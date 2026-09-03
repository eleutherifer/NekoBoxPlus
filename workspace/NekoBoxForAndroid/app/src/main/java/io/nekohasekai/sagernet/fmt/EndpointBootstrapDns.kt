package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions

internal fun buildEndpointBootstrapDnsServer(
    rawAddresses: List<String>,
    tag: String,
    detour: String,
    forTest: Boolean,
    queryDeadline: String,
    remoteStrategy: String,
    directStrategy: String,
): DNSServerOptions =
    buildDnsServerOptions(
        rawAddresses = rawAddresses,
        tagValue = tag,
        detourValue = detour,
        domainResolver =
            buildDomainResolverConfig(
                if (forTest) "dns-local" else "dns-direct",
                if (forTest) remoteStrategy else directStrategy,
            ),
        queryDeadline = queryDeadline,
    )
