package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecyclePolicyTest {
    @Test
    fun disconnectArmsStopWatchdog() {
        assertEquals(
            ServiceLifecyclePolicy.StopWatchdogAction.Arm,
            ServiceLifecyclePolicy.stopWatchdogAction(
                ServiceLifecyclePolicy.ConnectionIntent.Disconnect
            )
        )
    }

    @Test
    fun connectionRequestsCancelStopWatchdog() {
        listOf(
            ServiceLifecyclePolicy.ConnectionIntent.Start,
            ServiceLifecyclePolicy.ConnectionIntent.Reload,
            ServiceLifecyclePolicy.ConnectionIntent.Connected,
        ).forEach { connectionIntent ->
            assertEquals(
                ServiceLifecyclePolicy.StopWatchdogAction.Cancel,
                ServiceLifecyclePolicy.stopWatchdogAction(connectionIntent)
            )
        }
    }

    @Test
    fun reloadConnectedProfileUsesRestart() {
        assertEquals(
            ServiceLifecyclePolicy.ReloadAction.StopRestart,
            ServiceLifecyclePolicy.reloadAction(
                selectedProxy = 1L,
                stateStopped = false,
                stateCanStop = true,
                stateConnected = true,
                stateStopping = false,
                canReloadSelector = false,
            )
        )
    }

    @Test
    fun reloadStoppingProfileMarksPendingRestart() {
        assertEquals(
            ServiceLifecyclePolicy.ReloadAction.MarkPendingRestart,
            ServiceLifecyclePolicy.reloadAction(
                selectedProxy = 1L,
                stateStopped = false,
                stateCanStop = false,
                stateConnected = false,
                stateStopping = true,
                canReloadSelector = false,
            )
        )
    }

    @Test
    fun reloadEmptyProfileStopsWithoutRestart() {
        assertEquals(
            ServiceLifecyclePolicy.ReloadAction.StopEmpty,
            ServiceLifecyclePolicy.reloadAction(
                selectedProxy = 0L,
                stateStopped = false,
                stateCanStop = true,
                stateConnected = true,
                stateStopping = false,
                canReloadSelector = true,
            )
        )
    }

    @Test
    fun selectorReloadRequiresConnectedState() {
        assertEquals(
            ServiceLifecyclePolicy.ReloadAction.SelectorReload,
            ServiceLifecyclePolicy.reloadAction(
                selectedProxy = 1L,
                stateStopped = false,
                stateCanStop = true,
                stateConnected = true,
                stateStopping = false,
                canReloadSelector = true,
            )
        )

        assertEquals(
            ServiceLifecyclePolicy.ReloadAction.StopRestart,
            ServiceLifecyclePolicy.reloadAction(
                selectedProxy = 1L,
                stateStopped = false,
                stateCanStop = true,
                stateConnected = false,
                stateStopping = false,
                canReloadSelector = true,
            )
        )
    }

    @Test
    fun duplicateRestartStopWhileStoppingIsPreserved() {
        assertTrue(ServiceLifecyclePolicy.shouldPreserveRestartOnDuplicateStop(stateStopping = true, restart = true))
        assertFalse(ServiceLifecyclePolicy.shouldPreserveRestartOnDuplicateStop(stateStopping = true, restart = false))
        assertFalse(ServiceLifecyclePolicy.shouldPreserveRestartOnDuplicateStop(stateStopping = false, restart = true))
    }

    @Test
    fun latestProfileRejectsStaleRestartIntent() {
        assertFalse(
            ServiceLifecyclePolicy.shouldAcceptStart(
                desiredProfileId = 3L,
                requestedProfileId = 2L,
                isReload = false,
            )
        )
        assertTrue(
            ServiceLifecyclePolicy.shouldAcceptStart(
                desiredProfileId = 3L,
                requestedProfileId = 3L,
                isReload = false,
            )
        )
        assertTrue(
            ServiceLifecyclePolicy.shouldAcceptStart(
                desiredProfileId = 3L,
                requestedProfileId = 4L,
                isReload = true,
            )
        )
    }

    @Test
    fun startupMustStillMatchLatestProfile() {
        assertTrue(ServiceLifecyclePolicy.startupProfileStillDesired(3L, 3L))
        assertFalse(ServiceLifecyclePolicy.startupProfileStillDesired(2L, 3L))
        assertFalse(ServiceLifecyclePolicy.startupProfileStillDesired(2L, 0L))
    }

    @Test
    fun requestsIssuedBeforeDisconnectAreRejected() {
        assertFalse(ServiceLifecyclePolicy.shouldAcceptRequest(requestId = 10L, latestRequestId = 11L))
        assertTrue(ServiceLifecyclePolicy.shouldAcceptRequest(requestId = 11L, latestRequestId = 11L))
        assertTrue(ServiceLifecyclePolicy.shouldAcceptRequest(requestId = 12L, latestRequestId = 11L))
    }

    @Test
    fun explicitStopRecoveryStaysDisconnected() {
        assertFalse(
            ServiceLifecyclePolicy.shouldRestartAfterRecovery(
                restartRequested = false,
                pendingRestart = false,
            )
        )
    }

    @Test
    fun pendingProfileChangeRestartsAfterRecovery() {
        assertTrue(
            ServiceLifecyclePolicy.shouldRestartAfterRecovery(
                restartRequested = false,
                pendingRestart = true,
            )
        )
    }

    @Test
    fun explicitDisconnectOverridesQueuedRestart() {
        assertFalse(
            ServiceLifecyclePolicy.shouldRestartAfterStop(
                restartRequested = true,
                pendingRestart = true,
                desiredProfileId = 0L,
            )
        )
        assertTrue(
            ServiceLifecyclePolicy.shouldRestartAfterStop(
                restartRequested = false,
                pendingRestart = true,
                desiredProfileId = 3L,
            )
        )
    }

    @Test
    fun nativeCloseTimeoutUsesProcessRecovery() {
        assertEquals(
            ServiceLifecyclePolicy.StopCleanupAction.RecoverProcess,
            ServiceLifecyclePolicy.stopCleanupAction(
                shouldRestart = true,
                cleanupSucceeded = false,
                cleanupTimedOut = true,
            )
        )
    }

    @Test
    fun successfulRestartUsesSameProcessRestart() {
        assertEquals(
            ServiceLifecyclePolicy.StopCleanupAction.Restart,
            ServiceLifecyclePolicy.stopCleanupAction(
                shouldRestart = true,
                cleanupSucceeded = true,
                cleanupTimedOut = false,
            )
        )
    }

    @Test
    fun nonTimeoutCleanupErrorKeepsRequestedRestart() {
        assertEquals(
            ServiceLifecyclePolicy.StopCleanupAction.Restart,
            ServiceLifecyclePolicy.stopCleanupAction(
                shouldRestart = true,
                cleanupSucceeded = false,
                cleanupTimedOut = false,
            )
        )
    }

    @Test
    fun automaticRestartBackoffIsBounded() {
        assertEquals(1_000L, ServiceLifecyclePolicy.automaticRetryDelayMillis(1))
        assertEquals(2_000L, ServiceLifecyclePolicy.automaticRetryDelayMillis(2))
        assertEquals(5_000L, ServiceLifecyclePolicy.automaticRetryDelayMillis(3))
        assertEquals(10_000L, ServiceLifecyclePolicy.automaticRetryDelayMillis(4))
        assertEquals(30_000L, ServiceLifecyclePolicy.automaticRetryDelayMillis(5))
        assertEquals(30_000L, ServiceLifecyclePolicy.automaticRetryDelayMillis(Int.MAX_VALUE))
    }

    @Test
    fun onlyDifferentRunningProfileIsAProfileSwitch() {
        assertEquals(
            ServiceRestartCause.ProfileSwitch,
            ServiceLifecyclePolicy.profileReloadCause(1L, 2L),
        )
        assertEquals(
            ServiceRestartCause.Default,
            ServiceLifecyclePolicy.profileReloadCause(1L, 1L),
        )
        assertEquals(
            ServiceRestartCause.Default,
            ServiceLifecyclePolicy.profileReloadCause(null, 2L),
        )
    }

    @Test
    fun tunReuseIsLimitedToSuccessfulEligibleRestarts() {
        listOf(
            ServiceRestartCause.ProfileSwitch,
            ServiceRestartCause.NetworkChange,
            ServiceRestartCause.WakeReconnect,
        ).forEach { cause ->
            assertTrue(ServiceLifecyclePolicy.isTunReuseEligible(cause))
            assertTrue(
                ServiceLifecyclePolicy.shouldRetainTun(
                    restartCause = cause,
                    cleanupAction = ServiceLifecyclePolicy.StopCleanupAction.Restart,
                    cleanupSucceeded = true,
                )
            )
        }

        assertFalse(ServiceLifecyclePolicy.isTunReuseEligible(ServiceRestartCause.Default))

        assertFalse(
            ServiceLifecyclePolicy.shouldRetainTun(
                restartCause = ServiceRestartCause.Default,
                cleanupAction = ServiceLifecyclePolicy.StopCleanupAction.Restart,
                cleanupSucceeded = true,
            )
        )
        assertFalse(
            ServiceLifecyclePolicy.shouldRetainTun(
                restartCause = ServiceRestartCause.NetworkChange,
                cleanupAction = ServiceLifecyclePolicy.StopCleanupAction.Restart,
                cleanupSucceeded = false,
            )
        )
        assertFalse(
            ServiceLifecyclePolicy.shouldRetainTun(
                restartCause = ServiceRestartCause.ProfileSwitch,
                cleanupAction = ServiceLifecyclePolicy.StopCleanupAction.RecoverProcess,
                cleanupSucceeded = true,
            )
        )
    }
}
