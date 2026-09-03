package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionBootReceiverPolicyTest {

    @Test
    fun disablesReceiverWhenNoReconnectOrSubscriptionSchedulingNeedsIt() {
        assertFalse(
            SubscriptionBootReceiverPolicy.shouldEnableReceiver(
                persistAcrossReboot = false,
                hasAutoUpdateSubscriptions = false,
            )
        )
    }

    @Test
    fun enablesReceiverForAutoUpdateSubscriptionsWithoutReconnect() {
        assertTrue(
            SubscriptionBootReceiverPolicy.shouldEnableReceiver(
                persistAcrossReboot = false,
                hasAutoUpdateSubscriptions = true,
            )
        )
    }

    @Test
    fun enablesReceiverForReconnectWithoutAutoUpdateSubscriptions() {
        assertTrue(
            SubscriptionBootReceiverPolicy.shouldEnableReceiver(
                persistAcrossReboot = true,
                hasAutoUpdateSubscriptions = false,
            )
        )
    }

    @Test
    fun keepsReceiverEnabledWhenBothFeaturesNeedIt() {
        assertTrue(
            SubscriptionBootReceiverPolicy.shouldEnableReceiver(
                persistAcrossReboot = true,
                hasAutoUpdateSubscriptions = true,
            )
        )
    }
}
