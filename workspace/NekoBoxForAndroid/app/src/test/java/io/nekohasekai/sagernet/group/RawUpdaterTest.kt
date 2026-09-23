package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawUpdaterTest {
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
}
