package moe.matsuri.nb4a.proxy.byedpi

import moe.matsuri.nb4a.SingBoxOptions

fun buildSingBoxOutboundByeDPIBean(bean: ByeDPIBean): SingBoxOptions.Outbound_ByeDPIOptions {
    return SingBoxOptions.Outbound_ByeDPIOptions().apply {
        type = "byedpi"
        cli = bean.cliStrategy
    }
}
