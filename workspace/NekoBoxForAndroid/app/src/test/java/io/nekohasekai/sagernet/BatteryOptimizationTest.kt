package io.nekohasekai.sagernet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationTest {
    @Test
    fun androidBeforeMarshmallowDoesNotRequestExemption() {
        assertFalse(BatteryOptimization.shouldRequest(22, false, false))
    }

    @Test
    fun firstEligibleConnectionRequestsExemption() {
        assertTrue(BatteryOptimization.shouldRequest(23, false, false))
    }

    @Test
    fun exemptAppDoesNotRequestExemption() {
        assertFalse(BatteryOptimization.shouldRequest(37, true, false))
    }

    @Test
    fun previouslyAskedAppDoesNotRequestExemptionAgain() {
        assertFalse(BatteryOptimization.shouldRequest(37, false, true))
    }
}
