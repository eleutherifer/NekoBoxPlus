package io.nekohasekai.sagernet.routing

import io.nekohasekai.sagernet.database.CustomDnsServerEntity
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingRuleConvertersTest {
    @Test
    fun convertsXrayDomainSemanticsToSingBox() {
        val result = XrayRoutingValueConverter.xrayToSingBox(
            listOf(
                "plain",
                "domain:example.com",
                "full:full.example",
                "ext:geosite.dat:cn",
                "dotless:host",
            ),
            domains = true,
        )

        assertEquals(
            listOf("keyword:plain", "domain:example.com", "full:full.example", "geosite:cn"),
            result.values,
        )
        assertTrue(result.omitted)
    }

    @Test
    fun convertsSingBoxSuffixAndRejectsUnsupportedXrayIpForms() {
        assertEquals(
            listOf("domain:example.com", "keyword:needle"),
            XrayRoutingValueConverter.singBoxToXray(
                listOf("example.com", "keyword:needle"),
                domains = true,
            ).values,
        )
        val ips = XrayRoutingValueConverter.xrayToSingBox(
            listOf("ext:geoip.dat:cn", "!10.0.0.0/8", "192.0.2.0/24"),
            domains = false,
        )
        assertEquals(listOf("geoip:cn", "192.0.2.0/24"), ips.values)
        assertTrue(ips.omitted)
    }

    @Test
    fun dnsRuleImportAndExportIgnoreNormalOnlyFields() {
        val entity = RuleEntity(
            id = 99,
            userOrder = 42,
            type = "dns",
            name = "Complete",
            config = "{}",
            enabled = true,
            domains = "full:example.com",
            ip = "192.0.2.1",
            port = "443",
            sourcePort = "1234",
            networkType = setOf("wifi"),
            wifiSsid = "SSID",
            wifiBssid = "00:11:22:33:44:55",
            network = "tcp",
            source = "10.0.0.0/8",
            protocol = "tls",
            ruleset = "geosite:cn",
            clashMode = "direct",
            outbound = -1,
            packages = setOf("example.app"),
            createDnsRule = false,
            dnsAction = "reject",
            dnsServer = "dns-direct",
            dnsStrategy = "ipv4_only",
            dnsDisableCache = true,
            dnsRewriteTtl = 60,
            dnsClientSubnet = "192.0.2.0/24",
            dnsRcode = "REFUSED",
            dnsRejectMethod = "drop",
            dnsPredefinedAnswer = "answer",
            dnsPredefinedNs = "ns",
            dnsPredefinedExtra = "extra",
        )

        val stable = StableRoutingRuleMapper.export(entity).rule
        val restored = StableRoutingRuleMapper.import(stable, -1)
        assertEquals(0L, restored.outbound)
        assertTrue(restored.createDnsRule)
        assertEquals("reject", restored.dnsAction)
        assertEquals("", stable.dnsStrategy)
        assertEquals("", restored.dnsStrategy)
        assertEquals("extra", restored.dnsPredefinedExtra)
        assertEquals("dns-direct", stable.dnsServer.tag)
        assertNull(stable.outboundHash)
    }

    @Test
    fun normalRuleImportAndExportIgnoreDnsOnlyFields() {
        val entity = RuleEntity(
            type = "normal",
            enabled = true,
            outbound = -1L,
            createDnsRule = false,
            dnsAction = "reject",
            dnsServer = "dns-direct",
            dnsDisableCache = true,
            dnsRewriteTtl = 60,
            dnsPredefinedAnswer = "answer",
        )

        val stable = StableRoutingRuleMapper.export(entity).rule
        val restored = StableRoutingRuleMapper.import(stable, -1L)

        assertFalse(restored.createDnsRule)
        assertEquals(-1L, restored.outbound)
        assertEquals("route", restored.dnsAction)
        assertEquals("", restored.dnsServer)
        assertFalse(restored.dnsDisableCache)
        assertEquals(0, restored.dnsRewriteTtl)
        assertEquals("", restored.dnsPredefinedAnswer)
    }

    @Test
    fun clashModeRoundTripsThroughStableRule() {
        val stable = StableRoutingRuleMapper.export(RuleEntity(clashMode = "Streaming")).rule
        val restored = StableRoutingRuleMapper.import(stable, 0L)

        assertEquals("Streaming", stable.clashMode)
        assertEquals("Streaming", restored.clashMode)
    }

    @Test
    fun outboundHashResolverScansProfilesOnceAndStopsWhenResolved() {
        val profiles = listOf(ProxyEntity(id = 3), ProxyEntity(id = 1), ProxyEntity(id = 2))
        val calls = mutableListOf<Long>()
        val result = RoutingOutboundHashResolver.resolve(setOf("wanted"), profiles) { profile ->
            calls += profile.id
            if (profile.id == 2L) "wanted" else "other-${profile.id}"
        }

        assertEquals(listOf(1L, 2L), calls)
        assertEquals(2L, result.getValue("wanted").id)
    }

    @Test
    fun customOutboundUsesOnlyStableHashAndResolvedLocalId() {
        val exported = StableRoutingRuleMapper.export(
            RuleEntity(enabled = true, outbound = 99L),
        ) { outbound -> "profile-hash-$outbound" }

        assertEquals(StableRoutingOutbound.CUSTOM, exported.rule.outbound)
        assertEquals("profile-hash-99", exported.rule.outboundHash)
        assertFalse(exported.customOutboundFallback)
        assertEquals(7L, StableRoutingRuleMapper.import(exported.rule, 7L).outbound)

        val missing = StableRoutingRuleMapper.export(RuleEntity(outbound = 99L)) { null }
        assertEquals(StableRoutingOutbound.PROXY, missing.rule.outbound)
        assertNull(missing.rule.outboundHash)
        assertTrue(missing.customOutboundFallback)
    }

    @Test
    fun customDnsRuleUsesStableNumericReference() {
        val stable = StableRoutingRuleMapper.export(
            RuleEntity(type = "dns", dnsServer = "dns-custom"),
            customDnsServerId = { tag -> if (tag == "dns-custom") 3L else null },
        ).rule

        assertEquals(3L, stable.dnsServer.id)
        assertNull(stable.dnsServer.tag)
        assertEquals(
            "dns-custom",
            StableRoutingRuleMapper.import(stable, 0L, "dns-custom").dnsServer,
        )
    }

    @Test
    fun stableCustomDnsServerRoundTripsEverySetting() {
        val original = CustomDnsServerEntity(
            id = 77L,
            tag = "dns-custom",
            type = "https",
            userOrder = 42L,
            enabled = false,
            server = "dns.example",
            serverPort = 8443,
            path = "/dns-query",
            method = "POST",
            headers = "X-Test: value",
            domainResolver = "dns-direct",
            domainStrategy = "ipv4_only",
            disableCache = true,
            rewriteTtl = 60,
            clientSubnet = "192.0.2.0/24",
            detour = "proxy",
            bindInterface = "wlan0",
            inet4BindAddress = "192.0.2.2",
            inet6BindAddress = "2001:db8::2",
            connectTimeout = 5000L,
            tcpFastOpen = true,
            tcpMultiPath = true,
            udpFragment = "enabled",
            tlsServerName = "dns.example",
            tlsInsecure = true,
            tlsAlpn = "h2",
            tlsCertificates = "certificate",
            localPreferGo = true,
        )

        val stable = StableCustomDnsServerMapper.export(original, 1L)
        val restored = StableCustomDnsServerMapper.import(stable, 9L)

        assertEquals(1L, stable.id)
        assertEquals(original.copy(id = 9L, userOrder = 9L), restored)
    }
}
