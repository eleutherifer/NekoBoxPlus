package io.nekohasekai.sagernet.bg

internal data class ConnectionTestRetryPlan(
    val attempts: Int,
    val pauseMillis: Int,
)

internal object AutomaticConnectionTestPolicy {
    const val START_DELAY_MILLIS = 500L

    private const val MIN_ATTEMPTS = 2
    private const val MIN_PAUSE_MILLIS = 100

    fun effectiveAttempts(configured: Int): Int = configured.coerceAtLeast(MIN_ATTEMPTS)

    fun effectivePauseMillis(configured: Int): Int = configured.coerceAtLeast(MIN_PAUSE_MILLIS)

    fun retryPlan(automatic: Boolean, attempts: Int, pauseMillis: Int): ConnectionTestRetryPlan =
        if (automatic) {
            ConnectionTestRetryPlan(effectiveAttempts(attempts), effectivePauseMillis(pauseMillis))
        } else {
            ConnectionTestRetryPlan(attempts, pauseMillis)
        }
}
