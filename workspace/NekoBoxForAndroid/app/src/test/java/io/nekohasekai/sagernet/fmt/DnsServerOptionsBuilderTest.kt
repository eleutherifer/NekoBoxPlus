package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.Outbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsServerOptionsBuilderTest {

    @Test
    fun `url test profile server uses local dns bootstrap`() {
        val testResolver =
            buildProfileServerDomainResolverConfig(
                forTest = true,
                remoteStrategy = "prefer_ipv4",
                directStrategy = "prefer_ipv6",
            ).asMap()
        val regularResolver =
            buildProfileServerDomainResolverConfig(
                forTest = false,
                remoteStrategy = "prefer_ipv4",
                directStrategy = "prefer_ipv6",
            ).asMap()

        assertEquals("dns-local", testResolver["server"])
        assertEquals("prefer_ipv4", testResolver["strategy"])
        assertEquals("dns-direct", regularResolver["server"])
        assertEquals("prefer_ipv6", regularResolver["strategy"])
    }

    @Test
    fun `plain address builds sing-box udp dns server`() {
        val server = buildDnsServerOptions("1.1.1.1", "dns-direct", detourValue = TAG_DIRECT)
        val map = server.asMap()

        assertEquals("udp", map["type"])
        assertEquals("dns-direct", map["tag"])
        assertEquals("1.1.1.1", map["server"])
        assertFalse(map.containsKey("detour"))
        assertFalse(map.containsKey("address"))
        assertFalse(map.containsKey("address_resolver"))
        assertFalse(map.containsKey("server_port"))
    }

    @Test
    fun `https dns query default path is omitted`() {
        val server = buildDnsServerOptions("https://8.8.8.8/dns-query", "dns-remote")
        val map = server.asMap()

        assertEquals("https", map["type"])
        assertEquals("8.8.8.8", map["server"])
        assertFalse(map.containsKey("path"))
        assertFalse(map.containsKey("address"))
    }

    @Test
    fun `remote dns server can be detoured through proxy outbound`() {
        val server = buildDnsServerOptions("https://8.8.8.8/dns-query", "dns-remote", detourValue = TAG_PROXY)
        val map = server.asMap()

        assertEquals("https", map["type"])
        assertEquals("8.8.8.8", map["server"])
        assertEquals(TAG_PROXY, map["detour"])
    }

    @Test
    fun `single address dns list keeps concrete server shape`() {
        val server = buildDnsServerOptions(
            rawAddresses = listOf("https://8.8.8.8/dns-query"),
            tagValue = "dns-remote",
            detourValue = TAG_PROXY,
        ).asMap()

        assertEquals("https", server["type"])
        assertEquals("dns-remote", server["tag"])
        assertEquals("8.8.8.8", server["server"])
        assertEquals(TAG_PROXY, server["detour"])
        assertFalse(server.containsKey("servers"))
    }

    @Test
    fun `multiple direct dns addresses build balancer children`() {
        val server = buildDnsServerOptions(
            rawAddresses = listOf("1.1.1.1", "tls://dns.google"),
            tagValue = "dns-direct",
            detourValue = TAG_DIRECT,
            domainResolver = buildDomainResolverConfig("dns-local"),
        ).asMap()
        @Suppress("UNCHECKED_CAST")
        val children = server["servers"] as List<Map<String, Any>>

        assertEquals("balancer", server["type"])
        assertEquals("dns-direct", server["tag"])
        assertFalse(server.containsKey("detour"))
        assertFalse(server.containsKey("domain_resolver"))
        assertEquals(3, children.size)
        assertEquals("dns-direct-1", children[0]["tag"])
        assertEquals("udp", children[0]["type"])
        assertEquals("1.1.1.1", children[0]["server"])
        assertFalse(children[0].containsKey("detour"))
        assertEquals("dns-direct-2", children[1]["tag"])
        assertEquals("tcp", children[1]["type"])
        assertEquals("1.1.1.1", children[1]["server"])
        assertFalse(children[1].containsKey("detour"))
        assertEquals("dns-direct-3", children[2]["tag"])
        assertEquals("tls", children[2]["type"])
        assertEquals("dns.google", children[2]["server"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("dns-local", (children[2]["domain_resolver"] as Map<String, Any>)["server"])
    }

    @Test
    fun `multiple remote dns addresses put routing options on balancer children`() {
        val server = buildDnsServerOptions(
            rawAddresses = listOf("https://dns.example/dns-query", "h3://8.8.8.8/dns-query"),
            tagValue = "dns-remote",
            detourValue = TAG_PROXY,
            domainResolver = buildDomainResolverConfig("dns-direct", "prefer_ipv4"),
        ).asMap()
        @Suppress("UNCHECKED_CAST")
        val children = server["servers"] as List<Map<String, Any>>

        assertEquals("balancer", server["type"])
        assertEquals("dns-remote", server["tag"])
        assertFalse(server.containsKey("detour"))
        assertFalse(server.containsKey("domain_resolver"))
        assertEquals(TAG_PROXY, children[0]["detour"])
        assertEquals(TAG_PROXY, children[1]["detour"])
        @Suppress("UNCHECKED_CAST")
        val resolver = children[0]["domain_resolver"] as Map<String, Any>
        assertEquals("dns-direct", resolver["server"])
        assertEquals("prefer_ipv4", resolver["strategy"])
        assertFalse(children[1].containsKey("domain_resolver"))
    }

    @Test
    fun `url test dns uses local only to bootstrap detoured remote dns`() {
        val servers =
            buildUrlTestDnsServers(
                rawAddresses = listOf("https://dns.example/custom-query"),
                queryDeadline = "5s",
                detourValue = TAG_PROXY,
            ).map { it.asMap() }
        val local = servers[0]
        val remote = servers[1]

        assertEquals("local", local["type"])
        assertEquals("dns-local", local["tag"])
        assertFalse(local.containsKey("detour"))

        assertEquals("https", remote["type"])
        assertEquals("dns-remote", remote["tag"])
        assertEquals("dns.example", remote["server"])
        assertEquals("/custom-query", remote["path"])
        assertEquals(TAG_PROXY, remote["detour"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("dns-local", (remote["domain_resolver"] as Map<String, Any>)["server"])
    }

    @Test
    fun `endpoint bootstrap dns is routed through immediate detour`() {
        val server =
            buildEndpointBootstrapDnsServer(
                rawAddresses = listOf("https://dns.example/dns-query"),
                tag = "dns-bootstrap-1",
                detour = "awg",
                forTest = false,
                queryDeadline = "5s",
                remoteStrategy = "prefer_ipv4",
                directStrategy = "prefer_ipv6",
            ).asMap()

        assertEquals("dns-bootstrap-1", server["tag"])
        assertEquals("awg", server["detour"])
        @Suppress("UNCHECKED_CAST")
        val resolver = server["domain_resolver"] as Map<String, Any>
        assertEquals("dns-direct", resolver["server"])
        assertEquals("prefer_ipv6", resolver["strategy"])
    }

    @Test
    fun `url test expands plain dns while preserving explicit udp`() {
        val remote =
            buildUrlTestDnsServers(
                rawAddresses = listOf("8.8.8.8", "udp://1.1.1.1:5353"),
                queryDeadline = "5s",
                detourValue = TAG_PROXY,
            )[1].asMap()
        @Suppress("UNCHECKED_CAST")
        val children = remote["servers"] as List<Map<String, Any>>

        assertEquals(3, children.size)
        assertEquals("udp", children[0]["type"])
        assertEquals("8.8.8.8", children[0]["server"])
        assertFalse(children[0].containsKey("server_port"))
        assertEquals("tcp", children[1]["type"])
        assertEquals("8.8.8.8", children[1]["server"])
        assertFalse(children[1].containsKey("server_port"))
        assertEquals("udp", children[2]["type"])
        assertEquals("1.1.1.1", children[2]["server"])
        assertEquals(5353L, children[2]["server_port"])
        assertEquals(listOf(TAG_PROXY, TAG_PROXY, TAG_PROXY), children.map { it["detour"] })
    }

    @Test
    fun `single plain ip list builds udp tcp balancer`() {
        val server =
            buildDnsServerOptions(
                rawAddresses = listOf("8.8.8.8:5353"),
                tagValue = "dns-remote",
                detourValue = TAG_PROXY,
            ).asMap()
        @Suppress("UNCHECKED_CAST")
        val children = server["servers"] as List<Map<String, Any>>

        assertEquals("balancer", server["type"])
        assertEquals("dns-remote", server["tag"])
        assertEquals(listOf("dns-remote-1", "dns-remote-2"), children.map { it["tag"] })
        assertEquals(listOf("udp", "tcp"), children.map { it["type"] })
        assertEquals(listOf(TAG_PROXY, TAG_PROXY), children.map { it["detour"] })
        assertEquals(listOf(5353L, 5353L), children.map { it["server_port"] })
    }

    @Test
    fun `plain ipv6 expands and scheme less hostname stays udp only`() {
        val server =
            buildDnsServerOptions(
                rawAddresses = listOf("[2001:4860:4860::8888]:5353", "dns.example"),
                tagValue = "dns-remote",
            ).asMap()
        @Suppress("UNCHECKED_CAST")
        val children = server["servers"] as List<Map<String, Any>>

        assertEquals(listOf("udp", "tcp", "udp"), children.map { it["type"] })
        assertEquals("2001:4860:4860::8888", children[0]["server"])
        assertEquals("2001:4860:4860::8888", children[1]["server"])
        assertEquals(5353L, children[0]["server_port"])
        assertEquals(5353L, children[1]["server_port"])
        assertEquals("dns.example", children[2]["server"])
    }

    @Test
    fun `explicit tcp tls and https dns are not expanded`() {
        val server =
            buildDnsServerOptions(
                rawAddresses = listOf(
                    "tcp://1.1.1.1",
                    "tls://dns.google",
                    "https://dns.google/dns-query",
                ),
                tagValue = "dns-remote",
            ).asMap()
        @Suppress("UNCHECKED_CAST")
        val children = server["servers"] as List<Map<String, Any>>

        assertEquals(3, children.size)
        assertEquals(listOf("tcp", "tls", "https"), children.map { it["type"] })
    }

    @Test
    fun `url test dns injection preserves existing custom dns`() {
        val injectedDns = mapOf<String, Any?>("final" to "dns-remote")

        val absent = mutableMapOf<String, Any?>("outbounds" to emptyList<Any>())
        assertTrue(injectUrlTestDnsIfMissing(absent, injectedDns))
        assertEquals(injectedDns, absent["dns"])

        val nullDns = mutableMapOf<String, Any?>("dns" to null)
        assertTrue(injectUrlTestDnsIfMissing(nullDns, injectedDns))
        assertEquals(injectedDns, nullDns["dns"])

        val emptyDns = mutableMapOf<String, Any?>("dns" to emptyMap<String, Any>())
        assertFalse(injectUrlTestDnsIfMissing(emptyDns, injectedDns))
        assertEquals(emptyMap<String, Any>(), emptyDns["dns"])

        val customDns = mapOf("final" to "profile-dns")
        val populatedDns = mutableMapOf<String, Any?>("dns" to customDns)
        assertFalse(injectUrlTestDnsIfMissing(populatedDns, injectedDns))
        assertEquals(customDns, populatedDns["dns"])
    }

    @Test
    fun `remote dns detour skips empty direct outbound`() {
        val emptyDirect =
            Outbound().apply {
                type = "direct"
                tag = "Direct"
            }
        val proxy =
            Outbound().apply {
                type = "vless"
                tag = "proxy"
            }

        assertNull(remoteDnsDetourTag("Direct", listOf(emptyDirect)))
        assertEquals("proxy", remoteDnsDetourTag("proxy", listOf(proxy)))
        assertEquals("endpoint-proxy", remoteDnsDetourTag("endpoint-proxy", emptyList()))
    }

    @Test
    fun `custom url test detour follows effective outbound`() {
        val routed =
            mapOf<String, Any?>(
                "route" to mapOf("final" to "proxy"),
                "outbounds" to listOf(
                    mapOf("type" to "direct", "tag" to "direct"),
                    mapOf("type" to "vless", "tag" to "proxy"),
                ),
            )
        val firstTagged =
            mapOf<String, Any?>(
                "outbounds" to listOf(mapOf("type" to "vless", "tag" to "proxy")),
            )
        val firstUntagged =
            mapOf<String, Any?>(
                "outbounds" to listOf(mapOf("type" to "vless")),
            )
        val routedToUntagged =
            mapOf<String, Any?>(
                "route" to mapOf("final" to "1"),
                "outbounds" to listOf(
                    mapOf("type" to "direct", "tag" to "direct"),
                    mapOf("type" to "vless"),
                ),
            )
        val direct =
            mapOf<String, Any?>(
                "outbounds" to listOf(mapOf("type" to "direct", "tag" to "direct")),
            )

        assertEquals("proxy", customConfigUrlTestDetourTag(routed))
        assertEquals("proxy", customConfigUrlTestDetourTag(firstTagged))
        assertEquals("0", customConfigUrlTestDetourTag(firstUntagged))
        assertEquals("1", customConfigUrlTestDetourTag(routedToUntagged))
        assertNull(customConfigUrlTestDetourTag(direct))
        assertNull(customConfigUrlTestDetourTag(emptyMap()))
    }

    @Test
    fun `https dns custom path and domain resolver are emitted`() {
        val resolver = buildDomainResolverConfig("dns-direct", "prefer_ipv4")
        val server = buildDnsServerOptions("https://dns.example/custom-query", "dns-remote", domainResolver = resolver)
        val map = server.asMap()
        @Suppress("UNCHECKED_CAST")
        val domainResolver = map["domain_resolver"] as Map<String, Any>

        assertEquals("https", map["type"])
        assertEquals("dns.example", map["server"])
        assertEquals("/custom-query", map["path"])
        assertEquals("dns-direct", domainResolver["server"])
        assertEquals("prefer_ipv4", domainResolver["strategy"])
        assertFalse(map.containsKey("address_resolver"))
    }

    @Test
    fun `tls quic h3 and explicit ports build expected server fields`() {
        val tls = buildDnsServerOptions("tls://dns.google:8853", "dot").asMap()
        val quic = buildDnsServerOptions("quic://dns.example", "doq").asMap()
        val h3 = buildDnsServerOptions("h3://dns.example:4443/dns-query", "doh3").asMap()

        assertEquals("tls", tls["type"])
        assertEquals("dns.google", tls["server"])
        assertEquals(8853L, tls["server_port"])

        assertEquals("quic", quic["type"])
        assertEquals("dns.example", quic["server"])
        assertFalse(quic.containsKey("server_port"))

        assertEquals("h3", h3["type"])
        assertEquals("dns.example", h3["server"])
        assertEquals(4443L, h3["server_port"])
        assertFalse(h3.containsKey("path"))
    }

    @Test
    fun `local and dhcp dns servers use typed 1_13 objects`() {
        val local = buildDnsServerOptions("local", "dns-local").asMap()
        val dhcpAuto = buildDnsServerOptions("dhcp://auto", "dns-dhcp").asMap()
        val dhcpIface = buildDnsServerOptions("dhcp://wlan0", "dns-dhcp-wlan").asMap()

        assertEquals("local", local["type"])
        assertEquals("dns-local", local["tag"])
        assertFalse(local.containsKey("detour"))
        assertFalse(local.containsKey("address"))

        assertEquals("dhcp", dhcpAuto["type"])
        assertFalse(dhcpAuto.containsKey("interface"))

        assertEquals("dhcp", dhcpIface["type"])
        assertEquals("wlan0", dhcpIface["interface"])
    }

    @Test
    fun `ipv6 host port is normalized without brackets`() {
        val server = buildDnsServerOptions("[2606:4700:4700::1111]:5353", "dns-v6").asMap()

        assertEquals("udp", server["type"])
        assertEquals("2606:4700:4700::1111", server["server"])
        assertEquals(5353L, server["server_port"])
    }

    @Test
    fun `route dns block uses predefined rcode action instead of legacy dns server`() {
        val rules = buildRouteDnsRules(
            createDnsRule = true,
            outbound = -2L,
            uidList = emptyList(),
            domainList = listOf("ads.example"),
            ruleSet = null,
            rulesetTags = emptyList(),
            useFakeDns = false,
        )

        assertEquals(1, rules.size)
        val map = rules.single().asMap()
        assertEquals("predefined", map["action"])
        assertEquals("NOERROR", map["rcode"])
        assertFalse(map.containsKey("server"))
        assertFalse(map.containsKey("disable_cache"))
    }

    @Test
    fun `route dns rule does not carry legacy resolver strategy`() {
        val rules = buildRouteDnsRules(
            createDnsRule = true,
            outbound = 0L,
            uidList = emptyList(),
            domainList = listOf("example.com"),
            ruleSet = null,
            rulesetTags = emptyList(),
            useFakeDns = false,
        )

        val map = rules.single().asMap()
        assertEquals("dns-remote", map["server"])
        assertFalse(map.containsKey("strategy"))
        assertNull(map["action"])
    }
}
