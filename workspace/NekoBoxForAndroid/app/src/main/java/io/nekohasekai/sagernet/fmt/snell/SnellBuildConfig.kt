package io.nekohasekai.sagernet.fmt.snell

import moe.matsuri.nb4a.SingBoxOptions

fun buildSingBoxOutboundSnellBean(bean: SnellBean): SingBoxOptions.Outbound_SnellOptions {
    bean.initializeDefaultValues()
    return SingBoxOptions.Outbound_SnellOptions().apply {
        type = "snell"
        server = bean.serverAddress
        server_port = bean.serverPort
        psk = bean.psk
        if (!bean.userKey.isNullOrBlank()) {
            userkey = bean.userKey
        }
        version = bean.version
        userkey = bean.userKey.takeIf { it.isNotBlank() }
        mode = bean.mode.takeIf { bean.version == 6 && it.isNotBlank() && it != "default" }
        quic_proxy_mode = true.takeIf { bean.version == 6 && bean.quicProxyMode == true }

        if (!bean.network.isNullOrBlank()) {
            network = bean.network
        }

        if (bean.version != 6 && bean.obfsMode != null && bean.obfsMode.isNotBlank()) {
            obfs_mode = if (bean.version != null && bean.version >= 4 && bean.obfsMode == "tls") "" else bean.obfsMode
            if (obfs_mode.isNotBlank() && bean.obfsHost != null && bean.obfsHost.isNotBlank()) {
                obfs_host = bean.obfsHost
            }
        }

        if (bean.reuse == true) {
            reuse = true
        }
    }
}
