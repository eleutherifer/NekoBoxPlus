package io.nekohasekai.sagernet.routing

import io.nekohasekai.sagernet.database.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingProfileExporterPolicyTest {
    @Test
    fun nekoBoxPlusDnsKeepsAllServersWithCommaSeparator() {
        assertEquals(
            "https://one.example/dns-query,quic://two.example,8.8.8.8",
            RoutingProfileExporter.encodeNekoBoxPlusDns(
                """
                https://one.example/dns-query
                # ignored
                quic://two.example
                8.8.8.8
                """.trimIndent(),
            ),
        )
        assertEquals(null, RoutingProfileExporter.encodeNekoBoxPlusDns("\n# comment"))
    }

    @Test
    fun acceptsOnlyRulesRepresentableByHappAndIncy() {
        assertTrue(RoutingProfileExporter.isRepresentable(rule(-1, domains = "example.com")))
        assertTrue(RoutingProfileExporter.isRepresentable(rule(0, ip = "192.0.2.0/24")))
        assertFalse(RoutingProfileExporter.isRepresentable(rule(-1, domains = "example.com", port = "443")))
        assertFalse(RoutingProfileExporter.isRepresentable(rule(42, domains = "example.com")))
    }

    @Test
    fun recognizesOnlyFinalStructuralEverythingDirectRule() {
        val direct = rule(-1, port = "0:65535")
        assertTrue(RoutingProfileExporter.isEverythingDirect(direct))
        assertFalse(RoutingProfileExporter.isEverythingDirect(direct.copy(domains = "example.com")))

        val notFinal = RoutingProfileExporter.analyzeRules(
            listOf(direct, rule(0, domains = "example.com")),
        )
        assertFalse(notFinal.everythingDirect)
        assertTrue(notFinal.unsupportedRules)
    }

    @Test
    fun derivesFirstCategoryOrderAndDetectsAlternation() {
        val analysis = RoutingProfileExporter.analyzeRules(
            listOf(
                rule(-1, domains = "direct.example"),
                rule(-2, domains = "block.example"),
                rule(-1, ip = "192.0.2.0/24"),
                rule(0, domains = "proxy.example"),
                rule(-1, port = "0-65535"),
            ),
        )

        assertEquals(listOf("direct", "block", "proxy"), analysis.categoryOrder)
        assertTrue(analysis.simplifiedOrder)
        assertTrue(analysis.everythingDirect)
        assertEquals(4, analysis.rules.size)
    }

    private fun rule(
        outbound: Long,
        domains: String = "",
        ip: String = "",
        port: String = "",
    ) = RuleEntity(enabled = true, outbound = outbound, domains = domains, ip = ip, port = port)
}
