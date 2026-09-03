package io.nekohasekai.sagernet.fmt

data class ParsedDnsEndpoint(
    val scheme: String?,
    val host: String,
    val port: Int?,
    val path: String? = null,
)

fun parseDnsEndpoint(raw: String): ParsedDnsEndpoint? {
    var dns = raw.trim()
    if (dns.isEmpty() || dns.startsWith("#")) return null

    val scheme = dns.substringBefore("://", "")
        .lowercase()
        .takeIf { dns.contains("://") }

    if (scheme != null) {
        dns = dns.substringAfter("://")
    }

    val pathStart = dns.indexOf('/')
    val path =
        if (pathStart >= 0) {
            dns.substring(pathStart)
                .substringBefore("?")
                .substringBefore("#")
                .takeIf { it.isNotBlank() }
        } else {
            null
        }

    dns = dns.substringBefore("/")
        .substringBefore("?")
        .substringBefore("#")
        .trim()

    if (dns.isEmpty()) return null

    val host: String
    val port: Int?

    when {
        dns.startsWith("[") -> {
            val end = dns.indexOf(']')
            if (end <= 0) return null

            host = dns.substring(1, end) // normalize IPv6 without brackets

            val rest = dns.substring(end + 1)
            port = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.drop(1).toIntOrNull()
                else -> return null
            }
        }

        dns.count { it == ':' } == 1 -> {
            host = dns.substringBefore(":").trim()
            port = dns.substringAfter(":").toIntOrNull()
        }

        else -> {
            host = dns
            port = null
        }
    }

    if (host.isEmpty()) return null
    if (port != null && port !in 1..65535) return null

    return ParsedDnsEndpoint(scheme, host, port, path)
}
