package io.nekohasekai.sagernet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogcatRetentionSizeTest {
    @Test
    fun parsesSupportedUnits() {
        assertValue("1024kb", 1024, LogcatRetentionSize.parse("1024kb"))
        assertValue("1mb", 1024, LogcatRetentionSize.parse("1mb"))
    }

    @Test
    fun trimsAndNormalizesCase() {
        assertValue("1mb", 1024, LogcatRetentionSize.parse(" 1MB "))
        assertValue("250kb", 250, LogcatRetentionSize.parse("0250Kb"))
    }

    @Test
    fun clampsBothLimits() {
        assertValue("10kb", 10, LogcatRetentionSize.parse("0mb"))
        assertValue("10kb", 10, LogcatRetentionSize.parse("9kb"))
        assertValue("10kb", 10, LogcatRetentionSize.parse("10kb"))
        assertValue("1048576kb", 1024 * 1024, LogcatRetentionSize.parse("1048576kb"))
        assertValue("1024mb", 1024 * 1024, LogcatRetentionSize.parse("1024mb"))
        assertValue("1024mb", 1024 * 1024, LogcatRetentionSize.parse("1025mb"))
    }

    @Test
    fun capsValuesLargerThanMachineNumbers() {
        assertValue(
            "1024mb",
            1024 * 1024,
            LogcatRetentionSize.parse("999999999999999999999999999999999999999mb"),
        )
    }

    @Test
    fun rejectsInvalidSyntax() {
        listOf(null, "", "100", "1gb", "1.5mb", "-1kb", "kb", "1 mb").forEach {
            assertNull(it, LogcatRetentionSize.parse(it))
        }
    }

    @Test
    fun resolvesInvalidStoredValuesToDefault() {
        assertEquals(LogcatRetentionSize.default, LogcatRetentionSize.resolve(null, null))
        assertEquals(LogcatRetentionSize.default, LogcatRetentionSize.resolve("invalid", null))
        assertEquals(LogcatRetentionSize.default, LogcatRetentionSize.resolve(null, 0))
        assertEquals(LogcatRetentionSize.default, LogcatRetentionSize.resolve(null, -1))
    }

    @Test
    fun convertsLegacyKilobyteValues() {
        assertValue("10kb", 10, LogcatRetentionSize.resolve(null, 1))
        assertValue("250kb", 250, LogcatRetentionSize.resolve(null, 250))
        assertValue("1024mb", 1024 * 1024, LogcatRetentionSize.resolve(null, Int.MAX_VALUE))
    }

    private fun assertValue(
        expectedText: String,
        expectedKilobytes: Int,
        actual: LogcatRetentionSize.Value?,
    ) {
        assertEquals(expectedText, actual?.text)
        assertEquals(expectedKilobytes, actual?.kilobytes)
    }
}
