package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType

internal object ImportGroupSelectionPolicy {

    data class Group(
        val id: Long,
        val type: Int,
        val ungrouped: Boolean,
    )

    fun existingTargetGroupId(current: Group, groups: List<Group>): Long? {
        if (current.type == GroupType.BASIC) return current.id
        return groups.find { it.ungrouped && it.type == GroupType.BASIC }?.id
    }
}
