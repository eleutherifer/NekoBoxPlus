package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDnsRuleBuilderTest {

    @Test
    fun ruleEntityDefaultsToCreatingDnsRules() {
        assertTrue(RuleEntity().createDnsRule)
    }

    @Test
    fun proxyRouteCreatesRemoteDnsRuleWhenFakeDnsIsDisabled() {
        val rules = buildRouteDnsRules(
            createDnsRule = true,
            outbound = 0L,
            uidList = listOf(10001),
            domainList = listOf("domain:example.com"),
            ruleSet = null,
            rulesetTags = emptyList(),
            useFakeDns = false,
        )

        assertEquals(1, rules.size)
        assertEquals("dns-remote", rules[0].server)
        assertEquals(listOf(10001), rules[0].user_id)
        assertEquals(listOf("example.com"), rules[0].domain_suffix)
    }

    @Test
    fun bypassRouteUsesDirectDnsAndBlockRouteUsesPredefinedResponse() {
        val bypassRules = buildRouteDnsRules(
            createDnsRule = true,
            outbound = -1L,
            uidList = emptyList(),
            domainList = listOf("domain:example.com"),
            ruleSet = null,
            rulesetTags = emptyList(),
            useFakeDns = false,
        )
        val blockRules = buildRouteDnsRules(
            createDnsRule = true,
            outbound = -2L,
            uidList = emptyList(),
            domainList = listOf("domain:example.com"),
            ruleSet = null,
            rulesetTags = emptyList(),
            useFakeDns = false,
        )

        assertEquals("dns-direct", bypassRules.single().server)
        assertEquals("predefined", blockRules.single().action)
        assertEquals("NOERROR", blockRules.single().rcode)
        assertEquals(null, blockRules.single().server)
    }

    @Test
    fun blockRouteWithoutDnsMatcherDoesNotCreateCatchAllPredefinedRule() {
        val rules = buildRouteDnsRules(
            createDnsRule = true,
            outbound = -2L,
            uidList = emptyList(),
            domainList = null,
            ruleSet = null,
            rulesetTags = emptyList(),
            useFakeDns = false,
        )

        assertTrue(rules.isEmpty())
    }

    @Test
    fun proxyRouteWithoutDnsMatcherDoesNotCreateCatchAllServerRule() {
        val rules = buildRouteDnsRules(
            createDnsRule = true,
            outbound = 0L,
            uidList = emptyList(),
            domainList = null,
            ruleSet = null,
            rulesetTags = emptyList(),
            useFakeDns = false,
        )

        assertTrue(rules.isEmpty())
    }

    @Test
    fun proxyRouteCreatesFakeDnsRuleWhenFakeDnsIsEnabled() {
        val rules = buildRouteDnsRules(
            createDnsRule = true,
            outbound = 0L,
            uidList = emptyList(),
            domainList = listOf("domain:example.com"),
            ruleSet = null,
            rulesetTags = emptyList(),
            useFakeDns = true,
        )

        assertEquals(1, rules.size)
        assertEquals("dns-fake", rules[0].server)
        assertEquals(listOf(TAG_TUN), rules[0].inbound)
        assertEquals(listOf("A", "AAAA"), rules[0].query_type)
    }

    @Test
    fun disabledCreateDnsRuleSkipsAllRouteDerivedDnsRules() {
        val rules = buildRouteDnsRules(
            createDnsRule = false,
            outbound = 0L,
            uidList = listOf(10001),
            domainList = listOf("domain:example.com"),
            ruleSet = listOf("ruleset-site", "ruleset-ip"),
            rulesetTags = listOf("ruleset-site" to false, "ruleset-ip" to true),
            useFakeDns = true,
        )

        assertTrue(rules.isEmpty())
    }

    @Test
    fun domainRulesetDnsRulesSkipIpRulesets() {
        val rules = buildRouteDnsRules(
            createDnsRule = true,
            outbound = 0L,
            uidList = emptyList(),
            domainList = null,
            ruleSet = listOf("ruleset-site", "ruleset-ip"),
            rulesetTags = listOf("ruleset-site" to false, "ruleset-ip" to true),
            useFakeDns = false,
        )

        assertEquals(1, rules.size)
        assertEquals("dns-remote", rules[0].server)
        assertEquals(listOf("ruleset-site"), rules[0].rule_set)
        assertFalse(rules.any { it.rule_set == listOf("ruleset-ip") })
    }

    @Test
    fun routeDerivedDnsRulesIncludeClashModeWhenProvided() {
        val rules = buildRouteDnsRules(
            createDnsRule = true,
            outbound = 0L,
            uidList = emptyList(),
            domainList = listOf("domain:example.com"),
            ruleSet = listOf("ruleset-site"),
            rulesetTags = listOf("ruleset-site" to false),
            useFakeDns = false,
            clashMode = "Streaming",
        )

        assertEquals(2, rules.size)
        assertTrue(rules.all { it.clash_mode == "Streaming" })
    }

    @Test
    fun uidRuleWithIpCriteriaDoesNotCreateUidOnlyDnsRule() {
        val createBaseRule = shouldCreateBaseRouteDnsRule(
            uidList = listOf(10001),
            domainList = null,
            hasIpCriteria = true,
            hasDomainRuleset = false,
            hasOtherRouteCriteria = false,
            clashMode = "",
        )
        val rules = buildRouteDnsRules(
            createDnsRule = true,
            createBaseDnsRule = createBaseRule,
            outbound = 0L,
            uidList = listOf(10001),
            domainList = null,
            ruleSet = null,
            rulesetTags = emptyList(),
            useFakeDns = false,
        )

        assertFalse(createBaseRule)
        assertTrue(rules.isEmpty())
    }

    @Test
    fun appOnlyRuleStillCreatesUidDnsRule() {
        assertTrue(
            shouldCreateBaseRouteDnsRule(
                uidList = listOf(10001),
                domainList = null,
                hasIpCriteria = false,
                hasDomainRuleset = false,
                hasOtherRouteCriteria = false,
                clashMode = "",
            )
        )
    }
}
