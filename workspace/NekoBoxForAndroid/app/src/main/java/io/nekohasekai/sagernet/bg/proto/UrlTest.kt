package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import libcore.GroupURLTester
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl

class UrlTest {

    private val link = DataStore.connectionTestURL
    private val timeout: Int
    private val testType: Int
    private val runKey = SystemClock.elapsedRealtimeNanos().toString()
    private val pluginRuntime = UrlTestPluginRuntime(runKey)

    constructor(
        timeout: Int = DataStore.connectionTestTimeout,
        testType: Int = DataStore.profileTestType,
    ) {
        this.timeout = timeout
        this.testType = testType
    }

    companion object {
        fun isUnsupportedProfile(profile: ProxyEntity): Boolean {
            return profile.containsMasterDnsVPN() || profile.isByeDPI() ||
                    profile.type == ProxyEntity.TYPE_CHAIN && profile.containsByeDPI()
        }
    }

    suspend fun doTest(profile: ProxyEntity): Int {
        return doTest(profile) { config ->
            Libcore.newInstanceURLTest(
                config,
                "",
                link,
                timeout,
                testType,
                DataStore.connectionTestAttempts,
                DataStore.connectionTestPause,
                DataStore.connectionTestHardened,
                LocalResolverImpl,
            )
        }
    }

    suspend fun doGroupTest(profile: ProxyEntity, tester: GroupURLTester): Int {
        return doTest(profile) { config -> tester.test(config, "") }
    }

    private suspend fun doTest(profile: ProxyEntity, run: (String) -> Int): Int {
        var testFailure: Throwable? = null
        return try {
            if (isUnsupportedProfile(profile)) {
                error("This profile is not supported in URLTest")
            }
            val config = buildConfig(
                profile,
                forTest = true,
            )
            config.normalizeConfig()
            pluginRuntime.prepare(profile.id, config)
            run(config.config)
        } catch (error: Throwable) {
            testFailure = error
            throw error
        } finally {
            try {
                pluginRuntime.close(profile.id)
            } catch (cleanupError: Throwable) {
                testFailure?.addSuppressed(cleanupError) ?: throw cleanupError
            }
        }
    }
}
