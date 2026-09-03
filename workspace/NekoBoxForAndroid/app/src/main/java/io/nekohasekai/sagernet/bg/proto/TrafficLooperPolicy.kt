package io.nekohasekai.sagernet.bg.proto

internal object TrafficLooperPolicy {
    const val MIN_ACTIVE_DELAY_MS = 500L
    const val IDLE_DELAY_MS = 1000L

    fun sanitizeInterval(intervalMs: Long): Long {
        if (intervalMs <= 0L) return 0L
        return intervalMs.coerceAtLeast(MIN_ACTIVE_DELAY_MS)
    }

    fun hasSpeedConsumer(hasForegroundCallback: Boolean, notificationListening: Boolean): Boolean {
        return hasForegroundCallback || notificationListening
    }

    fun nextDelay(
        speedDelayMs: Long,
        trafficDelayMs: Long,
        profileTrafficStatistics: Boolean,
        hasSpeedConsumer: Boolean,
    ): Long? {
        val activeDelays = buildList {
            if (hasSpeedConsumer && speedDelayMs > 0L) add(speedDelayMs)
            if (profileTrafficStatistics && trafficDelayMs > 0L) add(trafficDelayMs)
        }
        activeDelays.minOrNull()?.let { return it }

        if (speedDelayMs > 0L) return IDLE_DELAY_MS
        return null
    }

    fun isDue(delayMs: Long, lastUpdateMs: Long, nowMs: Long): Boolean {
        return delayMs > 0L && nowMs - lastUpdateMs >= delayMs
    }
}
