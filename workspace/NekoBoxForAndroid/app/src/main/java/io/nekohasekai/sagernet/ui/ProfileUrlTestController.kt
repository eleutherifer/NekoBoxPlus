package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.proto.ProfileStatusUpdater
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.ProfileCountryResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object ProfileUrlTestController {
    private val profileLocks = ConcurrentHashMap<Long, Mutex>()

    fun start(profile: ProxyEntity) {
        runOnDefaultDispatcher {
            val lock = profileLocks.getOrPut(profile.id) { Mutex() }
            lock.withLock {
                if (UrlTest.isUnsupportedProfile(profile)) {
                    ProfileStatusUpdater.update(
                        profile.id,
                        status = 3,
                        error = "This profile is not supported in URLTest",
                        reloadDelayOrderedGroup = false
                    )
                    return@withLock
                }
                ProfileStatusUpdater.update(profile.id, status = 0, reloadDelayOrderedGroup = false)
                try {
                    val result = UrlTest(
                        timeout = DataStore.connectionTestTimeout,
                        testType = DataStore.profileTestType
                    ).doTest(profile)
                    ProfileStatusUpdater.update(
                        profile.id,
                        status = 1,
                        ping = result,
                        reloadDelayOrderedGroup = false
                    )
                } catch (e: PluginManager.PluginNotFoundException) {
                    ProfileStatusUpdater.update(
                        profile.id,
                        status = 2,
                        error = e.readableMessage,
                        reloadDelayOrderedGroup = false
                    )
                } catch (e: Exception) {
                    Logs.w(e)
                    ProfileStatusUpdater.update(
                        profile.id,
                        status = 3,
                        error = e.readableMessage,
                        reloadDelayOrderedGroup = false
                    )
                } finally {
                    ProfileCountryResolver.resolveAndUpdateDomain(profile.id)
                }
            }
        }
    }
}
