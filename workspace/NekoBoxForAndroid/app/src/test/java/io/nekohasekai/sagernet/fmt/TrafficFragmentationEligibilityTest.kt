package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.ExclaveFragmentationMethod
import io.nekohasekai.sagernet.TrafficFragmentation
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.SingBoxOptions.Outbound
import moe.matsuri.nb4a.proxy.byedpi.ByeDPIBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.direct.DirectBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficFragmentationEligibilityTest {

    @Test
    fun nativeMasterDnsVPNIsNeverEligibleForTrafficFragmentation() {
        val bean = MasterDnsVPNBean().apply { initializeDefaultValues() }
        val outbound = Outbound().apply {
            type = "masterdnsvpn"
        }

        listOf(
            TrafficFragmentation.STARIFLY,
            TrafficFragmentation.EXCLAVE,
            TrafficFragmentation.BYEDPI,
        ).forEach { mode ->
            assertFalse(
                isTrafficFragmentationEligible(
                    mode,
                    ExclaveFragmentationMethod.TLS_RECORD_FRAGMENTATION_AND_TCP_SEGMENTATION,
                    outbound,
                    bean,
                ),
            )
        }
    }

    @Test
    fun customMasterDnsVPNOutboundIsNeverEligibleForTrafficFragmentation() {
        val bean = ConfigBean().apply { initializeDefaultValues() }
        val outbound = CustomSingBoxOption("""{"type":"masterdnsvpn"}""")

        listOf(
            TrafficFragmentation.STARIFLY,
            TrafficFragmentation.EXCLAVE,
            TrafficFragmentation.BYEDPI,
        ).forEach { mode ->
            assertFalse(
                isTrafficFragmentationEligible(
                    mode,
                    ExclaveFragmentationMethod.TLS_RECORD_FRAGMENTATION_AND_TCP_SEGMENTATION,
                    outbound,
                    bean,
                ),
            )
        }
    }

    @Test
    fun customOutboundPreservesGeneratedMetadataWithoutTypedFields() {
        val outbound = CustomSingBoxOption("""{"type":"direct","tag":"original"}""").apply {
            _hack_config_map["tag"] = "generated"
            _hack_config_map["detour"] = "next"
        }

        val fields = outbound.asMap()

        assertEquals("direct", fields["type"])
        assertEquals("generated", fields["tag"])
        assertEquals("next", fields["detour"])
    }

    @Test
    fun customOutboundCustomJsonCanOverrideGeneratedMetadata() {
        val outbound = CustomSingBoxOption("""{"type":"direct"}""").apply {
            _hack_config_map["tag"] = "generated"
            _hack_custom_config = """{"tag":"user"}"""
        }

        assertEquals("user", outbound.asMap()["tag"])
    }

    @Test
    fun customOutboundGeneratedTagParticipatesInRemoteDnsDetourLookup() {
        val outbound = CustomSingBoxOption("""{"type":"direct"}""").apply {
            _hack_config_map["tag"] = "proxy"
        }

        assertEquals(null, remoteDnsDetourTag("proxy", listOf(outbound)))
    }

    @Test
    fun byedpiProfileRemainsIneligibleForTrafficFragmentation() {
        val bean = ByeDPIBean().apply { initializeDefaultValues() }
        val outbound = Outbound().apply {
            type = "byedpi"
        }

        assertFalse(
            isTrafficFragmentationEligible(
                TrafficFragmentation.BYEDPI,
                ExclaveFragmentationMethod.TLS_RECORD_FRAGMENTATION,
                outbound,
                bean,
            ),
        )
    }

    @Test
    fun normalOutboundRemainsEligibleForByeDPIFragmentation() {
        val bean = DirectBean().apply { initializeDefaultValues() }
        val outbound = Outbound().apply {
            type = "direct"
        }

        assertTrue(
            isTrafficFragmentationEligible(
                TrafficFragmentation.BYEDPI,
                ExclaveFragmentationMethod.TLS_RECORD_FRAGMENTATION,
                outbound,
                bean,
            ),
        )
    }
}
