package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.IPv6Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DnsStrategyPolicyTest {

    @Test
    fun tunDnsAddressesFollowIpv6Mode() {
        val ipv4Address = "172.19.0.2"
        val ipv6Address = "fdfe:dcba:9876::2"

        assertEquals(
            listOf(ipv4Address),
            tunDnsAddressesForIpv6Mode(IPv6Mode.DISABLE, ipv4Address, ipv6Address),
        )
        assertEquals(
            listOf(ipv4Address, ipv6Address),
            tunDnsAddressesForIpv6Mode(IPv6Mode.ENABLE, ipv4Address, ipv6Address),
        )
        assertEquals(
            listOf(ipv4Address, ipv6Address),
            tunDnsAddressesForIpv6Mode(IPv6Mode.PREFER, ipv4Address, ipv6Address),
        )
        assertEquals(
            listOf(ipv6Address),
            tunDnsAddressesForIpv6Mode(IPv6Mode.ONLY, ipv4Address, ipv6Address),
        )
    }

    @Test
    fun singleFamilyModesOverrideConfiguredDnsStrategy() {
        assertEquals("ipv4_only", dnsStrategyForIpv6Mode("prefer_ipv6", IPv6Mode.DISABLE))
        assertEquals("ipv6_only", dnsStrategyForIpv6Mode("prefer_ipv4", IPv6Mode.ONLY))
        assertEquals("ipv4_only", strictDnsStrategyForIpv6Mode(IPv6Mode.DISABLE))
        assertEquals("ipv6_only", strictDnsStrategyForIpv6Mode(IPv6Mode.ONLY))
    }

    @Test
    fun dualStackModesKeepConfiguredDnsStrategyAndDefaults() {
        assertEquals("ipv6_only", dnsStrategyForIpv6Mode("ipv6_only", IPv6Mode.ENABLE))
        assertEquals("prefer_ipv4", dnsStrategyForIpv6Mode("", IPv6Mode.ENABLE))
        assertEquals("prefer_ipv6", dnsStrategyForIpv6Mode("", IPv6Mode.PREFER))
        assertNull(strictDnsStrategyForIpv6Mode(IPv6Mode.ENABLE))
        assertNull(strictDnsStrategyForIpv6Mode(IPv6Mode.PREFER))
    }

    @Test
    fun singleFamilyModesForceDestinationResolution() {
        assertEquals("ipv4_only", destinationStrategyForIpv6Mode(false, IPv6Mode.DISABLE))
        assertEquals("ipv6_only", destinationStrategyForIpv6Mode(false, IPv6Mode.ONLY))
        assertEquals("", destinationStrategyForIpv6Mode(false, IPv6Mode.ENABLE))
        assertEquals("prefer_ipv6", destinationStrategyForIpv6Mode(true, IPv6Mode.PREFER))
    }

    @Test
    fun unsupportedDnsAddressFamilyGetsEmptySuccessfulResponse() {
        val ipv4OnlyRule = buildAddressFamilyFilterDnsRule(IPv6Mode.DISABLE)!!.asMap()
        assertEquals(6L, ipv4OnlyRule["ip_version"])
        assertEquals("predefined", ipv4OnlyRule["action"])
        assertEquals("NOERROR", ipv4OnlyRule["rcode"])
        assertFalse(ipv4OnlyRule.containsKey("strategy"))

        val ipv6OnlyRule = buildAddressFamilyFilterDnsRule(IPv6Mode.ONLY)!!.asMap()
        assertEquals(4L, ipv6OnlyRule["ip_version"])
        assertNull(buildAddressFamilyFilterDnsRule(IPv6Mode.ENABLE))
    }
}
