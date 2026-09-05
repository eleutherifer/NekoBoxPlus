package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomThemeLinkTest {

    @Test
    fun completeThemeRoundTripsWithReadableRgbValues() {
        val state = state()

        val link = CustomThemeLink.encode(state)
        val decoded = CustomThemeLink.decode(link)

        assertTrue(link.startsWith("sn://customtheme?light.primary="))
        assertFalse(link.contains('#'))
        assertFalse(link.contains("%23", ignoreCase = true))
        assertTrue(Regex("light\\.primary=[0-9A-F]{6}").containsMatchIn(link))
        assertStateEquals(state, decoded)
    }

    @Test
    fun linkOrderIsDeterministic() {
        assertEquals(CustomThemeLink.encode(state()), CustomThemeLink.encode(state()))
        val link = CustomThemeLink.encode(state())

        assertTrue(link.indexOf("light.primary=") < link.indexOf("dark.primary="))
        assertTrue(link.indexOf("dark.surfaceContainerHigh=") < link.indexOf("dynamicColors="))
        assertTrue(link.endsWith("statsBarPrimary=true"))
    }

    @Test
    fun unknownOptionsAreIgnoredForForwardCompatibility() {
        val state = state()
        val decoded = CustomThemeLink.decode(CustomThemeLink.encode(state) + "&futureOption=value")

        assertStateEquals(state, decoded)
    }

    @Test
    fun schemeAndHostAreCaseInsensitive() {
        val link = CustomThemeLink.encode(state()).replaceFirst("sn://customtheme", "SN://CUSTOMTHEME")

        assertStateEquals(state(), CustomThemeLink.decode(link))
    }

    @Test
    fun incompleteThemeIsRejected() {
        val link = CustomThemeLink.encode(state())
            .split('&')
            .filterNot { it.startsWith("dark.primary=") }
            .joinToString("&")

        assertRejected(link)
    }

    @Test
    fun duplicateKnownOptionIsRejected() {
        val link = CustomThemeLink.encode(state()) + "&light.primary=FFFFFF"

        assertRejected(link)
    }

    @Test
    fun malformedColorAndBooleanAreRejected() {
        assertRejected(CustomThemeLink.encode(state()).replace(Regex("light\\.primary=[0-9A-F]{6}"), "light.primary=#FFFFFF"))
        assertRejected(CustomThemeLink.encode(state()).replace("dynamicColors=true", "dynamicColors=yes"))
    }

    @Test
    fun pathPortUserInfoAndFragmentAreRejected() {
        val link = CustomThemeLink.encode(state())

        assertRejected(link.replace("customtheme?", "customtheme/path?"))
        assertRejected(link.replace("customtheme?", "customtheme:123?"))
        assertRejected(link.replace("customtheme?", "user@customtheme?"))
        assertRejected("$link#fragment")
    }

    @Test
    fun oversizedLinkIsRejected() {
        assertRejected(CustomThemeLink.encode(state()) + "&future=" + "x".repeat(8 * 1024))
    }

    @Test
    fun candidatesAreExtractedFromSurroundingClipboardText() {
        val link = CustomThemeLink.encode(state())

        assertEquals(listOf(link), CustomThemeLink.extractCandidates("Theme: <$link>."))
        assertEquals(2, CustomThemeLink.extractCandidates("$link\n$link").size)
        assertTrue(CustomThemeLink.extractCandidates("https://example.com").isEmpty())
    }

    private fun state(): CustomTheme.State {
        fun palette(offset: Int): CustomTheme.Palette {
            return CustomTheme.Palette(CustomTheme.colorSpecs.mapIndexed { index, spec ->
                spec.key to (0xFF000000L or (offset + index).toLong()).toInt()
            }.toMap().toMutableMap())
        }
        return CustomTheme.State(
            light = palette(0x102030),
            dark = palette(0x405060),
            dynamicColors = true,
            headerPrimary = false,
            statsBarPrimary = true,
        )
    }

    private fun assertStateEquals(expected: CustomTheme.State, actual: CustomTheme.State) {
        assertEquals(expected.light.colors, actual.light.colors)
        assertEquals(expected.dark.colors, actual.dark.colors)
        assertEquals(expected.dynamicColors, actual.dynamicColors)
        assertEquals(expected.headerPrimary, actual.headerPrimary)
        assertEquals(expected.statsBarPrimary, actual.statsBarPrimary)
    }

    private fun assertRejected(link: String) {
        assertTrue(runCatching { CustomThemeLink.decode(link) }.exceptionOrNull() is IllegalArgumentException)
    }
}
