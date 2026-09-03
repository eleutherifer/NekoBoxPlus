package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyGroup

object GroupTabSelectionPolicy {

    fun selectedIndex(groupIds: List<Long>, selectedGroupId: Long): Int {
        if (selectedGroupId <= 0L) return -1
        return groupIds.indexOf(selectedGroupId)
    }

    fun navigatorGroups(groups: List<ProxyGroup>, visibleGroupIds: LongArray): List<ProxyGroup> {
        val groupsById = groups.associateBy { it.id }
        return visibleGroupIds.map { groupsById[it] }.filterNotNull()
    }
}
