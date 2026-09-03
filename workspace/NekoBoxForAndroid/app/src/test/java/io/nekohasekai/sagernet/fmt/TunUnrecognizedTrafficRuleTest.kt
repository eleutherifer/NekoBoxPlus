package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TunUnrecognizedTrafficRuleTest {

    @Test
    fun blockOutboundIsConvertedToRejectAction() {
        val rule = moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions().apply { outbound = TAG_BLOCK }

        rule.replaceBlockOutboundWithRejectAction()

        assertEquals("reject", rule.action)
        assertNull(rule.outbound)
    }

    @Test
    fun blockModeUsesRejectAction() {
        val rule = buildTunUnrecognizedTrafficRule("block", bypassMode = false, mainProxyTag = TAG_PROXY)!!

        assertEquals(listOf(TAG_TUN), rule.inbound)
        assertEquals(listOf("android"), rule.package_name)
        assertEquals("reject", rule.action)
        assertNull(rule.outbound)

        val serialized = rule.asMap()
        assertEquals("reject", serialized["action"])
        assertFalse(serialized.containsKey("outbound"))
    }

    @Test
    fun bypassBlockModeUsesRejectAction() {
        val rule =
            buildTunUnrecognizedTrafficRule(
                "normal-direct-bypass-block",
                bypassMode = true,
                mainProxyTag = TAG_PROXY,
            )!!

        assertEquals("reject", rule.action)
        assertNull(rule.outbound)
    }

    @Test
    fun routingModesKeepTheirOutbound() {
        val directRule = buildTunUnrecognizedTrafficRule("direct", bypassMode = false, mainProxyTag = TAG_PROXY)!!
        val proxyRule = buildTunUnrecognizedTrafficRule("proxy", bypassMode = false, mainProxyTag = TAG_PROXY)!!

        assertEquals(TAG_DIRECT, directRule.outbound)
        assertNull(directRule.action)
        assertEquals(TAG_PROXY, proxyRule.outbound)
        assertNull(proxyRule.action)
    }

    @Test
    fun insecureModeDoesNotCreateRule() {
        assertNull(buildTunUnrecognizedTrafficRule("insecure", bypassMode = false, mainProxyTag = TAG_PROXY))
    }
}
