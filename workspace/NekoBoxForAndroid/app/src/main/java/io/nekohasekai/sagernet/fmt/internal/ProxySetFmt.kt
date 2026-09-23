package io.nekohasekai.sagernet.fmt.internal

import moe.matsuri.nb4a.SingBoxOptions

fun buildSingBoxOutboundProxySetBean(
    bean: ProxySetBean,
    outboundsByProfileId: Map<Long, String>,
): SingBoxOptions.Outbound {
    val outbounds = outboundsByProfileId.values.toList()
    if (bean.mode == ProxySetBean.MODE_SELECTOR) {
        return SingBoxOptions.Outbound_SelectorOptions().apply {
            type = "selector"
            this.outbounds = outbounds
            default_ = outboundsByProfileId[bean.defaultOutbound]
            interrupt_exist_connections = bean.interruptExistConnections
        }
    }
    return SingBoxOptions.Outbound_URLTestOptions().apply {
        type = "urltest"
        this.outbounds = outbounds
        url = bean.testURL
        interval = bean.testInterval
        idle_timeout = bean.testIdleTimeout
        tolerance = bean.testTolerance
        interrupt_exist_connections = bean.interruptExistConnections
    }
}
