package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.Inbound_TunOptions
import moe.matsuri.nb4a.SingBoxOptions.WireGuardEndpointOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UdpNatSettingsTest {

    @Test
    fun unspecifiedSettingsAreOmitted() {
        val settings = UdpNatSettings.fromPreferences("", "", "")
        val tun = Inbound_TunOptions().apply { applyUdpNatSettings(settings) }.asMap()
        val wireGuard = WireGuardEndpointOptions().apply { applyUdpNatSettings(settings) }.asMap()

        for (options in listOf(tun, wireGuard)) {
            assertFalse(options.containsKey("udp_mapping"))
            assertFalse(options.containsKey("udp_filtering"))
            assertFalse(options.containsKey("udp_nat_max"))
            assertFalse(options.containsKey("endpoint_independent_nat"))
        }
    }

    @Test
    fun supportedBehaviorsAreSerializedExactly() {
        val behaviors =
            listOf(
                "endpoint_independent",
                "address_dependent",
                "address_and_port_dependent",
            )

        for (behavior in behaviors) {
            val settings = UdpNatSettings.fromPreferences(behavior, behavior, "")
            val tun = Inbound_TunOptions().apply { applyUdpNatSettings(settings) }.asMap()
            val wireGuard = WireGuardEndpointOptions().apply { applyUdpNatSettings(settings) }.asMap()

            for (options in listOf(tun, wireGuard)) {
                assertEquals(behavior, options["udp_mapping"])
                assertEquals(behavior, options["udp_filtering"])
                assertFalse(options.containsKey("udp_nat_max"))
            }
        }
    }

    @Test
    fun explicitSessionLimitsAreSerialized() {
        for (value in listOf(0L, UDP_NAT_MAX_VALUE)) {
            val settings = UdpNatSettings.fromPreferences("", "", value.toString())
            val tun = Inbound_TunOptions().apply { applyUdpNatSettings(settings) }.asMap()
            val wireGuard = WireGuardEndpointOptions().apply { applyUdpNatSettings(settings) }.asMap()

            assertEquals(value, tun["udp_nat_max"])
            assertEquals(value, wireGuard["udp_nat_max"])
        }
    }

    @Test
    fun invalidStoredValuesAreOmitted() {
        val settings = UdpNatSettings.fromPreferences("invalid", "invalid", "4294967296")

        assertNull(settings.mapping)
        assertNull(settings.filtering)
        assertNull(settings.maxSessions)
    }
}
