package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCountryResolverTest {
    @Test
    fun extractsFirstFlagWithoutChangingOffsets() {
        val flag = ProfileCountryResolver.flagFromName("Fast 🇳🇱 node 🇩🇪")

        assertEquals("NL", flag?.code)
        assertEquals("🇳🇱", flag?.let { "Fast 🇳🇱 node 🇩🇪".substring(it.start, it.end) })
    }

    @Test
    fun ignoresSingleRegionalIndicator() {
        assertNull(ProfileCountryResolver.flagFromName("Node 🇳"))
    }

    @Test
    fun effectiveCountryPrefersResolvedProfileCountry() {
        val profile = ProxyEntity(countryCode = "DE").apply {
            socksBean = SOCKSBean().apply { name = "Fallback 🇳🇱" }
        }

        assertEquals("DE", ProfileCountryResolver.effectiveCountryCode(profile))
    }

    @Test
    fun effectiveCountryFallsBackToProfileNameFlag() {
        val profile = ProxyEntity().apply {
            socksBean = SOCKSBean().apply {
                name = "Fallback 🇳🇱"
            }
        }

        assertEquals("NL", ProfileCountryResolver.effectiveCountryCode(profile))
    }

    @Test
    fun presentationNameRemovesDisplayedCountryFlag() {
        val profile = ProxyEntity().apply {
            socksBean = SOCKSBean().apply { name = "Fallback 🇳🇱 node" }
        }

        assertEquals("Fallback node", ProfileCountryResolver.presentationName(profile, true))
        assertEquals("Fallback 🇳🇱 node", ProfileCountryResolver.presentationName(profile, false))
    }

    @Test
    fun outboundCountryHasHighestPrecedence() {
        assertFalse(
            ProfileCountryResolver.canReplace(
                ProfileCountryResolver.SOURCE_OUTBOUND,
                ProfileCountryResolver.SOURCE_ENDPOINT,
            )
        )
        assertTrue(
            ProfileCountryResolver.canReplace(
                ProfileCountryResolver.SOURCE_OUTBOUND,
                ProfileCountryResolver.SOURCE_OUTBOUND,
            )
        )
    }

    @Test
    fun outboundReplacesNameAndEndpointInference() {
        assertTrue(
            ProfileCountryResolver.canReplace(
                ProfileCountryResolver.SOURCE_NAME,
                ProfileCountryResolver.SOURCE_OUTBOUND,
            )
        )
        assertTrue(
            ProfileCountryResolver.canReplace(
                ProfileCountryResolver.SOURCE_ENDPOINT,
                ProfileCountryResolver.SOURCE_OUTBOUND,
            )
        )
    }

    @Test
    fun domainLimitDoesNotCountLiteralIpProfiles() {
        val addresses = listOf("192.0.2.1") +
            (1..12).flatMap { listOf("node$it.example", "2001:db8::$it") }

        val selected = ProfileCountryResolver.domainLookupIndexes(addresses)

        assertEquals(10, selected.size)
        assertEquals((1..10).map { "node$it.example" }, selected.map(addresses::get))
    }
}
