package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionBannerPolicyTest {

    @Test
    fun parsesTrafficAndClampsProgress() {
        val traffic = parseSubscriptionTraffic(
            "upload=40; download=80; total=100; expire=200",
        )

        assertEquals(120L, traffic?.used)
        assertEquals(100L, traffic?.total)
        assertEquals(1000, traffic?.progress)
        assertNull(parseSubscriptionTraffic("0"))
        assertNull(parseSubscriptionTraffic("expire=200"))
    }

    @Test
    fun trafficOnlyBannerUsesEnabledTrafficComponents() {
        val subscription = subscription {
            subscriptionUserinfo = "upload=25;download=25;total=100"
        }

        val presentation = subscriptionBannerPresentation(subscription)

        assertTrue(presentation.visible)
        assertFalse(presentation.hasAnnouncementContent)
        assertTrue(presentation.showTrafficText)
        assertTrue(presentation.showTrafficBar)
        assertEquals(500, presentation.traffic?.progress)
    }

    @Test
    fun expirationMakesBannerVisibleAndRespectsLayout() {
        val subscription = subscription {
            subscriptionUserinfo = "expire=1787501803"
        }

        assertEquals(1_787_501_803L, subscriptionBannerPresentation(subscription).expireAt)
        assertTrue(subscriptionBannerPresentation(subscription).visible)

        subscription.bannerLayout = SubscriptionBannerLayout.TRAFFIC_TEXT
        assertNull(subscriptionBannerPresentation(subscription).expireAt)
        assertFalse(subscriptionBannerPresentation(subscription).visible)
    }

    @Test
    fun formatsExpirationUsingLargestWholeUnit() {
        val now = 1_000_000_000_000L

        assertEquals(
            SubscriptionExpiration.Remaining(20L, SubscriptionExpirationUnit.DAYS),
            subscriptionExpiration(now / 1000L + 20L * 86_400L + 3_599L, now),
        )
        assertEquals(
            SubscriptionExpiration.Remaining(23L, SubscriptionExpirationUnit.HOURS),
            subscriptionExpiration(now / 1000L + 23L * 3_600L + 3_599L, now),
        )
        assertEquals(
            SubscriptionExpiration.Remaining(59L, SubscriptionExpirationUnit.MINUTES),
            subscriptionExpiration(now / 1000L + 59L * 60L + 59L, now),
        )
        assertEquals(
            SubscriptionExpiration.LessThanMinute,
            subscriptionExpiration(now / 1000L + 59L, now),
        )
        assertEquals(
            SubscriptionExpiration.Expired,
            subscriptionExpiration(now / 1000L, now),
        )
        assertEquals(
            SubscriptionExpiration.Expired,
            subscriptionExpiration(now / 1000L - 1L, now),
        )
    }

    @Test
    fun unlimitedTrafficCanDisplayBarWithoutCounterText() {
        val subscription = subscription {
            bannerLayout = SubscriptionBannerLayout.TRAFFIC_BAR
            subscriptionUserinfo = "upload=123;download=456;total=0"
        }

        val presentation = subscriptionBannerPresentation(subscription)

        assertTrue(presentation.visible)
        assertTrue(presentation.showTrafficBar)
        assertNull(presentation.traffic?.progress)
    }

    @Test
    fun disabledVisualComponentsHideBannerEvenWhenLinksExist() {
        val subscription = subscription {
            bannerLayout = SubscriptionBannerLayout.CLICKABLE
            announcementUrl = "https://status.example.com"
            supportUrl = "https://support.example.com"
        }

        assertFalse(subscriptionBannerPresentation(subscription).visible)
    }

    @Test
    fun hiddenAnnouncementUrlRemainsAnAction() {
        val subscription = subscription {
            bannerLayout =
                SubscriptionBannerLayout.ANNOUNCEMENTS or SubscriptionBannerLayout.CLICKABLE
            announcement = "Maintenance"
            announcementUrl = "https://status.example.com"
        }

        val presentation = subscriptionBannerPresentation(subscription)
        val links = subscriptionBannerLinks(subscription)

        assertTrue(presentation.visible)
        assertNull(presentation.announcementUrl)
        assertEquals(SubscriptionBannerDestination.ANNOUNCEMENT, links.single().destination)
    }

    @Test
    fun subscriptionPageUsesDocumentedFallbackOrder() {
        val subscription = subscription {
            profileWebPageUrl = "https://profile.example.com"
            homepage = "https://home.example.com"
            link = "https://subscription.example.com"
        }

        val page = subscriptionBannerLinks(subscription).single {
            it.destination == SubscriptionBannerDestination.SUBSCRIPTION_PAGE
        }

        assertEquals("https://profile.example.com", page.value)
    }

    @Test
    fun filtersUnsafeDestinations() {
        val subscription = subscription {
            announcementUrl = "javascript:alert(1)"
            supportUrl = "file:///tmp/provider"
            supportEmail = "support@example.com\nBcc:attacker@example.com"
            link = "https://safe.example.com"
        }

        val links = subscriptionBannerLinks(subscription)

        assertEquals(1, links.size)
        assertEquals(SubscriptionBannerDestination.SUBSCRIPTION_PAGE, links.single().destination)
    }

    @Test
    fun serializesMetadataAndLayoutWithBackwardCompatibleDefaults() {
        val original = subscription {
            bannerLayout = SubscriptionBannerLayout.ANNOUNCEMENTS
            announcement = "Maintenance"
            announcementUrl = "https://status.example.com"
            supportEmail = "support@example.com"
            expireAt = 1_787_501_803L
        }

        val restored = KryoConverters.deserialize(
            SubscriptionBean(),
            KryoConverters.serialize(original),
        )
        val legacyDefault = SubscriptionBean().apply { initializeDefaultValues() }

        assertEquals(SubscriptionBannerLayout.ANNOUNCEMENTS, restored.bannerLayout)
        assertEquals("Maintenance", restored.announcement)
        assertEquals("https://status.example.com", restored.announcementUrl)
        assertEquals("support@example.com", restored.supportEmail)
        assertEquals(1_787_501_803L, restored.expireAt)
        assertEquals(SubscriptionBannerLayout.ALL, legacyDefault.bannerLayout)
    }

    private fun subscription(block: SubscriptionBean.() -> Unit): SubscriptionBean {
        return SubscriptionBean().apply {
            initializeDefaultValues()
            block()
        }
    }
}
