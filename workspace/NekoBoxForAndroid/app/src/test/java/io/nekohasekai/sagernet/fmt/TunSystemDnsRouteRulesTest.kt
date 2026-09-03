package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunSystemDnsRouteRulesTest {

    @Test
    fun everySystemDnsRuleIsRestrictedToAndroidDnsUid() {
        val rules =
            buildTunSystemDnsRouteRules(
                dnsWhitelist = "1.1.1.1",
                dotWhitelist = "dns.google",
                dohWhitelist = "cloudflare-dns.com",
                outboundTag = TAG_BYPASS,
            )

        assertEquals(3, rules.size)
        rules.forEach { rule ->
            assertEquals(listOf(1051), rule.user_id)
            assertEquals(listOf(TAG_TUN), rule.inbound)
            assertEquals(TAG_BYPASS, rule.outbound)
            assertEquals(listOf(1051L), rule.asMap()["user_id"])
        }
    }

    @Test
    fun dohRuleRetainsResolverEndpointMatchers() {
        val rule =
            buildTunSystemDnsRouteRules(
                dnsWhitelist = "",
                dotWhitelist = "",
                dohWhitelist = "1.1.1.1, CLOUDFLARE-DNS.COM",
                outboundTag = TAG_PROXY,
            ).single()

        assertEquals(listOf("cloudflare-dns.com"), rule.domain)
        assertEquals(listOf("1.1.1.1/32"), rule.ip_cidr)
        assertEquals(listOf("tcp", "udp"), rule.network)
        assertEquals(listOf("tls", "quic"), rule.protocol)
        assertEquals(listOf(443), rule.port)
        assertEquals(TAG_PROXY, rule.outbound)
    }

    @Test
    fun emptyWhitelistsDoNotCreateRules() {
        val rules =
            buildTunSystemDnsRouteRules(
                dnsWhitelist = "",
                dotWhitelist = "",
                dohWhitelist = "",
                outboundTag = TAG_PROXY,
            )

        assertTrue(rules.isEmpty())
    }
}
