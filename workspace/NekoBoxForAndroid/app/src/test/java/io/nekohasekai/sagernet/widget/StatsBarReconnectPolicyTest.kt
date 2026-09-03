package io.nekohasekai.sagernet.widget

import io.nekohasekai.sagernet.bg.BaseService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsBarReconnectPolicyTest {
    @Test
    fun profileReconnectPreservesPositionUntilConnected() {
        val policy = StatsBarReconnectPolicy()

        assertTrue(
            policy.shouldPreservePosition(
                BaseService.State.Stopping,
                BaseService.State.Connected,
                profileChanged = true,
            )
        )
        assertTrue(
            policy.shouldPreservePosition(
                BaseService.State.Stopped,
                BaseService.State.Stopping,
                profileChanged = true,
            )
        )
        assertTrue(
            policy.shouldPreservePosition(
                BaseService.State.Connecting,
                BaseService.State.Stopped,
                profileChanged = true,
            )
        )
        assertFalse(
            policy.shouldPreservePosition(
                BaseService.State.Connected,
                BaseService.State.Connecting,
                profileChanged = false,
            )
        )
    }

    @Test
    fun failedReplacementProfileEventuallyHidesBar() {
        val policy = StatsBarReconnectPolicy()

        policy.shouldPreservePosition(
            BaseService.State.Stopping,
            BaseService.State.Connected,
            profileChanged = true,
        )
        policy.shouldPreservePosition(
            BaseService.State.Stopped,
            BaseService.State.Stopping,
            profileChanged = true,
        )
        policy.shouldPreservePosition(
            BaseService.State.Connecting,
            BaseService.State.Stopped,
            profileChanged = true,
        )
        assertTrue(
            policy.shouldPreservePosition(
                BaseService.State.Stopping,
                BaseService.State.Connecting,
                profileChanged = true,
            )
        )
        assertFalse(
            policy.shouldPreservePosition(
                BaseService.State.Stopped,
                BaseService.State.Stopping,
                profileChanged = true,
            )
        )
    }

    @Test
    fun explicitDisconnectDoesNotPreservePosition() {
        assertFalse(
            StatsBarReconnectPolicy().shouldPreservePosition(
                BaseService.State.Stopping,
                BaseService.State.Connected,
                profileChanged = false,
            )
        )
    }
}
