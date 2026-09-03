package io.nekohasekai.sagernet.ui

import java.util.Locale

internal const val SPEED_TEST_SERVER_AUTO = 0
internal const val SPEED_TEST_SERVER_ID = 1
internal const val SPEED_TEST_SERVER_SEARCH = 2
internal const val SPEED_TEST_SERVER_CUSTOM = 3
internal const val SPEED_TEST_FINAL_AVERAGE = 0
internal const val SPEED_TEST_FINAL_LAST = 1
internal const val SPEED_TEST_FINAL_MINIMUM = 2
internal const val SPEED_TEST_FINAL_MAXIMUM = 3

internal data class SpeedTestSettings(
    val durationMillis: Int = 5000,
    val connections: Int = 8,
    val serverMode: Int = SPEED_TEST_SERVER_AUTO,
    val serverValue: String = "",
    val finalResult: Int = SPEED_TEST_FINAL_AVERAGE,
)

internal fun validateSpeedTestSettings(settings: SpeedTestSettings): Boolean {
    if (settings.durationMillis !in 1000..30000) return false
    if (settings.connections !in 1..16) return false
    if (settings.finalResult !in SPEED_TEST_FINAL_AVERAGE..SPEED_TEST_FINAL_MAXIMUM) return false
    return when (settings.serverMode) {
        SPEED_TEST_SERVER_AUTO -> true
        SPEED_TEST_SERVER_ID -> settings.serverValue.toLongOrNull()?.let { it > 0 } == true
        SPEED_TEST_SERVER_SEARCH -> settings.serverValue.isNotBlank()
        SPEED_TEST_SERVER_CUSTOM -> runCatching {
            val uri = java.net.URI(settings.serverValue)
            uri.host != null && uri.userInfo == null && uri.scheme in setOf("http", "https")
        }.getOrDefault(false)
        else -> false
    }
}

internal fun formatSpeedBits(bytesPerSecond: Long): String {
    val bitsPerSecond = bytesPerSecond.coerceAtLeast(0).toDouble() * 8.0
    val (value, unit) = when {
        bitsPerSecond >= 1_000_000_000 -> bitsPerSecond / 1_000_000_000 to "Gbps"
        bitsPerSecond >= 1_000_000 -> bitsPerSecond / 1_000_000 to "Mbps"
        bitsPerSecond >= 1_000 -> bitsPerSecond / 1_000 to "Kbps"
        else -> bitsPerSecond to "bps"
    }
    return String.format(Locale.getDefault(), "%.2f %s", value, unit)
}
