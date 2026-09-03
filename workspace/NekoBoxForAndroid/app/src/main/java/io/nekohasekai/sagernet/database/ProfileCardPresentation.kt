package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.wireguard.hasAmneziaWG31Options
import io.nekohasekai.sagernet.fmt.wireguard.hasAmneziaWG3Options

private val insecureShadowsocksMethods = setOf(
    "none",
    "aes-128-ctr",
    "aes-192-ctr",
    "aes-256-ctr",
    "aes-128-cfb",
    "aes-192-cfb",
    "aes-256-cfb",
    "rc4-md5",
    "chacha20-ietf",
    "xchacha20",
)

private fun String?.isEnabledEncryption(): Boolean =
    !isNullOrBlank() && !equals("none", ignoreCase = true)

private fun String?.shortNetwork(): String = when (this?.lowercase()) {
    "tcp" -> "TCP"
    "ws" -> "WS"
    "http", "h2" -> "HTTP"
    "quic" -> "QUIC"
    "grpc" -> "gRPC"
    "xhttp" -> "XHTTP"
    "httpupgrade" -> "HTTPUp"
    null, "" -> "TCP"
    else -> uppercase()
}

private fun StandardV2RayBean.transportEncryption(): String = when {
    security.equals("reality", ignoreCase = true) -> "Reality"
    security.equals("tls", ignoreCase = true) -> "TLS"
    else -> "None"
}

private fun StandardV2RayBean.v2RayCardType(protocol: String): String =
    "$protocol ${type.shortNetwork()} ${transportEncryption()}"

private fun StandardV2RayBean.shortV2RayCardType(): String = buildString {
    append(if (isVLESS) "VL" else "VM")
    append('.')
    append(type.shortNetwork())

    when (transportEncryption()) {
        "TLS" -> append(".TLS")
        "Reality" -> append(".R")
    }

    if (isVLESS && vlessEncryption.isEnabledEncryption()) append('*')
}

fun ProxyEntity.profileCardType(short: Boolean = false): String = if (short) {
    shortProfileCardType()
} else when (type) {
    ProxyEntity.TYPE_SS -> "Shadowsocks ${ssBean?.method.orEmpty()}".trimEnd()
    ProxyEntity.TYPE_SSR -> "ShadowsocksR ${ssrBean?.method.orEmpty()}".trimEnd()
    ProxyEntity.TYPE_VMESS -> vmessBean?.let { bean ->
        if (bean.isVLESS) {
            buildString {
                append(bean.v2RayCardType("VLESS"))
                if (bean.vlessEncryption.isEnabledEncryption()) append(" ML-KEM")
            }
        } else {
            bean.v2RayCardType("VMess")
        }
    } ?: displayType()
    ProxyEntity.TYPE_TROJAN -> trojanBean?.v2RayCardType("Trojan") ?: displayType()
    ProxyEntity.TYPE_TROJAN_GO -> {
        val network = if (trojanGoBean?.type.equals("ws", ignoreCase = true)) "WS" else "TCP"
        "Trojan-Go $network TLS"
    }
    ProxyEntity.TYPE_MIERU -> {
        val network = if (mieruBean?.protocol == MieruBean.PROTOCOL_UDP) "UDP" else "TCP"
        "Mieru $network"
    }
    ProxyEntity.TYPE_NAIVE -> "Naïve ${naiveBean?.proto.orEmpty().uppercase().ifBlank { "HTTPS" }}"
    ProxyEntity.TYPE_HYSTERIA -> hysteriaBean?.let { bean ->
        val network = if (bean.protocolVersion == 1) {
            when (bean.protocol) {
                HysteriaBean.PROTOCOL_FAKETCP -> "FakeTCP"
                HysteriaBean.PROTOCOL_WECHAT_VIDEO -> "WeChat"
                else -> "UDP"
            }
        } else {
            "QUIC"
        }
        "Hysteria ${bean.protocolVersion ?: 2} $network"
    } ?: displayType()
    ProxyEntity.TYPE_TUIC -> "TUIC v${tuicBean?.protocolVersion ?: 5} QUIC"
    ProxyEntity.TYPE_JUICITY -> "Juicity QUIC"
    ProxyEntity.TYPE_SNELL -> snellBean?.let { bean ->
        buildList {
            add("Snell")
            add("v${bean.version ?: 4}")
            bean.network?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
            bean.mode?.takeIf { it.isNotBlank() && it != "default" }?.let { add(it) }
        }.joinToString(" ")
    } ?: displayType()
    ProxyEntity.TYPE_MASTERDNSVPN -> {
        val encryption = when (masterDnsVPNBean?.dataEncryptionMethod) {
            0 -> "None"
            1 -> "XOR"
            2 -> "ChaCha20"
            3 -> "AES-128-GCM"
            4 -> "AES-192-GCM"
            5 -> "AES-256-GCM"
            else -> "Unknown"
        }
        "MasterDnsVPN $encryption"
    }
    ProxyEntity.TYPE_SHADOWTLS -> "ShadowTLS v${shadowTLSBean?.version ?: 3} TLS"
    ProxyEntity.TYPE_ANYTLS -> {
        val encryption = if (anyTLSBean?.realityPubKey.isNullOrBlank()) "TLS" else "Reality"
        "AnyTLS $encryption"
    }
    ProxyEntity.TYPE_TRUST_TUNNEL -> {
        val network = if (trustTunnelBean?.quic == true) "QUIC" else "HTTPS"
        "TrustTunnel $network"
    }
    ProxyEntity.TYPE_MASQUE -> {
        val network = if (masqueBean?.useHTTP2 == true) "HTTP/2" else "HTTP/3"
        "MASQUE $network"
    }
    ProxyEntity.TYPE_AWG -> {
        val version = when {
            awgBean?.hasAmneziaWG31Options() == true -> "3.1"
            awgBean?.hasAmneziaWG3Options() == true -> "3.0"
            else -> "2.0"
        }
        "AmneziaWG $version"
    }
    else -> displayType()
}

private fun ProxyEntity.shortProfileCardType(): String = when (type) {
    ProxyEntity.TYPE_SOCKS -> when (socksBean?.protocolName()) {
        "SOCKS4" -> "S4"
        "SOCKS4A" -> "S4A"
        else -> "S5"
    }
    ProxyEntity.TYPE_HTTP -> if (httpBean?.isTLS() == true) "HTTPS" else "HTTP"
    ProxyEntity.TYPE_SS -> "Sh.S"
    ProxyEntity.TYPE_SSR -> "SSR"
    ProxyEntity.TYPE_VMESS -> vmessBean?.shortV2RayCardType() ?: "VMess"
    ProxyEntity.TYPE_TROJAN -> "Trjn"
    ProxyEntity.TYPE_TROJAN_GO -> "TrGo"
    ProxyEntity.TYPE_MIERU -> "Mieru"
    ProxyEntity.TYPE_NAIVE -> "Naïve"
    ProxyEntity.TYPE_HYSTERIA -> if (hysteriaBean?.protocolVersion == 1) "Hy1" else "Hy2"
    ProxyEntity.TYPE_SSH -> "SSH"
    ProxyEntity.TYPE_WG -> "WG"
    ProxyEntity.TYPE_AWG -> "AWG"
    ProxyEntity.TYPE_TUIC -> "TUIC"
    ProxyEntity.TYPE_JUICITY -> "Juic"
    ProxyEntity.TYPE_SNELL -> "Snell"
    ProxyEntity.TYPE_MASTERDNSVPN -> "MDVPN"
    ProxyEntity.TYPE_BYEDPI -> "ByDPI"
    ProxyEntity.TYPE_SHADOWTLS -> "ShTLS"
    ProxyEntity.TYPE_ANYTLS -> "AnTLS"
    ProxyEntity.TYPE_TRUST_TUNNEL -> "TrTun"
    ProxyEntity.TYPE_MASQUE -> "MASQ"
    ProxyEntity.TYPE_DIRECT -> "Dir"
    ProxyEntity.TYPE_TAILSCALE -> "Tail"
    ProxyEntity.TYPE_PROXY_SET ->
        if (proxySetBean?.mode == ProxySetBean.MODE_URL_TEST) "URLT" else "Sel"
    ProxyEntity.TYPE_CHAIN -> "Chain"
    else -> displayType().take(5)
}

private fun StandardV2RayBean.hasInsecureTls(globalAllowInsecure: Boolean): Boolean =
    security.equals("tls", ignoreCase = true) && (allowInsecure == true || globalAllowInsecure)

fun ProxyEntity.isInsecureProfile(globalAllowInsecure: Boolean): Boolean = when (type) {
    ProxyEntity.TYPE_SOCKS -> true
    ProxyEntity.TYPE_HTTP -> httpBean?.let { !it.isTLS() || it.hasInsecureTls(globalAllowInsecure) } == true
    ProxyEntity.TYPE_SS -> ssBean?.method?.lowercase() in insecureShadowsocksMethods
    ProxyEntity.TYPE_SSR -> true
    ProxyEntity.TYPE_VMESS -> vmessBean?.let { bean ->
        if (!bean.isVLESS) {
            bean.hasInsecureTls(globalAllowInsecure)
        } else if (bean.vlessEncryption.isEnabledEncryption()) {
            false
        } else {
            when {
                bean.security.equals("reality", ignoreCase = true) -> false
                bean.security.equals("tls", ignoreCase = true) ->
                    bean.allowInsecure == true || globalAllowInsecure
                else -> true
            }
        }
    } == true
    ProxyEntity.TYPE_TROJAN ->
        trojanBean?.hasInsecureTls(globalAllowInsecure) == true
    ProxyEntity.TYPE_TROJAN_GO ->
        trojanGoBean?.let { it.allowInsecure == true || globalAllowInsecure } == true
    ProxyEntity.TYPE_HYSTERIA ->
        hysteriaBean?.let { it.allowInsecure == true || globalAllowInsecure } == true
    ProxyEntity.TYPE_SSH -> sshBean?.publicKey.isNullOrBlank()
    ProxyEntity.TYPE_TUIC ->
        tuicBean?.let { it.allowInsecure == true || globalAllowInsecure } == true
    ProxyEntity.TYPE_JUICITY -> juicityBean?.let {
        it.pinnedCertchainSha256.isNullOrBlank() &&
                (it.allowInsecure == true || globalAllowInsecure)
    } == true
    ProxyEntity.TYPE_SNELL ->
        snellBean?.let { it.version == 6 && it.mode.equals("unsafe-raw", ignoreCase = true) } == true
    ProxyEntity.TYPE_MASTERDNSVPN ->
        masterDnsVPNBean?.dataEncryptionMethod == 0
    ProxyEntity.TYPE_SHADOWTLS ->
        shadowTLSBean?.hasInsecureTls(globalAllowInsecure) == true
    ProxyEntity.TYPE_ANYTLS -> anyTLSBean?.let {
        it.realityPubKey.isNullOrBlank() && (it.allowInsecure == true || globalAllowInsecure)
    } == true
    ProxyEntity.TYPE_TRUST_TUNNEL -> trustTunnelBean?.let { bean ->
        val usesCronet = bean.useCronetHttps == true ||
                bean.utlsFingerprint == "cronet" ||
                (bean.quic == true && bean.useCronetQuic == true)
        !usesCronet &&
                bean.certPublicKeySha256.isNullOrBlank() &&
                (bean.allowInsecure == true || globalAllowInsecure)
    } == true
    ProxyEntity.TYPE_MASQUE ->
        masqueBean?.let { it.tlsInsecure == true || globalAllowInsecure } == true
    else -> false
}

fun ProxyEntity.shouldHighlightAsInsecure(
    globalAllowInsecure: Boolean,
    dontHighlightInsecureProfiles: Boolean,
): Boolean = !dontHighlightInsecureProfiles && isInsecureProfile(globalAllowInsecure)
