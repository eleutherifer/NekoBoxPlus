package io.nekohasekai.sagernet.routing

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class RoutingProfileCodecTest {
    @Test
    fun happAndIncyUseSeparateProcessorsWithSharedMapping() {
        val json = sampleJson()
        val happ = HappRoutingLinkProcessor.process(link("happ", json))
        val incy = IncyRoutingLinkProcessor.process(link("incy", json))

        assertEquals(RoutingProfileFormat.HAPP, happ.format)
        assertEquals(RoutingProfileFormat.INCY, incy.format)
        assertEquals(happ.copy(format = RoutingProfileFormat.INCY), incy)
        assertEquals("Example", happ.name)
    }

    @Test
    fun mapsSupportedSettingsAndRouteOrder() {
        val candidate = HappRoutingLinkProcessor.process(link("happ", sampleJson()))

        assertEquals(
            listOf(
                RoutingSettingKind.REMOTE_DNS,
                RoutingSettingKind.DIRECT_DNS,
                RoutingSettingKind.GEO_ASSETS,
                RoutingSettingKind.DNS_HOSTS,
                RoutingSettingKind.FAKE_DNS,
                RoutingSettingKind.DOMAIN_STRATEGY,
            ),
            candidate.settings.map { it.kind },
        )
        assertEquals("https://dns.example/dns-query", candidate.settings[0].value)
        assertEquals("8.8.8.8", candidate.settings[1].value)
        assertEquals("example.com 192.0.2.1", candidate.settings[3].value)
        assertEquals(
            listOf(
                RoutingRuleKind.PROXY_SITES,
                RoutingRuleKind.PROXY_IP,
                RoutingRuleKind.BLOCK_SITES,
                RoutingRuleKind.BLOCK_IP,
                RoutingRuleKind.DIRECT_SITES,
                RoutingRuleKind.DIRECT_IP,
                RoutingRuleKind.EVERYTHING_DIRECT,
            ),
            candidate.rules.map { it.kind },
        )
    }

    @Test
    fun acceptsBooleanJsonAndUrlSafeUnpaddedBase64() {
        val json = """{"Name":"Boolean values","GlobalProxy":false,"FakeDNS":true}"""
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        val candidate = IncyRoutingLinkProcessor.process("incy://routing/onadd/$payload")

        assertEquals(RoutingRuleKind.EVERYTHING_DIRECT, candidate.rules.single().kind)
        assertEquals("true", candidate.settings.single().value)
    }

    @Test
    fun matchesProviderOnlyWhenBothUrlsMatch() {
        val provider = RoutingProviderCatalog.byId(6)!!
        assertEquals(provider, RoutingProviderCatalog.match(provider.geoipUrl, provider.geositeUrl))
        assertNull(RoutingProviderCatalog.match(provider.geoipUrl, "https://example.com/geosite.dat"))
    }

    @Test
    fun encodeUsesRequestedOnaddSchemeAndRoundTrips() {
        val profile = ExternalRoutingProfile(name = "Round trip", fakeDns = "false")
        val link = RoutingProfileCodec.encode(RoutingProfileFormat.INCY, profile)

        assertTrue(link.startsWith("incy://routing/onadd/"))
        assertEquals("Round trip", RoutingProfileCodec.decode(link).second.name)
        assertTrue(RoutingProfileCodec.supports("incy://routing/add/invalid"))
    }

    @Test
    fun acceptsAddAndOnaddForBothSchemes() {
        for (scheme in listOf("happ", "v2raytun", "incy")) {
            for (action in listOf("add", "onadd")) {
                val original = link(scheme, """{"Name":"$scheme-$action"}""", action)
                assertEquals("$scheme-$action", RoutingProfileCodec.decode(original).second.name)
                assertTrue(RoutingLinkProcessors.forLink(original)!!.supports(original))
            }
        }
    }

    @Test
    fun nekoBoxPlusUsesAddLinkAndFullRules() {
        val json = """{"Name":"Neko","Rules":[{"Name":"Full","Enabled":true,"Domains":"example.com","Outbound":"direct"}],"LastUpdated":123}"""
        val candidate = NekoBoxPlusRoutingLinkProcessor.process(link("sn", json, "add"))

        assertEquals(RoutingProfileFormat.NEKOBOX_PLUS, candidate.format)
        assertEquals("Full", candidate.rules.single().fullRule?.name)
        assertTrue(RoutingProfileCodec.supports(link("sn", json, "add")))
        assertTrue(RoutingLinkProcessors.forLink(link("sn", json, "add")) is NekoBoxPlusRoutingLinkProcessor)
        assertNull(RoutingLinkProcessors.forLink(link("sn", json, "onadd")))
    }

    @Test
    fun nekoBoxPlusEncodeUsesExactAddPrefix() {
        val link = RoutingProfileCodec.encode(
            RoutingProfileFormat.NEKOBOX_PLUS,
            ExternalRoutingProfile(name = "Neko", rules = listOf(StableRoutingRule())),
        )

        assertTrue(link.startsWith("sn://routing/add/"))
        assertEquals("Neko", RoutingProfileCodec.decode(link).second.name)
    }

    @Test
    fun nekoBoxPlusEncodingOmitsFieldsThatDoNotBelongToRuleType() {
        val link = RoutingProfileCodec.encode(
            RoutingProfileFormat.NEKOBOX_PLUS,
            ExternalRoutingProfile(
                rules = listOf(
                    StableRoutingRule(type = "normal", createDnsRule = false, dnsAction = "reject"),
                    StableRoutingRule(
                        type = "dns",
                        outbound = "direct",
                        dnsAction = "reject",
                        dnsStrategy = "ipv4_only",
                        clashMode = "Streaming",
                    ),
                ),
            ),
        )
        val json = String(
            Base64.getDecoder().decode(link.substringAfter("sn://routing/add/")),
            StandardCharsets.UTF_8,
        )
        val rules = JsonParser.parseString(json).asJsonObject.getAsJsonArray("Rules")
        val normal = rules[0].asJsonObject
        val dns = rules[1].asJsonObject

        assertTrue(normal.has("Outbound"))
        assertTrue(normal.has("CreateDnsRule"))
        assertFalse(normal.has("DnsAction"))
        assertFalse(normal.has("DnsDisableCache"))
        assertTrue(dns.has("DnsAction"))
        assertTrue(dns.has("DnsDisableCache"))
        assertFalse(dns.has("DnsStrategy"))
        assertEquals("Streaming", dns.get("ClashMode").asString)
        assertFalse(dns.has("Outbound"))
        assertFalse(dns.has("OutboundHash"))
        assertFalse(dns.has("CreateDnsRule"))
    }

    @Test
    fun nekoBoxPlusImportsCommaSeparatedDnsServers() {
        val json = """{"Name":"DNS","RemoteDns":"https://one.example/dns-query,quic://two.example","DomesticDns":"1.1.1.1,8.8.8.8","Rules":[]}"""
        val candidate = NekoBoxPlusRoutingLinkProcessor.process(link("sn", json, "add"))

        assertEquals(
            "https://one.example/dns-query\nquic://two.example",
            candidate.settings.first { it.kind == RoutingSettingKind.REMOTE_DNS }.value,
        )
        assertEquals(
            "1.1.1.1\n8.8.8.8",
            candidate.settings.first { it.kind == RoutingSettingKind.DIRECT_DNS }.value,
        )
    }

    @Test
    fun nekoBoxPlusCustomDnsUsesNumberForCustomAndStringForBuiltin() {
        val profile = ExternalRoutingProfile(
            customDnsServers = listOf(
                StableCustomDnsServer(id = 1L, tag = "dns-custom", server = "1.1.1.1"),
            ),
            rules = listOf(
                StableRoutingRule(
                    type = "dns",
                    dnsServer = StableDnsServerReference.id(1L),
                ),
                StableRoutingRule(
                    type = "dns",
                    dnsServer = StableDnsServerReference.tag("dns-direct"),
                ),
            ),
        )

        val encoded = RoutingProfileCodec.encode(RoutingProfileFormat.NEKOBOX_PLUS, profile)
        val json = String(
            Base64.getDecoder().decode(encoded.substringAfter("sn://routing/add/")),
            StandardCharsets.UTF_8,
        )
        val root = JsonParser.parseString(json).asJsonObject
        val rules = root.getAsJsonArray("Rules")

        assertEquals(1L, rules[0].asJsonObject.get("DnsServer").asLong)
        assertEquals("dns-direct", rules[1].asJsonObject.get("DnsServer").asString)
        assertEquals(1L, root.getAsJsonArray("CustomDNSServers")[0].asJsonObject.get("Id").asLong)

        val candidate = NekoBoxPlusRoutingLinkProcessor.process(encoded)
        val customDnsSetting = candidate.settings.single {
            it.kind == RoutingSettingKind.CUSTOM_DNS_SERVERS
        }
        assertEquals("dns-custom\nudp://1.1.1.1", customDnsSetting.value)
        assertEquals("dns-custom", candidate.rules[0].resolvedDnsServer)
        assertEquals("dns-direct", candidate.rules[1].resolvedDnsServer)

        assertEquals(
            listOf(candidate.rules[1]),
            RoutingImportManager.selectedImportRules(
                candidate,
                selectedSettings = emptySet(),
                selectedRuleIndexes = setOf(0, 1),
            ),
        )
        assertEquals(
            candidate.rules,
            RoutingImportManager.selectedImportRules(
                candidate,
                selectedSettings = setOf(RoutingSettingKind.CUSTOM_DNS_SERVERS),
                selectedRuleIndexes = setOf(0, 1),
            ),
        )
    }

    @Test
    fun nekoBoxPlusDistinguishesMissingAndEmptyCustomDnsLists() {
        val missing = NekoBoxPlusRoutingLinkProcessor.process(
            link("sn", """{"Rules":[]}""", "add"),
        )
        val empty = NekoBoxPlusRoutingLinkProcessor.process(
            link("sn", """{"Rules":[],"CustomDNSServers":[]}""", "add"),
        )

        assertNull(missing.customDnsServers)
        assertFalse(missing.settings.any { it.kind == RoutingSettingKind.CUSTOM_DNS_SERVERS })
        assertEquals(emptyList<StableCustomDnsServer>(), empty.customDnsServers)
        assertTrue(empty.settings.any { it.kind == RoutingSettingKind.CUSTOM_DNS_SERVERS })
        assertNull(RoutingImportManager.importedCustomDnsServers(missing))
        assertTrue(RoutingImportManager.importedCustomDnsServers(empty).orEmpty().isEmpty())
    }

    private fun link(scheme: String, json: String, action: String = "onadd"): String =
        "$scheme://routing/$action/" + Base64.getEncoder()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))

    private fun sampleJson() = """
        {
          "Name": "Example",
          "GlobalProxy": "false",
          "RemoteDNSType": "DoH",
          "RemoteDNSDomain": "https://dns.example/dns-query",
          "RemoteDNSIP": "1.1.1.1",
          "DomesticDNSType": "DoU",
          "DomesticDNSIP": "8.8.8.8",
          "Geoipurl": "https://example.com/geoip.dat",
          "Geositeurl": "https://example.com/geosite.dat",
          "DnsHosts": {"example.com": "192.0.2.1"},
          "RouteOrder": "proxy-block-direct",
          "DirectSites": ["geosite:direct"],
          "DirectIp": ["geoip:direct"],
          "ProxySites": ["geosite:proxy"],
          "ProxyIp": ["geoip:proxy"],
          "BlockSites": ["geosite:block"],
          "BlockIp": ["geoip:block"],
          "DomainStrategy": "IPOnDemand",
          "FakeDNS": "false",
          "LastUpdated": "123",
          "Unsupported": "ignored"
        }
    """.trimIndent()
}
