package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionUpdateSchedulePolicyTest {

    @Test
    fun returnsNoScheduleWithoutAutoUpdateSubscriptions() {
        assertNull(
            SubscriptionUpdateSchedulePolicy.schedule(
                subscriptions = emptyList(),
                nowSeconds = 1_000L,
            )
        )
    }

    @Test
    fun clampsIntervalToWorkManagerMinimum() {
        val schedule = SubscriptionUpdateSchedulePolicy.schedule(
            subscriptions = listOf(
                SubscriptionUpdateSchedulePolicy.SubscriptionState(
                    autoUpdateDelayMinutes = 5,
                    lastUpdatedSeconds = 900,
                )
            ),
            nowSeconds = 1_000L,
        )

        assertEquals(15L, schedule!!.intervalMinutes)
    }

    @Test
    fun usesSmallestSubscriptionDelayAsInterval() {
        val schedule = SubscriptionUpdateSchedulePolicy.schedule(
            subscriptions = listOf(
                SubscriptionUpdateSchedulePolicy.SubscriptionState(
                    autoUpdateDelayMinutes = 120,
                    lastUpdatedSeconds = 0,
                ),
                SubscriptionUpdateSchedulePolicy.SubscriptionState(
                    autoUpdateDelayMinutes = 60,
                    lastUpdatedSeconds = 0,
                )
            ),
            nowSeconds = 1_000L,
        )

        assertEquals(60L, schedule!!.intervalMinutes)
    }

    @Test
    fun doesNotDelayWhenNoSubscriptionIsDue() {
        val schedule = SubscriptionUpdateSchedulePolicy.schedule(
            subscriptions = listOf(
                SubscriptionUpdateSchedulePolicy.SubscriptionState(
                    autoUpdateDelayMinutes = 60,
                    lastUpdatedSeconds = 1_000,
                )
            ),
            nowSeconds = 2_000L,
        )

        assertEquals(0L, schedule!!.initialDelaySeconds)
    }

    @Test
    fun schedulesDueSubscriptionSoonAndCapsInitialDelay() {
        val schedule = SubscriptionUpdateSchedulePolicy.schedule(
            subscriptions = listOf(
                SubscriptionUpdateSchedulePolicy.SubscriptionState(
                    autoUpdateDelayMinutes = 60,
                    lastUpdatedSeconds = 0,
                )
            ),
            nowSeconds = 4_000L,
        )

        assertEquals(60L, schedule!!.initialDelaySeconds)
    }

    @Test
    fun schedulesSoonWhenAnySubscriptionIsDue() {
        val schedule = SubscriptionUpdateSchedulePolicy.schedule(
            subscriptions = listOf(
                SubscriptionUpdateSchedulePolicy.SubscriptionState(
                    autoUpdateDelayMinutes = 15,
                    lastUpdatedSeconds = 1_099,
                ),
                SubscriptionUpdateSchedulePolicy.SubscriptionState(
                    autoUpdateDelayMinutes = 60,
                    lastUpdatedSeconds = 0,
                )
            ),
            nowSeconds = 2_000L,
        )

        assertEquals(1L, schedule!!.initialDelaySeconds)
    }
}
