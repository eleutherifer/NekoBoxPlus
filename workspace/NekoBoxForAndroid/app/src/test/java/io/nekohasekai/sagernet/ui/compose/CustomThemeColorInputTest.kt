package io.nekohasekai.sagernet.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomThemeColorInputTest {
    @Test
    fun parsesSixDigitColorsWithOptionalHash() {
        assertEquals(0xFFA1B2C3.toInt(), parseOpaqueHexColor("#A1b2C3"))
        assertEquals(0xFF001122.toInt(), parseOpaqueHexColor("001122"))
    }

    @Test
    fun rejectsInvalidColors() {
        assertNull(parseOpaqueHexColor("#12345"))
        assertNull(parseOpaqueHexColor("#GG0011"))
        assertNull(parseOpaqueHexColor("#80112233"))
    }

    @Test
    fun formatsOpaqueColor() {
        assertEquals("#01A2FF", formatOpaqueHexColor(0x7F01A2FF))
    }
}
