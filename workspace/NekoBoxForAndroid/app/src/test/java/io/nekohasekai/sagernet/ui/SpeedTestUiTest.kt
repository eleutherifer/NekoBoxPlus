package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestUiTest {
    @Test
    fun validatesSupportedSettings() {
        assertTrue(validateSpeedTestSettings(SpeedTestSettings()))
        assertTrue(
            validateSpeedTestSettings(
                SpeedTestSettings(serverMode = SPEED_TEST_SERVER_ID, serverValue = "1234"),
            ),
        )
        assertTrue(
            validateSpeedTestSettings(
                SpeedTestSettings(
                    serverMode = SPEED_TEST_SERVER_CUSTOM,
                    serverValue = "https://speed.example.com",
                ),
            ),
        )
    }

    @Test
    fun rejectsUnsafeOrOutOfRangeSettings() {
        assertFalse(validateSpeedTestSettings(SpeedTestSettings(durationMillis = 999)))
        assertFalse(validateSpeedTestSettings(SpeedTestSettings(connections = 17)))
        assertFalse(validateSpeedTestSettings(SpeedTestSettings(finalResult = 4)))
        assertFalse(
            validateSpeedTestSettings(
                SpeedTestSettings(serverMode = SPEED_TEST_SERVER_SEARCH, serverValue = " "),
            ),
        )
        assertFalse(
            validateSpeedTestSettings(
                SpeedTestSettings(
                    serverMode = SPEED_TEST_SERVER_CUSTOM,
                    serverValue = "file:///tmp/server",
                ),
            ),
        )
    }

    @Test
    fun formatsAdaptiveNetworkBitRates() {
        assertTrue(formatSpeedBits(125).endsWith("Kbps"))
        assertTrue(formatSpeedBits(125_000).endsWith("Mbps"))
        assertTrue(formatSpeedBits(125_000_000).endsWith("Gbps"))
    }
}
