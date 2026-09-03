package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficLooperPolicyTest {

    @Test
    fun sanitizeIntervalKeepsDisabledAndClampsAggressiveValues() {
        assertEquals(0L, TrafficLooperPolicy.sanitizeInterval(0L))
        assertEquals(0L, TrafficLooperPolicy.sanitizeInterval(-1L))
        assertEquals(TrafficLooperPolicy.MIN_ACTIVE_DELAY_MS, TrafficLooperPolicy.sanitizeInterval(1L))
        assertEquals(1000L, TrafficLooperPolicy.sanitizeInterval(1000L))
    }

    @Test
    fun nextDelayUsesIdleDelayWhenOnlySpeedIsConfiguredWithoutConsumers() {
        assertEquals(
            TrafficLooperPolicy.IDLE_DELAY_MS,
            TrafficLooperPolicy.nextDelay(
                speedDelayMs = 500L,
                trafficDelayMs = 0L,
                profileTrafficStatistics = false,
                hasSpeedConsumer = false,
            )
        )
    }

    @Test
    fun nextDelayUsesTrafficDelayWhenSpeedHasNoConsumers() {
        assertEquals(
            10_000L,
            TrafficLooperPolicy.nextDelay(
                speedDelayMs = 500L,
                trafficDelayMs = 10_000L,
                profileTrafficStatistics = true,
                hasSpeedConsumer = false,
            )
        )
    }

    @Test
    fun nextDelayUsesMinimumActiveDelayWhenSpeedHasConsumers() {
        assertEquals(
            500L,
            TrafficLooperPolicy.nextDelay(
                speedDelayMs = 500L,
                trafficDelayMs = 10_000L,
                profileTrafficStatistics = true,
                hasSpeedConsumer = true,
            )
        )
    }

    @Test
    fun nextDelayReturnsNullWhenNoWorkCanEverBeScheduled() {
        assertNull(
            TrafficLooperPolicy.nextDelay(
                speedDelayMs = 0L,
                trafficDelayMs = 0L,
                profileTrafficStatistics = false,
                hasSpeedConsumer = false,
            )
        )
    }

    @Test
    fun speedConsumersIncludeForegroundAndNotificationListeners() {
        assertTrue(TrafficLooperPolicy.hasSpeedConsumer(true, false))
        assertTrue(TrafficLooperPolicy.hasSpeedConsumer(false, true))
        assertFalse(TrafficLooperPolicy.hasSpeedConsumer(false, false))
    }

    @Test
    fun isDueRequiresPositiveDelayAndElapsedInterval() {
        assertFalse(TrafficLooperPolicy.isDue(0L, 0L, 10_000L))
        assertFalse(TrafficLooperPolicy.isDue(1000L, 9500L, 10_000L))
        assertTrue(TrafficLooperPolicy.isDue(1000L, 9000L, 10_000L))
    }
}
