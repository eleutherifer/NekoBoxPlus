package io.nekohasekai.sagernet.fmt.trusttunnel

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.applySharedTLSOptions
import libcore.Libcore
import libcore.TrustTunnelURL
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private fun String?.blankAsNull(): String? = if (isNullOrBlank()) null else this
private val emptyCertificateRegex =
    Regex("""\A\s*-----BEGIN CERTIFICATE-----\s*-----END CERTIFICATE-----\s*\z""")

private fun String?.validCertificateOrNull(): String? {
    val certificate = blankAsNull() ?: return null
    if (emptyCertificateRegex.matches(certificate)) return null
    return certificate
}

private fun String?.certificateLinesOrNull(): List<String>? = validCertificateOrNull()?.lines()

private fun String.decodeUrlComponent(): String =
    URLDecoder.decode(replace("+", "%2B"), StandardCharsets.UTF_8.name())

private fun parseThroneTrustTunnel(link: String): TrustTunnelBean? {
    val uri = URI(link)
    if (!uri.scheme.equals("tt", ignoreCase = true)) return null
    val rawUserInfo = uri.rawUserInfo ?: return null
    val separator = rawUserInfo.indexOf(':')
    require(separator >= 0) { "missing TrustTunnel password" }

    val username = rawUserInfo.substring(0, separator).decodeUrlComponent()
    val password = rawUserInfo.substring(separator + 1).decodeUrlComponent()
    val host = uri.host ?: error("missing TrustTunnel server")
    require(username.isNotEmpty()) { "missing TrustTunnel username" }
    require(password.isNotEmpty()) { "missing TrustTunnel password" }

    val query = uri.rawQuery.orEmpty().split('&').mapNotNull { item ->
        if (item.isEmpty()) return@mapNotNull null
        val parts = item.split('=', limit = 2)
        val key = parts[0].decodeUrlComponent()
        val value = parts.getOrElse(1) { "" }.decodeUrlComponent()
        key to value
    }.toMap()

    return TrustTunnelBean().apply {
        serverAddress = host
        serverPort = uri.port.takeIf { it > 0 } ?: 443
        serverName = query["sni"].orEmpty().ifEmpty { host }
        this.username = username
        this.password = password
        name = uri.rawFragment?.decodeUrlComponent().orEmpty()
        quic = true
        utlsFingerprint = "chrome"
    }
}

fun parseTrustTunnel(link: String): TrustTunnelBean {
    parseThroneTrustTunnel(link)?.let { return it }

    val url = Libcore.parseTrustTunnelLink(link)
    return TrustTunnelBean().apply {
        serverAddress = url.host
        serverPort = url.port.toInt()
        serverName = url.serverName
        username = url.username
        password = url.password
        allowInsecure = url.skipVerification
        certificates = url.certificate
        quic = url.quic
        name = url.name
        clientRandomPrefix = url.clientRandomPrefix
    }
}

fun TrustTunnelBean.toUri(): String {
    return TrustTunnelURL().apply {
        host = serverAddress
        port = serverPort
        serverName = this@toUri.serverName
        username = this@toUri.username
        password = this@toUri.password
        skipVerification = allowInsecure == true
        certificate = certificates.validCertificateOrNull().orEmpty()
        quic = this@toUri.quic == true
        name = this@toUri.name
        clientRandomPrefix = this@toUri.clientRandomPrefix
    }.build()
}

fun buildSingBoxOutboundTrustTunnelBean(bean: TrustTunnelBean): SingBoxOptions.Outbound_TrustTunnelOptions {
    return SingBoxOptions.Outbound_TrustTunnelOptions().apply {
        val useCronetHttps = bean.useCronetHttps == true || bean.utlsFingerprint == "cronet"
        type = SingBoxOptions.TYPE_TRUST_TUNNEL
        server = bean.serverAddress
        server_port = bean.serverPort
        username = bean.username
        password = bean.password
        if (bean.healthCheck == true) health_check = true
        val forceQuic = bean.quic == true && bean.forceQuic == true
        val useCronetQuic = bean.quic == true && bean.useCronetQuic == true
        val useCronet = useCronetHttps || useCronetQuic
        if (!useCronet) {
            client_random_prefix = bean.clientRandomPrefix.blankAsNull()
        }
        if (useCronetHttps) use_cronet_https = true
        if (useCronetQuic) use_cronet_quic = true
        if (bean.quic == true) {
            quic = true
            if (forceQuic) force_quic = true
            quic_congestion_control = bean.quicCongestionControl.blankAsNull()
        }

        tls = SingBoxOptions.OutboundTLSOptions().apply {
            enabled = true
            server_name = bean.serverName.blankAsNull()
            if (!useCronet && (bean.allowInsecure == true || DataStore.globalAllowInsecure)) insecure = true
            if (!forceQuic) {
                alpn = bean.alpn.blankAsNull()?.listByLineOrComma()
            }
            certificate = bean.certificates.certificateLinesOrNull()
            certificate_public_key_sha256 = bean.certPublicKeySha256.blankAsNull()?.lines()
            client_certificate = bean.clientCert.blankAsNull()?.listByLineOrComma()
            client_key = bean.clientKey.blankAsNull()?.listByLineOrComma()
            if (!useCronetHttps && !forceQuic && bean.tlsFragment == true) {
                fragment = true
                fragment_fallback_delay = bean.tlsFragmentFallbackDelay.blankAsNull()
            } else if (!useCronetHttps && !forceQuic && bean.tlsRecordFragment == true) {
                record_fragment = true
            }
            if (bean.quic != true) {
                bean.tlsSpoof.blankAsNull()?.let {
                    spoof = it
                    spoof_method = bean.tlsSpoofMethod.blankAsNull()
                }
            }
            bean.utlsFingerprint.blankAsNull()?.takeIf { !forceQuic && it != "cronet" }?.let {
                utls = SingBoxOptions.OutboundUTLSOptions().apply {
                    enabled = true
                    fingerprint = it
                }
            }
            if (bean.ech == true) {
                ech = SingBoxOptions.OutboundECHOptions().apply {
                    enabled = true
                    config = bean.echConfig.blankAsNull()?.lines()
                    query_server_name = bean.echQueryServerName.blankAsNull()
                }
            }
            applySharedTLSOptions(bean)
        }
    }
}
