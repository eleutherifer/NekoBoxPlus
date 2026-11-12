package io.nekohasekai.sagernet.backup.throne

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import org.junit.Assert.*
import org.junit.Test

class ThrSettingsTest {
    @Test fun globalPerAppRoutingPreservesEnabledModeAndSelectedApps() {
        for (enabled in listOf(false, true)) {
            for (bypass in listOf(false, true)) {
                for (packages in listOf("", "com.example.first\ncom.example.second")) {
                    val notices = mutableListOf<ThrOmission>()
                    val result = ThrSettings.storedValues(listOf(
                        KeyValuePair(Key.PROXY_APPS).put(enabled),
                        KeyValuePair(Key.BYPASS_MODE).put(bypass),
                        KeyValuePair(Key.INDIVIDUAL).put(packages),
                    ), notices)
                    assertEquals(mapOf("proxyApps" to enabled.toString(), "bypassMode" to bypass.toString(),
                        "individual" to packages), result)
                    assertTrue(notices.isEmpty())
                }
            }
        }
    }

    @Test fun valuesUseThroneTypesAndUnsupportedSettingsNeverLeakValues() {
        val notices = mutableListOf<ThrOmission>()
        val result = ThrSettings.storedValues(listOf(
            KeyValuePair(Key.ENABLE_DNS_ROUTING).put(true),
            KeyValuePair(Key.MTU).put("1500"),
            KeyValuePair(Key.CONNECTION_TEST_TIMEOUT).put(12000L),
            KeyValuePair("webdavPassword").put("fixture-secret"),
        ), notices)
        assertEquals("true", result["enable_dns_routing"])
        assertEquals("1500", result["vpn_mtu"])
        assertEquals("12000", result["url_test_timeout_ms"])
        assertFalse(result.containsKey("webdavPassword"))
        assertEquals("webdavPassword", notices.single().key)
        assertFalse(notices.toString().contains("fixture-secret"))
        assertEquals("localhost", ThrSettings.dnsAddress("local"))
    }
}
