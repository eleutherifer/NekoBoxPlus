package io.nekohasekai.sagernet.fmt.internal

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.isInsecureProfile
import moe.matsuri.nb4a.SingBoxOptions

fun ProxySetBean.filterInsecureProfiles(
    profiles: List<ProxyEntity>,
    globalAllowInsecure: Boolean,
): List<ProxyEntity> {
    if (!skipInsecureProfiles) return profiles
    return profiles.filterNot { it.isInsecureProfile(globalAllowInsecure) }
}

fun buildSingBoxOutboundProxySetBean(
    bean: ProxySetBean,
    outboundsByProfileId: Map<Long, String>,
): SingBoxOptions.Outbound {
    val outbounds = outboundsByProfileId.values.toList()
    require(outbounds.isNotEmpty()) { "Proxy set has no eligible profiles" }
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
