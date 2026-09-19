package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.BaseService
import org.junit.Assert.assertFalse
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
}
