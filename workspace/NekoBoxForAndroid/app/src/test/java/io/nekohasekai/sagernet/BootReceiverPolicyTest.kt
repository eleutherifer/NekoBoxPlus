package io.nekohasekai.sagernet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverPolicyTest {

    @Test
    fun packageReplaceReconnectsWhenPersistenceIsEnabled() {
        assertTrue(
            BootReceiverPolicy.shouldStartService(
                action = "android.intent.action.MY_PACKAGE_REPLACED",
                persistAcrossReboot = true,
                selectedProxy = 1L,
                sdkInt = 35,
                userUnlocked = true,
            )
        )
    }

    @Test
    fun packageReplaceDoesNotReconnectWhenOnlySubscriptionSchedulingNeedsReceiver() {
        assertFalse(
            BootReceiverPolicy.shouldStartService(
                action = "android.intent.action.MY_PACKAGE_REPLACED",
                persistAcrossReboot = false,
                selectedProxy = 1L,
                sdkInt = 35,
                userUnlocked = true,
            )
        )
    }

    @Test
    fun lockedBootDoesNotReconnectBeforeCredentialStorageIsAvailable() {
        assertFalse(
            BootReceiverPolicy.shouldStartService(
                action = "android.intent.action.LOCKED_BOOT_COMPLETED",
                persistAcrossReboot = true,
                selectedProxy = 1L,
                sdkInt = 35,
                userUnlocked = true,
            )
        )
    }

    @Test
    fun bootDoesNotReconnectWithoutSelectedProxy() {
        assertFalse(
            BootReceiverPolicy.shouldStartService(
                action = "android.intent.action.BOOT_COMPLETED",
                persistAcrossReboot = true,
                selectedProxy = 0L,
                sdkInt = 35,
                userUnlocked = true,
            )
        )
    }
}
