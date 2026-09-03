package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.utils.parseSubscriptionUserinfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawUpdaterTest {
    @Test
    fun `unsupported JSON cannot become a destructive empty update`() = runBlocking {
        assertNull(RawUpdater.parseRaw("""{"unrelated":"document"}"""))
    }

    @Test
    fun `parses expiration from subscription userinfo`() {
        assertEquals(
            1_787_501_803L,
            parseSubscriptionUserinfo(
                "upload=0; download=830851009; total=993211187200; expire=1787501803",
            )?.expireAt,
        )
        assertNull(parseSubscriptionUserinfo("upload=1; expire=invalid")?.expireAt)
        assertNull(parseSubscriptionUserinfo("upload=1; expire=-1")?.expireAt)
        assertNull(parseSubscriptionUserinfo("upload=1")?.expireAt)
    }

    @Test
    fun `decodes base64 profile title as UTF-8`() {
        assertEquals("Подписка 🚀", decodeProfileTitle("base64:0J/QvtC00L/QuNGB0LrQsCDwn5qA"))
    }

    @Test
    fun `accepts case-insensitive prefix and unpadded base64`() {
        assertEquals("NekoBox", decodeProfileTitle("BASE64:TmVrb0JveA"))
    }

    @Test
    fun `accepts plain profile title`() {
        assertEquals("My subscription", decodeProfileTitle(" My subscription "))
    }

    @Test
    fun `rejects empty null and malformed profile titles`() {
        assertNull(decodeProfileTitle(""))
        assertNull(decodeProfileTitle("NULL"))
        assertNull(decodeProfileTitle("base64:"))
        assertNull(decodeProfileTitle("base64:not%base64"))
    }

    @Test
    fun `converts first update interval from hours to minutes`() {
        assertEquals(720, profileUpdateIntervalMinutes(" 12 ", isFirstUpdate = true))
    }

    @Test
    fun `ignores update interval after first update`() {
        assertNull(profileUpdateIntervalMinutes("12", isFirstUpdate = false))
    }

    @Test
    fun `merge preserves user auto update settings after provider defaults were consumed`() {
        val current = SubscriptionBean().applyDefaultValues().apply {
            link = "https://new.example/sub"
            autoUpdate = false
            autoUpdateDelay = 180
            providerAutoUpdateDefaultsApplied = true
        }
        val updated = SubscriptionBean().applyDefaultValues().apply {
            link = "https://old.example/sub"
            autoUpdate = true
            autoUpdateDelay = 60
        }

        mergeCurrentSubscriptionSettings(current, updated)

        assertEquals("https://new.example/sub", updated.link)
        assertEquals(false, updated.autoUpdate)
        assertEquals(180, updated.autoUpdateDelay)
        assertEquals(true, updated.providerAutoUpdateDefaultsApplied)
    }

    @Test
    fun `merge keeps first provider defaults when they were not consumed`() {
        val current = SubscriptionBean().applyDefaultValues().apply {
            autoUpdate = false
            autoUpdateDelay = 1440
            providerAutoUpdateDefaultsApplied = false
        }
        val updated = SubscriptionBean().applyDefaultValues().apply {
            autoUpdate = true
            autoUpdateDelay = 360
        }

        mergeCurrentSubscriptionSettings(current, updated)

        assertEquals(true, updated.autoUpdate)
        assertEquals(360, updated.autoUpdateDelay)
        assertEquals(true, updated.providerAutoUpdateDefaultsApplied)
    }

    @Test
    fun `subscription serialization preserves consumed provider defaults`() {
        val subscription = SubscriptionBean().applyDefaultValues().apply {
            providerAutoUpdateDefaultsApplied = true
        }

        val restored = KryoConverters.subscriptionDeserialize(KryoConverters.serialize(subscription))

        assertEquals(true, restored.providerAutoUpdateDefaultsApplied)
    }

    @Test
    fun `rejects invalid update intervals`() {
        assertNull(profileUpdateIntervalMinutes("", isFirstUpdate = true))
        assertNull(profileUpdateIntervalMinutes("1.5", isFirstUpdate = true))
        assertNull(profileUpdateIntervalMinutes("0", isFirstUpdate = true))
        assertNull(profileUpdateIntervalMinutes("-1", isFirstUpdate = true))
        assertNull(profileUpdateIntervalMinutes(Long.MAX_VALUE.toString(), isFirstUpdate = true))
    }

    @Test
    fun `reads Xray metadata from subscription body header`() {
        val headers = parseXraySubscriptionBodyHeaders(
            """
                ﻿# profile-title: 🏴 ЧЕРНЫЕ СПИСКИ 🏴 BLACK LISTS
                # PROFILE-UPDATE-INTERVAL: 1
                # subscription-userinfo: upload=29; download=12; total=10737418240000000

                ss://example
            """.trimIndent()
        )

        assertEquals("🏴 ЧЕРНЫЕ СПИСКИ 🏴 BLACK LISTS", headers.profileTitle)
        assertEquals("1", headers.profileUpdateInterval)
        assertEquals(
            "upload=29; download=12; total=10737418240000000",
            headers.subscriptionUserinfo,
        )
    }

    @Test
    fun `only reads metadata from the file header`() {
        val headers = parseXraySubscriptionBodyHeaders(
            """
                # an ordinary header comment
                vmess://example
                # profile-title: ignored
                # profile-update-interval: 24
            """.trimIndent()
        )

        assertNull(headers.profileTitle)
        assertNull(headers.profileUpdateInterval)
        assertNull(headers.subscriptionUserinfo)
    }

    @Test
    fun `keeps first duplicate body header value`() {
        val headers = parseXraySubscriptionBodyHeaders(
            """
                #profile-title: first
                # profile-title: second
                #profile-update-interval: 6
                #profile-update-interval: 12
            """.trimIndent()
        )

        assertEquals("first", headers.profileTitle)
        assertEquals("6", headers.profileUpdateInterval)
    }

    @Test
    fun `response metadata takes precedence over body metadata`() {
        assertEquals("response", responseOrBodyHeader("response", "body"))
        assertEquals("body", responseOrBodyHeader("", "body"))
        assertEquals("body", responseOrBodyHeader("   ", "body"))
    }

    @Test
    fun `reads banner and support metadata from body headers`() {
        val headers = parseXraySubscriptionBodyHeaders(
            """
                # announce: base64:U2NoZWR1bGVkIG1haW50ZW5hbmNl
                # announce-url: https://status.example.com
                # support-url: https://support.example.com
                # support-email: support@example.com
                # profile-web-page-url: https://account.example.com
                # homepage: https://example.com
                vless://example
            """.trimIndent(),
        )

        assertEquals("base64:U2NoZWR1bGVkIG1haW50ZW5hbmNl", headers.announcement)
        assertEquals("https://status.example.com", headers.announcementUrl)
        assertEquals("https://support.example.com", headers.supportUrl)
        assertEquals("support@example.com", headers.supportEmail)
        assertEquals("https://account.example.com", headers.profileWebPageUrl)
        assertEquals("https://example.com", headers.homepage)
    }
}
