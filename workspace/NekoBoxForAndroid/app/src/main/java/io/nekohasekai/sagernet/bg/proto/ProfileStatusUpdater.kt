package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object ProfileStatusUpdater {
    private val profileLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun update(
        profileId: Long,
        status: Int,
        ping: Int = 0,
        error: String? = null,
        reloadDelayOrderedGroup: Boolean = false,
    ) {
        val lock = profileLocks.getOrPut(profileId) { Mutex() }
        lock.withLock {
            val profile = ProfileManager.getProfile(profileId) ?: return
            profile.status = status
            profile.ping = ping
            profile.error = error
            ProfileManager.updateProfile(profile)
            if (
                reloadDelayOrderedGroup &&
                SagerDatabase.groupDao.getById(profile.groupId)?.order == GroupOrder.BY_DELAY
            ) {
                GroupManager.postReload(profile.groupId)
            }
        }
    }
}
