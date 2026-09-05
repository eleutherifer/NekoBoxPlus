package io.nekohasekai.sagernet.utils

data class SubscriptionUserinfo(
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long? = null,
    val expireAt: Long? = null,
)

fun parseSubscriptionUserinfo(value: String?): SubscriptionUserinfo? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty() || raw == "0") return null

    val fields = raw.split(';').mapNotNull { component ->
        val separator = component.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val key = component.substring(0, separator).trim().lowercase()
        val number = component.substring(separator + 1).trim().toLongOrNull()
            ?.takeIf { it >= 0L } ?: return@mapNotNull null
        key to number
    }.toMap()

    val upload = fields["upload"] ?: 0L
    val download = fields["download"] ?: 0L
    val total = fields["total"]?.takeIf { it > 0L }
    val expireAt = fields["expire"]?.takeIf { it > 0L }
    if (upload == 0L && download == 0L && total == null && expireAt == null) return null
    return SubscriptionUserinfo(upload, download, total, expireAt)
}
