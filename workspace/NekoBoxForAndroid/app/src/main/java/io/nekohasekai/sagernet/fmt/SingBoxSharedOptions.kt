package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import moe.matsuri.nb4a.SingBoxOptions.OutboundTLSOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundECHOptions
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import moe.matsuri.nb4a.utils.listByLineOrComma

private val SHARED_TLS_FIELD_NAMES = setOf(
    "tlsCurvePreferences",
    "tlsCertificatePublicKeySha256",
    "tlsXrayCertificateSha256",
    "tlsClientCertificate",
    "tlsClientKey",
    "echQueryServerName",
)

fun AbstractBean.supportsSharedTLSFieldInjection(): Boolean {
    var profileClass: Class<*>? = javaClass
    while (profileClass != null && profileClass != AbstractBean::class.java) {
        if (profileClass.declaredFields.any { it.name in SHARED_TLS_FIELD_NAMES }) return false
        profileClass = profileClass.superclass
    }
    return true
}

internal data class DialOptionCapabilities(
    val tcp: Boolean,
    val udpFragment: Boolean,
) {
    companion object {
        val NONE = DialOptionCapabilities(tcp = false, udpFragment = false)
        val TCP = DialOptionCapabilities(tcp = true, udpFragment = false)
        val UDP = DialOptionCapabilities(tcp = false, udpFragment = true)
        val TCP_AND_UDP = DialOptionCapabilities(tcp = true, udpFragment = true)
    }
}

private val TCP_ONLY_DIAL_OPTION_TYPES = setOf(
    "anytls",
    "byedpi",
    "http",
    "shadowtls",
    "snell",
    "ssh",
    "trojan",
    "vless",
    "vmess",
)

private val UDP_ONLY_DIAL_OPTION_TYPES = setOf(
    "hysteria",
    "hysteria2",
    "juicity",
    "tuic",
    "wireguard",
)

private val TCP_AND_UDP_DIAL_OPTION_TYPES = setOf(
    "direct",
    "shadowsocks",
    "shadowsocksr",
    "socks",
    "tailscale",
)

internal fun SingBoxOption.resolveDialOptionCapabilities(bean: AbstractBean): DialOptionCapabilities =
    when (optionType()) {
        in TCP_ONLY_DIAL_OPTION_TYPES -> DialOptionCapabilities.TCP
        in UDP_ONLY_DIAL_OPTION_TYPES -> DialOptionCapabilities.UDP
        in TCP_AND_UDP_DIAL_OPTION_TYPES -> DialOptionCapabilities.TCP_AND_UDP
        "mieru" -> when (bean) {
            is MieruBean -> if (bean.network() == "udp") DialOptionCapabilities.UDP else DialOptionCapabilities.TCP
            else -> if ((asMap()["transport"] as? String).equals("UDP", ignoreCase = true)) {
                DialOptionCapabilities.UDP
            } else {
                DialOptionCapabilities.TCP
            }
        }
        "naive" -> {
            val usesQuic = if (bean is NaiveBean) bean.proto == "quic" else asMap()["quic"] == true
            if (usesQuic) DialOptionCapabilities.UDP else DialOptionCapabilities.TCP
        }
        "trusttunnel" -> {
            val (usesQuic, forcesQuic) = if (bean is TrustTunnelBean) {
                (bean.quic == true) to (bean.forceQuic == true)
            } else {
                (asMap()["quic"] == true) to (asMap()["force_quic"] == true)
            }
            when {
                forcesQuic -> DialOptionCapabilities.UDP
                usesQuic -> DialOptionCapabilities.TCP_AND_UDP
                else -> DialOptionCapabilities.TCP
            }
        }
        "masque" -> {
            val usesHttp2 = if (bean is MasqueBean) bean.useHTTP2 == true else asMap()["transport"] == "h2"
            if (usesHttp2) DialOptionCapabilities.TCP else DialOptionCapabilities.UDP
        }
        // These options do not establish a compatible upstream transport themselves.
        "awg", "masterdnsvpn", "selector", "urltest" -> DialOptionCapabilities.NONE
        // Raw custom outbounds are only modified when their type is recognized above.
        else -> DialOptionCapabilities.NONE
    }

internal fun SingBoxOption.applySharedDialOptions(
    bean: AbstractBean,
    capabilities: DialOptionCapabilities,
) {
    if (capabilities.tcp && optionType() != "anytls" && bean.tcpFastOpen == true) {
        _hack_config_map["tcp_fast_open"] = true
    }
    if (capabilities.tcp && bean.tcpMultiPath == true) {
        _hack_config_map["tcp_multi_path"] = true
    }
    if (capabilities.udpFragment) {
        bean.udpFragment?.let {
            _hack_config_map["udp_fragment"] = it
        }
    }
    if (capabilities.tcp && bean.disableTcpKeepAlive == true) {
        _hack_config_map["disable_tcp_keep_alive"] = true
    }
    if (capabilities.tcp) {
        bean.tcpKeepAlive?.takeIf { it.isNotBlank() }?.let {
            _hack_config_map["tcp_keep_alive"] = it.trim()
        }
        bean.tcpKeepAliveInterval?.takeIf { it.isNotBlank() }?.let {
            _hack_config_map["tcp_keep_alive_interval"] = it.trim()
        }
    }
}

internal fun SingBoxOption.applyGlobalDialOverrides(
    tcpFastOpen: Boolean,
    tcpMultiPath: Boolean,
    udpFragment: String,
    capabilities: DialOptionCapabilities,
) {
    if (capabilities.tcp && optionType() != "anytls" && tcpFastOpen) {
        _hack_config_map["tcp_fast_open"] = true
    }
    if (capabilities.tcp && tcpMultiPath) {
        _hack_config_map["tcp_multi_path"] = true
    }
    if (capabilities.udpFragment) {
        when (udpFragment) {
            "true" -> _hack_config_map["udp_fragment"] = true
            "false" -> _hack_config_map["udp_fragment"] = false
        }
    }
}

fun SingBoxOption.applyConfiguredDialOptions(
    bean: AbstractBean,
    tcpFastOpen: Boolean,
    tcpMultiPath: Boolean,
    udpFragment: String,
) {
    val capabilities = resolveDialOptionCapabilities(bean)
    if (capabilities == DialOptionCapabilities.NONE) return

    applySharedDialOptions(bean, capabilities)
    applyGlobalDialOverrides(tcpFastOpen, tcpMultiPath, udpFragment, capabilities)
}

fun OutboundTLSOptions.applySharedTLSOptions(bean: AbstractBean) {
    if (!bean.supportsSharedTLSFieldInjection()) return

    bean.tlsCurvePreferences?.takeIf { it.isNotBlank() }?.let {
        curve_preferences = it.listByLineOrComma()
    }
    bean.tlsCertificatePublicKeySha256?.takeIf { it.isNotBlank() }?.let {
        require(certificate == null) {
            "TLS certificate authority and public-key pinning cannot be used together"
        }
        certificate_public_key_sha256 = it.listByLineOrComma()
    }
    bean.tlsXrayCertificateSha256?.takeIf { it.isNotBlank() }?.let {
        require(certificate == null && certificate_public_key_sha256 == null) {
            "Xray certificate pinning cannot be combined with other certificate verification options"
        }
        xray_certificate_sha256 = it.listByLineOrComma()
    }
    val clientCertificate = bean.tlsClientCertificate.orEmpty().trim()
    val clientKey = bean.tlsClientKey.orEmpty().trim()
    require(clientCertificate.isBlank() == clientKey.isBlank()) {
        "TLS client certificate and private key must be provided together"
    }
    if (clientCertificate.isNotBlank()) {
        client_certificate = clientCertificate.lines()
        client_key = clientKey.lines()
    }
    bean.echQueryServerName?.takeIf { it.isNotBlank() }?.let { queryName ->
        if (ech == null) {
            ech = OutboundECHOptions().apply { enabled = true }
        }
        ech?.query_server_name = queryName
    }
}
