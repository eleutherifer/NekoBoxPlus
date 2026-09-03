package io.nekohasekai.sagernet

internal object BootReceiverPolicy {
    private const val ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED"

    fun shouldStartService(
        action: String?,
        persistAcrossReboot: Boolean,
        selectedProxy: Long,
        sdkInt: Int,
        userUnlocked: Boolean,
    ): Boolean {
        if (!persistAcrossReboot || selectedProxy <= 0) return false

        val userStorageAvailable = when (action) {
            ACTION_LOCKED_BOOT_COMPLETED -> false
            else -> sdkInt < 24 || userUnlocked
        }
        return userStorageAvailable
    }
}
