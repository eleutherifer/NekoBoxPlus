package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticConnectionTestPolicyTest {

    @Test
    fun manualRetryPlanPreservesConfiguredValues() {
        assertEquals(
            ConnectionTestRetryPlan(attempts = 1, pauseMillis = 0),
            AutomaticConnectionTestPolicy.retryPlan(false, attempts = 1, pauseMillis = 0),
        )
    }

    @Test
    fun automaticRetryPlanAppliesSafeMinimumsTogether() {
        assertEquals(
            ConnectionTestRetryPlan(attempts = 2, pauseMillis = 100),
            AutomaticConnectionTestPolicy.retryPlan(true, attempts = 1, pauseMillis = 0),
        )
    }

    @Test
    fun attemptsHaveAutomaticMinimum() {
        assertEquals(2, AutomaticConnectionTestPolicy.effectiveAttempts(1))
        assertEquals(2, AutomaticConnectionTestPolicy.effectiveAttempts(2))
        assertEquals(4, AutomaticConnectionTestPolicy.effectiveAttempts(4))
    }

    @Test
    fun pauseHasAutomaticMinimum() {
        assertEquals(100, AutomaticConnectionTestPolicy.effectivePauseMillis(50))
        assertEquals(100, AutomaticConnectionTestPolicy.effectivePauseMillis(100))
        assertEquals(250, AutomaticConnectionTestPolicy.effectivePauseMillis(250))
    }
}
