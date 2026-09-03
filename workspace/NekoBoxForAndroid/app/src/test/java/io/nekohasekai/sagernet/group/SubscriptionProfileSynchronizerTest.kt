package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.AppDataSources
import io.nekohasekai.sagernet.database.DatabaseTransactionRunner
import io.nekohasekai.sagernet.database.GroupDataSource
import io.nekohasekai.sagernet.database.ProfileDataSource
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleDataSource
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionProfileSynchronizerTest {
    @Test
    fun allMutationsRunInsideOneTransaction() {
        var insideTransaction = false
        var inserted: ProxyEntity? = null
        var groupUpdated = false
        val profiles = proxy<ProfileDataSource> { method, args ->
            when (method.name) {
                "getByGroup" -> emptyList<ProxyEntity>()
                "nextOrder" -> 1L
                "addProxy" -> {
                    assertTrue(insideTransaction)
                    inserted = args.single() as ProxyEntity
                    42L
                }
                "updateProxy", "deleteProxy" -> {
                    assertTrue(insideTransaction)
                    0
                }
                "countByGroup" -> 1L
                else -> error("Unexpected profile call: ${method.name}")
            }
        }
        val groups = proxy<GroupDataSource> { method, _ ->
            when (method.name) {
                "updateGroup" -> {
                    assertTrue(insideTransaction)
                    groupUpdated = true
                    Unit
                }
                else -> error("Unexpected group call: ${method.name}")
            }
        }
        val transactions = object : DatabaseTransactionRunner {
            override fun <T> run(block: () -> T): T {
                check(!insideTransaction)
                insideTransaction = true
                return try {
                    block()
                } finally {
                    insideTransaction = false
                }
            }
        }
        val group = ProxyGroup(id = 7L).apply { subscription = SubscriptionBean() }

        AppData.installForTest(
            AppDataSources(profiles, groups, proxy(), transactions),
        ).use {
            val result = SubscriptionProfileSynchronizer.synchronize(
                group,
                listOf(profile("Node")),
                applyUpdateOrder = true,
            )

            assertEquals(listOf("Node"), result.added)
            assertEquals(7L, inserted?.groupId)
            assertEquals(1L, inserted?.userOrder)
            assertTrue(groupUpdated)
            assertTrue(group.subscription!!.lastUpdated > 0)
        }
    }

    private fun profile(name: String) = ShadowsocksBean().apply {
        initializeDefaultValues()
        this.name = name
        serverAddress = "proxy.example"
        serverPort = 443
        method = "aes-256-gcm"
        password = "secret"
    }

    private inline fun <reified T> proxy(
        noinline handler: (java.lang.reflect.Method, Array<out Any?>) -> Any? = { method, _ ->
            error("Unexpected ${method.name} call")
        },
    ): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        handler(method, args.orEmpty())
    } as T
}
