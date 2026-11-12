package io.nekohasekai.sagernet.backup.throne

import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.AppLogLevel
import io.nekohasekai.sagernet.LogcatRetentionSize
import io.nekohasekai.sagernet.fmt.parseDnsDomainOverrides
import io.nekohasekai.sagernet.fmt.destinationStrategyForIpv6Mode
import org.json.JSONArray
import io.nekohasekai.sagernet.database.preference.KeyValuePair

/** Explicit allowlist: source camelCase preference keys are not Throne setting names or wire values. */
internal object ThrSettings {
    private val booleans = mapOf(
        Key.ENABLE_DNS_ROUTING to "enable_dns_routing", Key.ENABLE_FAKEDNS to "fakedns",
        Key.DNS_DISABLE_CACHE to "dns_disable_cache", Key.DNS_DISABLE_EXPIRE to "dns_disable_expire",
        Key.DNS_OPTIMISTIC_CACHE to "dns_optimistic", Key.DNS_STORE_CACHE to "dns_persist_cache",
        Key.DNS_REVERSE_MAPPING to "dns_reverse_mapping", Key.GLOBAL_ALLOW_INSECURE to "skip_cert",
        Key.PROXY_APPS to "proxyApps", Key.BYPASS_MODE to "bypassMode",
        Key.METERED_NETWORK to "meteredNetwork", Key.APPEND_HTTP_PROXY to "appendHttpProxy",
        Key.NETWORK_CHANGE_RESET_CONNECTIONS to "networkChangeResetConnections",
        Key.WAKE_RESET_CONNECTIONS to "wakeResetConnections", Key.SHOW_DIRECT_SPEED to "showDirectSpeed",
        Key.HIDE_FROM_RECENT_APPS to "hideFromRecentApps", Key.ALWAYS_SHOW_ADDRESS to "alwaysShowAddress",
        "showGroupInNotification" to "showGroupInNotification",
        Key.ENABLE_GROUP_UPDATE_DIALOG to "sub_show_change_popup", Key.PERSIST_ACROSS_REBOOT to "remember_enable",
    )
    private val strings = mapOf(
        Key.REMOTE_DNS to "remote_dns", Key.DIRECT_DNS to "direct_dns",
        Key.DNS_TIMEOUT to "dns_query_timeout", Key.DNS_OPTIMISTIC_TIMEOUT to "dns_optimistic_timeout",
        Key.CONNECTION_TEST_URL to "test_url",
        Key.INDIVIDUAL to "individual", Key.HTTP_PROXY_BYPASS to "httpProxyBypass",
        Key.SERVICE_MODE to "serviceMode", Key.APP_LANGUAGE to "appLanguage",
        Key.APP_TLS_VERSION to "appTLSVersion", Key.MIXED_USERNAME to "inbound_user",
        Key.MIXED_PASSWORD to "inbound_pass",
    )
    private val integers = mapOf(Key.MTU to "vpn_mtu", Key.DNS_CACHE_CAPACITY to "dns_cache_capacity",
        Key.CONNECTION_TEST_TIMEOUT to "url_test_timeout_ms", Key.CONNECTION_TEST_CONCURRENT to "test_concurrent",
        Key.SPEED_TEST_DURATION to "speed_test_timeout_ms", Key.NIGHT_THEME to "nightTheme",
        Key.GROUP_LAYOUT_MODE to "groupLayoutMode", Key.MIXED_PORT to "inbound_socks_port")

    fun convert(stored: List<KeyValuePair>, omissions: MutableList<ThrOmission>): Map<String, String> {
        val result = storedValues(stored, omissions)
        effectiveDefaults(stored, result, omissions)
        return result
    }

    internal fun storedValues(stored: List<KeyValuePair>, omissions: MutableList<ThrOmission>): MutableMap<String, String> {
        val result = linkedMapOf<String, String>()
        for (entry in stored) {
            val value = when {
                entry.key in booleans -> entry.boolean?.toString()?.let { booleans.getValue(entry.key) to it }
                entry.key in strings -> entry.string?.let { strings.getValue(entry.key) to it }
                entry.key in integers -> (entry.long ?: entry.string?.toLongOrNull())?.let {
                    integers.getValue(entry.key) to it.toString()
                }
                else -> null
            }
            if (value != null) result[value.first] = value.second
            else if (entry.key !in translated) omissions += ThrOmission("setting", null, "unsupported_setting", entry.key)
        }
        return result
    }

    private fun effectiveDefaults(stored: List<KeyValuePair>, result: MutableMap<String, String>, omissions: MutableList<ThrOmission>) {
        val values = stored.associateBy { it.key }
        fun string(key: String, default: String = "") = values[key]?.string ?: default
        fun bool(key: String, default: Boolean = false) = values[key]?.boolean ?: default
        fun number(key: String, default: Int) = (values[key]?.long?.toInt() ?: values[key]?.string?.toIntOrNull()) ?: default
        fun notice(key: String) { omissions += ThrOmission("setting", null, "unsupported_setting", key) }
        val ipv6 = number(Key.IPV6_MODE, IPv6Mode.DISABLE)
        for ((key, target, default) in listOf(
            Triple(Key.REMOTE_DNS, "remote_dns", "https://1.1.1.1/dns-query"),
            Triple(Key.DIRECT_DNS, "direct_dns", "local"))) {
            val servers = string(key, default).lineSequence().map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
            if (servers.size == 1) result[target] = dnsAddress(servers.single())
            else { result.remove(target); notice(key) }
        }
        result["enable_dns_routing"] = bool(Key.ENABLE_DNS_ROUTING, true).toString()
        result["fakedns"] = bool(Key.ENABLE_FAKEDNS, true).toString()
        result["dns_persist_cache"] = bool(Key.DNS_STORE_CACHE, true).toString()
        result["dns_query_timeout"] = string(Key.DNS_TIMEOUT, "10s")
        result["dns_optimistic_timeout"] = string(Key.DNS_OPTIMISTIC_TIMEOUT, "5s")
        result["vpn_mtu"] = number(Key.MTU, 9000).toString()
        result["vpn_ipv6"] = (ipv6 != IPv6Mode.DISABLE).toString()
        result["domain_strategy"] = destinationStrategyForIpv6Mode(bool(Key.RESOLVE_DESTINATION), ipv6)
        result["outbound_domain_strategy"] = string("domain_strategy_for_server", "prefer_ipv4").let {
            if (it == "auto" || it.isEmpty()) "prefer_ipv4" else it
        }
        for ((source, target) in listOf("domain_strategy_for_remote" to "remote_dns_disable_ipv6",
            "domain_strategy_for_direct" to "direct_dns_disable_ipv6")) {
            val strategy = string(source, "auto")
            result[target] = (ipv6 == IPv6Mode.DISABLE || strategy == "ipv4_only").toString()
            if (strategy !in setOf("", "auto", "ipv4_only")) notice(source)
        }
        result["fakeip_disable_ipv6"] = (ipv6 == IPv6Mode.DISABLE).toString()
        result["test_url"] = string(Key.CONNECTION_TEST_URL, io.nekohasekai.sagernet.CONNECTION_TEST_URL)
        result["url_test_timeout_ms"] = number(Key.CONNECTION_TEST_TIMEOUT, 10000).toString()
        result["test_concurrent"] = number(Key.CONNECTION_TEST_CONCURRENT, 5).toString()
        result["skip_cert"] = bool(Key.GLOBAL_ALLOW_INSECURE).toString()
        result["proxyApps"] = bool(Key.PROXY_APPS).toString()
        result["bypassMode"] = bool(Key.BYPASS_MODE, true).toString()
        result["individual"] = string(Key.INDIVIDUAL)
        result["disable_private_range_bypass"] = (!bool(Key.BYPASS_LAN)).toString()
        result["disable_mixed_inbound"] = (!(string(Key.SERVICE_MODE, "vpn") == "proxy" || bool(Key.REQUIRE_PROXY_IN_VPN))).toString()
        result["inbound_address"] = if (bool(Key.ALLOW_ACCESS)) "0.0.0.0" else string(Key.MIXED_LISTENER, "127.0.0.1")
        result["inbound_auth"] = (string(Key.MIXED_USERNAME, "User").isNotEmpty() || string(Key.MIXED_PASSWORD).isNotEmpty()).toString()
        result["inbound_user"] = string(Key.MIXED_USERNAME, "User")
        result["skip_delete_confirmation"] = (!bool(Key.CONFIRM_PROFILE_DELETE, true)).toString()
        result["disable_traffic_stats"] = (!(bool(Key.PROFILE_TRAFFIC_STATISTICS, true) && number(Key.PROFILE_TRAFFIC_UPDATE_INTERVAL, 0) > 0)).toString()
        result["use_mozilla_certs"] = (number(Key.CERT_PROVIDER, 2) == 1).toString()
        if (number(Key.CERT_PROVIDER, 2) == 3) notice(Key.CERT_PROVIDER)
        result["log_level"] = AppLogLevel.fromPreferenceValue(number(Key.LOG_LEVEL, 0)).singBoxName
        if (number(Key.LOG_LEVEL, 0) == 5) notice(Key.LOG_LEVEL)
        result["logBufSize"] = LogcatRetentionSize.resolve(values[Key.LOG_BUF_SIZE]?.string, values[Key.LOG_BUF_SIZE]?.long?.toInt()).kilobytes.toString()
        val theme = number(Key.APP_THEME, 0)
        if (theme in 0..21) {
            result["appTheme"] = theme.toString()
            result["useSystemTheme"] = (theme == 0).toString()
        } else if (theme == 22) {
            result["appTheme"] = "21"; result["amoledTheme"] = "true"; result["nightTheme"] = "1"
        } else notice(Key.APP_THEME)
        val hosts = hosts(string(Key.DNS_DOMAIN_OVERRIDES))
        result["dns_predefined_rules"] = JSONArray(hosts).toString()
        result["dns_predefined_enable"] = hosts.isNotEmpty().toString()
    }

    internal fun hosts(raw: String): List<String> = parseDnsDomainOverrides(raw).flatMap { (domain, addresses) ->
        addresses.distinct().map { address -> "$address $domain" }
    }

    internal fun dnsAddress(value: String): String = if (value.trim() == "local") "localhost" else value.trim()

    private val translated = setOf(Key.PROFILE_ID, Key.PROFILE_CURRENT, Key.PROFILE_GROUP,
        Key.TUN_UNRECOGNIZED_TRAFFIC, Key.IPV6_MODE, Key.GLOBAL_TCP_FAST_OPEN, Key.GLOBAL_TCP_MULTI_PATH,
        Key.GLOBAL_UDP_FRAGMENT, Key.DNS_DOMAIN_OVERRIDES, Key.RESOLVE_DESTINATION,
        Key.BYPASS_LAN, Key.REQUIRE_PROXY_IN_VPN, Key.ALLOW_ACCESS, Key.MIXED_LISTENER,
        Key.CONFIRM_PROFILE_DELETE, Key.PROFILE_TRAFFIC_STATISTICS, Key.PROFILE_TRAFFIC_UPDATE_INTERVAL,
        Key.CERT_PROVIDER, Key.LOG_LEVEL, Key.LOG_BUF_SIZE, Key.APP_THEME, "domain_strategy_for_server",
        "domain_strategy_for_remote", "domain_strategy_for_direct")
}
