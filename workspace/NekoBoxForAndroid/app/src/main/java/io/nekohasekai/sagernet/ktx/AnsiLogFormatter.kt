package io.nekohasekai.sagernet.ktx

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

object AnsiLogFormatter {

    enum class SpanType {
        Foreground,
        Background,
        Bold,
        Italic,
        Underline,
    }

    data class SpanRange(
        val type: SpanType,
        val start: Int,
        val end: Int,
        val color: Int? = null,
    )

    data class ParsedLog(
        val text: String,
        val spans: List<SpanRange>,
    )

    private data class StyleState(
        val foreground: Int? = null,
        val background: Int? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
    ) {
        val hasStyle: Boolean
            get() = foreground != null || background != null || bold || italic || underline
    }

    fun toSpannable(
        text: String,
        fallbackLineColor: ((String) -> Int?)? = null,
        highlightQuery: String? = null,
        highlightForeground: Int? = null,
        highlightBackground: Int? = null,
    ): SpannableStringBuilder {
        val parsed = parse(text, fallbackLineColor)
        return SpannableStringBuilder(parsed.text).apply {
            for (span in parsed.spans) {
                if (span.start >= span.end) continue
                when (span.type) {
                    SpanType.Foreground -> {
                        span.color?.let { color ->
                            setSpan(
                                ForegroundColorSpan(color),
                                span.start,
                                span.end,
                                SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }

                    SpanType.Background -> {
                        span.color?.let { color ->
                            setSpan(
                                BackgroundColorSpan(color),
                                span.start,
                                span.end,
                                SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }

                    SpanType.Bold -> setSpan(
                        StyleSpan(Typeface.BOLD),
                        span.start,
                        span.end,
                        SPAN_EXCLUSIVE_EXCLUSIVE,
                    )

                    SpanType.Italic -> setSpan(
                        StyleSpan(Typeface.ITALIC),
                        span.start,
                        span.end,
                        SPAN_EXCLUSIVE_EXCLUSIVE,
                    )

                    SpanType.Underline -> setSpan(
                        UnderlineSpan(),
                        span.start,
                        span.end,
                        SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            if (!highlightQuery.isNullOrEmpty()) {
                for (range in findHighlightRanges(parsed.text, highlightQuery)) {
                    val index = range.first
                    val end = range.last + 1
                    highlightForeground?.let {
                        setSpan(
                            ForegroundColorSpan(it),
                            index,
                            end,
                            SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    highlightBackground?.let {
                        setSpan(
                            BackgroundColorSpan(it),
                            index,
                            end,
                            SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }
        }
    }

    fun findHighlightRanges(text: String, query: String): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        val ranges = mutableListOf<IntRange>()
        var searchStart = 0
        while (searchStart < lowerText.length) {
            val index = lowerText.indexOf(lowerQuery, searchStart)
            if (index < 0) break
            ranges += index until index + lowerQuery.length
            searchStart = index + lowerQuery.length
        }
        return ranges
    }

    /** Removes complete ANSI CSI sequences without allocating style spans. */
    fun plainText(text: String): String {
        if (ESC !in text) return text
        val cleanText = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] == ESC && index + 1 < text.length && text[index + 1] == '[') {
                val sequenceEnd = findCsiEnd(text, index + 2)
                if (sequenceEnd != -1) {
                    index = sequenceEnd + 1
                    continue
                }
            }
            cleanText.append(text[index++])
        }
        return cleanText.toString()
    }

    fun parse(
        text: String,
        fallbackLineColor: ((String) -> Int?)? = null,
    ): ParsedLog {
        val cleanText = StringBuilder(text.length)
        val spans = mutableListOf<SpanRange>()
        var state = StyleState()
        var styleStart = 0
        var index = 0

        fun flushStyle(end: Int) {
            if (!state.hasStyle || styleStart >= end) return
            state.foreground?.let {
                spans += SpanRange(SpanType.Foreground, styleStart, end, it)
            }
            state.background?.let {
                spans += SpanRange(SpanType.Background, styleStart, end, it)
            }
            if (state.bold) spans += SpanRange(SpanType.Bold, styleStart, end)
            if (state.italic) spans += SpanRange(SpanType.Italic, styleStart, end)
            if (state.underline) spans += SpanRange(SpanType.Underline, styleStart, end)
        }

        while (index < text.length) {
            val char = text[index]
            if (char == ESC && index + 1 < text.length && text[index + 1] == '[') {
                val sequenceEnd = findCsiEnd(text, index + 2)
                if (sequenceEnd != -1) {
                    val final = text[sequenceEnd]
                    if (final == 'm') {
                        flushStyle(cleanText.length)
                        state = applySgr(text.substring(index + 2, sequenceEnd), state)
                        styleStart = cleanText.length
                    }
                    index = sequenceEnd + 1
                    continue
                }
            }
            cleanText.append(char)
            index++
        }
        flushStyle(cleanText.length)

        if (fallbackLineColor != null) {
            applyFallbackLineColors(cleanText.toString(), spans, fallbackLineColor)
        }

        return ParsedLog(cleanText.toString(), spans)
    }

    private fun applySgr(parameters: String, initialState: StyleState): StyleState {
        val codes = parameters.split(';').map { it.toIntOrNull() ?: 0 }.ifEmpty { listOf(0) }
        var state = initialState
        var index = 0
        while (index < codes.size) {
            when (val code = codes[index]) {
                0 -> state = StyleState()
                1 -> state = state.copy(bold = true)
                3 -> state = state.copy(italic = true)
                4 -> state = state.copy(underline = true)
                22 -> state = state.copy(bold = false)
                23 -> state = state.copy(italic = false)
                24 -> state = state.copy(underline = false)
                39 -> state = state.copy(foreground = null)
                49 -> state = state.copy(background = null)
                in 30..37 -> state = state.copy(foreground = normalColor(code - 30))
                in 40..47 -> state = state.copy(background = normalColor(code - 40))
                in 90..97 -> state = state.copy(foreground = brightColor(code - 90))
                in 100..107 -> state = state.copy(background = brightColor(code - 100))
                38, 48 -> {
                    val parsed = parseExtendedColor(codes, index + 1)
                    if (parsed != null) {
                        state = if (code == 38) {
                            state.copy(foreground = parsed.color)
                        } else {
                            state.copy(background = parsed.color)
                        }
                        index = parsed.nextIndex - 1
                    }
                }
            }
            index++
        }
        return state
    }

    private fun applyFallbackLineColors(
        text: String,
        spans: MutableList<SpanRange>,
        fallbackLineColor: (String) -> Int?,
    ) {
        var lineStart = 0
        while (lineStart <= text.length) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline == -1) text.length else newline
            if (lineEnd > lineStart && !hasColorSpan(spans, lineStart, lineEnd)) {
                fallbackLineColor(text.substring(lineStart, lineEnd))?.let {
                    spans += SpanRange(SpanType.Foreground, lineStart, lineEnd, it)
                }
            }
            if (newline == -1) break
            lineStart = newline + 1
        }
    }

    private fun hasColorSpan(spans: List<SpanRange>, start: Int, end: Int): Boolean {
        return spans.any {
            (it.type == SpanType.Foreground || it.type == SpanType.Background) &&
                it.start < end &&
                it.end > start
        }
    }

    private fun findCsiEnd(text: String, start: Int): Int {
        for (index in start until text.length) {
            val code = text[index].code
            if (code in 0x40..0x7E) return index
        }
        return -1
    }

    private data class ExtendedColor(val color: Int, val nextIndex: Int)

    private fun parseExtendedColor(codes: List<Int>, start: Int): ExtendedColor? {
        return when (codes.getOrNull(start)) {
            5 -> codes.getOrNull(start + 1)?.takeIf { it in 0..255 }?.let {
                ExtendedColor(xterm256Color(it), start + 2)
            }

            2 -> {
                val red = codes.getOrNull(start + 1)
                val green = codes.getOrNull(start + 2)
                val blue = codes.getOrNull(start + 3)
                if (red != null && green != null && blue != null &&
                    red in 0..255 && green in 0..255 && blue in 0..255
                ) {
                    ExtendedColor(argb(red, green, blue), start + 4)
                } else {
                    null
                }
            }

            else -> null
        }
    }

    private fun normalColor(index: Int): Int = NORMAL_COLORS[index]

    private fun brightColor(index: Int): Int = BRIGHT_COLORS[index]

    private fun xterm256Color(code: Int): Int {
        if (code < 8) return normalColor(code)
        if (code < 16) return brightColor(code - 8)
        if (code in 16..231) {
            val adjusted = code - 16
            val red = XTERM_COMPONENTS[adjusted / 36]
            val green = XTERM_COMPONENTS[(adjusted / 6) % 6]
            val blue = XTERM_COMPONENTS[adjusted % 6]
            return argb(red, green, blue)
        }
        val gray = 8 + (code - 232) * 10
        return argb(gray, gray, gray)
    }

    private fun argb(red: Int, green: Int, blue: Int): Int {
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private val NORMAL_COLORS = intArrayOf(
        argb(0, 0, 0),
        argb(255, 33, 88),
        argb(46, 204, 113),
        argb(229, 229, 0),
        argb(52, 152, 219),
        argb(155, 89, 182),
        argb(93, 173, 226),
        argb(236, 240, 241),
    )

    private val BRIGHT_COLORS = intArrayOf(
        argb(127, 140, 141),
        argb(255, 85, 85),
        argb(85, 255, 85),
        argb(255, 255, 85),
        argb(85, 85, 255),
        argb(255, 85, 255),
        argb(85, 255, 255),
        argb(255, 255, 255),
    )

    private val XTERM_COMPONENTS = intArrayOf(0, 95, 135, 175, 215, 255)
    private const val ESC = '\u001B'
}
