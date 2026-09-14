package io.nekohasekai.sagernet.bg

import org.junit.Assert.*
import org.junit.Test

class PendingConnectionRecoveryTest {
    @Test
    fun defersAndCoalescesUntilNetworkAndUrlTestAllowRecovery() {
        val pending = PendingConnectionRecovery()
        pending.request(false, true, ServiceRestartCause.WakeReconnect)
        assertNull(pending.take(false))
        pending.request(true, false, ServiceRestartCause.NetworkChange)
        assertNull(pending.take(false))
        val request = pending.take(true)!!
        assertTrue(request.reset)
        assertTrue(request.reconnect)
        assertFalse(pending.hasPending)
        assertNull(pending.take(true))
    }

    @Test
    fun doesNotCreateUnrequestedRecovery() {
        val pending = PendingConnectionRecovery()
        pending.request(false, false, ServiceRestartCause.WakeReconnect)
        assertNull(pending.take(true))
    }
}
