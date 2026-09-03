package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.CustomDnsServerEntity
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleType
import moe.matsuri.nb4a.SingBoxOptions.Outbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CustomDnsConfigBuilderTest {

    @Test
    fun customHttpsServerBuildsTypedOptions() {
        val server = buildCustomDnsServerOptions(
            CustomDnsServerEntity(
                tag = "dns-custom",
                type = "https",
                server = "dns.example",
                serverPort = 8443,
                path = "/query",
                method = "POST",
                headers = "User-Agent: NB4A",
                domainResolver = "dns-direct",
                domainStrategy = "prefer_ipv4",
                tlsServerName = "dns.example",
            ),
            "effective-proxy-tag",
        ).asMap()

        assertEquals("https", server["type"])
        assertEquals("dns-custom", server["tag"])
        assertEquals("dns.example", server["server"])
        assertEquals(8443L, server["server_port"])
        assertEquals("/query", server["path"])
        assertEquals("POST", server["method"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("NB4A", (server["headers"] as Map<String, String>)["User-Agent"])
        assertNotNull(server["tls"])
        assertEquals("effective-proxy-tag", server["detour"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("dns-direct", (server["domain_resolver"] as Map<String, Any>)["server"])
    }

    @Test
    fun customDnsDetourValuesAreNormalizedAndResolved() {
        assertEquals(TAG_DIRECT, normalizeCustomDnsDetour(""))
        assertEquals(TAG_DIRECT, normalizeCustomDnsDetour("direct"))
        assertEquals(TAG_PROXY, normalizeCustomDnsDetour(" proxy "))
        assertEquals(TAG_DIRECT, normalizeCustomDnsDetour("legacy-custom-tag"))

        val proxy = Outbound().apply {
            type = "vless"
            tag = "generated-main-tag"
        }
        val direct = Outbound().apply {
            type = TAG_DIRECT
            tag = "generated-direct-tag"
        }

        assertEquals("", customDnsDetourTag("", "generated-main-tag", listOf(proxy)))
        assertEquals("", customDnsDetourTag("direct", "generated-main-tag", listOf(proxy)))
        assertEquals("generated-main-tag", customDnsDetourTag("proxy", "generated-main-tag", listOf(proxy)))
        assertEquals("", customDnsDetourTag("proxy", "generated-direct-tag", listOf(direct)))
        assertEquals("", customDnsDetourTag("legacy-custom-tag", "generated-main-tag", listOf(proxy)))
    }

    @Test
    fun customDnsServerOmitsEmptyDetour() {
        val server = buildCustomDnsServerOptions(
            CustomDnsServerEntity(tag = "dns-custom", server = "1.1.1.1"),
            "",
        ).asMap()

        assertFalse(server.containsKey("detour"))
    }

    @Test
    fun validationRejectsReservedAndDuplicateTags() {
        val existing = listOf(CustomDnsServerEntity(id = 1L, tag = "dns-custom", server = "1.1.1.1"))

        assertNotNull(validateCustomDnsServer(CustomDnsServerEntity(tag = "dns-direct"), existing))
        assertNotNull(validateCustomDnsServer(CustomDnsServerEntity(tag = "dns-custom", server = "1.0.0.1"), existing))
        assertNull(validateCustomDnsServer(CustomDnsServerEntity(id = 1L, tag = "dns-custom", server = "1.1.1.1"), existing))
    }

    @Test
    fun validationRequiresBuiltinDomainResolverForDomainServers() {
        assertNotNull(
            validateCustomDnsServer(
                CustomDnsServerEntity(tag = "dns-custom", type = "https", server = "dns.example"),
                emptyList(),
            ),
        )
        assertNotNull(
            validateCustomDnsServer(
                CustomDnsServerEntity(
                    tag = "dns-custom",
                    type = "https",
                    server = "dns.example",
                    domainResolver = "dns-custom-resolver",
                ),
                emptyList(),
            ),
        )
        assertNull(
            validateCustomDnsServer(
                CustomDnsServerEntity(
                    tag = "dns-custom",
                    type = "https",
                    server = "dns.example",
                    domainResolver = "dns-direct",
                ),
                emptyList(),
            ),
        )
    }

    @Test
    fun standaloneDnsRuleRoutesToCustomServer() {
        val rule = RuleEntity(
            type = RuleType.DNS.value,
            domains = "domain:example.com",
            dnsAction = "route",
            dnsServer = "dns-custom",
            dnsStrategy = "prefer_ipv6",
            config = """{"disable_cache":true}""",
        )

        val map = buildStandaloneDnsRule(rule, setOf("dns-custom"))!!.asMap()

        assertEquals("dns-custom", map["server"])
        assertFalse(map.containsKey("strategy"))
        assertEquals(listOf("example.com"), map["domain_suffix"])
        assertEquals(true, map["disable_cache"])
    }

    @Test
    fun standaloneDnsRuleSkipsMissingCustomServer() {
        val rule = RuleEntity(
            type = RuleType.DNS.value,
            domains = "domain:example.com",
            dnsAction = "route",
            dnsServer = "dns-missing",
        )

        assertNull(buildStandaloneDnsRule(rule, emptySet()))
    }

    @Test
    fun dnsBlockTargetUsesPredefinedNoError() {
        val rule = RuleEntity(
            type = RuleType.DNS.value,
            domains = "domain:ads.example",
            dnsAction = "predefined",
            dnsRcode = "NOERROR",
        )

        val map = buildStandaloneDnsRule(rule, emptySet())!!.asMap()

        assertEquals("predefined", map["action"])
        assertEquals("NOERROR", map["rcode"])
        assertFalse(map.containsKey("server"))
    }

    @Test
    fun connectionIpResolveHostReturnsOnlyDomainNames() {
        assertEquals("ipv4.ipleak.net", connectionIpResolveHost("https://ipv4.ipleak.net/json/"))
        assertNull(connectionIpResolveHost("https://192.0.2.1/json/"))
        assertNull(connectionIpResolveHost("not a URL"))
    }
}
