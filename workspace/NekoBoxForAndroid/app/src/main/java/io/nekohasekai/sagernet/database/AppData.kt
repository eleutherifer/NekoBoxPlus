package io.nekohasekai.sagernet.database

import java.util.concurrent.Callable

interface ProfileDataSource {
    fun getAll(): List<ProxyEntity>
    fun getAllIds(): List<Long>
    fun getIdsByGroup(groupId: Long): List<Long>
    fun getByGroup(groupId: Long): List<ProxyEntity>
    fun getEntities(profileIds: List<Long>): List<ProxyEntity>
    fun countByGroup(groupId: Long): Long
    fun nextOrder(groupId: Long): Long?
    fun getById(profileId: Long): ProxyEntity?
    fun deleteById(profileId: Long): Int
    fun deleteByGroup(groupId: Long)
    fun deleteByGroup(groupIds: LongArray)
    fun deleteProxy(profile: ProxyEntity): Int
    fun deleteProxy(profiles: List<ProxyEntity>): Int
    fun updateProxy(profile: ProxyEntity): Int
    fun updateProxy(profiles: List<ProxyEntity>): Int
    fun updateTraffic(profileId: Long, rx: Long, tx: Long): Int
    fun resetTraffic(profileIds: LongArray): Int
    fun clearTestResults(): Int
    fun addProxy(profile: ProxyEntity): Long
    fun insert(profiles: List<ProxyEntity>)
    fun deleteAll(groupId: Long): Int
    fun reset()
}

interface GroupDataSource {
    fun allGroups(): List<ProxyGroup>
    suspend fun subscriptions(): List<ProxyGroup>
    fun nextOrder(): Long?
    fun getById(groupId: Long): ProxyGroup?
    fun deleteById(groupId: Long): Int
    fun deleteGroup(group: ProxyGroup)
    fun deleteGroup(groups: List<ProxyGroup>)
    fun createGroup(group: ProxyGroup): Long
    fun updateGroup(group: ProxyGroup)
    fun reset()
    fun insert(groups: List<ProxyGroup>)
}

interface RuleDataSource {
    fun checkVpnNeeded(): List<RuleEntity>
    fun allRules(): List<RuleEntity>
    fun enabledRules(enabled: Boolean = true): List<RuleEntity>
    fun nextOrder(): Long?
    fun getById(ruleId: Long): RuleEntity?
    fun dnsRulesUsingServer(serverTag: String): List<RuleEntity>
    fun deleteById(ruleId: Long): Int
    fun deleteRule(rule: RuleEntity)
    fun deleteRules(rules: List<RuleEntity>)
    fun createRule(rule: RuleEntity): Long
    fun updateRule(rule: RuleEntity)
    fun updateRules(rules: List<RuleEntity>)
    fun reset()
    fun insert(rules: List<RuleEntity>)
}

interface DatabaseTransactionRunner {
    fun <T> run(block: () -> T): T
}

data class AppDataSources(
    val profiles: ProfileDataSource,
    val groups: GroupDataSource,
    val rules: RuleDataSource,
    val transactions: DatabaseTransactionRunner,
)

object AppData {
    @Volatile
    private var sources = productionSources()

    val profiles: ProfileDataSource get() = sources.profiles
    val groups: GroupDataSource get() = sources.groups
    val rules: RuleDataSource get() = sources.rules
    val transactions: DatabaseTransactionRunner get() = sources.transactions

    @Synchronized
    internal fun installForTest(replacement: AppDataSources): AutoCloseable {
        val previous = sources
        sources = replacement
        return AutoCloseable {
            synchronized(this) {
                check(sources === replacement) { "AppData test installation closed out of order" }
                sources = previous
            }
        }
    }

    private fun productionSources() = AppDataSources(
        profiles = RoomProfileDataSource,
        groups = RoomGroupDataSource,
        rules = RoomRuleDataSource,
        transactions = RoomTransactionRunner,
    )
}

private object RoomProfileDataSource : ProfileDataSource {
    private val dao get() = SagerDatabase.proxyDao

    override fun getAll() = dao.getAll()
    override fun getAllIds() = dao.getAllIds()
    override fun getIdsByGroup(groupId: Long) = dao.getIdsByGroup(groupId)
    override fun getByGroup(groupId: Long) = dao.getByGroup(groupId)
    override fun getEntities(profileIds: List<Long>) = dao.getEntities(profileIds)
    override fun countByGroup(groupId: Long) = dao.countByGroup(groupId)
    override fun nextOrder(groupId: Long) = dao.nextOrder(groupId)
    override fun getById(profileId: Long) = dao.getById(profileId)
    override fun deleteById(profileId: Long) = dao.deleteById(profileId)
    override fun deleteByGroup(groupId: Long) = dao.deleteByGroup(groupId)
    override fun deleteByGroup(groupIds: LongArray) = dao.deleteByGroup(groupIds)
    override fun deleteProxy(profile: ProxyEntity) = dao.deleteProxy(profile)
    override fun deleteProxy(profiles: List<ProxyEntity>) = dao.deleteProxy(profiles)
    override fun updateProxy(profile: ProxyEntity) = dao.updateProxy(profile)
    override fun updateProxy(profiles: List<ProxyEntity>) = dao.updateProxy(profiles)
    override fun updateTraffic(profileId: Long, rx: Long, tx: Long) = dao.updateTraffic(profileId, rx, tx)
    override fun resetTraffic(profileIds: LongArray) = dao.resetTraffic(profileIds)
    override fun clearTestResults() = dao.clearTestResults()
    override fun addProxy(profile: ProxyEntity) = dao.addProxy(profile)
    override fun insert(profiles: List<ProxyEntity>) = dao.insert(profiles)
    override fun deleteAll(groupId: Long) = dao.deleteAll(groupId)
    override fun reset() = dao.reset()
}

private object RoomGroupDataSource : GroupDataSource {
    private val dao get() = SagerDatabase.groupDao

    override fun allGroups() = dao.allGroups()
    override suspend fun subscriptions() = dao.subscriptions()
    override fun nextOrder() = dao.nextOrder()
    override fun getById(groupId: Long) = dao.getById(groupId)
    override fun deleteById(groupId: Long) = dao.deleteById(groupId)
    override fun deleteGroup(group: ProxyGroup) = dao.deleteGroup(group)
    override fun deleteGroup(groups: List<ProxyGroup>) = dao.deleteGroup(groups)
    override fun createGroup(group: ProxyGroup) = dao.createGroup(group)
    override fun updateGroup(group: ProxyGroup) = dao.updateGroup(group)
    override fun reset() = dao.reset()
    override fun insert(groups: List<ProxyGroup>) = dao.insert(groups)
}

private object RoomRuleDataSource : RuleDataSource {
    private val dao get() = SagerDatabase.rulesDao

    override fun checkVpnNeeded() = dao.checkVpnNeeded()
    override fun allRules() = dao.allRules()
    override fun enabledRules(enabled: Boolean) = dao.enabledRules(enabled)
    override fun nextOrder() = dao.nextOrder()
    override fun getById(ruleId: Long) = dao.getById(ruleId)
    override fun dnsRulesUsingServer(serverTag: String) = dao.dnsRulesUsingServer(serverTag)
    override fun deleteById(ruleId: Long) = dao.deleteById(ruleId)
    override fun deleteRule(rule: RuleEntity) = dao.deleteRule(rule)
    override fun deleteRules(rules: List<RuleEntity>) = dao.deleteRules(rules)
    override fun createRule(rule: RuleEntity) = dao.createRule(rule)
    override fun updateRule(rule: RuleEntity) = dao.updateRule(rule)
    override fun updateRules(rules: List<RuleEntity>) = dao.updateRules(rules)
    override fun reset() = dao.reset()
    override fun insert(rules: List<RuleEntity>) = dao.insert(rules)
}

private object RoomTransactionRunner : DatabaseTransactionRunner {
    override fun <T> run(block: () -> T): T =
        SagerDatabase.instance.runInTransaction(Callable(block))
}
