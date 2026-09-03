package io.nekohasekai.sagernet

import java.math.BigInteger
import java.util.Locale

object LogcatRetentionSize {
    const val DEFAULT_TEXT = "250kb"

    private const val KILOBYTES_PER_MEGABYTE = 1024
    private const val MIN_KILOBYTES = 10
    private const val MAX_MEGABYTES = 1024
    private const val MAX_KILOBYTES = MAX_MEGABYTES * KILOBYTES_PER_MEGABYTE

    private val inputPattern = Regex("^(\\d+)(kb|mb)$")
    private val minKilobytes = BigInteger.valueOf(MIN_KILOBYTES.toLong())
    private val maxKilobytes = BigInteger.valueOf(MAX_KILOBYTES.toLong())
    private val kilobytesPerMegabyte = BigInteger.valueOf(KILOBYTES_PER_MEGABYTE.toLong())

    data class Value(
        val text: String,
        val kilobytes: Int,
    )

    val default = Value(DEFAULT_TEXT, 250)

    fun parse(input: String?): Value? {
        val normalized = input?.trim()?.lowercase(Locale.ROOT) ?: return null
        val match = inputPattern.matchEntire(normalized) ?: return null
        val amount = match.groupValues[1].toBigInteger()
        val unit = match.groupValues[2]
        val kilobytes = if (unit == "mb") amount * kilobytesPerMegabyte else amount

        return when {
            kilobytes < minKilobytes -> Value("10kb", MIN_KILOBYTES)
            kilobytes > maxKilobytes -> Value("1024mb", MAX_KILOBYTES)
            else -> Value("$amount$unit", kilobytes.toInt())
        }
    }

    fun resolve(storedValue: String?, legacyKilobytes: Int?): Value {
        parse(storedValue)?.let { return it }
        if (storedValue != null || legacyKilobytes == null || legacyKilobytes <= 0) return default

        return when {
            legacyKilobytes < MIN_KILOBYTES -> Value("10kb", MIN_KILOBYTES)
            legacyKilobytes > MAX_KILOBYTES -> Value("1024mb", MAX_KILOBYTES)
            else -> Value("${legacyKilobytes}kb", legacyKilobytes)
        }
    }
}
