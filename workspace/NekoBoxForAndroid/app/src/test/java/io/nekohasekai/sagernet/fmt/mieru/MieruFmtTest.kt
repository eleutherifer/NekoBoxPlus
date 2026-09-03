package io.nekohasekai.sagernet.fmt.mieru

import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.junit.Assert.assertEquals
import org.junit.Test

class MieruFmtTest {

    @Test
    fun lowEntropyOptionsSurviveLinkRoundTripAndOutboundConversion() {
        val source = MieruBean().apply {
            serverAddress = "example.com"
            serverPort = 443
            username = "user"
            password = "password"
            lowEntropyMode = "LOW_ENTROPY_MODE_40"
            lowEntropyMaskRotation = "LOW_ENTROPY_MASK_ROTATE_LEFT_3"
        }.applyDefaultValues()

        val parsed = parseMieru(source.toUri()).single()
        assertEquals(source.lowEntropyMode, parsed.lowEntropyMode)
        assertEquals(source.lowEntropyMaskRotation, parsed.lowEntropyMaskRotation)

        val outbound = buildSingBoxOutboundMieruBean(parsed)
        assertEquals(source.lowEntropyMode, outbound.low_entropy_mode)
        assertEquals(source.lowEntropyMaskRotation, outbound.low_entropy_mask_rotation)
    }
}
