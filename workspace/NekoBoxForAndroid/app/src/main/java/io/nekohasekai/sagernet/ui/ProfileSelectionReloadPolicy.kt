package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.BaseService

internal object ProfileSelectionReloadPolicy {
    fun shouldReload(selectionChanged: Boolean, serviceState: BaseService.State): Boolean {
        if (!selectionChanged) return false
        return when (serviceState) {
            BaseService.State.Connecting,
            BaseService.State.Connected,
            BaseService.State.Stopping -> true
            BaseService.State.Idle,
            BaseService.State.Stopped -> false
        }
    }
}
