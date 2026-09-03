package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.openconnect.OpenConnectBean
import io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean
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
    val tcpFastOpen: Boolean = tcp,
) {
    companion object {
        val NONE = DialOptionCapabilities(tcp = false, udpFragment = false)
        val TCP = DialOptionCapabilities(tcp = true, udpFragment = false)
        val TCP_WITHOUT_FAST_OPEN = DialOptionCapabilities(
            tcp = true,
            udpFragment = false,
            tcpFastOpen = false,
        )
        val UDP = DialOptionCapabilities(tcp = false, udpFragment = true)
        val TCP_AND_UDP = DialOptionCapabilities(tcp = true, udpFragment = true)
    }
}

private val TCP_ONLY_DIAL_OPTION_TYPES = setOf(
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

private fun String?.isTcpTransport(): Boolean = this?.lowercase()?.startsWith("tcp") == true

private fun String?.isUdpTransport(): Boolean = this.isNullOrBlank() || this.lowercase().startsWith("udp")

private fun OpenVPNBean.dialOptionCapabilities(): DialOptionCapabilities {
    val transports = buildList {
        add(network)
        additionalRemotes.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { remote ->
            add(remote.substringBefore("://", missingDelimiterValue = "udp"))
        }
    }
    return DialOptionCapabilities(
        tcp = transports.any(String?::isTcpTransport),
        udpFragment = transports.any(String?::isUdpTransport),
    )
}

private fun SingBoxOption.customOpenVPNCapabilities(): DialOptionCapabilities {
    val options = asMap()
    val transports = buildList {
        add(options["network"] as? String)
        (options["servers"] as? List<*>)?.forEach { server ->
            add((server as? Map<*, *>)?.get("network") as? String)
        }
    }
    return DialOptionCapabilities(
        tcp = transports.any(String?::isTcpTransport),
        udpFragment = transports.any(String?::isUdpTransport),
    )
}

internal fun SingBoxOption.resolveDialOptionCapabilities(bean: AbstractBean): DialOptionCapabilities =
    when (optionType()) {
        "anytls" -> DialOptionCapabilities.TCP_WITHOUT_FAST_OPEN
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
        "openvpn-client" -> if (bean is OpenVPNBean) bean.dialOptionCapabilities() else customOpenVPNCapabilities()
        "openconnect" -> {
            val noUdp = if (bean is OpenConnectBean) bean.noUDP == true else asMap()["no_udp"] == true
            if (noUdp) DialOptionCapabilities.TCP else DialOptionCapabilities.TCP_AND_UDP
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
    if (capabilities.tcpFastOpen && bean.tcpFastOpen == true) {
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
    if (capabilities.tcp && !(bean is OpenConnectBean && bean.disableTcpKeepAlive == true)) {
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
    if (capabilities.tcpFastOpen && tcpFastOpen) {
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
    handshake_timeout = bean.tlsHandshakeTimeout?.trim()?.takeIf { it.isNotEmpty() }
}

fun SingBoxOption.applySharedQUICOptions(bean: AbstractBean) {
    bean.quicIdleTimeout?.trim()?.takeIf { it.isNotEmpty() }?.let { _hack_config_map["idle_timeout"] = it }
    bean.quicKeepAlivePeriod?.trim()?.takeIf { it.isNotEmpty() }?.let { _hack_config_map["keep_alive_period"] = it }
    bean.quicStreamReceiveWindow?.takeIf { it > 0 }?.let { _hack_config_map["stream_receive_window"] = it }
    bean.quicConnectionReceiveWindow?.takeIf { it > 0 }?.let { _hack_config_map["connection_receive_window"] = it }
    bean.quicMaxConcurrentStreams?.takeIf { it > 0 }?.let { _hack_config_map["max_concurrent_streams"] = it }
    bean.quicInitialPacketSize?.takeIf { it > 0 }?.let { _hack_config_map["initial_packet_size"] = it }
    if (bean.quicDisablePathMtuDiscovery == true) {
        _hack_config_map["disable_path_mtu_discovery"] = true
    }
}
