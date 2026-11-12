package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ConnectionRecoveryQueueTest {
    @Test
    fun networkHandoffCoalescesReconnectAndWaitsForAvailableNetwork() {
        val recovered = LinkedBlockingQueue<Pair<Boolean, Boolean>>()
        val recovery = ConnectionRecoveryQueue(
            urlTestRunning = { false },
            pause = {},
            recover = { reconnect, reset, _ -> recovered.add(reconnect to reset) },
            log = {},
        )
        try {
            recovery.ready()
            recovery.network(false)
            recovery.request(true, false, ServiceRestartCause.NetworkChange)
            recovery.urlTestFinished()
            assertNull(recovered.poll(100, TimeUnit.MILLISECONDS))
            recovery.network(true)
            recovery.request(true, false, ServiceRestartCause.NetworkChange)
            recovery.urlTestFinished()
            assertEquals(true to false, recovered.poll(2, TimeUnit.SECONDS))
            recovery.urlTestFinished()
            assertNull(recovered.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            recovery.close()
        }
    }

    @Test
    fun disabledCoreSleepKeepsCoreAwakeOnIdleStartupAndPreservesWakeRecovery() {
        val pauses = LinkedBlockingQueue<Boolean>()
        val resets = LinkedBlockingQueue<ServiceRestartCause>()
        val recovery = ConnectionRecoveryQueue(
            urlTestRunning = { false },
            pause = { pauses.add(it) },
            recover = { _, _, cause -> resets.add(cause) },
            log = {},
            disableCoreSleep = true,
        )
        try {
            recovery.idle(true, reconnect = false, reset = true)
            recovery.network(true)
            recovery.ready()
            assertEquals(false, pauses.poll(2, TimeUnit.SECONDS))
            recovery.urlTestFinished()
            assertNull(resets.poll(100, TimeUnit.MILLISECONDS))

            recovery.idle(false, reconnect = false, reset = true)
            assertEquals(false, pauses.poll(2, TimeUnit.SECONDS))
            recovery.urlTestFinished()
            assertEquals(ServiceRestartCause.WakeReconnect, resets.poll(2, TimeUnit.SECONDS))
        } finally {
            recovery.close()
        }
    }

    @Test
    fun disabledCoreSleepKeepsCoreAwakeAcrossIdleCyclesWithoutEnablingRecovery() {
        val pauses = LinkedBlockingQueue<Boolean>()
        val resets = LinkedBlockingQueue<Boolean>()
        val recovery = ConnectionRecoveryQueue(
            urlTestRunning = { false },
            pause = { pauses.add(it) },
            recover = { _, reset, _ -> resets.add(reset) },
            log = {},
            disableCoreSleep = true,
        )
        try {
            recovery.network(true)
            recovery.ready()
            assertEquals(false, pauses.poll(2, TimeUnit.SECONDS))
            repeat(2) {
                recovery.idle(true, reconnect = false, reset = false)
                assertEquals(false, pauses.poll(2, TimeUnit.SECONDS))
                recovery.idle(false, reconnect = false, reset = false)
                assertEquals(false, pauses.poll(2, TimeUnit.SECONDS))
            }
            recovery.urlTestFinished()
            assertNull(resets.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            recovery.close()
        }
    }

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

    @Test
    fun screenOnRecoversAtTwoMinuteThreshold() {
        val now = AtomicLong(1_000L)
        val resets = LinkedBlockingQueue<ServiceRestartCause>()
        val recovery = ConnectionRecoveryQueue(
            urlTestRunning = { false },
            pause = {},
            recover = { _, _, cause -> resets.add(cause) },
            log = {},
            elapsedRealtime = now::get,
        )
        try {
            recovery.network(true)
            recovery.ready()
            recovery.screen(on = false, reconnect = false, reset = true)
            now.addAndGet(120_000L)
            recovery.screen(on = true, reconnect = false, reset = true)
            recovery.urlTestFinished()

            assertEquals(ServiceRestartCause.WakeReconnect, resets.poll(2, TimeUnit.SECONDS))
        } finally {
            recovery.close()
        }
    }

    @Test
    fun earlyScreenOnClearsRecordedTimestampAndNextScreenOffStartsFreshInterval() {
        val now = AtomicLong(1_000L)
        val resets = LinkedBlockingQueue<ServiceRestartCause>()
        val recovery = ConnectionRecoveryQueue(
            urlTestRunning = { false },
            pause = {},
            recover = { _, _, cause -> resets.add(cause) },
            log = {},
            elapsedRealtime = now::get,
        )
        try {
            recovery.network(true)
            recovery.ready()
            recovery.screen(on = false, reconnect = false, reset = true)
            now.addAndGet(119_999L)
            recovery.screen(on = true, reconnect = false, reset = true)
            now.addAndGet(120_000L)
            recovery.screen(on = true, reconnect = false, reset = true)
            recovery.urlTestFinished()

            assertNull(resets.poll(200, TimeUnit.MILLISECONDS))

            recovery.screen(on = false, reconnect = false, reset = true)
            now.addAndGet(120_000L)
            recovery.screen(on = true, reconnect = false, reset = true)
            recovery.urlTestFinished()
            assertEquals(ServiceRestartCause.WakeReconnect, resets.poll(2, TimeUnit.SECONDS))
        } finally {
            recovery.close()
        }
    }

    @Test
    fun deviceIdleExitAndScreenOnCoalesce() {
        val now = AtomicLong(1_000L)
        val resets = LinkedBlockingQueue<ServiceRestartCause>()
        val recovery = ConnectionRecoveryQueue(
            urlTestRunning = { false },
            pause = {},
            recover = { _, _, cause -> resets.add(cause) },
            log = {},
            elapsedRealtime = now::get,
        )
        try {
            recovery.network(true)
            recovery.ready()
            recovery.screen(on = false, reconnect = false, reset = true)
            recovery.idle(idle = true, reconnect = false, reset = true)
            now.addAndGet(120_000L)
            recovery.idle(idle = false, reconnect = false, reset = true)
            recovery.screen(on = true, reconnect = false, reset = true)
            recovery.urlTestFinished()

            assertEquals(ServiceRestartCause.WakeReconnect, resets.poll(2, TimeUnit.SECONDS))
            recovery.urlTestFinished()
            assertNull(resets.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            recovery.close()
        }
    }
}
