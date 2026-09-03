package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.Inbound_TunOptions
import moe.matsuri.nb4a.SingBoxOptions.WireGuardEndpointOptions
import moe.matsuri.nb4a.SingBoxOptions.OpenVPNClientEndpointOptions
import moe.matsuri.nb4a.SingBoxOptions.OpenConnectEndpointOptions

internal const val UDP_NAT_MAX_VALUE = 0xFFFF_FFFFL

internal data class UdpNatSettings(
    val mapping: String?,
    val filtering: String?,
    val maxSessions: Long?,
) {
    companion object {
        private val behaviors =
            setOf(
                "endpoint_independent",
                "address_dependent",
                "address_and_port_dependent",
            )

        fun fromPreferences(
            mapping: String,
            filtering: String,
            maxSessions: String,
        ) = UdpNatSettings(
            mapping = mapping.takeIf { it in behaviors },
            filtering = filtering.takeIf { it in behaviors },
            maxSessions =
                maxSessions
                    .toLongOrNull()
                    ?.takeIf { it in 0..UDP_NAT_MAX_VALUE },
        )
    }
}

internal fun Inbound_TunOptions.applyUdpNatSettings(settings: UdpNatSettings) {
    udp_mapping = settings.mapping
    udp_filtering = settings.filtering
    udp_nat_max = settings.maxSessions
}

internal fun WireGuardEndpointOptions.applyUdpNatSettings(settings: UdpNatSettings) {
    udp_mapping = settings.mapping
    udp_filtering = settings.filtering
    udp_nat_max = settings.maxSessions
}

internal fun OpenVPNClientEndpointOptions.applyUdpNatSettings(settings: UdpNatSettings) {
    udp_mapping = settings.mapping
    udp_filtering = settings.filtering
    udp_nat_max = settings.maxSessions
}

internal fun OpenConnectEndpointOptions.applyUdpNatSettings(settings: UdpNatSettings) {
    udp_mapping = settings.mapping
    udp_filtering = settings.filtering
    udp_nat_max = settings.maxSessions
}
