package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.routing.RoutingSettingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDnsPlannerTest {
    private val preferences = ConfigDnsPreferences(
        remoteDns = "# ignored\nhttps://default.example/dns-query",
        directDns = "8.8.8.8",
        domainOverrides = "default.example 192.0.2.1",
        fakeDns = false,
        resolveDestination = false,
        ipv6Mode = IPv6Mode.DISABLE,
    )

    @Test
    fun subscriptionRoutingOverridesDnsPreferences() {
        val plan = ConfigDnsPlanner.plan(
            preferences,
            mapOf(
                RoutingSettingKind.REMOTE_DNS to "https://subscription.example/dns-query",
                RoutingSettingKind.DIRECT_DNS to "1.1.1.1",
                RoutingSettingKind.DNS_HOSTS to "subscription.example 198.51.100.1",
                RoutingSettingKind.FAKE_DNS to "true",
                RoutingSettingKind.DOMAIN_STRATEGY to "true",
            ),
            forTest = false,
        )

        assertEquals(listOf("https://subscription.example/dns-query"), plan.remoteServers)
        assertEquals(listOf("1.1.1.1"), plan.directServers)
        assertEquals(listOf("198.51.100.1"), plan.domainOverrides["subscription.example"])
        assertTrue(plan.useFakeDns)
        assertTrue(plan.resolveDestination)
        assertEquals(IPv6Mode.DISABLE, plan.ipv6Mode)
    }

    @Test
    fun testModeDisablesFakeDnsAndOverridesWhileEnablingIpv6() {
        val plan = ConfigDnsPlanner.plan(preferences.copy(fakeDns = true), emptyMap(), forTest = true)

        assertFalse(plan.useFakeDns)
        assertTrue(plan.domainOverrides.isEmpty())
        assertEquals(IPv6Mode.ENABLE, plan.ipv6Mode)
    }
}
