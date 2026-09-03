package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.ExclaveFragmentationMethod
import io.nekohasekai.sagernet.TrafficFragmentation
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.byedpi.ByeDPIBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean

private fun SingBoxOption.hasEnabledTls(): Boolean {
    val tlsOptions = asMap()["tls"] as? Map<*, *>
    return tlsOptions?.get("enabled") == true
}

private fun AbstractBean.isTlsBased(): Boolean = when (this) {
    is StandardV2RayBean -> security == "tls" || security == "reality"
    is ShadowTLSBean, is AnyTLSBean -> true
    is NaiveBean -> proto == "https"
    else -> false
}

private fun AbstractBean.isTcpBased(): Boolean = when (this) {
    is StandardV2RayBean -> type !in setOf("kcp", "quic")
    is NaiveBean -> proto != "quic"
    is MieruBean -> protocol != MieruBean.PROTOCOL_UDP
    else -> canTCPing()
}

internal fun isTrafficFragmentationEligible(
    trafficFragmentation: String,
    exclaveFragmentMethod: Int,
    outbound: SingBoxOption,
    bean: AbstractBean,
): Boolean {
    if (trafficFragmentation !in setOf(
            TrafficFragmentation.STARIFLY,
            TrafficFragmentation.EXCLAVE,
            TrafficFragmentation.BYEDPI,
        )
    ) {
        return false
    }
    if (bean is ByeDPIBean || bean is MasterDnsVPNBean || outbound.optionType() == "masterdnsvpn") return false

    val tlsBased = outbound.hasEnabledTls() || bean.isTlsBased()
    return when (trafficFragmentation) {
        TrafficFragmentation.STARIFLY -> tlsBased
        TrafficFragmentation.EXCLAVE -> when (exclaveFragmentMethod) {
            ExclaveFragmentationMethod.TCP_SEGMENTATION -> bean.isTcpBased()
            ExclaveFragmentationMethod.TLS_RECORD_FRAGMENTATION_AND_TCP_SEGMENTATION ->
                tlsBased || bean.isTcpBased()
            else -> tlsBased
        }
        else -> true
    }
}
