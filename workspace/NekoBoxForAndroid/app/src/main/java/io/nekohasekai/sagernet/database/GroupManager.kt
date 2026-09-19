package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.routing.SubscriptionRoutingRepository

object GroupManager {

    enum class ReloadReason {
        General,
        Manual,
        UrlTest,
    }

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)

        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
        suspend fun groupReloaded(groupId: Long, reason: ReloadReason) = groupUpdated(groupId)
        suspend fun groupProfileCountChanged(groupId: Long) = Unit
    }

    interface Interface {
        suspend fun confirm(message: String): Boolean
        suspend fun alert(message: String)
        suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean
        )

        suspend fun onUpdateFailure(group: ProxyGroup, message: String)
    }

    private val listeners = ArrayList<Listener>()
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    suspend fun clearGroup(groupId: Long) {
        DataStore.selectedProxy = 0L
        SagerDatabase.proxyDao.deleteAll(groupId)
        iterator { groupUpdated(groupId) }
    }

    fun rearrange(groupId: Long) {
        val entities = SagerDatabase.proxyDao.getByGroup(groupId)
        for (index in entities.indices) {
            entities[index].userOrder = (index + 1).toLong()
        }
        SagerDatabase.proxyDao.updateProxy(entities)
    }

    suspend fun postUpdate(group: ProxyGroup) {
        iterator { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(SagerDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postReload(groupId: Long, reason: ReloadReason = ReloadReason.General) {
        iterator { groupReloaded(groupId, reason) }
    }

    suspend fun postProfileCountChanged(groupId: Long) {
        iterator { groupProfileCountChanged(groupId) }
    }

    suspend fun createGroup(group: ProxyGroup): ProxyGroup {
        group.userOrder = SagerDatabase.groupDao.nextOrder() ?: 1
        group.id = SagerDatabase.groupDao.createGroup(group.applyDefaultValues())
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup) {
        SagerDatabase.groupDao.updateGroup(group)
        iterator { groupUpdated(group) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(groupId: Long) {
        SubscriptionRoutingRepository.deleteFiles(groupId)
        SagerDatabase.groupDao.deleteById(groupId)
        SagerDatabase.proxyDao.deleteByGroup(groupId)
        iterator { groupRemoved(groupId) }
        ensureFallbackGroup()
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) {
        group.forEach { SubscriptionRoutingRepository.deleteFiles(it.id) }
        SagerDatabase.groupDao.deleteGroup(group)
        SagerDatabase.proxyDao.deleteByGroup(group.map { it.id }.toLongArray())
        for (proxyGroup in group) iterator { groupRemoved(proxyGroup.id) }
        ensureFallbackGroup()
        SubscriptionUpdater.reconfigureUpdater()
    }

    private suspend fun ensureFallbackGroup() {
        val groups = SagerDatabase.groupDao.allGroups()
        if (groups.isEmpty()) {
            val group = ProxyGroup(ungrouped = true)
            group.id = SagerDatabase.groupDao.createGroup(group)
            DataStore.selectedGroup = group.id
            iterator { groupAdd(group) }
            return
        }
        if (groups.none { it.id == DataStore.selectedGroup }) {
            DataStore.selectedGroup = groups[0].id
        }
    }

    fun canDelete(groupId: Long): Boolean {
        if (groupId <= 0L) return false
        return SagerDatabase.groupDao.allGroups().size > 1
    }

}
