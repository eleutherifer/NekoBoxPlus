package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.ProxyEntity

internal fun mergeProfileRefresh(
    profile: ProxyEntity,
    cached: ProxyEntity?,
    preserveTraffic: Boolean,
): ProxyEntity {
    if (!preserveTraffic || cached == null) return profile
    return profile.copy(tx = cached.tx, rx = cached.rx).also {
        it.dirty = profile.dirty
    }
}

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
