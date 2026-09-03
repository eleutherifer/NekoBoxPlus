package io.nekohasekai.sagernet.bg

internal object SubscriptionBootReceiverPolicy {
    fun shouldEnableReceiver(
        persistAcrossReboot: Boolean,
        hasAutoUpdateSubscriptions: Boolean,
    ): Boolean {
        return persistAcrossReboot || hasAutoUpdateSubscriptions
    }
}
