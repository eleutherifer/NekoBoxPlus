package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreRecoveryPolicyTest {
    @Test
    fun unexpectedConnectionLossRecoversWhenGuardIsArmed() {
        assertTrue(
            CoreRecoveryPolicy.shouldRecoverOnConnectionLoss(
                connectionGuardEnabled = true,
                connectionRecoveryArmed = true,
                disarmed = false,
                expectedStop = false,
            )
        )
    }

    @Test
    fun connectionLossIsIgnoredWhenGuardIsDisabled() {
        assertFalse(
            CoreRecoveryPolicy.shouldRecoverOnConnectionLoss(
                connectionGuardEnabled = false,
                connectionRecoveryArmed = true,
                disarmed = false,
                expectedStop = false,
            )
        )
    }

    @Test
    fun overloadOnlyModeDoesNotRecoverOnConnectionLoss() {
        assertFalse(
            CoreRecoveryPolicy.shouldRecoverOnConnectionLoss(
                connectionGuardEnabled = true,
                connectionRecoveryArmed = false,
                disarmed = false,
                expectedStop = false,
            )
        )
    }

    @Test
    fun manualDisarmSuppressesRecovery() {
        assertFalse(
            CoreRecoveryPolicy.shouldRecoverOnConnectionLoss(
                connectionGuardEnabled = true,
                connectionRecoveryArmed = true,
                disarmed = true,
                expectedStop = false,
            )
        )
    }

    @Test
    fun expectedStopSuppressesRecovery() {
        assertFalse(
            CoreRecoveryPolicy.shouldRecoverOnConnectionLoss(
                connectionGuardEnabled = true,
                connectionRecoveryArmed = true,
                disarmed = false,
                expectedStop = true,
            )
        )
    }
}
