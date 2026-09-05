package io.nekohasekai.sagernet.bg

enum class ServiceRestartOrigin {
    Manual,
    Automatic,
}

enum class ServiceRestartCause {
    Default,
    ProfileSwitch,
    NetworkChange,
    WakeReconnect,
}

internal object ServiceLifecyclePolicy {
    enum class ConnectionIntent {
        Start,
        Reload,
        Disconnect,
        Connected,
    }

    enum class StopWatchdogAction {
        Arm,
        Cancel,
    }

    enum class ReloadAction {
        StopEmpty,
        SelectorReload,
        Start,
        StopRestart,
        MarkPendingRestart,
        Ignore,
    }

    enum class StopCleanupAction {
        Stop,
        Restart,
        RecoverProcess,
    }

    fun stopWatchdogAction(connectionIntent: ConnectionIntent): StopWatchdogAction {
        return when (connectionIntent) {
            ConnectionIntent.Disconnect -> StopWatchdogAction.Arm
            ConnectionIntent.Start,
            ConnectionIntent.Reload,
            ConnectionIntent.Connected -> StopWatchdogAction.Cancel
        }
    }

    fun reloadAction(
        selectedProxy: Long,
        stateStopped: Boolean,
        stateCanStop: Boolean,
        stateConnected: Boolean,
        stateStopping: Boolean,
        canReloadSelector: Boolean,
    ): ReloadAction {
        if (selectedProxy == 0L) return ReloadAction.StopEmpty
        if (stateConnected && canReloadSelector) return ReloadAction.SelectorReload
        return when {
            stateStopped -> ReloadAction.Start
            stateCanStop -> ReloadAction.StopRestart
            stateStopping -> ReloadAction.MarkPendingRestart
            else -> ReloadAction.Ignore
        }
    }

    fun shouldPreserveRestartOnDuplicateStop(stateStopping: Boolean, restart: Boolean): Boolean {
        return stateStopping && restart
    }

    fun shouldAcceptStart(
        desiredProfileId: Long,
        requestedProfileId: Long,
        isReload: Boolean,
    ): Boolean {
        return isReload || desiredProfileId == 0L || requestedProfileId == desiredProfileId
    }

    fun startupProfileStillDesired(startedProfileId: Long, desiredProfileId: Long): Boolean {
        return startedProfileId == desiredProfileId
    }

    fun shouldAcceptRequest(requestId: Long, latestRequestId: Long): Boolean {
        return requestId >= latestRequestId
    }

    fun shouldRestartAfterRecovery(restartRequested: Boolean, pendingRestart: Boolean): Boolean {
        return restartRequested || pendingRestart
    }

    fun shouldRestartAfterStop(
        restartRequested: Boolean,
        pendingRestart: Boolean,
        desiredProfileId: Long,
    ): Boolean {
        return (restartRequested || pendingRestart) && desiredProfileId != 0L
    }

    fun profileReloadCause(runningProfileId: Long?, selectedProfileId: Long): ServiceRestartCause {
        return if (runningProfileId != null && runningProfileId != selectedProfileId) {
            ServiceRestartCause.ProfileSwitch
        } else {
            ServiceRestartCause.Default
        }
    }

    fun shouldRetainTun(
        restartCause: ServiceRestartCause,
        cleanupAction: StopCleanupAction,
        cleanupSucceeded: Boolean,
    ): Boolean {
        return cleanupAction == StopCleanupAction.Restart && cleanupSucceeded &&
            isTunReuseEligible(restartCause)
    }

    fun isTunReuseEligible(restartCause: ServiceRestartCause): Boolean =
        restartCause == ServiceRestartCause.ProfileSwitch ||
            restartCause == ServiceRestartCause.NetworkChange ||
            restartCause == ServiceRestartCause.WakeReconnect

    fun stopCleanupAction(
        shouldRestart: Boolean,
        cleanupSucceeded: Boolean,
        cleanupTimedOut: Boolean,
    ): StopCleanupAction {
        if (cleanupTimedOut) return StopCleanupAction.RecoverProcess
        return if (shouldRestart) {
            StopCleanupAction.Restart
        } else {
            StopCleanupAction.Stop
        }
    }

    fun automaticRetryDelayMillis(attempt: Int): Long {
        return when (attempt.coerceAtLeast(1)) {
            1 -> 1_000L
            2 -> 2_000L
            3 -> 5_000L
            4 -> 10_000L
            else -> 30_000L
        }
    }
}
