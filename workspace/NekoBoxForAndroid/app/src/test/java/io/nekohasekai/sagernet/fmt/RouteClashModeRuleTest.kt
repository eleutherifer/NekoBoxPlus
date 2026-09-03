package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.RuleEntity
import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteClashModeRuleTest {

    @Test
    fun blankClashModeIsOmittedFromGeneratedRouteRule() {
        val routeRule = Rule_DefaultOptions()

        routeRule.applyRouteClashMode(RuleEntity(clashMode = ""))

        assertNull(routeRule.clash_mode)
    }

    @Test
    fun nonBlankClashModeIsAddedToGeneratedRouteRule() {
        val routeRule = Rule_DefaultOptions()

        routeRule.applyRouteClashMode(RuleEntity(clashMode = "Streaming"))

        assertEquals("Streaming", routeRule.clash_mode)
    }

    @Test
    fun blankClashModeAlwaysAppliesToTunPackageSelection() {
        assertTrue(RuleEntity(clashMode = "").appliesToClashMode("Streaming"))
    }

    @Test
    fun matchingClashModeAppliesToTunPackageSelectionCaseInsensitively() {
        assertTrue(RuleEntity(clashMode = "Streaming").appliesToClashMode("streaming"))
    }

    @Test
    fun otherClashModeDoesNotApplyToTunPackageSelection() {
        assertFalse(RuleEntity(clashMode = "Streaming").appliesToClashMode("Rule"))
    }

    @Test
    fun bypassRuleDoesNotContributePackagesToTunFilter() {
        val rule = RuleEntity(outbound = -1L, packages = setOf("com.example.app"))

        assertFalse(rule.contributesPackagesToTunFilter("Rule"))
    }

    @Test
    fun activeNonBypassRuleContributesPackagesToTunFilter() {
        val rule = RuleEntity(
            clashMode = "Streaming",
            outbound = 0L,
            packages = setOf("com.example.app"),
        )

        assertTrue(rule.contributesPackagesToTunFilter("streaming"))
    }

    @Test
    fun validCachedClashModeIsCanonicalizedFromConfiguredRules() {
        val rules = listOf(RuleEntity(clashMode = "Streaming"))

        assertEquals("Streaming", resolveActiveClashMode("streaming", rules))
    }

    @Test
    fun missingOrStaleCachedClashModeFallsBackToRule() {
        val rules = listOf(RuleEntity(clashMode = "Streaming"))

        assertEquals("Rule", resolveActiveClashMode("", rules))
        assertEquals("Rule", resolveActiveClashMode("Removed", rules))
    }
}
