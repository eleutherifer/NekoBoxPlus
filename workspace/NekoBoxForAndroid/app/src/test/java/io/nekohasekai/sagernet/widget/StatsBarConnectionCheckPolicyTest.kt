package io.nekohasekai.sagernet.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsBarConnectionCheckPolicyTest {
    @Test
    fun recreatedViewDoesNotRepeatAutomaticCheckForCurrentConnection() {
        val policy = StatsBarConnectionCheckPolicy()

        assertTrue(policy.onConnected().shouldRunAutomaticCheck)
        policy.retainStatus("HTTPS: 42ms")
        policy.retainIpInfo("203.0.113.1")
        assertFalse(policy.onConnected().shouldRunAutomaticCheck)
        assertEquals(
            "HTTPS: 42ms" to "203.0.113.1",
            policy.retainedPresentation(),
        )
    }

    @Test
    fun reconnectAllowsAnotherAutomaticCheck() {
        val policy = StatsBarConnectionCheckPolicy()
        val firstConnection = policy.onConnected()

        policy.onDisconnected()
        val secondConnection = policy.onConnected()

        assertFalse(policy.isCurrent(firstConnection.session))
        assertNull(policy.retainedPresentation())
        assertTrue(secondConnection.shouldRunAutomaticCheck)
        assertTrue(policy.isCurrent(secondConnection.session))
    }

    @Test
    fun resolverReadyUsesTheSameConnectionSessionDecision() {
        val policy = StatsBarConnectionCheckPolicy()

        policy.onDisconnected()
        assertTrue(policy.onConnected().shouldRunAutomaticCheck)
        assertFalse(policy.onConnected().shouldRunAutomaticCheck)
    }
}
