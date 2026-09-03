package io.nekohasekai.sagernet.fmt.openvpn

import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.wrapIPV6Host

private val externalFileDirectives = setOf(
    "ca",
    "cert",
    "key",
    "tls-auth",
    "tls-crypt",
    "tls-crypt-v2",
    "secret",
    "crl-verify",
)

private val ignoredDirectives = setOf(
    "client",
    "nobind",
    "persist-key",
    "persist-tun",
    "verb",
    "mute",
    "resolv-retry",
)

private fun routePrefix(network: String, netmask: String?): String? {
    if (network.contains('/')) return network
    if (netmask.isNullOrBlank()) return null
    val octets = netmask.split('.').mapNotNull(String::toIntOrNull)
    if (octets.size != 4 || octets.any { it !in 0..255 }) return null
    val bits = octets.joinToString("") { it.toString(2).padStart(8, '0') }
    if ("01" in bits) return null
    return "$network/${bits.count { it == '1' }}"
}

private fun tokenizeOpenVPN(line: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    for (char in line) {
        if (escaped) {
            current.append(char)
            escaped = false
            continue
        }
        if (char == '\\') {
            escaped = true
            continue
        }
        if (quote != null) {
            if (char == quote) {
                quote = null
            } else {
                current.append(char)
            }
        } else {
            when (char) {
                '\'', '"' -> quote = char
                '#', ';' -> break
                ' ', '\t' -> if (current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
        }
    }
    if (current.isNotEmpty()) result += current.toString()
    return result
}

fun parseOpenVPNConfig(text: String, profileName: String = ""): OpenVPNBean {
    val bean = OpenVPNBean().applyDefaultValues().apply { name = profileName }
    val inline = mutableMapOf<String, StringBuilder>()
    var block: String? = null
    for (rawLine in text.lineSequence()) {
        val trimmed = rawLine.trim()
        if (block != null) {
            if (trimmed.equals("</$block>", true)) {
                block = null
            } else {
                inline.getValue(block).append(rawLine).append('\n')
            }
            continue
        }
        Regex("^<([A-Za-z0-9-]+)>$")
            .matchEntire(trimmed)
            ?.groupValues
            ?.get(1)
            ?.lowercase()
            ?.let {
                block = it
                inline[it] = StringBuilder()
                continue
            }
        val args = tokenizeOpenVPN(rawLine)
        if (args.isEmpty()) continue
        val key = args[0].lowercase()
        val value = args.drop(1)
        fun one() = value.firstOrNull().orEmpty()
        when (key) {
            "remote" -> {
                val host = one()
                if (host.isBlank()) error("OpenVPN remote is missing a host")
                val port = value.getOrNull(1)?.toIntOrNull() ?: 1194
                val network = value.getOrNull(2)?.removeSuffix("-client")?.lowercase() ?: bean.network
                if (bean.serverAddress == "127.0.0.1") {
                    bean.serverAddress = host
                    bean.serverPort = port
                    bean.network = network
                } else {
                    val scheme = if (network.startsWith("tcp")) "tcp" else "udp"
                    bean.additionalRemotes += "$scheme://${host.wrapIPV6Host()}:$port\n"
                }
            }

            "proto" -> bean.network = one().removeSuffix("-client").let {
                if (it.startsWith("tcp")) "tcp" else "udp"
            }

            "remote-random" -> bean.remoteRandom = true
            "auth-user-pass" -> if (one().isNotBlank()) {
                error("External OpenVPN auth-user-pass files are not supported")
            }

            "auth-retry" -> bean.authRetry = one()
            "static-challenge" -> {
                bean.staticChallenge = one()
                bean.staticChallengeEcho = value.getOrNull(1) == "1"
            }

            "topology" -> bean.topology = one()
            "ifconfig" -> {
                val local = one()
                val remoteOrMask = value.getOrNull(1).orEmpty()
                bean.addresses = routePrefix(local, remoteOrMask) ?: local.takeIf(String::isNotBlank)?.let { "$it/32" }.orEmpty()
                if (routePrefix(local, remoteOrMask) == null) bean.peerAddress = remoteOrMask
            }
            "ifconfig-ipv6" -> {
                bean.addresses = listOf(bean.addresses, one()).filter(String::isNotBlank).joinToString("\n")
                bean.peerAddressIPv6 = value.getOrNull(1).orEmpty()
            }

            "tun-mtu" -> bean.mtu = one().toIntOrNull() ?: 0
            "server-poll-timeout" -> bean.udpTimeout = one() + "s"
            "verify-x509-name" -> {
                bean.tlsServerName = one()
                bean.tlsServerNameType = value.getOrNull(1).orEmpty()
            }

            "peer-fingerprint" -> bean.peerFingerprints += one() + "\n"
            "remote-cert-ku" -> bean.remoteCertificateKU = value.joinToString("\n")
            "remote-cert-eku" -> bean.remoteCertificateEKU = one()
            "remote-cert-tls" -> bean.remoteCertificateTLS = one()
            "tls-cert-profile" -> bean.certificateProfile = one()
            "ns-cert-type" -> bean.nsCertificateType = one()
            "tls-version-min" -> bean.tlsVersionMin = one()
            "tls-version-max" -> bean.tlsVersionMax = one()
            "tls-cipher" -> bean.tlsCipher = value.joinToString(":")
            "tls-groups" -> bean.tlsGroups = value.joinToString(":")
            "key-direction" -> bean.controlWrapDirection = one()
            "data-ciphers", "ncp-ciphers" -> bean.dataCiphers = one().replace(':', '\n')
            "data-ciphers-fallback" -> bean.dataCiphersFallback = one()
            "cipher" -> bean.dataCiphersFallback = one()
            "auth" -> bean.auth = one()
            "mssfix" -> {
                bean.mssFix = one().toIntOrNull() ?: 0
                bean.mssFixMode = value.getOrNull(1).orEmpty()
                bean.mssFixDisabled = one() == "0"
            }
            "fragment" -> bean.fragment = one().toIntOrNull() ?: 0
            "replay-window" -> {
                bean.replayWindow = one().toIntOrNull() ?: 0
                bean.replayWindowTime = value.getOrNull(1)?.takeIf(String::isNotBlank)?.plus("s").orEmpty()
            }
            "compress" -> bean.compression = one()
            "comp-lzo" -> bean.compressionLZO = one().ifBlank { "yes" }
            "allow-compression" -> bean.allowCompression = one()
            "route-nopull" -> bean.routeNoPull = true
            "pull-filter" -> bean.pullFilters += value.joinToString(" ") + "\n"
            "route", "route-ipv6" -> routePrefix(one(), value.getOrNull(1))?.let { bean.routes += "$it\n" }
            "route-gateway" -> bean.routeGateway = one()
            "route-metric" -> bean.routeMetric = one().toIntOrNull() ?: 0
            "redirect-gateway" -> {
                bean.redirectGateway = true
                bean.redirectGatewayFlags = value.joinToString("\n")
            }
            "redirect-private" -> {
                bean.redirectPrivate = true
                bean.redirectGatewayFlags = value.joinToString("\n")
            }
            "block-ipv6" -> bean.blockIPv6 = true

            "ping" -> bean.pingInterval = one() + "s"
            "ping-restart" -> if (one() == "0") bean.pingRestartDisabled = true else bean.pingRestart = one() + "s"
            "reneg-sec" -> if (one() == "0") bean.renegotiateDisabled = true else bean.renegotiateInterval = one() + "s"
            "reneg-bytes" -> bean.renegotiateBytes = one().toLongOrNull() ?: 0L
            "reneg-pkts" -> bean.renegotiatePackets = one().toLongOrNull() ?: 0L
            "tls-timeout" -> bean.tlsTimeout = one() + "s"
            "hand-window" -> bean.handshakeWindow = one() + "s"
            "explicit-exit-notify" -> bean.explicitExitNotify = one().toIntOrNull() ?: 1
            in externalFileDirectives -> if (one().isNotBlank()) {
                error(
                    "External OpenVPN file reference '$key' is not supported; " +
                        "use an inline block",
                )
            }

            in ignoredDirectives -> Unit
            "dev", "dev-type" -> if (one().startsWith("tap")) {
                error("OpenVPN TAP mode is not supported")
            }

            "http-proxy",
            "socks-proxy",
            "tls-client-cert-not-required",
            -> error("Unsupported security-critical OpenVPN directive: $key")
        }
    }
    if (block != null) error("Unclosed OpenVPN inline block: $block")
    bean.caCertificates = inline["ca"]?.toString()?.trim().orEmpty()
    bean.clientCertificate = inline["cert"]?.toString()?.trim().orEmpty()
    bean.clientKey = inline["key"]?.toString()?.trim().orEmpty()
    listOf("tls-auth", "tls-crypt", "tls-crypt-v2").firstNotNullOfOrNull { type ->
        inline[type]?.toString()?.trim()?.takeIf(String::isNotBlank)?.let { type to it }
    }?.let { (type, key) ->
        bean.controlWrapType = type
        bean.controlWrapKey = key
    }
    inline["secret"]?.toString()?.trim()?.takeIf(String::isNotBlank)?.let {
        bean.mode = "static_key"
        bean.staticKey = it
        bean.staticKeyDirection = bean.controlWrapDirection
        bean.controlWrapDirection = ""
        bean.cipher = bean.dataCiphersFallback
        bean.dataCiphersFallback = ""
    }
    inline["auth-user-pass"]?.toString()?.lineSequence()?.filter(String::isNotBlank)?.toList()?.let {
        bean.username = it.getOrNull(0).orEmpty()
        bean.password = it.getOrNull(1).orEmpty()
    }
    if (bean.serverAddress == "127.0.0.1") error("OpenVPN profile does not contain a remote server")
    return bean
}
