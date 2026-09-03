package io.nekohasekai.sagernet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPermissionTest {
    @Test
    fun android16DoesNotRequirePermission() {
        assertFalse(LocalNetworkPermission.isRequired(36, TunImplementation.SYSTEM, false))
    }

    @Test
    fun android17RequiresPermissionForSystem() {
        assertTrue(LocalNetworkPermission.isRequired(37, TunImplementation.SYSTEM, false))
    }

    @Test
    fun android17RequiresPermissionForMixed() {
        assertTrue(LocalNetworkPermission.isRequired(37, TunImplementation.MIXED, false))
    }

    @Test
    fun android17DoesNotRequirePermissionForGvisor() {
        assertFalse(LocalNetworkPermission.isRequired(37, TunImplementation.GVISOR, false))
    }

    @Test
    fun grantedPermissionDoesNotNeedAnotherRequest() {
        assertFalse(LocalNetworkPermission.isRequired(37, TunImplementation.SYSTEM, true))
    }
}
