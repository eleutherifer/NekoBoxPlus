package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.masterdns.deleteMasterDnsVPNProfileCache
import io.nekohasekai.sagernet.fmt.tailscale.deleteTailscaleProfileState
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.utils.ProfileCountryResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.sql.SQLException
import java.util.*


object ProfileManager {

    interface Listener {
        suspend fun onAdd(profile: ProxyEntity)
        suspend fun onUpdated(data: List<TrafficData>)
        suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean)
        suspend fun onRemoved(groupId: Long, profileId: Long)
    }

    interface RuleListener {
        suspend fun onAdd(rule: RuleEntity)
        suspend fun onUpdated(rule: RuleEntity)
        suspend fun onRemoved(ruleId: Long)
        suspend fun onCleared()
    }

    private val listeners = ArrayList<Listener>()
    private val ruleListeners = ArrayList<RuleListener>()

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    suspend fun ruleIterator(what: suspend RuleListener.() -> Unit) {
        val ruleListeners = synchronized(ruleListeners) {
            ruleListeners.toList()
        }
        for (listener in ruleListeners) {
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

    fun addListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.add(listener)
        }
    }

    fun removeListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.remove(listener)
        }
    }

    suspend fun createProfile(groupId: Long, bean: AbstractBean): ProxyEntity {
        val profile = createProfileWithoutDomainLookup(groupId, bean)
        ProfileCountryResolver.resolveAndUpdateDomain(profile.id)
        return getProfile(profile.id) ?: profile
    }

    suspend fun createProfiles(groupId: Long, beans: List<AbstractBean>): List<ProxyEntity> {
        val profiles = beans.map { createProfileWithoutDomainLookup(groupId, it) }
        val domainProfiles = ProfileCountryResolver.domainLookupIndexes(
            profiles.map { it.requireBean().serverAddress }
        ).map(profiles::get)
        coroutineScope {
            domainProfiles.chunked(5).forEach { chunk ->
                chunk.map { profile ->
                    async { ProfileCountryResolver.resolveAndUpdateDomain(profile.id) }
                }.awaitAll()
            }
        }
        return profiles.map { getProfile(it.id) ?: it }
    }

    private suspend fun createProfileWithoutDomainLookup(
        groupId: Long,
        bean: AbstractBean,
    ): ProxyEntity {
        bean.applyDefaultValues()

        val profile = ProxyEntity(groupId = groupId).apply {
            id = 0
            putBean(bean)
            userOrder = AppData.profiles.nextOrder(groupId) ?: 1
            ProfileCountryResolver.initialize(this)
        }
        profile.id = AppData.profiles.addProxy(profile)
        iterator { onAdd(profile) }
        return profile
    }

    suspend fun updateProfile(profile: ProxyEntity) {
        AppData.profiles.updateProxy(profile)
        iterator { onUpdated(profile, false) }
    }

    suspend fun updateEditedProfile(profile: ProxyEntity) {
        val previous = getProfile(profile.id)
        val previousBean = previous?.requireBean()
        val bean = profile.requireBean()
        val endpointChanged = previousBean == null ||
            previousBean.name != bean.name ||
            previousBean.serverAddress != bean.serverAddress ||
            previousBean.serverPort != bean.serverPort
        if (endpointChanged) ProfileCountryResolver.initialize(profile)
        updateProfile(profile)
        if (endpointChanged) ProfileCountryResolver.resolveAndUpdateDomain(profile.id)
    }

    suspend fun updateProfile(profiles: List<ProxyEntity>) {
        AppData.profiles.updateProxy(profiles)
        profiles.forEach {
            iterator { onUpdated(it, false) }
        }
    }

    suspend fun updateTraffic(profileId: Long, rx: Long, tx: Long) {
        AppData.profiles.updateTraffic(profileId, rx, tx)
    }

    suspend fun resetTraffic(profileIds: LongArray) {
        if (profileIds.isNotEmpty()) {
            AppData.profiles.resetTraffic(profileIds)
        }
    }

    suspend fun deleteProfile2(groupId: Long, profileId: Long) {
        val profile = getProfile(profileId)
        if (AppData.profiles.deleteById(profileId) == 0) return
        if (profile?.masterDnsVPNBean != null) {
            deleteMasterDnsVPNProfileCache(profileId)
        }
        if (profile?.tailscaleBean != null) deleteTailscaleProfileState(profileId)
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        GroupManager.postProfileCountChanged(groupId)
    }

    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        val profile = getProfile(profileId)
        if (AppData.profiles.deleteById(profileId) == 0) return
        if (profile?.masterDnsVPNBean != null) {
            deleteMasterDnsVPNProfileCache(profileId)
        }
        if (profile?.tailscaleBean != null) deleteTailscaleProfileState(profileId)
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        iterator { onRemoved(groupId, profileId) }
        if (AppData.profiles.countByGroup(groupId) > 1) {
            GroupManager.rearrange(groupId)
        }
    }

    fun getProfile(profileId: Long): ProxyEntity? {
        if (profileId == 0L) return null
        return try {
            AppData.profiles.getById(profileId)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            null
        }
    }

    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> {
        if (profileIds.isEmpty()) return listOf()
        return try {
            AppData.profiles.getEntities(profileIds)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            listOf()
        }
    }

    suspend fun transferProfiles(
        profileIds: List<Long>,
        targetGroupId: Long,
        operation: ProfileTransferOperation,
    ): ProfileTransferResult {
        if (profileIds.isEmpty()) {
            return ProfileTransferResult(0, 0, emptySet())
        }

        val changedProfiles = mutableListOf<ProxyEntity>()
        val sourceGroupIds = linkedSetOf<Long>()
        var skippedCount = 0

        AppData.transactions.run {
            val target = AppData.groups.getById(targetGroupId)
            if (target?.type != io.nekohasekai.sagernet.GroupType.BASIC) {
                throw ProfileTransferTargetUnavailableException()
            }

            val profilesById = AppData.profiles.getEntities(profileIds).associateBy { it.id }
            val profiles = profileIds.mapNotNull(profilesById::get)
            skippedCount += profileIds.size - profiles.size
            var targetOrder = AppData.profiles.nextOrder(targetGroupId) ?: 1L

            when (operation) {
                ProfileTransferOperation.COPY -> {
                    profiles.forEach { source ->
                        val copy = ProfileTransferPolicy.copyForTarget(
                            source,
                            targetGroupId,
                            targetOrder++,
                        )
                        copy.id = AppData.profiles.addProxy(copy)
                        changedProfiles.add(copy)
                    }
                }

                ProfileTransferOperation.MOVE -> {
                    profiles.forEach { source ->
                        val moved = ProfileTransferPolicy.moveForTarget(
                            source,
                            targetGroupId,
                            targetOrder,
                        )
                        if (moved == null) {
                            skippedCount++
                        } else {
                            targetOrder++
                            sourceGroupIds.add(source.groupId)
                            changedProfiles.add(moved)
                        }
                    }
                    if (changedProfiles.isNotEmpty()) {
                        AppData.profiles.updateProxy(changedProfiles)
                    }
                    sourceGroupIds.forEach { sourceGroupId ->
                        val remaining = AppData.profiles.getByGroup(sourceGroupId)
                        val reordered = remaining.mapIndexedNotNull { index, profile ->
                            val newOrder = (index + 1).toLong()
                            profile.takeIf { it.userOrder != newOrder }?.copy(userOrder = newOrder)
                        }
                        if (reordered.isNotEmpty()) {
                            AppData.profiles.updateProxy(reordered)
                        }
                    }
                }
            }
        }

        when (operation) {
            ProfileTransferOperation.COPY -> {
                changedProfiles.forEach { profile ->
                    iterator { onAdd(profile) }
                }
            }

            ProfileTransferOperation.MOVE -> {
                changedProfiles.forEach { profile ->
                    iterator { onUpdated(profile, false) }
                }
                (sourceGroupIds + targetGroupId).forEach { groupId ->
                    GroupManager.postUpdate(groupId)
                }
            }
        }

        val affectedGroupIds = when {
            changedProfiles.isEmpty() -> emptySet()
            operation == ProfileTransferOperation.COPY -> setOf(targetGroupId)
            else -> sourceGroupIds + targetGroupId
        }
        return ProfileTransferResult(changedProfiles.size, skippedCount, affectedGroupIds)
    }

    // postUpdate: post to listeners, don't change the DB

    suspend fun postUpdate(profileId: Long, noTraffic: Boolean = false) {
        postUpdate(getProfile(profileId) ?: return, noTraffic)
    }

    suspend fun postUpdate(profile: ProxyEntity, noTraffic: Boolean = false) {
        iterator { onUpdated(profile, noTraffic) }
    }

    suspend fun postUpdate(data: List<TrafficData>) {
        if (data.isEmpty()) return
        iterator { onUpdated(data) }
    }

    suspend fun createRule(rule: RuleEntity, post: Boolean = true): RuleEntity {
        rule.userOrder = AppData.rules.nextOrder() ?: 1
        rule.id = AppData.rules.createRule(rule)
        if (post) {
            ruleIterator { onAdd(rule) }
        }
        return rule
    }

    suspend fun duplicateRuleAfter(rule: RuleEntity): RuleEntity {
        lateinit var duplicate: RuleEntity
        AppData.transactions.run {
            val rulesDao = AppData.rules
            val rules = rulesDao.allRules().toMutableList()
            val sourceIndex = rules.indexOfFirst { it.id == rule.id }
            if (sourceIndex == -1) {
                duplicate = rule.copy(id = 0L, userOrder = rulesDao.nextOrder() ?: 1)
                duplicate.id = rulesDao.createRule(duplicate)
                return@run
            }

            duplicate = rules[sourceIndex].copy(id = 0L, userOrder = 0L)
            rules.add(sourceIndex + 1, duplicate)

            val updated = ArrayList<RuleEntity>()
            rules.forEachIndexed { index, item ->
                val newOrder = (index + 1).toLong()
                if (item.id == 0L) {
                    duplicate.userOrder = newOrder
                } else if (item.userOrder != newOrder) {
                    item.userOrder = newOrder
                    updated.add(item)
                }
            }
            if (updated.isNotEmpty()) {
                rulesDao.updateRules(updated)
            }
            duplicate.id = rulesDao.createRule(duplicate)
        }
        return duplicate
    }

    suspend fun updateRule(rule: RuleEntity) {
        AppData.rules.updateRule(rule)
        ruleIterator { onUpdated(rule) }
    }

    suspend fun deleteRule(ruleId: Long) {
        AppData.rules.deleteById(ruleId)
        ruleIterator { onRemoved(ruleId) }
    }

    suspend fun deleteRules(rules: List<RuleEntity>) {
        AppData.rules.deleteRules(rules)
        ruleIterator {
            rules.forEach {
                onRemoved(it.id)
            }
        }
    }

    suspend fun replaceRules(rules: List<RuleEntity>) {
        AppData.transactions.run {
            AppData.rules.reset()
            rules.forEachIndexed { index, rule ->
                rule.id = 0L
                rule.userOrder = (index + 1).toLong()
                rule.id = AppData.rules.createRule(rule)
            }
        }
        DataStore.rulesFirstCreate = true
        ruleIterator { onCleared() }
        rules.forEach { rule -> ruleIterator { onAdd(rule) } }
    }

    suspend fun getRules(): List<RuleEntity> {
        var rules = AppData.rules.allRules()
        if (rules.isEmpty() && !DataStore.rulesFirstCreate) {
            DataStore.rulesFirstCreate = true
            createRule(
                RuleEntity(
                    name = app.getString(R.string.route_opt_bypass_bittorrent),
                    protocol = "bittorrent",
                    outbound = -1,
                    enabled = true
                )
            )
            createRule(
                RuleEntity(
                    name = app.getString(R.string.route_opt_block_ads),
                    domains = "geosite:category-ads-all",
                    outbound = -2,
                    enabled = true
                )
            )

            val countryRules = getCountryRulesForFirstRun()

            for (rule in countryRules) {
                rule.enabled = true
                createRule(rule, false)
            }

            rules = AppData.rules.allRules()
        }
        return rules
    }

    private suspend fun getCountryRulesForFirstRun(): List<RuleEntity> {
        return when (DataStore.firstRunRoutingRegion.takeIf { it.isNotBlank() } ?: Locale.getDefault().country.lowercase()) {
            "cn" -> getChinaRules()
            "ir" -> getIranRules()
            "ru" -> getRussiaRules()
            else -> listOf(getChinaRules(), getIranRules(), getRussiaRules()).flatten()
        }
    }

    suspend fun getChinaRules(): List<RuleEntity> {
        val displayCountry = "中国"

        return listOf(
            RuleEntity(
                name = app.getString(R.string.route_play_store, displayCountry),
                domains = listOf(
                    "regexp:\\.googleapis.cn",
                    "regexp:\\.xn--ngstr-lra8j.com",
                    "regexp:\\.xn--ngstr-cn-8za9o.com"
                ).joinToString("\n"),
            ),
            RuleEntity(
                name = app.getString(R.string.route_bypass_domain, displayCountry),
                domains = "geosite:cn",
                outbound = -1
            ),
            RuleEntity(
                name = app.getString(R.string.route_bypass_ip, displayCountry),
                ip = "geoip:cn",
                outbound = -1
            )
        )
    }

    suspend fun getIranRules(): List<RuleEntity> {
        val displayCountry = "Iran"

        return listOf(
            RuleEntity(
                name = app.getString(R.string.route_bypass_domain, displayCountry),
                domains = "geosite:ir",
                outbound = -1
            ),
            RuleEntity(
                name = app.getString(R.string.route_bypass_ip, displayCountry),
                ip = "geoip:ir",
                outbound = -1
            )
        )
    }

    suspend fun getRussiaRules(): List<RuleEntity> {
        val displayCountry = "Russia"

        return listOf(
            RuleEntity(
                name = app.getString(R.string.route_bypass_domain, displayCountry),
                domains = listOf(
                    "geosite:category-ru",
                    "geosite:category-gov-ru",
                    "regexp:\\.ru$",
                    "regexp:\\.su$",
                    "regexp:\\.рф$",
                    "regexp:\\.by$",
                    "regexp:\\.ru.com$",
                    "regexp:\\.ru.net$",
                    "regexp:\\.moscow$",
                    "regexp:\\.xn--p1ai$",
                    "regexp:\\.xn--p1acf$",
                    "regexp:\\.xn--80aswg$",
                    "regexp:\\.xn--c1avg$",
                    "regexp:\\.xn--80asehdb$",
                    "regexp:\\.xn--d1acj3b$",
                    "regexp:\\.xn--90ais$"
                ).joinToString("\n"),
                outbound = -1
            ),
            RuleEntity(
                name = app.getString(R.string.route_bypass_ip, displayCountry),
                ip = "geoip:ru",
                outbound = -1
            )
        )
    }
}
