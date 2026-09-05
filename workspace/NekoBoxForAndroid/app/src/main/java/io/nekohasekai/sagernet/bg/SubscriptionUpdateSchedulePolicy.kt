package io.nekohasekai.sagernet.bg

import kotlin.math.min

internal object SubscriptionUpdateSchedulePolicy {
    private const val MIN_PERIODIC_INTERVAL_MINUTES = 15L
    private const val MAX_INITIAL_DELAY_SECONDS = 60L

    data class SubscriptionState(
        val autoUpdateDelayMinutes: Int,
        val lastUpdatedSeconds: Int,
    )

    data class Schedule(
        val intervalMinutes: Long,
        val initialDelaySeconds: Long,
    )

    fun schedule(
        subscriptions: List<SubscriptionState>,
        nowSeconds: Long,
    ): Schedule? {
        if (subscriptions.isEmpty()) return null

        val intervalMinutes = subscriptions
            .minOf { it.autoUpdateDelayMinutes.toLong() }
            .coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES)

        val overdueSeconds = subscriptions.maxOf {
            nowSeconds - it.lastUpdatedSeconds - (it.autoUpdateDelayMinutes.toLong() * 60L)
        }
        val initialDelaySeconds = if (overdueSeconds > 0) {
            min(overdueSeconds, MAX_INITIAL_DELAY_SECONDS)
        } else {
            0L
        }

        return Schedule(
            intervalMinutes = intervalMinutes,
            initialDelaySeconds = initialDelaySeconds,
        )
    }
}
