package io.nekohasekai.sagernet.group

import android.os.Build
import io.nekohasekai.sagernet.SpoofApp
import io.nekohasekai.sagernet.ktx.app
import moe.matsuri.nb4a.utils.HwidGenerator
import java.util.Locale

internal data class SubscriptionRequestFingerprint(
    val userAgent: String,
    val headers: Map<String, String>,
)

private const val HAPP_USER_AGENT = "Happ/3.26.3/Android/17839452147361875676"
private const val LEGACY_HAPP_USER_AGENT = "Happ/3.17.0/Android/17756505247711753599"
private const val V2RAY_TUN_USER_AGENT = "v2raytun/android"
private const val INCY_USER_AGENT = "INCY/3.4.3/android Dalvik/2.1.0"

internal fun defaultSpoofUserAgent(spoofApp: Int): String =
    when (spoofApp) {
        SpoofApp.HAPP -> HAPP_USER_AGENT
        SpoofApp.V2RAY_TUN -> V2RAY_TUN_USER_AGENT
        SpoofApp.INCY -> INCY_USER_AGENT
        else -> ""
    }

internal fun normalizeSpoofUserAgent(spoofApp: Int, customUserAgent: String): String {
    if (spoofApp == SpoofApp.NONE) return customUserAgent
    if (
        customUserAgent.isBlank() ||
        spoofApp == SpoofApp.HAPP && customUserAgent == LEGACY_HAPP_USER_AGENT
    ) {
        return defaultSpoofUserAgent(spoofApp)
    }
    return customUserAgent
}

internal fun shouldWarnAboutMissingSpoofHwid(spoofApp: Int, hwidEnabled: Boolean): Boolean =
    spoofApp != SpoofApp.NONE && !hwidEnabled

internal fun buildSubscriptionRequestFingerprint(
    spoofApp: Int,
    hwidEnabled: Boolean,
    customUserAgent: String,
    fallbackUserAgent: String,
    manufacturer: String = Build.MANUFACTURER,
    model: String = Build.MODEL,
    sdkVersion: Int = Build.VERSION.SDK_INT,
    locale: Locale = Locale.getDefault(),
    hwid: String? = if (hwidEnabled) HwidGenerator.generate(app, spoofApp) else null,
): SubscriptionRequestFingerprint {
    val headers = linkedMapOf<String, String>()
    val fullModel = "$manufacturer $model".trim()

    when (spoofApp) {
        SpoofApp.HAPP -> {
            headers["X-Device-Model"] = model
            headers["X-Ver-Os"] = sdkVersion.toString()
            headers["X-Device-Os"] = "Android"
            headers["X-Device-Locale"] = locale.language
        }
        SpoofApp.V2RAY_TUN -> {
            headers["X-App-Version"] = "5.25.80"
            headers["X-Device-Model"] = fullModel
            headers["X-Ver-Os"] = "Android $sdkVersion"
            headers["X-Device-Os"] = "Android"
        }
        SpoofApp.INCY -> {
            val deviceLocale =
                locale.country.takeIf(String::isNotEmpty)?.let { "${locale.language}_$it" }
                    ?: locale.language
            headers["Accept"] = "*/*"
            headers["Accept-Language"] = locale.toLanguageTag()
            headers["X-Client"] = "INCY"
            headers["X-Device-Locale"] = deviceLocale
            headers["X-App-Version"] = "3.4.3"
            headers["X-Device-Model"] = fullModel
            headers["X-Ver-Os"] = sdkVersion.toString()
            headers["X-Device-Os"] = "Android"
        }
        else -> {
            if (hwidEnabled) {
                headers["X-Device-Os"] = "Android"
                headers["X-Ver-Os"] = sdkVersion.toString()
                headers["X-Device-Model"] = fullModel
            }
        }
    }

    if (hwid != null) headers["X-Hwid"] = hwid

    return SubscriptionRequestFingerprint(
        userAgent =
            normalizeSpoofUserAgent(spoofApp, customUserAgent)
                .takeIf(String::isNotBlank)
                ?: fallbackUserAgent,
        headers = headers,
    )
}
