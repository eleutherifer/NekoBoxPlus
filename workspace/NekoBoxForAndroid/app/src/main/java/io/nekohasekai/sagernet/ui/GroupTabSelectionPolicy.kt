package io.nekohasekai.sagernet.ui

object GroupTabSelectionPolicy {

    fun selectedIndex(groupIds: List<Long>, selectedGroupId: Long): Int {
        if (selectedGroupId <= 0L) return -1
        return groupIds.indexOf(selectedGroupId)
    }
}
