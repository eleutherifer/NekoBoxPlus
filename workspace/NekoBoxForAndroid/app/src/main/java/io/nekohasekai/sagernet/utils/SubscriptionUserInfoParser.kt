package io.nekohasekai.sagernet.utils

data class SubscriptionUserInfo(
    val usedBytes: Long,
    val totalBytes: Long,
    val expiresAtEpochSeconds: Long?,
)

object SubscriptionUserInfoParser {
    private val valuePattern = Regex("(?:^|[;,\\s])([a-zA-Z]+)=([0-9]+)")

    fun parse(header: String): SubscriptionUserInfo {
        val values = valuePattern.findAll(header).associate { match ->
            match.groupValues[1].lowercase() to match.groupValues[2].toLongOrNull()
        }
        val upload = values["upload"] ?: 0L
        val download = values["download"] ?: 0L
        return SubscriptionUserInfo(
            usedBytes = saturatedAdd(upload, download),
            totalBytes = values["total"] ?: 0L,
            expiresAtEpochSeconds = values["expire"]?.takeIf { it > 0L },
        )
    }

    private fun saturatedAdd(first: Long, second: Long): Long =
        if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second
}
