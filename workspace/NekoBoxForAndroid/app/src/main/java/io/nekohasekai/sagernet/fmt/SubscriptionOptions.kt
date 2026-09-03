package io.nekohasekai.sagernet.fmt

import okhttp3.HttpUrl

internal fun HttpUrl.queryParameterAny(vararg names: String): String? =
    names.firstNotNullOfOrNull { queryParameter(it) }

internal fun String?.subscriptionBoolean(): Boolean =
    this?.equals("true", ignoreCase = true) == true || this == "1" ||
        this?.equals("yes", ignoreCase = true) == true || this?.equals("on", ignoreCase = true) == true

internal fun Map<*, *>.subscriptionValue(vararg names: String): Any? {
    val normalizedNames = names.map { it.replace('_', '-').lowercase() }.toSet()
    return entries.firstOrNull {
        it.key.toString().replace('_', '-').lowercase() in normalizedNames
    }?.value
}

internal fun Any?.subscriptionLines(): String = when (this) {
    is List<*> -> filterNotNull().joinToString("\n") { it.toString() }
    null -> ""
    else -> toString()
}

internal fun Any?.subscriptionBoolean(): Boolean = toString().subscriptionBoolean()

internal fun AbstractBean.applyUriTLSQUICOptions(link: HttpUrl) {
    link.queryParameterAny("handshake_timeout", "handshake-timeout", "tls_handshake_timeout", "tls-handshake-timeout")?.let { tlsHandshakeTimeout = it }
    link.queryParameterAny("idle_timeout", "idle-timeout")?.let { quicIdleTimeout = it }
    link.queryParameterAny("keep_alive_period", "keep-alive-period")?.let { quicKeepAlivePeriod = it }
    link.queryParameterAny("stream_receive_window", "stream-receive-window")?.toLongOrNull()?.let { quicStreamReceiveWindow = it }
    link.queryParameterAny("connection_receive_window", "connection-receive-window")?.toLongOrNull()?.let { quicConnectionReceiveWindow = it }
    link.queryParameterAny("max_concurrent_streams", "max-concurrent-streams")?.toIntOrNull()?.let { quicMaxConcurrentStreams = it }
    link.queryParameterAny("initial_packet_size", "initial-packet-size")?.toIntOrNull()?.let { quicInitialPacketSize = it }
    link.queryParameterAny("disable_path_mtu_discovery", "disable-path-mtu-discovery")?.let {
        quicDisablePathMtuDiscovery = it.subscriptionBoolean()
    }
}

internal fun AbstractBean.applyClashTLSQUICOptions(options: Map<*, *>) {
    options.subscriptionValue("handshake-timeout", "tls-handshake-timeout")?.let {
        tlsHandshakeTimeout = it.toString()
    }
    options.subscriptionValue("idle-timeout")?.let { quicIdleTimeout = it.toString() }
    options.subscriptionValue("keep-alive-period")?.let { quicKeepAlivePeriod = it.toString() }
    options.subscriptionValue("stream-receive-window")?.toString()?.toLongOrNull()?.let {
        quicStreamReceiveWindow = it
    }
    options.subscriptionValue("connection-receive-window")?.toString()?.toLongOrNull()?.let {
        quicConnectionReceiveWindow = it
    }
    options.subscriptionValue("max-concurrent-streams")?.toString()?.toIntOrNull()?.let {
        quicMaxConcurrentStreams = it
    }
    options.subscriptionValue("initial-packet-size")?.toString()?.toIntOrNull()?.let {
        quicInitialPacketSize = it
    }
    options.subscriptionValue("disable-path-mtu-discovery")?.let {
        quicDisablePathMtuDiscovery = it.subscriptionBoolean()
    }
}

internal fun HttpUrl.Builder.addTLSQUICOptions(bean: AbstractBean) {
    bean.tlsHandshakeTimeout.takeIf { !it.isNullOrBlank() }?.let { addQueryParameter("handshake_timeout", it) }
    bean.quicIdleTimeout.takeIf { !it.isNullOrBlank() }?.let { addQueryParameter("idle_timeout", it) }
    bean.quicKeepAlivePeriod.takeIf { !it.isNullOrBlank() }?.let { addQueryParameter("keep_alive_period", it) }
    bean.quicStreamReceiveWindow?.takeIf { it > 0 }?.let { addQueryParameter("stream_receive_window", it.toString()) }
    bean.quicConnectionReceiveWindow?.takeIf { it > 0 }?.let { addQueryParameter("connection_receive_window", it.toString()) }
    bean.quicMaxConcurrentStreams?.takeIf { it > 0 }?.let { addQueryParameter("max_concurrent_streams", it.toString()) }
    bean.quicInitialPacketSize?.takeIf { it > 0 }?.let { addQueryParameter("initial_packet_size", it.toString()) }
    if (bean.quicDisablePathMtuDiscovery == true) addQueryParameter("disable_path_mtu_discovery", "1")
}
