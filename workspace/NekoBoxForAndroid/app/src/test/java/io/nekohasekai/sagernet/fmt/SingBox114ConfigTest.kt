package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.DNSOptions
import moe.matsuri.nb4a.SingBoxOptions.MyOptions
import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import moe.matsuri.nb4a.SingBoxOptions.RouteOptions
import moe.matsuri.nb4a.SingBoxOptions.RuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class SingBox114ConfigTest {

    @Test
    fun mixedInlineAndRuleSetDestinationMatchersAreSplit() {
        val rule = Rule_DefaultOptions().apply {
            domain_suffix = listOf("example.com")
            ip_cidr = listOf("192.0.2.0/24")
            rule_set = listOf("ruleset-site")
            port = listOf(443)
            network = listOf("tcp")
            outbound = "proxy"
            _hack_config_map["custom_marker"] = "retained"
        }

        val rules = splitRouteRuleSetSemantics114(rule).map { it.asMap() }

        assertEquals(2, rules.size)
        assertEquals(listOf("example.com"), rules[0]["domain_suffix"])
        assertEquals(listOf("192.0.2.0/24"), rules[0]["ip_cidr"])
        assertFalse(rules[0].containsKey("rule_set"))
        assertEquals(listOf("ruleset-site"), rules[1]["rule_set"])
        assertFalse(rules[1].containsKey("domain_suffix"))
        assertFalse(rules[1].containsKey("ip_cidr"))
        rules.forEach {
            assertEquals(listOf(443L), it["port"])
            assertEquals(listOf("tcp"), it["network"])
            assertEquals("proxy", it["outbound"])
            assertEquals("retained", it["custom_marker"])
        }
    }

    @Test
    fun routeRuleSetSplitLeavesUnambiguousRulesUnchanged() {
        val ruleSetOnly = Rule_DefaultOptions().apply {
            rule_set = listOf("ruleset-site")
            outbound = "proxy"
        }
        val inlineOnly = Rule_DefaultOptions().apply {
            domain_suffix = listOf("example.com")
            outbound = "proxy"
        }

        assertEquals(listOf(ruleSetOnly), splitRouteRuleSetSemantics114(ruleSetOnly))
        assertEquals(listOf(inlineOnly), splitRouteRuleSetSemantics114(inlineOnly))
    }

    @Test
    fun routeRuleSetSplitDoesNotRewriteAdvancedSemantics() {
        fun mixedRule() = Rule_DefaultOptions().apply {
            domain_suffix = listOf("example.com")
            rule_set = listOf("ruleset-site")
            outbound = "proxy"
        }

        val inverted = mixedRule().apply { invert = true }
        val sourceRuleSet = mixedRule().apply {
            _hack_config_map["rule_set_ip_cidr_match_source"] = true
        }
        val logical = mixedRule().apply {
            _hack_config_map["type"] = "logical"
        }

        assertEquals(listOf(inverted), splitRouteRuleSetSemantics114(inverted))
        assertEquals(listOf(sourceRuleSet), splitRouteRuleSetSemantics114(sourceRuleSet))
        assertEquals(listOf(logical), splitRouteRuleSetSemantics114(logical))
    }

    @Test
    fun remoteRuleSetsUseRootHttpClient() {
        val ruleSet = RuleSet().apply {
            type = "remote"
            tag = "remote"
            url = "https://example.com/rules.srs"
        }
        val options = MyOptions().apply {
            route = RouteOptions().apply { rule_set = mutableListOf(ruleSet) }
            applySingBox114Config("proxy")
        }

        assertEquals("ruleset-download", ruleSet.http_client)
        assertEquals("ruleset-download", options.route.default_http_client)
        assertEquals("proxy", options.http_clients.single().detour)
    }

    @Test
    fun legacyDnsRuleStrategiesAreRemovedWithoutSplittingActions() {
        val root = linkedMapOf<String, Any?>(
            "dns" to linkedMapOf<String, Any?>(
                "rules" to mutableListOf(
                    linkedMapOf<String, Any?>(
                        "domain_suffix" to listOf("example.com"),
                        "server" to "dns-direct",
                        "strategy" to "ipv4_only",
                        "timeout" to "5s",
                    ),
                    linkedMapOf<String, Any?>(
                        "strategy" to "prefer_ipv4",
                        "action" to "route-options",
                    ),
                    linkedMapOf<String, Any?>(
                        "domain" to listOf("cached.example"),
                        "strategy" to "prefer_ipv6",
                        "disable_cache" to true,
                        "action" to "route-options",
                    ),
                ),
            ),
        )

        root.sanitizeDNSRules114()

        @Suppress("UNCHECKED_CAST")
        val rules = (root["dns"] as Map<String, Any?>)["rules"] as List<Map<String, Any?>>
        assertEquals(2, rules.size)
        assertEquals(listOf("example.com"), rules[0]["domain_suffix"])
        assertEquals("dns-direct", rules[0]["server"])
        assertEquals("5s", rules[0]["timeout"])
        assertFalse(rules[0].containsKey("strategy"))
        assertFalse(rules[0].containsKey("action"))
        assertEquals("route-options", rules[1]["action"])
        assertEquals(true, rules[1]["disable_cache"])
        assertFalse(rules[1].containsKey("strategy"))
    }

    @Test
    fun legacyDnsRuleStrategyIsRemovedFromLogicalRuleTree() {
        val child = linkedMapOf<String, Any?>(
            "domain" to listOf("example.com"),
            "strategy" to "prefer_ipv4",
        )
        val logical = linkedMapOf<String, Any?>(
            "type" to "logical",
            "mode" to "or",
            "rules" to mutableListOf(child),
            "server" to "dns-remote",
            "strategy" to "prefer_ipv6",
        )
        val root = linkedMapOf<String, Any?>(
            "dns" to linkedMapOf<String, Any?>("rules" to mutableListOf(logical)),
        )

        root.sanitizeDNSRules114()

        @Suppress("UNCHECKED_CAST")
        val sanitizedLogical = ((root["dns"] as Map<String, Any?>)["rules"] as List<Map<String, Any?>>).single()
        @Suppress("UNCHECKED_CAST")
        val sanitizedChild = (sanitizedLogical["rules"] as List<Map<String, Any?>>).single()
        assertFalse(sanitizedLogical.containsKey("strategy"))
        assertFalse(sanitizedChild.containsKey("strategy"))
        assertEquals("dns-remote", sanitizedLogical["server"])
    }

    @Test
    fun strictAddressFamilyStrategyOverridesMergedDnsConfig() {
        val dns = linkedMapOf<String, Any?>(
            "strategy" to "prefer_ipv6",
            "rules" to emptyList<Any>(),
        )
        val root = linkedMapOf<String, Any?>("dns" to dns)

        root.sanitizeDNSRules114("ipv4_only")

        assertEquals("ipv4_only", dns["strategy"])
    }

    @Test
    fun requiredAddressFamilyRulePrecedesMergedDnsRules() {
        val customRule = linkedMapOf<String, Any?>(
            "domain" to listOf("example.com"),
            "server" to "dns-direct",
        )
        val familyRule = linkedMapOf<String, Any?>(
            "ip_version" to 6L,
            "action" to "predefined",
            "rcode" to "NOERROR",
        )
        val dns = linkedMapOf<String, Any?>("rules" to listOf(customRule))
        val root = linkedMapOf<String, Any?>("dns" to dns)

        root.sanitizeDNSRules114(firstRule = familyRule)

        @Suppress("UNCHECKED_CAST")
        val rules = dns["rules"] as List<Map<String, Any?>>
        assertEquals(familyRule, rules[0])
        assertEquals(customRule, rules[1])
    }

    @Test
    fun newDnsClientOptionsSerialize() {
        val dns = DNSOptions().apply {
            timeout = "10s"
            optimistic = moe.matsuri.nb4a.SingBoxOptions.OptimisticDNSOptions().apply {
                enabled = true
                timeout = "5s"
            }
        }.asMap()

        assertEquals("10s", dns["timeout"])
        assertNotNull(dns["optimistic"])
    }
}
