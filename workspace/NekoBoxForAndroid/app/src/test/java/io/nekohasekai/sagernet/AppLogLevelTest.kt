package io.nekohasekai.sagernet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogLevelTest {
    @Test
    fun existingPreferenceValuesKeepTheirMeaning() {
        assertEquals(AppLogLevel.NONE, AppLogLevel.fromPreferenceValue(0))
        assertEquals(AppLogLevel.WARNING, AppLogLevel.fromPreferenceValue(1))
        assertEquals(AppLogLevel.INFO, AppLogLevel.fromPreferenceValue(2))
        assertEquals(AppLogLevel.DEBUG, AppLogLevel.fromPreferenceValue(3))
        assertEquals(AppLogLevel.TRACE, AppLogLevel.fromPreferenceValue(4))
    }

    @Test
    fun newPreferenceValuesMapToMissingSingBoxLevels() {
        assertEquals(AppLogLevel.PANIC, AppLogLevel.fromPreferenceValue(5))
        assertEquals(AppLogLevel.FATAL, AppLogLevel.fromPreferenceValue(6))
        assertEquals(AppLogLevel.ERROR, AppLogLevel.fromPreferenceValue(7))
        assertEquals("panic", AppLogLevel.PANIC.singBoxName)
        assertEquals("fatal", AppLogLevel.FATAL.singBoxName)
        assertEquals("error", AppLogLevel.ERROR.singBoxName)
    }

    @Test
    fun noneAndPanicRemainDistinct() {
        assertFalse(AppLogLevel.NONE.outputEnabled)
        assertTrue(AppLogLevel.PANIC.outputEnabled)
        assertEquals(AppLogLevel.NONE.singBoxName, AppLogLevel.PANIC.singBoxName)
    }

    @Test
    fun severityFilteringUsesSingBoxOrdering() {
        assertFalse(AppLogLevel.NONE.allows(AppLogLevel.ERROR))
        assertTrue(AppLogLevel.ERROR.allows(AppLogLevel.ERROR))
        assertFalse(AppLogLevel.ERROR.allows(AppLogLevel.WARNING))
        assertTrue(AppLogLevel.WARNING.allows(AppLogLevel.ERROR))
        assertTrue(AppLogLevel.INFO.allows(AppLogLevel.WARNING))
        assertFalse(AppLogLevel.INFO.allows(AppLogLevel.DEBUG))
        assertTrue(AppLogLevel.TRACE.allows(AppLogLevel.DEBUG))
    }
}
