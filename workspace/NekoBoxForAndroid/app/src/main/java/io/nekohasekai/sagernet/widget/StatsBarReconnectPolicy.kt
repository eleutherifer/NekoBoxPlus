package io.nekohasekai.sagernet.widget

import io.nekohasekai.sagernet.bg.BaseService

internal class StatsBarReconnectPolicy {
    private var reconnectInProgress = false
    private var reconnectAttemptStarted = false

    fun shouldPreservePosition(
        state: BaseService.State,
        previousState: BaseService.State,
        profileChanged: Boolean,
    ): Boolean {
        if (
            previousState == BaseService.State.Connected &&
            state == BaseService.State.Stopping &&
            profileChanged
        ) {
            reconnectInProgress = true
            reconnectAttemptStarted = false
        }
        if (reconnectInProgress && state == BaseService.State.Connecting) {
            reconnectAttemptStarted = true
        }

        val preservePosition =
            reconnectInProgress &&
                when (state) {
                    BaseService.State.Stopping,
                    BaseService.State.Connecting -> true
                    BaseService.State.Stopped,
                    BaseService.State.Idle -> !reconnectAttemptStarted
                    BaseService.State.Connected -> false
                }

        if (
            reconnectInProgress &&
            (state == BaseService.State.Connected ||
                ((state == BaseService.State.Stopped || state == BaseService.State.Idle) &&
                    reconnectAttemptStarted))
        ) {
            reconnectInProgress = false
            reconnectAttemptStarted = false
        }
        return preservePosition
    }
}
