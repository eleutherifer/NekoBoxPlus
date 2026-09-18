package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.ProxyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSelectionReloadPolicyTest {
    @Test
    fun changedProfileReloadsActiveOrStoppingService() {
        assertTrue(ProfileSelectionReloadPolicy.shouldReload(true, BaseService.State.Connecting))
        assertTrue(ProfileSelectionReloadPolicy.shouldReload(true, BaseService.State.Connected))
        assertTrue(ProfileSelectionReloadPolicy.shouldReload(true, BaseService.State.Stopping))
    }

    @Test
    fun changedProfileDoesNotStartStoppedService() {
        assertFalse(ProfileSelectionReloadPolicy.shouldReload(true, BaseService.State.Idle))
        assertFalse(ProfileSelectionReloadPolicy.shouldReload(true, BaseService.State.Stopped))
    }

    @Test
    fun unchangedProfileNeverReloads() {
        BaseService.State.values().forEach { state ->
            assertFalse(ProfileSelectionReloadPolicy.shouldReload(false, state))
        }
    }

    @Test
    fun selectionRefreshPreservesNewerCachedTraffic() {
        val cached = ProxyEntity(id = 1L, tx = 12_000L, rx = 34_000L)
        val databaseProfile = ProxyEntity(
            id = 1L,
            tx = 0L,
            rx = 0L,
            status = 1,
        ).also { it.dirty = true }

        val merged = mergeProfileRefresh(
            profile = databaseProfile,
            cached = cached,
            preserveTraffic = true,
        )

        assertEquals(12_000L, merged.tx)
        assertEquals(34_000L, merged.rx)
        assertEquals(1, merged.status)
        assertTrue(merged.dirty)
    }

    @Test
    fun authoritativeRefreshCanReplaceTraffic() {
        val cached = ProxyEntity(id = 1L, tx = 12_000L, rx = 34_000L)
        val databaseProfile = ProxyEntity(id = 1L, tx = 50L, rx = 100L)

        assertSame(
            databaseProfile,
            mergeProfileRefresh(
                profile = databaseProfile,
                cached = cached,
                preserveTraffic = false,
            ),
        )
    }
}
