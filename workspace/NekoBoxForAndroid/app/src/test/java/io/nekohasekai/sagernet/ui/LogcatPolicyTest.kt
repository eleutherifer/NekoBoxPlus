package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogcatPolicyTest {
    @Test
    fun pageRequestsAreAlignedAndDeduplicated() {
        val starts = LogPagePolicy.pageStarts(
            lines = (480L..525L).toList(),
            lineCount = 2_000,
            pageSize = 500,
            maxPages = 32,
        )

        assertEquals(listOf(0L, 500L), starts)
    }

    @Test
    fun pageRequestsSkipCachedLinesAndRespectBounds() {
        val starts = LogPagePolicy.pageStarts(
            lines = listOf(-10L, 10L, 510L, 5_000L),
            lineCount = 1_200,
            pageSize = 500,
            maxPages = 2,
            isCached = { it == 10L },
        )

        assertEquals(listOf(0L, 500L), starts)
    }

    @Test
    fun filteredLineMapContainsOnlyStableVisiblePositions() {
        val builder = LogLineMap.Builder()
        listOf(4L, 19L, 42L).forEach(builder::add)
        val map = builder.build()

        assertEquals(3, map.size)
        assertEquals(4, map[0])
        assertEquals(19, map[1])
        assertEquals(42, map[2])
        assertEquals(1, map.positionOf(19))
        assertEquals(2, map.positionOf(30))

        val extended = map.appended(listOf(100L, 120L))
        assertEquals(5, extended.size)
        assertEquals(100, extended[3])
        assertEquals(120, extended[4])
    }

    @Test
    fun virtualPositionsMapLinearlyIncludingBothEnds() {
        assertEquals(10_000, LogVirtualPositionPolicy.itemCount(10_000))
        assertEquals(0, LogVirtualPositionPolicy.lineToPosition(0, 10_000, 10_000))
        assertEquals(9_999, LogVirtualPositionPolicy.lineToPosition(9_999, 10_000, 10_000))
        assertEquals(5_000, LogVirtualPositionPolicy.positionToLine(5_000, 10_000, 10_000))
    }

    @Test
    fun virtualPositionsScaleLogsLargerThanRecyclerViewCanRepresent() {
        val total = Int.MAX_VALUE.toLong() + 10_000
        val count = LogVirtualPositionPolicy.itemCount(total)

        assertEquals(Int.MAX_VALUE, count)
        assertEquals(total - 1, LogVirtualPositionPolicy.positionToLine(count - 1, count, total))
        assertEquals(count - 1, LogVirtualPositionPolicy.lineToPosition(total - 1, count, total))
    }

    @Test
    fun parserRecognizesBothLogFormatsAndInheritsContinuationSeverity() {
        val lines = LogcatLineParser.parse(
            "2026/07/10 [Warning] app warning\n" +
                "continuation\n" +
                "\u001B[31mERROR[0012] core failure\u001B[0m\n",
        )

        assertEquals(3, lines.size)
        assertEquals(LogcatSeverity.WARN, lines[0].severity)
        assertEquals(LogcatSeverity.WARN, lines[1].severity)
        assertEquals(LogcatSeverity.ERROR, lines[2].severity)
        assertEquals("ERROR[0012] core failure", lines[2].plainText)
    }

    @Test
    fun unmarkedInitialLinesDefaultToInfo() {
        val lines = LogcatLineParser.parse("plugin returned an error while probing\n")

        assertEquals(LogcatSeverity.INFO, lines.single().severity)
    }

    @Test
    fun filteringUsesThresholdAndCaseInsensitiveVisibleText() {
        val lines = LogcatLineParser.parse(
            "[Error] Connection FAILED\n" +
                "[Info] connection ready\n" +
                "[Debug] connection details\n",
        )

        val warnings = LogcatLineParser.filter(lines, LogcatSeverity.WARN, "connection")
        val info = LogcatLineParser.filter(lines, LogcatSeverity.INFO, "READY")

        assertEquals(listOf(LogcatSeverity.ERROR), warnings.map { it.severity })
        assertEquals(listOf(LogcatSeverity.INFO), info.map { it.severity })
    }

    @Test
    fun sparseIndexReadsCompleteLinesByAbsoluteNumber() {
        val file = createTempFile()
        file.writeText((0..600).joinToString("\n") { "line $it" } + "\n")

        val index = LogFileIndex.build(file)
        val lines = index.read(file, 254, 5)

        assertEquals(601, index.lineCount)
        assertTrue(index.checkpoints.size >= 3)
        assertEquals((254L..258L).toList(), lines.map { it.number })
        assertEquals("line 254", lines.first().plainText)
        file.delete()
    }

    @Test
    fun bufferedIndexHandlesCrLfAndLinesLargerThanItsBuffer() {
        val file = createTempFile()
        val longLine = "я".repeat(20_000)
        file.writeText("[Warning] first\r\n$longLine\r\nlast")

        val index = LogFileIndex.build(file)
        val lines = index.read(file, 0, 3)

        assertEquals(3, index.lineCount)
        assertEquals("[Warning] first", lines[0].plainText)
        assertEquals(longLine, lines[1].plainText)
        assertEquals(LogcatSeverity.WARN, lines[2].severity)
        file.delete()
    }

    @Test
    fun indexExtendsForAppendsAndRebuildsAfterTruncation() {
        val file = createTempFile()
        file.writeText("one\n")
        val initial = LogFileIndex.build(file)
        file.appendText("two\n")

        val appended = initial.extend(file)
        assertEquals(2, appended.lineCount)
        assertEquals("two", appended.read(file, 1, 1).single().plainText)

        file.writeText("replacement\n")
        val truncated = LogFileIndex.build(file)
        assertEquals(1, truncated.lineCount)
        assertEquals("replacement", truncated.read(file, 0, 1).single().plainText)
        file.delete()
    }

    @Test
    fun indexPreservesUtf8AndSeverityAcrossCheckpoints() {
        val file = createTempFile()
        file.writeText(
            buildString {
                append("[Warning] предупреждение\n")
                repeat(300) { append("продолжение $it\n") }
            },
        )

        val index = LogFileIndex.build(file)
        val line = index.read(file, 280, 1).single()

        assertEquals("продолжение 279", line.plainText)
        assertEquals(LogcatSeverity.WARN, line.severity)
        file.delete()
    }

    @Test
    fun extendingAnIncompleteUtf8LineReindexesItAsOneLine() {
        val file = createTempFile()
        file.writeText("[Info] незавершённая")
        val initial = LogFileIndex.build(file)
        file.appendText(" строка\n")

        val extended = initial.extend(file)

        assertEquals(1, extended.lineCount)
        assertEquals(
            "[Info] незавершённая строка",
            extended.read(file, 0, 1).single().plainText,
        )
        file.delete()
    }

    @Test
    fun lightweightIndexerRecognizesAnsiWrappedSeverity() {
        assertEquals(
            LogcatSeverity.ERROR,
            LogcatLineParser.severityInRawText("\u001B[31mERROR[42] failed\u001B[0m"),
        )
    }

    private fun createTempFile(): File = kotlin.io.path.createTempFile("nb4a-log", ".log").toFile()
}
