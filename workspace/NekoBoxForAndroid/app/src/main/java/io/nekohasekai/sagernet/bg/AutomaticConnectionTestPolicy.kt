package io.nekohasekai.sagernet.bg

internal object AutomaticConnectionTestPolicy {
    const val START_DELAY_MILLIS = 500L

    private const val MIN_ATTEMPTS = 2
    private const val MIN_PAUSE_MILLIS = 100

    fun effectiveAttempts(configured: Int): Int = configured.coerceAtLeast(MIN_ATTEMPTS)

    fun effectivePauseMillis(configured: Int): Int = configured.coerceAtLeast(MIN_PAUSE_MILLIS)
}
