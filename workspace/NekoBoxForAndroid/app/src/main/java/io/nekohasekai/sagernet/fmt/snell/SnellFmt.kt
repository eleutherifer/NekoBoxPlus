package io.nekohasekai.sagernet.fmt.snell

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseSnell(url: String): SnellBean {
    val link = url.replace("snell://", "https://").toHttpUrlOrNull()
        ?: error("Invalid snell URL")

    return SnellBean().apply {
        serverAddress = link.host
        serverPort = link.port
        psk = link.username
        name = link.fragment ?: ""

        link.queryParameter("version")?.toIntOrNull()?.let {
            version = it.coerceIn(1, 6)
        }
        link.queryParameter("userkey")?.let { userKey = it }
        link.queryParameter("obfs-mode")?.let { obfsMode = it }
        link.queryParameter("obfs-host")?.let { obfsHost = it }
        link.queryParameter("reuse")?.let { reuse = it.toBoolean() }
        link.queryParameter("network")?.let { network = it }
        link.queryParameter("mode")?.let { mode = it }
        link.queryParameter("quic-proxy-mode")?.let { quicProxyMode = it.toBoolean() }
    }
}

fun SnellBean.toUri(): String {
    val builder = linkBuilder().username(psk).host(serverAddress).port(serverPort)
    builder.addQueryParameter("version", version.toString())
    if (version == 6) {
        if (mode.isNotBlank() && mode != "default") builder.addQueryParameter("mode", mode)
        if (quicProxyMode == true) builder.addQueryParameter("quic-proxy-mode", "true")
    } else {
        if (obfsMode.isNotBlank()) builder.addQueryParameter("obfs-mode", obfsMode)
        if (obfsHost.isNotBlank()) builder.addQueryParameter("obfs-host", obfsHost)
    }
    if (reuse) builder.addQueryParameter("reuse", "true")
    if (network.isNotBlank()) builder.addQueryParameter("network", network)
    if (userKey.isNotBlank()) builder.addQueryParameter("userkey", userKey)
    if (name.isNotBlank()) builder.fragment(name)
    return builder.toLink("snell")
}

fun parseClashSnell(proxy: Map<String, Any?>): SnellBean {
    return SnellBean().apply {
        name = proxy["name"] as? String ?: ""
        serverAddress = proxy["server"] as? String ?: ""
        serverPort = (proxy["port"] as? Number)?.toInt() ?: 443
        psk = proxy["psk"] as? String ?: ""
        version = ((proxy["version"] as? Number)?.toInt() ?: 4).coerceIn(1, 6)
        userKey = proxy["userkey"] as? String ?: ""
        mode = proxy["mode"] as? String ?: ""
        reuse = proxy["reuse"] as? Boolean ?: false

        val udpEnabled = proxy["udp"] as? Boolean ?: false
        network = if (udpEnabled) "" else "tcp"

        (proxy["obfs-opts"] as? Map<*, *>)?.let { obfsOpts ->
            obfsMode = obfsOpts["mode"] as? String ?: ""
            obfsHost = obfsOpts["host"] as? String ?: ""
        }
    }
}
