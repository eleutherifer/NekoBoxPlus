package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType

enum class ProfileTransferOperation {
    COPY,
    MOVE,
}

data class ProfileTransferResult(
    val changedCount: Int,
    val skippedCount: Int,
    val affectedGroupIds: Set<Long>,
)

class ProfileTransferTargetUnavailableException : IllegalStateException()

internal object ProfileTransferPolicy {

    fun eligibleGroups(groups: List<ProxyGroup>) =
        groups.filter { it.type == GroupType.BASIC }

    fun copyForTarget(source: ProxyEntity, targetGroupId: Long, targetOrder: Long) =
        source.copy(id = 0L, groupId = targetGroupId, userOrder = targetOrder)

    fun moveForTarget(
        source: ProxyEntity,
        targetGroupId: Long,
        targetOrder: Long,
    ): ProxyEntity? {
        if (source.groupId == targetGroupId) return null
        return source.copy(groupId = targetGroupId, userOrder = targetOrder)
    }
}
