package io.nekohasekai.sagernet

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Stable, versioned contract exchanged between the Go core (libcore) and the
 * Android VpnService through [libcore.BoxPlatformInterface.OpenTun].
 *
 * libcore used to marshal raw `github.com/sagernet/sing-tun` option structs
 * across the JNI boundary. Those structs are version-dependent and not a stable
 * public surface, so this payload is now the only thing the Android side is
 * allowed to depend on. Field names are explicit snake_case and mirror the Go
 * `androidTunPayload` struct 1:1.
 *
 * The Android side parses and validates this before touching
 * `VpnService.Builder`; per-app inclusion/exclusion, HTTP proxy, metering, the
 * session name and the `ParcelFileDescriptor` lifecycle remain app-owned.
 */
data class AndroidTunPayload(
    val version: Int = 0,
    val mtu: Long = 0,
    val auto_route: Boolean = false,
    val inet4_address: String? = null,
    val inet6_address: String? = null,
    val dns_mode: String? = null,
    val dns_servers: List<String>? = null,
    val inet4_routes: List<String>? = null,
    val inet6_routes: List<String>? = null,
) {
    /**
     * Typed, validated view of the payload applied to [android.net.VpnService.Builder].
     */
    data class Plan(
        val mtu: Int,
        val autoRoute: Boolean,
        val inet4Address: Cidr?,
        val inet6Address: Cidr?,
        val dnsMode: String,
        val dnsServers: List<String>,
        val inet4Routes: List<Cidr>,
        val inet6Routes: List<Cidr>,
    )

    /** A parsed CIDR (interface address or route range). */
    data class Cidr(
        val address: String,
        val prefixLength: Int,
    ) {
        val isIpv4: Boolean
            get() = !address.contains(':')
    }

    companion object {
        /** Supported payload contract version. Bump in lockstep with libcore. */
        const val VERSION: Int = 2

        /** MTU bounds enforced before the TUN is created. */
        const val MTU_MIN: Long = 576L
        const val MTU_MAX: Long = 65535L

        private val gson = Gson()

        /**
         * Parse and validate the JSON payload produced by libcore.
         * @throws IllegalArgumentException on any malformed or inconsistent payload.
         */
        @JvmStatic
        fun parse(json: String): Plan {
            val payload =
                try {
                    gson.fromJson(json, AndroidTunPayload::class.java)
                } catch (e: JsonSyntaxException) {
                    throw IllegalArgumentException("invalid android tun payload json", e)
                }
            requireNotNull(payload) { "empty android tun payload" }
            return payload.validate()
        }
    }

    /**
     * Validate the raw payload and produce a typed [Plan].
     *
     * Rules:
     * - [version] must match [VERSION].
     * - [mtu] must be within the supported range.
     * - At least one address family must be declared.
     * - DNS mode and servers must agree, and a DNS server without its address
     *   family is rejected so the platform never declares an unrequested family.
     * - All addresses, DNS servers and routes must be numeric CIDRs/IPs of the
     *   matching family.
     */
    fun validate(): Plan {
        require(version == VERSION) { "unsupported android tun payload version: $version" }
        require(mtu in MTU_MIN..MTU_MAX) { "mtu $mtu out of range [$MTU_MIN, $MTU_MAX]" }

        val v4Address = inet4_address?.let { parseCidr(it, expectV4 = true, "inet4_address") }
        val v6Address = inet6_address?.let { parseCidr(it, expectV4 = false, "inet6_address") }
        require(v4Address != null || v6Address != null) {
            "tun requires at least one interface address family"
        }

        val dnsMode = requireNotNull(dns_mode) { "dns_mode is missing" }
        require(dnsMode in setOf("disabled", "native", "hijack")) { "unsupported dns_mode: $dnsMode" }
        val dnsServers =
            (dns_servers ?: emptyList()).mapIndexed { index, server ->
                parseAnyIpAddress(server, "dns_servers[$index]")
            }
        if (dnsMode == "disabled") {
            require(dnsServers.isEmpty()) { "dns_servers must be empty when dns_mode is disabled" }
        } else {
            require(dnsServers.isNotEmpty()) { "dns_servers is empty while dns_mode is $dnsMode" }
        }
        require(dnsServers.none { it.contains(':') } || v6Address != null) {
            "ipv6 dns server declared without inet6 address"
        }
        require(dnsServers.none { !it.contains(':') } || v4Address != null) {
            "ipv4 dns server declared without inet4 address"
        }

        val v4Routes =
            (inet4_routes ?: emptyList())
                .map { parseCidr(it, expectV4 = true, "inet4_routes") }
        val v6Routes =
            (inet6_routes ?: emptyList())
                .map { parseCidr(it, expectV4 = false, "inet6_routes") }

        // Routes belong to a family only when that family is declared, so the
        // platform never advertises routes for an unrequested address family.
        if (v4Address == null) {
            require(v4Routes.isEmpty()) { "inet4_routes declared without inet4 address" }
        }
        if (v6Address == null) {
            require(v6Routes.isEmpty()) { "inet6_routes declared without inet6 address" }
        }

        return Plan(
            mtu = mtu.toInt(),
            autoRoute = auto_route,
            inet4Address = v4Address,
            inet6Address = v6Address,
            dnsMode = dnsMode,
            dnsServers = dnsServers,
            inet4Routes = v4Routes,
            inet6Routes = v6Routes,
        )
    }
}

// --- pure numeric IP/CIDR parsing (no android.* deps, JVM-testable) ----------

/** Parse and validate a CIDR of the expected family. */
private fun parseCidr(
    raw: String,
    expectV4: Boolean,
    what: String,
): AndroidTunPayload.Cidr {
    val text = raw.trim()
    require(text.isNotEmpty()) { "$what is empty" }
    val slash = text.indexOf('/')
    require(slash in 1 until text.length - 1) { "$what is not a valid cidr: $raw" }
    val addressPart = text.substring(0, slash).trim()
    val prefixPart = text.substring(slash + 1).trim()
    val prefix = prefixPart.toIntOrNull()
    requireNotNull(prefix) { "$what prefix is not an integer: $raw" }
    val isV4 = isIpv4Literal(addressPart)
    val isV6 = !isV4 && isIpv6Literal(addressPart)
    require(isV4 || isV6) { "$what address is not a numeric ip: $raw" }
    require(isV4 == expectV4) {
        "$what family mismatch: expected ${if (expectV4) "ipv4" else "ipv6"}: $raw"
    }
    val maxPrefix = if (isV4) 32 else 128
    require(prefix in 0..maxPrefix) { "$what prefix $prefix out of range [0, $maxPrefix]: $raw" }
    return AndroidTunPayload.Cidr(addressPart, prefix)
}

/** Parse and validate a bare numeric IP. */
private fun parseAnyIpAddress(raw: String, what: String): String {
    val text = raw.trim()
    require(text.isNotEmpty()) { "$what is empty" }
    val isV4 = isIpv4Literal(text)
    val isV6 = !isV4 && isIpv6Literal(text)
    require(isV4 || isV6) { "$what is not a numeric ip: $raw" }
    return text
}

private fun isIpv4Literal(s: String): Boolean {
    if (s.isEmpty() || s.contains(':')) return false
    val parts = s.split('.')
    if (parts.size != 4) return false
    return parts.all { octet ->
        octet.isNotEmpty() && octet.length <= 3 &&
            octet.all { it.isDigit() } && (octet.toIntOrNull() ?: -1) in 0..255
    }
}

private fun isIpv6Literal(s: String): Boolean {
    if (s.isEmpty()) return false
    // IPv6 literals here are pure hex groups separated by ':' (no embedded IPv4).
    if (s.any { it != ':' && it.digitToIntOrNull(16) == null }) return false
    val doubleColon = s.indexOf("::")
    if (doubleColon >= 0 && s.indexOf("::", doubleColon + 1) >= 0) return false
    if (doubleColon < 0) {
        val groups = s.split(':')
        return groups.size == 8 && groups.all { isIpv6Group(it) }
    }
    val before = s.substring(0, doubleColon)
    val after = s.substring(doubleColon + 2)
    val beforeGroups = if (before.isEmpty()) emptyList() else before.split(':')
    val afterGroups = if (after.isEmpty()) emptyList() else after.split(':')
    if (beforeGroups.any { !isIpv6Group(it) }) return false
    if (afterGroups.any { !isIpv6Group(it) }) return false
    return beforeGroups.size + afterGroups.size <= 7
}

private fun isIpv6Group(g: String): Boolean = g.length in 1..4 && g.all { it.digitToIntOrNull(16) != null }
