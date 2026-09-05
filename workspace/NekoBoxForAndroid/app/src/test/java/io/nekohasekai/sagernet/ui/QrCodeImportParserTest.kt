package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeImportParserTest {
    @Test
    fun `parses a proxy QR payload`() = runBlocking {
        val result = QrCodeImportParser.parse(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443" +
                    "?type=tcp&encryption=none#server",
        )

        assertTrue(result is QrCodeImportResult.Profiles)
        assertEquals(1, (result as QrCodeImportResult.Profiles).profiles.size)
    }

    @Test
    fun `routes a subscription QR payload`() = runBlocking {
        val link = "https://example.com/subscription"

        assertEquals(
            QrCodeImportResult.Subscription(
                SubscriptionLinkImportPolicy.Candidate(link, null),
            ),
            QrCodeImportParser.parse(link),
        )
    }

    @Test
    fun `preserves subscription name from QR payload fragment`() = runBlocking {
        assertEquals(
            QrCodeImportResult.Subscription(
                SubscriptionLinkImportPolicy.Candidate(
                    "https://example.com/subscription",
                    "My Group",
                ),
            ),
            QrCodeImportParser.parse("https://example.com/subscription#My%20Group"),
        )
    }

    @Test
    fun `routes explicit subscription schemes`() = runBlocking {
        listOf(
            "clash://install-config?url=https%3A%2F%2Fexample.com%2Fsubscription",
            "clash://install-config/?url=https%3A%2F%2Fexample.com%2Fsubscription",
            "sn://subscription?url=https%3A%2F%2Fexample.com%2Fsubscription",
        ).forEach { link ->
            assertEquals(
                QrCodeImportResult.Subscription(
                    SubscriptionLinkImportPolicy.Candidate(link, null),
                ),
                QrCodeImportParser.parse(link),
            )
        }
    }

    @Test
    fun `keeps authenticated HTTP proxy QR payload as profile`() = runBlocking {
        val result = QrCodeImportParser.parse("https://user:password@example.com:443#proxy")

        assertTrue(result is QrCodeImportResult.Profiles)
    }

    @Test
    fun `returns empty result for unsupported content`() = runBlocking {
        assertSame(QrCodeImportResult.Empty, QrCodeImportParser.parse("not a proxy"))
    }
}
