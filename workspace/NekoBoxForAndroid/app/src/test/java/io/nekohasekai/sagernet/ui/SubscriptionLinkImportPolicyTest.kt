package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionLinkImportPolicyTest {

    @Test
    fun singleHttpsLinkUsesFragmentAsNameAndRemovesItFromStoredLink() {
        val candidate = SubscriptionLinkImportPolicy.singleHttpCandidate(
            "https://example.com/subscription?id=1#My%20Group",
        )

        assertEquals("https://example.com/subscription?id=1", candidate?.link)
        assertEquals("My Group", candidate?.name)
    }

    @Test
    fun singleHttpLinkUsesExistingClipboardCleanup() {
        val candidate = SubscriptionLinkImportPolicy.singleHttpCandidate(
            "Subscription: <http://example.com/path#Office%2BHome>.",
        )

        assertEquals("http://example.com/path", candidate?.link)
        assertEquals("Office+Home", candidate?.name)
    }

    @Test
    fun linkWithoutFragmentNormalizesExistingSubscriptionForMatching() {
        assertEquals(
            "https://example.com/sub",
            SubscriptionLinkImportPolicy.linkWithoutFragment(
                "https://example.com/sub#Previous name",
            ),
        )
    }

    @Test
    fun multipleHttpLinksDoNotProduceCandidate() {
        assertNull(
            SubscriptionLinkImportPolicy.singleHttpCandidate(
                "https://example.com/one\nhttps://example.com/two",
            ),
        )
    }

    @Test
    fun textWithoutHttpLinkDoesNotProduceCandidate() {
        assertNull(SubscriptionLinkImportPolicy.singleHttpCandidate("vmess://example"))
    }

    @Test
    fun detectsSingleHappCryptLink() {
        assertEquals(
            true,
            SubscriptionLinkImportPolicy.isHappCryptLink("  HAPP://CRYPT/encrypted-value  "),
        )
    }

    @Test
    fun doesNotTreatMixedClipboardAsSingleHappCryptLink() {
        assertEquals(
            false,
            SubscriptionLinkImportPolicy.isHappCryptLink(
                "happ://crypt/encrypted-value\nhttps://example.com/subscription",
            ),
        )
    }

    @Test
    fun buildsNamedSubscriptionImportLink() {
        assertEquals(
            "clash://install-config/?url=https%3A%2F%2Fexample.com%2Fsub&name=My%20Group",
            SubscriptionLinkImportPolicy.toImportLink(
                SubscriptionLinkImportPolicy.Candidate(
                    "https://example.com/sub",
                    "My Group",
                ),
            ),
        )
    }
}
