package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.utils.parseSubscriptionUserinfo
import java.net.URI

object SubscriptionBannerLayout {
    const val ANNOUNCEMENTS = 1
    const val ANNOUNCEMENT_URLS = 1 shl 1
    const val TRAFFIC_TEXT = 1 shl 2
    const val TRAFFIC_BAR = 1 shl 3
    const val CLICKABLE = 1 shl 4
    const val EXPIRATION_TIME = 1 shl 5
    const val ALL = ANNOUNCEMENTS or ANNOUNCEMENT_URLS or TRAFFIC_TEXT or TRAFFIC_BAR or
        CLICKABLE or EXPIRATION_TIME

    const val VALUE_ANNOUNCEMENTS = "announcements"
    const val VALUE_ANNOUNCEMENT_URLS = "announcement_urls"
    const val VALUE_TRAFFIC_TEXT = "traffic_text"
    const val VALUE_TRAFFIC_BAR = "traffic_bar"
    const val VALUE_CLICKABLE = "clickable"
    const val VALUE_EXPIRATION_TIME = "expiration_time"

    val allValues = setOf(
        VALUE_ANNOUNCEMENTS,
        VALUE_ANNOUNCEMENT_URLS,
        VALUE_TRAFFIC_TEXT,
        VALUE_TRAFFIC_BAR,
        VALUE_EXPIRATION_TIME,
        VALUE_CLICKABLE,
    )

    fun toValues(mask: Int): Set<String> = buildSet {
        if (mask and ANNOUNCEMENTS != 0) add(VALUE_ANNOUNCEMENTS)
        if (mask and ANNOUNCEMENT_URLS != 0) add(VALUE_ANNOUNCEMENT_URLS)
        if (mask and TRAFFIC_TEXT != 0) add(VALUE_TRAFFIC_TEXT)
        if (mask and TRAFFIC_BAR != 0) add(VALUE_TRAFFIC_BAR)
        if (mask and EXPIRATION_TIME != 0) add(VALUE_EXPIRATION_TIME)
        if (mask and CLICKABLE != 0) add(VALUE_CLICKABLE)
    }

    fun fromValues(values: Set<String>): Int {
        var mask = 0
        if (VALUE_ANNOUNCEMENTS in values) mask = mask or ANNOUNCEMENTS
        if (VALUE_ANNOUNCEMENT_URLS in values) mask = mask or ANNOUNCEMENT_URLS
        if (VALUE_TRAFFIC_TEXT in values) mask = mask or TRAFFIC_TEXT
        if (VALUE_TRAFFIC_BAR in values) mask = mask or TRAFFIC_BAR
        if (VALUE_EXPIRATION_TIME in values) mask = mask or EXPIRATION_TIME
        if (VALUE_CLICKABLE in values) mask = mask or CLICKABLE
        return mask
    }
}

data class SubscriptionTraffic(
    val used: Long,
    val total: Long?,
) {
    val progress: Int?
        get() = total?.takeIf { it > 0L }?.let {
            ((used.coerceIn(0L, it).toDouble() / it.toDouble()) * 1000.0).toInt()
        }
}

data class SubscriptionBannerPresentation(
    val announcement: String?,
    val announcementUrl: String?,
    val traffic: SubscriptionTraffic?,
    val showTrafficText: Boolean,
    val showTrafficBar: Boolean,
    val expireAt: Long?,
    val clickable: Boolean,
) {
    val visible: Boolean
        get() = announcement != null || announcementUrl != null ||
            (traffic != null && (showTrafficText || showTrafficBar)) || expireAt != null

    val hasAnnouncementContent: Boolean
        get() = announcement != null || announcementUrl != null
}

enum class SubscriptionBannerDestination {
    ANNOUNCEMENT,
    SUPPORT,
    EMAIL_SUPPORT,
    SUBSCRIPTION_PAGE,
}

data class SubscriptionBannerLink(
    val destination: SubscriptionBannerDestination,
    val value: String,
)

fun parseSubscriptionTraffic(value: String?): SubscriptionTraffic? {
    val fields = parseSubscriptionUserinfo(value) ?: return null
    val upload = fields.upload
    val download = fields.download
    val used = if (Long.MAX_VALUE - upload < download) Long.MAX_VALUE else upload + download
    val total = fields.total
    if (used == 0L && total == null) return null
    return SubscriptionTraffic(used, total)
}

fun subscriptionBannerPresentation(subscription: SubscriptionBean): SubscriptionBannerPresentation {
    val mask = subscription.bannerLayout ?: SubscriptionBannerLayout.ALL
    val parsedUserinfo = parseSubscriptionUserinfo(subscription.subscriptionUserinfo)
    return SubscriptionBannerPresentation(
        announcement = subscription.announcement
            ?.trim()
            ?.takeIf { it.isNotEmpty() && mask and SubscriptionBannerLayout.ANNOUNCEMENTS != 0 },
        announcementUrl = subscription.announcementUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() && mask and SubscriptionBannerLayout.ANNOUNCEMENT_URLS != 0 },
        traffic = parseSubscriptionTraffic(subscription.subscriptionUserinfo),
        showTrafficText = mask and SubscriptionBannerLayout.TRAFFIC_TEXT != 0,
        showTrafficBar = mask and SubscriptionBannerLayout.TRAFFIC_BAR != 0,
        expireAt = (subscription.expireAt?.takeIf { it > 0L } ?: parsedUserinfo?.expireAt)
            ?.takeIf { mask and SubscriptionBannerLayout.EXPIRATION_TIME != 0 },
        clickable = mask and SubscriptionBannerLayout.CLICKABLE != 0,
    )
}

enum class SubscriptionExpirationUnit {
    DAYS,
    HOURS,
    MINUTES,
}

sealed interface SubscriptionExpiration {
    data object Expired : SubscriptionExpiration
    data object LessThanMinute : SubscriptionExpiration
    data class Remaining(val value: Long, val unit: SubscriptionExpirationUnit) :
        SubscriptionExpiration
}

fun subscriptionExpiration(expireAt: Long, nowMillis: Long): SubscriptionExpiration {
    val expireMillis = if (expireAt > Long.MAX_VALUE / 1000L) Long.MAX_VALUE else expireAt * 1000L
    val remainingMillis = expireMillis - nowMillis
    if (remainingMillis <= 0L) return SubscriptionExpiration.Expired
    if (remainingMillis < 60_000L) return SubscriptionExpiration.LessThanMinute
    val minutes = remainingMillis / 60_000L
    return when {
        minutes >= 24L * 60L -> SubscriptionExpiration.Remaining(
            minutes / (24L * 60L),
            SubscriptionExpirationUnit.DAYS,
        )
        minutes >= 60L -> SubscriptionExpiration.Remaining(
            minutes / 60L,
            SubscriptionExpirationUnit.HOURS,
        )
        else -> SubscriptionExpiration.Remaining(minutes, SubscriptionExpirationUnit.MINUTES)
    }
}

private fun webUrlOrNull(value: String?): String? {
    val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val scheme = runCatching { URI(normalized).scheme }.getOrNull()
    return normalized.takeIf { scheme.equals("http", true) || scheme.equals("https", true) }
}

fun subscriptionBannerLinks(subscription: SubscriptionBean): List<SubscriptionBannerLink> = buildList {
    webUrlOrNull(subscription.announcementUrl)?.let {
        add(SubscriptionBannerLink(SubscriptionBannerDestination.ANNOUNCEMENT, it))
    }
    webUrlOrNull(subscription.supportUrl)?.let {
        add(SubscriptionBannerLink(SubscriptionBannerDestination.SUPPORT, it))
    }
    subscription.supportEmail
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.contains('\n') && it.contains('@') }
        ?.let { add(SubscriptionBannerLink(SubscriptionBannerDestination.EMAIL_SUPPORT, it)) }
    sequenceOf(subscription.profileWebPageUrl, subscription.homepage, subscription.link)
        .mapNotNull(::webUrlOrNull)
        .firstOrNull()
        ?.let { add(SubscriptionBannerLink(SubscriptionBannerDestination.SUBSCRIPTION_PAGE, it)) }
}
