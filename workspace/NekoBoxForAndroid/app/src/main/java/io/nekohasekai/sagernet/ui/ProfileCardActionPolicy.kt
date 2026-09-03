package io.nekohasekai.sagernet.ui

internal object ProfileCardActionPolicy {
    fun shouldShowUrlTest(
        selectMode: Boolean,
        notificationSwitchPopup: Boolean,
        batchSelection: Boolean,
    ): Boolean = !batchSelection && (!selectMode || notificationSwitchPopup)
}
