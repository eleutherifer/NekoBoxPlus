package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogcatPolicyTest {
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
    fun initialTailStartsAtACompleteLine() {
        val file = createTempFile()
        file.writeText("first line\nsecond line\nthird line\n")

        val snapshot = LogTailReader.readInitial(file, 18)

        assertFalse(snapshot.text.startsWith("ond line"))
        assertTrue(snapshot.text.endsWith("third line\n"))
        assertEquals(file.length(), snapshot.offset)
        file.delete()
    }

    @Test
    fun appendedReaderTracksTruncationAndNewOffset() {
        val file = createTempFile()
        file.writeText("one\n")
        val initial = LogTailReader.readInitial(file, 1024)
        file.appendText("two\n")

        val appended = LogTailReader.readAppended(file, initial.offset)
        assertEquals("two\n", appended.text)
        assertEquals(file.length(), appended.offset)

        file.writeText("")
        val truncated = LogTailReader.readAppended(file, appended.offset)
        assertEquals("", truncated.text)
        assertEquals(0, truncated.offset)
        file.delete()
    }

    private fun createTempFile(): File = kotlin.io.path.createTempFile("nb4a-log", ".log").toFile()
}
