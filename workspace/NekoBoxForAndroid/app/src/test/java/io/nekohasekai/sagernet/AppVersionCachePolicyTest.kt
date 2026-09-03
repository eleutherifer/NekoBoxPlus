package io.nekohasekai.sagernet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionCachePolicyTest {
    @Test
    fun missingStoredVersionRequiresCleanup() {
        assertTrue(AppVersionCachePolicy.shouldClearCache(null, 10))
    }

    @Test
    fun differentStoredVersionRequiresCleanup() {
        assertTrue(AppVersionCachePolicy.shouldClearCache(9, 10))
        assertTrue(AppVersionCachePolicy.shouldClearCache(11, 10))
    }

    @Test
    fun matchingStoredVersionSkipsCleanup() {
        assertFalse(AppVersionCachePolicy.shouldClearCache(10, 10))
    }
}
