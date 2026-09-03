package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibcoreMemoryPolicyTest {

    @Test
    fun explicitLimitWins() {
        assertEquals(
            96L * MEBIBYTE,
            calculateLibcoreMemoryLimit(configuredMebibytes = 96),
        )
    }

    @Test
    fun zeroDisablesMemoryLimit() {
        assertEquals(0L, calculateLibcoreMemoryLimit(configuredMebibytes = 0))
    }

    @Test
    fun invalidAndOverflowingLimitsAreClamped() {
        assertEquals(0L, calculateLibcoreMemoryLimit(configuredMebibytes = -1))
        assertEquals(
            Long.MAX_VALUE / MEBIBYTE * MEBIBYTE,
            calculateLibcoreMemoryLimit(configuredMebibytes = Long.MAX_VALUE),
        )
    }

    @Test
    fun trimPolicyOnlyIncludesRunningMemoryPressure() {
        assertFalse(shouldSweepLibcoreMemory(5))
        assertTrue(shouldSweepLibcoreMemory(10))
        assertTrue(shouldSweepLibcoreMemory(15))
        assertFalse(shouldSweepLibcoreMemory(20))
        assertFalse(shouldSweepLibcoreMemory(21))
        assertFalse(shouldSweepLibcoreMemory(39))
        assertFalse(shouldSweepLibcoreMemory(40))
        assertFalse(shouldSweepLibcoreMemory(60))
    }

    private companion object {
        const val MEBIBYTE = 1024L * 1024L
    }
}
