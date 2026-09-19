package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.OutboundTLSOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundECHOptions
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import moe.matsuri.nb4a.utils.listByLineOrComma

private val SHARED_TLS_FIELD_NAMES = setOf(
    "tlsCurvePreferences",
    "tlsCertificatePublicKeySha256",
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

fun SingBoxOption.applySharedDialOptions(bean: AbstractBean) {
    if (bean.disableTcpKeepAlive == true) {
        _hack_config_map["disable_tcp_keep_alive"] = true
    }
    bean.tcpKeepAlive?.takeIf { it.isNotBlank() }?.let {
        _hack_config_map["tcp_keep_alive"] = it.trim()
    }
    bean.tcpKeepAliveInterval?.takeIf { it.isNotBlank() }?.let {
        _hack_config_map["tcp_keep_alive_interval"] = it.trim()
    }
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
