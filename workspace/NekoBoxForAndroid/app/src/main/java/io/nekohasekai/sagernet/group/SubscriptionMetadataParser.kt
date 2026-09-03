package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.utils.parseSubscriptionUserinfo
import moe.matsuri.nb4a.utils.Util
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeProfileTitle(headerValue: String): String? {
    val value = headerValue.trim()
    if (value.isEmpty() || value.equals("null", ignoreCase = true)) return null
    if (!value.startsWith("base64:", ignoreCase = true)) return value

    val encoded = value.substringAfter(':').trim()
    if (encoded.isEmpty()) return null
    val padded = encoded.padEnd(encoded.length + (4 - encoded.length % 4) % 4, '=')
    val decoded = runCatching { Base64.Default.decode(padded) }
        .recoverCatching { Base64.UrlSafe.decode(padded) }
        .getOrNull()
        ?: return null
    return decoded.toString(Charsets.UTF_8).trim()
        .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
}

internal fun profileUpdateIntervalMinutes(headerValue: String, isFirstUpdate: Boolean): Int? {
    if (!isFirstUpdate) return null
    val hours = headerValue.trim().toLongOrNull() ?: return null
    if (hours <= 0L || hours > Int.MAX_VALUE / 60L) return null
    return (hours * 60L).toInt()
}

internal data class XraySubscriptionBodyHeaders(
    val profileTitle: String? = null,
    val profileUpdateInterval: String? = null,
    val subscriptionUserinfo: String? = null,
    val announcement: String? = null,
    val announcementUrl: String? = null,
    val supportUrl: String? = null,
    val supportEmail: String? = null,
    val profileWebPageUrl: String? = null,
    val homepage: String? = null,
)

internal fun parseXraySubscriptionBodyHeaders(text: String): XraySubscriptionBodyHeaders {
    val values = linkedMapOf<String, String>()
    for ((index, rawLine) in text.lineSequence().withIndex()) {
        val line = rawLine.let { if (index == 0) it.removePrefix("\uFEFF") else it }.trim()
        if (line.isEmpty()) continue
        if (!line.startsWith('#')) break
        val header = line.substring(1).trimStart()
        val separator = header.indexOf(':')
        if (separator <= 0) continue
        val name = header.substring(0, separator).trim().lowercase(Locale.ROOT)
        val value = header.substring(separator + 1).trim().takeIf(String::isNotEmpty) ?: continue
        values.putIfAbsent(name, value)
    }
    return XraySubscriptionBodyHeaders(
        profileTitle = values["profile-title"],
        profileUpdateInterval = values["profile-update-interval"],
        subscriptionUserinfo = values["subscription-userinfo"],
        announcement = values["announce"],
        announcementUrl = values["announce-url"],
        supportUrl = values["support-url"],
        supportEmail = values["support-email"],
        profileWebPageUrl = values["profile-web-page-url"],
        homepage = values["homepage"],
    )
}

internal fun responseOrBodyHeader(responseHeader: String, bodyHeader: String?): String =
    responseHeader.ifBlank { bodyHeader.orEmpty() }

internal data class SubscriptionMetadata(
    val userinfo: String,
    val expireAt: Long,
    val announcement: String,
    val announcementUrl: String,
    val supportUrl: String,
    val supportEmail: String,
    val profileWebPageUrl: String,
    val homepage: String,
    val autoUpdateIntervalMinutes: Int?,
    val suggestedName: String?,
)

internal object SubscriptionMetadataParser {
    fun parse(document: SubscriptionDocument, isFirstUpdate: Boolean): SubscriptionMetadata {
        val body = parseXraySubscriptionBodyHeaders(document.body)
        val headers = document.headers
        val userinfo = responseOrBodyHeader(headers["Subscription-Userinfo"], body.subscriptionUserinfo)
        val title = decodeProfileTitle(responseOrBodyHeader(headers["profile-title"], body.profileTitle))
        val dispositionName = headers["content-disposition"]
            .takeIf(String::isNotBlank)
            ?.let(Util::decodeFilename)
            ?.takeIf(String::isNotBlank)

        return SubscriptionMetadata(
            userinfo = userinfo,
            expireAt = parseSubscriptionUserinfo(userinfo)?.expireAt ?: 0L,
            announcement = decodeProfileTitle(
                responseOrBodyHeader(headers["announce"], body.announcement),
            ).orEmpty(),
            announcementUrl = responseOrBodyHeader(headers["announce-url"], body.announcementUrl),
            supportUrl = responseOrBodyHeader(headers["support-url"], body.supportUrl),
            supportEmail = responseOrBodyHeader(headers["support-email"], body.supportEmail),
            profileWebPageUrl = responseOrBodyHeader(
                headers["profile-web-page-url"],
                body.profileWebPageUrl,
            ),
            homepage = responseOrBodyHeader(headers["homepage"], body.homepage),
            autoUpdateIntervalMinutes = profileUpdateIntervalMinutes(
                responseOrBodyHeader(headers["profile-update-interval"], body.profileUpdateInterval),
                isFirstUpdate,
            ),
            suggestedName = title ?: dispositionName,
        )
    }
}
