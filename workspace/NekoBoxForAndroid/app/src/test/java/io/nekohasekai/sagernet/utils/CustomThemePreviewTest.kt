package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomThemePreviewTest {

    @Test
    fun pendingPreviewRoundTripsThroughDurableJson() {
        val pending = CustomThemePreview.Pending(
            id = "preview-id",
            previousTheme = 7,
            previousState = state(0x102030, false),
            candidateState = state(0x405060, true),
            expiresAt = 15_000L,
        )

        val decoded = CustomThemePreview.decode(CustomThemePreview.encode(pending))

        assertEquals(pending.id, decoded.id)
        assertEquals(pending.previousTheme, decoded.previousTheme)
        assertEquals(pending.previousState.light.colors, decoded.previousState.light.colors)
        assertEquals(pending.candidateState.dark.colors, decoded.candidateState.dark.colors)
        assertEquals(pending.expiresAt, decoded.expiresAt)
    }

    @Test
    fun countdownUsesCeilingAndIsClampedToPreviewDuration() {
        val pending = CustomThemePreview.Pending(
            id = "preview-id",
            previousTheme = 1,
            previousState = state(0x102030, false),
            candidateState = state(0x405060, true),
            expiresAt = 10_000L,
        )

        assertEquals(5, CustomThemePreview.remainingSeconds(pending, now = 0L))
        assertEquals(5, CustomThemePreview.remainingSeconds(pending, now = 5_001L))
        assertEquals(1, CustomThemePreview.remainingSeconds(pending, now = 9_999L))
        assertEquals(0, CustomThemePreview.remainingSeconds(pending, now = 10_000L))
    }

    private fun state(color: Int, enabled: Boolean): CustomTheme.State {
        fun palette(value: Int) = CustomTheme.Palette(
            CustomTheme.colorSpecs.associate { it.key to (0xFF000000L or value.toLong()).toInt() }
                .toMutableMap(),
        )
        return CustomTheme.State(
            light = palette(color),
            dark = palette(color + 1),
            dynamicColors = enabled,
            headerPrimary = !enabled,
            statsBarPrimary = enabled,
        )
    }
}
