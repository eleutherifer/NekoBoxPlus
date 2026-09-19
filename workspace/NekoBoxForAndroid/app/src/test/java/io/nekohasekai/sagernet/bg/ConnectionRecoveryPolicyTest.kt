package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryPolicyTest {
    @Test
    fun allowsRequestedResetWithoutUrlTest() {
        assertTrue(shouldResetConnections(resetRequested = true, urlTestRunning = false))
    }

    @Test
    fun suppressesRequestedResetDuringUrlTest() {
        assertFalse(shouldResetConnections(resetRequested = true, urlTestRunning = true))
    }

    @Test
    fun doesNotCreateUnrequestedReset() {
        assertFalse(shouldResetConnections(resetRequested = false, urlTestRunning = false))
        assertFalse(shouldResetConnections(resetRequested = false, urlTestRunning = true))
    }
}
