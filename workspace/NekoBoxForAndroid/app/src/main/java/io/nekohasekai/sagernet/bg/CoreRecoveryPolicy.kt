package io.nekohasekai.sagernet.bg

internal object CoreRecoveryPolicy {
    fun shouldRecoverOnConnectionLoss(
        connectionGuardEnabled: Boolean,
        connectionRecoveryArmed: Boolean,
        disarmed: Boolean,
        expectedStop: Boolean,
    ): Boolean {
        return connectionGuardEnabled && connectionRecoveryArmed && !disarmed && !expectedStop
    }
}
