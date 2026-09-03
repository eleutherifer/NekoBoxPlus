package io.nekohasekai.sagernet

internal object AppVersionCachePolicy {
    fun shouldClearCache(storedVersionCode: Int?, currentVersionCode: Int): Boolean =
        storedVersionCode == null || storedVersionCode != currentVersionCode
}
