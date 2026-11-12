package io.nekohasekai.sagernet.backup.throne

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ThrCompatibilityTest {
    @Test fun everyGlobalPreferenceHasAnAuditDisposition() {
        val root = File("src/main/java/io/nekohasekai/sagernet")
        val constants = Regex("const val (\\w+)\\s*=\\s*\"([^\"]*)\"").findAll(File(root, "Constants.kt").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
        val keys = Regex("configurationStore\\.\\w+\\((Key\\.\\w+|\"[^\"]+\")")
            .findAll(File(root, "database/DataStore.kt").readText().substringBefore("// old cache"))
            .map { it.groupValues[1] }.map { if (it.startsWith("Key.")) constants.getValue(it.removePrefix("Key.")) else it.trim('"') }.toSet()
        val uiKeys = Regex("(?:sw|text|list)\\(\"([^\"]+)\"")
            .findAll(File(root, "ui/compose/GlobalSettingsScreen.kt").readText()).map { it.groupValues[1] }.toSet()
        val catalog = JSONObject(javaClass.getResource("/throne-settings-catalog.json")!!.readText())
        assertEquals(keys + uiKeys, catalog.keys().asSequence().toSet())
    }

    @Test fun nativeRuleOverridesKeepConditionsAndOptions() {
        val notices = mutableListOf<ThrOmission>()
        val rules = ThrRoutes.convert(listOf(RuleEntity(id = 1, enabled = true,
            config = """{"action":"resolve","strategy":"ipv4_only","domain":["example.com"],"invert":true}""")),
            emptySet(), "insecure", notices, "TUN", "Common").single().rules
        assertTrue(notices.isEmpty())
        assertEquals("resolve", rules.single()["action"])
        assertEquals("ipv4_only", rules.single()["strategy"])
        assertEquals(1, rules.single()["invert"])
        assertEquals("[\"example.com\"]", rules.single()["domain_json"])
    }

    @Test fun hostsAndEffectiveDefaultsSurviveNativeEncoding() {
        val omissions = mutableListOf<ThrOmission>()
        val settings = ThrSettings.convert(listOf(
            KeyValuePair(Key.DNS_DOMAIN_OVERRIDES).put("Example.COM. 192.0.2.1 2001:db8::1 # comment\nexample.com 192.0.2.1\nbad invalid\n# comment"),
            KeyValuePair(Key.IPV6_MODE).put("1"),
            KeyValuePair(Key.RESOLVE_DESTINATION).put(false),
            KeyValuePair(Key.PROXY_APPS).put(true),
        ), omissions)
        assertEquals("true", settings["dns_predefined_enable"])
        assertEquals(listOf("192.0.2.1 example.com", "2001:db8::1 example.com"),
            JSONArray(settings.getValue("dns_predefined_rules")).let { array -> (0 until array.length()).map(array::getString) })
        assertEquals("", settings["domain_strategy"])
        assertEquals("true", settings["proxyApps"])
        assertFalse(omissions.any { it.key == Key.DNS_DOMAIN_OVERRIDES })
        val empty = ThrSettings.convert(emptyList(), mutableListOf())
        assertEquals("[]", empty["dns_predefined_rules"])
        assertEquals("false", empty["dns_predefined_enable"])
        // Optional hand-off to the actual Throne JVM decoder; never contains user configuration.
        System.getProperty("throne.contract.fixture")?.let { path ->
            val master = MasterDnsVPNBean().apply { initializeDefaultValues(); domains = "tunnel.example"; encryptionKey = "fixture" }
            File(path).writeText(JSONObject().put("settings", JSONObject(settings))
                .put("profile", ThrProfiles.masterDns(master, 1, mutableListOf())).toString())
        }
    }

    @Test fun conditionalProtectionAndActionOnlyRulesDoNotRequireUnusedProfiles() {
        for (bypass in listOf(false, true)) {
            val rows = ThrRoutes.convert(listOf(RuleEntity(outbound = 999, config = "{\"action\":\"reject\"}")),
                emptySet(), "normal-direct-bypass-block", mutableListOf(), "TUN", "Common", bypassMode = bypass).single().rules
            assertEquals(if (bypass) -3L else -2L, rows.first()["outbound_id"])
            assertEquals(-3L, rows.last()["outbound_id"])
            assertTrue(rows.all { it["action"] == "route" })
        }
    }

    @Test fun nestedUnsupportedSecurityOptionsRefuseTheProfile() {
        val error = assertThrows(ThrProfiles.Unsupported::class.java) {
            ThrProfiles.normalize(JSONObject("""{"type":"vless","tls":{"enabled":true,"reality":{"enabled":true,"public_key":"fixture","unsupported_key":true}}}"""))
        }
        assertEquals("tls.reality.unsupported_key", error.field)
    }

    @Test fun masterDnsKeepsNativeOptionsAndReportsAdvancedTuning() {
        val bean = MasterDnsVPNBean().apply { initializeDefaultValues(); domains = "tunnel.example"; encryptionKey = "fixture"; arqWindowSize += 1 }
        val notices = mutableListOf<ThrOmission>()
        val json = ThrProfiles.masterDns(bean, 42, notices)
        assertEquals("masterdns", json.getString("type"))
        assertEquals("xor", json.getString("encryption_method"))
        assertEquals("tunnel.example", json.getJSONArray("domain").getString(0))
        assertTrue(notices.any { it.id == 42L && it.key == "arqWindowSize" })
    }
}
