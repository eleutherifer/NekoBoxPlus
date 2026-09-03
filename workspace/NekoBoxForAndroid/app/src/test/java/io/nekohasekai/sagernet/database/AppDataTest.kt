package io.nekohasekai.sagernet.database

import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppDataTest {
    @Test
    fun installedSourcesAreUsedAndRestored() {
        val profiles = stub<ProfileDataSource>()
        val groups = stub<GroupDataSource>()
        val rules = stub<RuleDataSource>()
        var transactionCalls = 0
        val transactions = object : DatabaseTransactionRunner {
            override fun <T> run(block: () -> T): T {
                transactionCalls++
                return block()
            }
        }
        val previousProfiles = AppData.profiles

        AppData.installForTest(AppDataSources(profiles, groups, rules, transactions)).use {
            assertSame(profiles, AppData.profiles)
            assertSame(groups, AppData.groups)
            assertSame(rules, AppData.rules)
            assertEquals("result", AppData.transactions.run { "result" })
            assertEquals(1, transactionCalls)
        }

        assertSame(previousProfiles, AppData.profiles)
    }

    private inline fun <reified T> stub(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected ${method.name} call") } as T
}
