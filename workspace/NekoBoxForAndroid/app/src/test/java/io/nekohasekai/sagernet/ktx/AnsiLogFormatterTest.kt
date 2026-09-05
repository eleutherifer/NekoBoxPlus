package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.ktx.AnsiLogFormatter.SpanType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiLogFormatterTest {
    @Test
    fun plainTextMatchesParsedTextWithoutAllocatingSpans() {
        val text = "before\u001B[31merror\u001B[0m\u001B[2K after \u001B[31"

        assertEquals(AnsiLogFormatter.parse(text).text, AnsiLogFormatter.plainText(text))
    }

    @Test
    fun parseRemovesSimpleAnsiSequences() {
        val parsed = AnsiLogFormatter.parse("\u001B[31merror\u001B[0m")

        assertEquals("error", parsed.text)
        assertTrue(parsed.hasSpan(SpanType.Foreground, 0, 5, 0xFFFF2158.toInt()))
    }

    @Test
    fun parseAppliesMultipleBasicColorsAndReset() {
        val parsed = AnsiLogFormatter.parse("a\u001B[33mwarn\u001B[0m b \u001B[36mdebug\u001B[0m")

        assertEquals("awarn b debug", parsed.text)
        assertTrue(parsed.hasSpan(SpanType.Foreground, 1, 5, 0xFFE5E500.toInt()))
        assertTrue(parsed.hasSpan(SpanType.Foreground, 8, 13, 0xFF5DADE2.toInt()))
        assertFalse(parsed.hasSpan(SpanType.Foreground, 5, 8))
    }

    @Test
    fun parseAppliesExtended256Color() {
        val parsed = AnsiLogFormatter.parse("\u001B[38;5;196mred\u001B[0m")

        assertEquals("red", parsed.text)
        assertTrue(parsed.hasSpan(SpanType.Foreground, 0, 3, 0xFFFF0000.toInt()))
    }

    @Test
    fun parseAppliesTrueColor() {
        val parsed = AnsiLogFormatter.parse("\u001B[38;2;1;2;3mcustom\u001B[0m")

        assertEquals("custom", parsed.text)
        assertTrue(parsed.hasSpan(SpanType.Foreground, 0, 6, 0xFF010203.toInt()))
    }

    @Test
    fun parseAppliesBackgroundAndTextStyles() {
        val parsed = AnsiLogFormatter.parse("\u001B[1;3;4;48;2;10;20;30mstyled\u001B[0m")

        assertEquals("styled", parsed.text)
        assertTrue(parsed.hasSpan(SpanType.Bold, 0, 6))
        assertTrue(parsed.hasSpan(SpanType.Italic, 0, 6))
        assertTrue(parsed.hasSpan(SpanType.Underline, 0, 6))
        assertTrue(parsed.hasSpan(SpanType.Background, 0, 6, 0xFF0A141E.toInt()))
    }

    @Test
    fun parseHandlesStyleChangesWithinLine() {
        val parsed = AnsiLogFormatter.parse("\u001B[31mred \u001B[32mgreen\u001B[0m plain")

        assertEquals("red green plain", parsed.text)
        assertTrue(parsed.hasSpan(SpanType.Foreground, 0, 4, 0xFFFF2158.toInt()))
        assertTrue(parsed.hasSpan(SpanType.Foreground, 4, 9, 0xFF2ECC71.toInt()))
        assertFalse(parsed.hasSpan(SpanType.Foreground, 9, 15))
    }

    @Test
    fun parseAppliesFallbackOnlyToLinesWithoutAnsiColor() {
        val parsed = AnsiLogFormatter.parse(
            "[Info] plain\n[Error] \u001B[31mansi\u001B[0m",
        ) { line ->
            when {
                line.contains("[Info]") -> 0xFF86C166.toInt()
                line.contains("[Error]") -> 0xFFFF0000.toInt()
                else -> null
            }
        }

        assertEquals("[Info] plain\n[Error] ansi", parsed.text)
        assertTrue(parsed.hasSpan(SpanType.Foreground, 0, 12, 0xFF86C166.toInt()))
        assertTrue(parsed.hasSpan(SpanType.Foreground, 21, 25, 0xFFFF2158.toInt()))
        assertFalse(parsed.hasSpan(SpanType.Foreground, 13, 24, 0xFFFF0000.toInt()))
    }

    @Test
    fun parseRemovesNonSgrCsiAndKeepsMalformedEscapes() {
        val parsed = AnsiLogFormatter.parse("before\u001B[2Kafter \u001B[31")

        assertEquals("beforeafter \u001B[31", parsed.text)
    }

    @Test
    fun highlightRangesAreCaseInsensitiveAndFindEveryOccurrence() {
        assertEquals(
            listOf(0..4, 11..15),
            AnsiLogFormatter.findHighlightRanges("Error then ERROR", "error"),
        )
        assertTrue(AnsiLogFormatter.findHighlightRanges("text", "").isEmpty())
    }

    private fun AnsiLogFormatter.ParsedLog.hasSpan(
        type: SpanType,
        start: Int,
        end: Int,
        color: Int? = null,
    ): Boolean {
        return spans.any {
            it.type == type &&
                it.start == start &&
                it.end == end &&
                (color == null || it.color == color)
        }
    }
}
