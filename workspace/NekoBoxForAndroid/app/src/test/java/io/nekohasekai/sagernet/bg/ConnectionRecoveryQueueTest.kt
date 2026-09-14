package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionRecoveryQueueTest {
    @Test
    fun wakeRecoveryWaitsForNetworkAndUrlTest() {
        val testing = AtomicBoolean(true)
        val pauses = LinkedBlockingQueue<Boolean>()
        val resets = LinkedBlockingQueue<ServiceRestartCause>()
        val recovery = ConnectionRecoveryQueue(
            urlTestRunning = testing::get,
            pause = { pauses.add(it) },
            recover = { _, _, cause -> resets.add(cause) },
            log = {},
        )
        try {
            recovery.ready()
            assertEquals(false, pauses.poll(2, TimeUnit.SECONDS))
            recovery.idle(true, reconnect = false, reset = true)
            assertEquals(true, pauses.poll(2, TimeUnit.SECONDS))
            recovery.idle(false, reconnect = false, reset = true)
            assertEquals(false, pauses.poll(2, TimeUnit.SECONDS))
            recovery.urlTestFinished()
            assertNull(resets.poll(100, TimeUnit.MILLISECONDS))
            recovery.network(true)
            recovery.urlTestFinished()
            assertNull(resets.poll(100, TimeUnit.MILLISECONDS))
            testing.set(false)
            recovery.urlTestFinished()
            assertEquals(ServiceRestartCause.WakeReconnect, resets.poll(2, TimeUnit.SECONDS))
            recovery.urlTestFinished()
            assertNull(resets.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            recovery.close()
        }
    }

    @Test
    fun startupWhileIdleAndExplicitOptOutDoNotCreateReset() {
        val pauses = LinkedBlockingQueue<Boolean>()
        val resets = LinkedBlockingQueue<Boolean>()
        val recovery = ConnectionRecoveryQueue(
            urlTestRunning = { false },
            pause = { pauses.add(it) },
            recover = { _, reset, _ -> resets.add(reset) },
            log = {},
        )
        try {
            recovery.idle(true, reconnect = false, reset = false)
            recovery.network(true)
            recovery.ready()
            assertEquals(true, pauses.poll(2, TimeUnit.SECONDS))
            recovery.idle(false, reconnect = false, reset = false)
            assertEquals(false, pauses.poll(2, TimeUnit.SECONDS))
            recovery.urlTestFinished()
            assertNull(resets.poll(100, TimeUnit.MILLISECONDS))
            recovery.close()
            recovery.request(false, true, ServiceRestartCause.NetworkChange)
            recovery.urlTestFinished()
            assertNull(resets.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            recovery.close()
        }
    }
}
