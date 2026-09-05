package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTestSessionStateTest {
    @Test
    fun runningConnectionCanClaimAutomaticCheckOnlyOnce() {
        val state = ConnectionTestSessionState()

        assertTrue(state.claim(connected = true))
        assertFalse(state.claim(connected = true))
    }

    @Test
    fun presentationSurvivesUiClientsAndClearsWithConnection() {
        val state = ConnectionTestSessionState()
        state.claim(connected = true)
        state.setPresentation(true, "HTTPS: 42ms", "203.0.113.1")

        assertEquals(
            ConnectionTestSessionState.Presentation("HTTPS: 42ms", "203.0.113.1"),
            state.presentation(),
        )

        state.reset()

        assertNull(state.presentation())
        assertTrue(state.claim(connected = true))
    }

    @Test
    fun disconnectedServiceCannotClaimOrStorePresentation() {
        val state = ConnectionTestSessionState()

        assertFalse(state.claim(connected = false))
        state.setPresentation(false, "HTTPS: 42ms", null)
        assertNull(state.presentation())
    }
}
