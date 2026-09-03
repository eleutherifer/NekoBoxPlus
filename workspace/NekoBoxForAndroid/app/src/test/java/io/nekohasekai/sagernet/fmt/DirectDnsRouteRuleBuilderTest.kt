package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectDnsRouteRuleBuilderTest {

    @Test
    fun `tls and quic direct dns use port 853`() {
        val rule = buildDirectDnsRouteRule(
            """
            tls://1.1.1.1
            quic://[2001:4860:4860::8888]
            """.trimIndent(),
        )!!

        assertEquals(listOf("1.1.1.1/32", "2001:4860:4860::8888/128"), rule.ip_cidr)
        assertEquals(listOf("tcp", "udp"), rule.network)
        assertEquals(listOf(853), rule.port)
        assertEquals(TAG_BYPASS, rule.outbound)
    }

    @Test
    fun `multiple direct dns endpoints aggregate hosts networks and ports`() {
        val rule = buildDirectDnsRouteRule(
            """
            https://dns.example/dns-query
            tls://dns.google
            9.9.9.9:5353
            """.trimIndent(),
        )!!

        assertEquals(listOf("9.9.9.9/32"), rule.ip_cidr)
        assertEquals(listOf("dns.example", "dns.google"), rule.domain)
        assertEquals(listOf("tcp", "udp"), rule.network)
        assertEquals(listOf(443, 853, 5353), rule.port)
    }

    @Test
    fun `blank direct dns does not create route rule`() {
        assertNull(buildDirectDnsRouteRule("\n# comment\n"))
    }
}
