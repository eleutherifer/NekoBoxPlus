package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionMetadataParserTest {
    @Test
    fun responseHeadersOverrideBodyMetadata() {
        val document = SubscriptionDocument(
            body = """
                # profile-title: Body name
                # profile-update-interval: 12
                # support-url: https://body.example
                ss://example
            """.trimIndent(),
            headers = SubscriptionHeaders.of(
                mapOf(
                    "PROFILE-TITLE" to "base64:TmV0d29yayBuYW1l",
                    "Profile-Update-Interval" to "2",
                    "SUPPORT-URL" to "https://header.example",
                ),
            ),
            source = SubscriptionDocument.Source.NETWORK,
        )

        val metadata = SubscriptionMetadataParser.parse(document, isFirstUpdate = true)

        assertEquals("Network name", metadata.suggestedName)
        assertEquals(120, metadata.autoUpdateIntervalMinutes)
        assertEquals("https://header.example", metadata.supportUrl)
    }

    @Test
    fun updateIntervalIsIgnoredAfterFirstUpdate() {
        val document = SubscriptionDocument(
            body = "# profile-update-interval: 2\nss://example",
            source = SubscriptionDocument.Source.CONTENT,
        )

        assertNull(
            SubscriptionMetadataParser.parse(document, isFirstUpdate = false)
                .autoUpdateIntervalMinutes,
        )
    }
}
