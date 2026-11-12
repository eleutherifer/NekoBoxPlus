package io.nekohasekai.sagernet.backup.throne

import io.nekohasekai.sagernet.database.RuleEntity
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ThrRoutesTest {
    private fun convert(rules: List<RuleEntity>, mode: String = "insecure", ids: Set<Long> = emptySet()): List<ThrRoutes.Profile> =
        ThrRoutes.convert(rules, ids, mode, mutableListOf(), "TUN protection", "Common")

    @Test fun commonRulesAreInterleavedInEveryModeWithoutClashFields() {
        val routes = convert(listOf(
            RuleEntity(id = 1, userOrder = 1, name = "common-first", enabled = true),
            RuleEntity(id = 2, userOrder = 2, name = "work", clashMode = "Work", enabled = true),
            RuleEntity(id = 3, userOrder = 3, name = "common-last", enabled = false),
            RuleEntity(id = 4, userOrder = 4, name = "home", clashMode = "Home", enabled = true),
            RuleEntity(id = 5, userOrder = 5, name = "work-last", clashMode = "work", enabled = true),
        ))
        assertEquals(listOf("", "Work", "Home"), routes.map { it.mode })
        assertEquals(listOf("common-first", "common-last"), routes[0].rules.map { it["name"] })
        assertEquals(listOf("common-first", "work", "common-last", "work-last"), routes[1].rules.map { it["name"] })
        assertEquals(1, routes[1].rules[2]["disabled"])
        assertTrue(routes.all { profile -> profile.rules.none { "clash_mode" in it } })
    }

    @Test fun allProtectionModesUseUnknownAppsAndCorrectTarget() {
        for ((mode, target) in listOf("block" to -3L, "normal-direct-bypass-block" to -3L,
            "direct" to -2L, "proxy" to -1L)) {
            val rows = convert(listOf(RuleEntity(name = "last")), mode).single().rules
            assertEquals("TUN protection", rows[0]["name"])
            assertEquals(target, rows[0]["outbound_id"])
            assertEquals("route", rows[0]["action"])
            assertEquals("unknown", JSONArray(rows[0]["package_name_json"] as String).getString(0))
        }
        assertTrue(convert(emptyList(), "insecure").single().rules.isEmpty())
    }

    @Test fun protectionFollowsDnsHijackAndPrecedesCatchAll() {
        val rows = convert(listOf(
            RuleEntity(name = "dns", userOrder = 1, enabled = true, protocol = "dns", config = "{\"action\":\"hijack-dns\"}"),
            RuleEntity(name = "catch-all", userOrder = 2, enabled = true),
        ), "block").single().rules
        assertEquals(listOf("dns", "TUN protection", "catch-all"), rows.map { it["name"] })
    }

    @Test fun missingProfilesDnsAndUnrepresentableCustomRulesAreReportedAndSkipped() {
        val omissions = mutableListOf<ThrOmission>()
        val result = ThrRoutes.convert(listOf(
            RuleEntity(id = 1, name = "Custom DNS", type = "dns"),
            RuleEntity(id = 2, name = "Unavailable proxy", outbound = 22),
            RuleEntity(id = 3, name = "Custom matcher", config = "{\"user_id\":[1234]}"),
            RuleEntity(id = 4, name = "Block", outbound = -2, dnsDisableCache = true),
        ), emptySet(), "insecure", omissions, "TUN", "Common")
        assertEquals(1, result.single().rules.size)
        assertEquals("route", result.single().rules.single()["action"])
        assertEquals(-3L, result.single().rules.single()["outbound_id"])
        assertEquals(setOf(1L, 2L, 3L, 4L), omissions.map { it.id }.toSet())
        assertEquals(mapOf(1L to "Custom DNS", 2L to "Unavailable proxy", 3L to "Custom matcher", 4L to "Block"),
            omissions.associate { it.id to it.entityName })
    }

    @Test fun proxyDirectAndPositiveTargetsAreTranslated() {
        val result = convert(listOf(RuleEntity(outbound = 0), RuleEntity(outbound = -1),
            RuleEntity(outbound = -2), RuleEntity(outbound = 20)), ids = setOf(20)).single()
        assertEquals(listOf(-1L, -2L, -3L, 20L), result.rules.map { it["outbound_id"] })
        assertTrue(result.rules.all { it["action"] == "route" })
    }

    @Test fun blockRulesAndRejectOverridesUseRouteToBlockInEveryMode() {
        val result = convert(listOf(
            RuleEntity(name = "block", userOrder = 1, enabled = true, outbound = -2, domains = "full:blocked.example"),
            RuleEntity(name = "route-block", userOrder = 2, enabled = true, outbound = -2, config = "{\"action\":\"route\"}"),
            RuleEntity(name = "reject-direct", userOrder = 3, enabled = false, outbound = -1, config = "{\"action\":\"reject\"}"),
            RuleEntity(name = "reject-proxy", userOrder = 4, enabled = true, config = "{\"action\":\"reject\"}"),
            RuleEntity(name = "reject-profile", userOrder = 5, enabled = true, outbound = 20,
                clashMode = "Work", config = "{\"action\":\"reject\"}"),
        ), ids = setOf(20))
        assertEquals(listOf(4, 5), result.map { it.rules.size })
        assertTrue(result.flatMap { it.rules }.all { it["action"] == "route" && it["outbound_id"] == -3L })
        assertEquals("[\"blocked.example\"]", result.first().rules.first()["domain_json"])
        assertEquals(1, result.first().rules[2]["disabled"])
    }

    @Test fun multipleProtocolsAndNetworksExpandWithoutBroadeningMatchers() {
        val rows = convert(listOf(RuleEntity(network = "tcp,udp", protocol = "http\ntls", domains = "full:example.com"))).single().rules
        assertEquals(4, rows.size)
        assertEquals(setOf("tcp" to "http", "tcp" to "tls", "udp" to "http", "udp" to "tls"),
            rows.map { it["network"] to it["protocol"] }.toSet())
        assertTrue(rows.all { it["domain_json"] == "[\"example.com\"]" })
    }

    @Test fun geoMatchersUseThroneRuleSetNamesAndPreserveOtherMatchers() {
        val rows = convert(listOf(RuleEntity(
            domains = "geosite:ru\ngeosite:category-ads-all\nfull:example.com",
            ip = "geoip:ru\ngeoip:private\n192.0.2.0/24",
            ruleset = "geoip:de\ngeosite:de\ncustom-set\nhttps://example.com/geosite:custom.srs",
        ))).single().rules
        assertEquals(2, rows.size)
        val row = rows.last()
        val sets = JSONArray(row["rule_set_json"] as String)
        assertEquals(listOf("geosite-ru", "geosite-category-ads-all", "geoip-ru", "geoip-de", "geosite-de",
            "custom-set", "https://example.com/geosite:custom.srs"),
            (0 until sets.length()).map(sets::getString))
        assertEquals("[\"example.com\"]", rows.first()["domain_json"])
        assertEquals("[\"192.0.2.0/24\"]", rows.first()["ip_cidr_json"])
        assertEquals(1, rows.first()["ip_is_private"])
    }
}
