package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMainViewPolicyTest {
    @Test
    fun `enabled by default through Android 8`() {
        assertTrue(LegacyMainViewPolicy.defaultEnabled(23))
        assertTrue(LegacyMainViewPolicy.defaultEnabled(27))
    }

    @Test
    fun `disabled by default from Android 9`() {
        assertFalse(LegacyMainViewPolicy.defaultEnabled(28))
        assertFalse(LegacyMainViewPolicy.defaultEnabled(37))
    }
}
