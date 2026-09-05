package io.nekohasekai.sagernet.fmt.tuic

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.addTLSQUICOptions
import io.nekohasekai.sagernet.fmt.applyClashTLSQUICOptions
import io.nekohasekai.sagernet.fmt.applySharedTLSOptions
import io.nekohasekai.sagernet.fmt.applySharedQUICOptions
import io.nekohasekai.sagernet.fmt.applyUriTLSQUICOptions
import io.nekohasekai.sagernet.fmt.subscriptionBoolean
import io.nekohasekai.sagernet.fmt.subscriptionLines
import io.nekohasekai.sagernet.fmt.subscriptionValue
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.toLink
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseTuic(url: String): TuicBean {
    // https://github.com/daeuniverse/dae/discussions/182
    val link = url.replace("tuic://", "https://").toHttpUrlOrNull() ?: error(
        "invalid tuic link $url"
    )
    return TuicBean().apply {
        protocolVersion = 5

        name = link.fragment
        serverAddress = link.host
        serverPort = link.port

        val rawUser = link.username
        val rawPass = link.password

        if (rawUser.contains(":")) {
            val parts = rawUser.split(":", limit = 2)
            uuid = parts[0]
            token = parts.getOrElse(1) { "" }
        } else {
            uuid = rawUser
            token = rawPass
        }

        link.queryParameter("sni")?.let {
            sni = it
        }
        link.queryParameter("congestion_control")?.let {
            congestionController = it
        }
        link.queryParameter("udp_relay_mode")?.let {
            udpRelayMode = it
        }
        link.queryParameter("alpn")?.let {
            if (it != "none") alpn = it
        }
        link.queryParameter("allow_insecure")?.let {
            if (it == "1") allowInsecure = true
        }
        link.queryParameter("disable_sni")?.let {
            if (it == "1") disableSNI = true
        }
        link.queryParameter("zero_rtt_handshake")?.let { reduceRTT = it.subscriptionBoolean() }
        applyUriTLSQUICOptions(link)
    }
}

fun TuicBean.toUri(): String {
    val builder = linkBuilder().username(uuid).password(token).host(serverAddress).port(serverPort)

    builder.addQueryParameter("congestion_control", congestionController)
    builder.addQueryParameter("udp_relay_mode", udpRelayMode)

    if (sni.isNotBlank()) builder.addQueryParameter("sni", sni)
    if (alpn.isNotBlank()) builder.addQueryParameter("alpn", alpn)
    if (allowInsecure) builder.addQueryParameter("allow_insecure", "1")
    if (disableSNI) builder.addQueryParameter("disable_sni", "1")
    if (reduceRTT) builder.addQueryParameter("zero_rtt_handshake", "1")
    builder.addTLSQUICOptions(this)
    if (name.isNotBlank()) builder.fragment(name)

    return builder.toLink("tuic")
}

fun parseClashTuic(proxy: Map<String, Any?>): TuicBean = TuicBean().applyDefaultValues().apply {
    name = proxy.subscriptionValue("name")?.toString() ?: ""
    serverAddress = proxy.subscriptionValue("ip", "server")?.toString() ?: serverAddress
    serverPort = proxy.subscriptionValue("port")?.toString()?.toIntOrNull() ?: serverPort
    uuid = proxy.subscriptionValue("uuid")?.toString() ?: ""
    token = proxy.subscriptionValue("password", "token")?.toString() ?: ""
    protocolVersion = if (proxy.subscriptionValue("token") != null && proxy.subscriptionValue("uuid") == null) 4 else 5
    allowInsecure = proxy.subscriptionValue("skip-cert-verify", "allow-insecure").subscriptionBoolean()
    disableSNI = proxy.subscriptionValue("disable-sni").subscriptionBoolean()
    reduceRTT = proxy.subscriptionValue("reduce-rtt", "zero-rtt-handshake").subscriptionBoolean()
    sni = proxy.subscriptionValue("sni", "server-name")?.toString() ?: ""
    alpn = proxy.subscriptionValue("alpn").subscriptionLines()
    congestionController = proxy.subscriptionValue("congestion-controller", "congestion-control")?.toString()
        ?: congestionController
    udpRelayMode = proxy.subscriptionValue("udp-relay-mode")?.toString() ?: udpRelayMode
    applyClashTLSQUICOptions(proxy)
}

fun buildSingBoxOutboundTuicBean(bean: TuicBean): SingBoxOptions.Outbound_TUICOptions {
    if (bean.protocolVersion == 4) throw Exception("TUIC v4 is no longer supported")
    return SingBoxOptions.Outbound_TUICOptions().apply {
        type = "tuic"
        server = bean.serverAddress
        server_port = bean.serverPort
        uuid = bean.uuid
        password = bean.token
        congestion_control = bean.congestionController
        when (bean.udpRelayMode) {
            "quic" -> udp_relay_mode = "quic"
        }
        zero_rtt_handshake = bean.reduceRTT
        applySharedQUICOptions(bean)
        tls = SingBoxOptions.OutboundTLSOptions().apply {
            if (bean.sni.isNotBlank()) {
                server_name = bean.sni
            }
            if (bean.alpn.isNotBlank()) {
                alpn = bean.alpn.listByLineOrComma()
            }
            if (bean.caText.isNotBlank()) {
                certificate = bean.caText
            }
            disable_sni = bean.disableSNI
            insecure = bean.allowInsecure || DataStore.globalAllowInsecure
            enabled = true
            applySharedTLSOptions(bean)
        }
    }
}
