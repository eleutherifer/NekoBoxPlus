package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.RuleEntity
import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
