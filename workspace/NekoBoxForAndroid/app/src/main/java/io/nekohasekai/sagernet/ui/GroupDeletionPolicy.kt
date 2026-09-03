package io.nekohasekai.sagernet.ui

object GroupDeletionPolicy {

    fun canSwipeDelete(
        isUngrouped: Boolean,
        canDeleteGroup: Boolean,
        isUpdating: Boolean,
    ): Boolean {
        if (isUpdating) return false
        return canDeleteGroup
    }
}
