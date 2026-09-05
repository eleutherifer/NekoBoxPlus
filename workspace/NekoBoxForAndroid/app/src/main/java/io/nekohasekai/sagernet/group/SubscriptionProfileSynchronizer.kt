package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.app.AppGraph
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.ProfileCountryResolver

internal data class SubscriptionSyncResult(
    val changed: Int,
    val added: List<String>,
    val updated: Map<String, String>,
    val deleted: List<String>,
)

internal object SubscriptionProfileSynchronizer {
    fun synchronize(
        group: ProxyGroup,
        profiles: List<AbstractBean>,
        applyUpdateOrder: Boolean,
    ): SubscriptionSyncResult = AppData.transactions.run {
        val existing = AppData.profiles.getByGroup(group.id)
        val byName = profiles.associateBy(AbstractBean::displayName)
        val toDelete = ArrayList<ProxyEntity>()
        val toReplace = existing.mapNotNull { entity ->
            val name = entity.displayName()
            if (name in byName) {
                name to entity
            } else {
                toDelete += entity
                null
            }
        }.toMap()

        Logs.d("toDelete profiles: ${toDelete.size}")
        Logs.d("toReplace profiles: ${toReplace.size}")

        val toUpdate = ArrayList<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = linkedMapOf<String, String>()
        val deleted = toDelete.map(ProxyEntity::displayName)
        var userOrder = 1L
        var appendedUserOrder = AppData.profiles.nextOrder(group.id) ?: 1L
        var changed = toDelete.size
        val originOrderIds = mutableListOf<Long>()

        for ((name, bean) in byName) {
            val entity = toReplace[name]
            if (entity != null) {
                originOrderIds += entity.id
                val existingBean = entity.requireBean()
                bean.customOutboundJson = existingBean.customOutboundJson
                bean.customConfigJson = existingBean.customConfigJson
                preserveMuxSettings(existingBean, bean)
                when {
                    existingBean != bean -> {
                        changed++
                        entity.putBean(bean)
                        toUpdate += entity
                        updated[entity.displayName()] = name
                        Logs.d("Updated profile: $name")
                    }

                    applyUpdateOrder && entity.userOrder != userOrder -> {
                        entity.putBean(bean)
                        entity.userOrder = userOrder
                        toUpdate += entity
                        Logs.d("Reordered profile: $name")
                    }

                    else -> Logs.d("Ignored profile: $name")
                }
            } else {
                changed++
                val profileId = AppData.profiles.addProxy(
                    ProxyEntity(
                        groupId = group.id,
                        userOrder = if (applyUpdateOrder) userOrder else appendedUserOrder++,
                    ).apply {
                        putBean(bean)
                        ProfileCountryResolver.initialize(this)
                    },
                )
                originOrderIds += profileId
                added += name
                Logs.d("Inserted profile: $name")
            }
            userOrder++
        }

        AppData.profiles.updateProxy(toUpdate).also { Logs.d("Updated profiles: $it") }
        AppData.profiles.deleteProxy(toDelete).also { Logs.d("Deleted profiles: $it") }

        val existingCount = AppData.profiles.countByGroup(group.id).toInt()
        if (existingCount != profiles.size) {
            Logs.e("Exist profiles: $existingCount, new profiles: ${profiles.size}")
        }

        group.subscription!!.lastUpdated = (AppGraph.clock.currentTimeMillis() / 1000L).toInt()
        group.setOriginOrderIds(originOrderIds)
        AppData.groups.updateGroup(group)
        SubscriptionSyncResult(changed, added, updated, deleted)
    }
}
