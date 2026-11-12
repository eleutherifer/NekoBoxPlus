package io.nekohasekai.sagernet.backup.throne

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleType
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale

internal typealias ThrRow = Map<String, Any?>

internal object ThrRoutes {
    data class Profile(val id: Long, val name: String, val mode: String, val rules: List<ThrRow>)

    fun convert(
        rules: List<RuleEntity>, profileIds: Set<Long>, tunMode: String,
        omissions: MutableList<ThrOmission>, protectionName: String, commonName: String,
        bypassMode: Boolean = true,
        resolveRuleSet: (String) -> String = { it.removePrefix("rsip:").removePrefix("rssite:") },
    ): List<Profile> {
        val ordered = rules.sortedWith(compareBy(RuleEntity::userOrder, RuleEntity::id))
        val converted = ordered.mapNotNull { rule ->
            if (rule.type == RuleType.DNS.value) {
                omissions += ThrOmission("rule", rule.id, "dns_rule", entityName = rule.name)
                return@mapNotNull null
            }
            if (!rule.createDnsRule || rule.dnsServer.isNotBlank() || rule.dnsAction != "route" ||
                rule.dnsStrategy.isNotBlank() || rule.dnsDisableCache || rule.dnsRewriteTtl != 0 || rule.dnsClientSubnet.isNotBlank()) omissions += ThrOmission("rule", rule.id, "dns_override", entityName = rule.name)
            try {
                val config = if (rule.config.isBlank()) JSONObject() else JSONObject(rule.config)
                // These action-only overrides have an exact persisted representation. All other custom JSON
                // is rejected as a whole rather than dropping a matcher and widening the rule.
                require(config.keys().asSequence().all { it == "action" || it in overrideLists || it in overrideScalars })
                val action = config.optString("action").ifBlank { "route" }
                require(action in setOf("route", "reject", "hijack-dns", "sniff", "resolve", "route-options", "direct", "bypass"))
                if (action == "route" && rule.outbound > 0 && rule.outbound !in profileIds) {
                    omissions += ThrOmission("rule", rule.id, "missing_target", entityName = rule.name)
                    return@mapNotNull null
                }
                val rows = lines(rule.network).ifEmpty { listOf("") }.flatMap { network ->
                    lines(rule.protocol).ifEmpty { listOf("") }.map { protocol ->
                        row(rule.copy(network = network, protocol = protocol,
                            ruleset = lines(rule.ruleset).joinToString("\n", transform = resolveRuleSet)), action).toMutableMap().apply {
                            for (key in config.keys()) {
                                if (key == "action") continue
                                val value = config.get(key)
                                if (key in overrideLists) {
                                    require(value is JSONArray)
                                    val items = (0 until value.length()).map { value.get(it) }
                                    require(items.all { it is String || (key in setOf("port", "source_port") && it is Number) })
                                    put("${key}_json", JSONArray(items.map { it.toString() }).toString())
                                } else {
                                    require(if (key in booleanOverrides) value is Boolean else value is String)
                                    put(key, if (value is Boolean) if (value) 1 else 0 else value)
                                }
                            }
                        }
                    }
                }
                rule to rows.flatMap { converted ->
                    val destination = setOf("domain_json", "domain_suffix_json", "domain_keyword_json", "domain_regex_json", "ip_cidr_json", "ip_is_private")
                    val hasInline = destination.any { converted[it] != null && converted[it] != "[]" && converted[it] != 0 }
                    if (converted["rule_set_json"] != "[]" && hasInline && converted["invert"] != 1) {
                        listOf(converted - "rule_set_json", converted - destination)
                    } else listOf(converted)
                }
            } catch (_: Exception) {
                omissions += ThrOmission("rule", rule.id, if (rule.config.isBlank()) "profile_options" else "custom_json", entityName = rule.name)
                null
            }
        }
        val modes = ordered.map { it.clashMode.trim() }.filter(String::isNotEmpty)
            .distinctBy { it.lowercase(Locale.ROOT) }
        return (listOf("") + modes).mapIndexed { index, mode ->
            val rows: MutableList<ThrRow> = converted.filter { (rule, _) ->
                rule.clashMode.isBlank() || (mode.isNotEmpty() && rule.clashMode.trim().equals(mode, true))
            }.flatMap { it.second }.toMutableList()
            insertProtection(rows, tunMode, protectionName, bypassMode)
            Profile(index + 1L, mode.ifEmpty { commonName }, mode, rows)
        }
    }

    private val overrideLists = setOf("domain", "domain_suffix", "domain_keyword", "domain_regex", "ip_cidr",
        "source_ip_cidr", "port", "port_range", "source_port", "source_port_range", "inbound", "package_name",
        "package_name_regex", "network_type", "wifi_ssid", "wifi_bssid", "process_name", "process_path",
        "process_path_regex", "sniffers")
    private val booleanOverrides = setOf("invert", "ip_is_private", "source_ip_is_private", "network_is_expensive",
        "no_drop", "tls_fragment", "tls_record_fragment", "sniff_override_dest")
    private val overrideScalars = setOf("ip_version", "invert", "ip_is_private", "source_ip_is_private",
        "network_is_expensive", "reject_method", "no_drop", "override_address", "override_port", "strategy",
        "tls_fragment", "tls_fragment_fallback_delay", "tls_record_fragment", "sniff_override_dest")

    /** Must precede catch-all routes, but leave DNS hijacking first when present. */
    internal fun insertProtection(rows: MutableList<ThrRow>, tunMode: String, name: String, bypassMode: Boolean = true) {
        if (tunMode == "insecure") return
        val outbound = when (tunMode) { "direct" -> -2L; "proxy" -> -1L; "normal-direct-bypass-block" -> if (bypassMode) -3L else -2L; else -> -3L }
        val rule = mapOf(
            "name" to name,
            "package_name_json" to "[\"unknown\"]",
            "action" to "route",
            "outbound_id" to outbound,
        )
        val dns = rows.indexOfFirst { it["action"] == "hijack-dns" || it["outbound_id"] == -4L }
        rows.add(if (dns < 0) 0 else dns + 1, rule)
    }

    private fun row(rule: RuleEntity, action: String): ThrRow = buildMap {
        put("name", rule.name)
        put("disabled", if (rule.enabled) 0 else 1)
        // Throne persists blocking as a route to its Block target, including explicit reject overrides.
        put("outbound_id", if (action == "reject") -3L else when (rule.outbound) {
            0L -> -1L; -1L -> -2L; -2L -> -3L; else -> rule.outbound
        })
        put("action", if (action == "reject") "route" else action)
        val domains = lines(rule.domains).groupBy { value ->
            when {
                value.startsWith("full:") -> "domain_json"
                value.startsWith("regexp:") -> "domain_regex_json"
                value.startsWith("keyword:") -> "domain_keyword_json"
                value.startsWith("geosite:") -> "rule_set_json"
                else -> "domain_suffix_json"
            }
        }
        for ((column, values) in domains) {
            put(column, JSONArray(values.map {
                if (column == "rule_set_json") it else it.removePrefix("full:").removePrefix("domain:")
                    .removePrefix("regexp:").removePrefix("keyword:")
            }).toString())
        }
        val ips = lines(rule.ip)
        put("ip_cidr_json", JSONArray(ips.filterNot { it.startsWith("geoip:") }).toString())
        put("ip_is_private", if ("geoip:private" in ips) 1 else 0)
        val sets = domains["rule_set_json"].orEmpty() + ips.filter { it.startsWith("geoip:") && it != "geoip:private" } + lines(rule.ruleset)
        put("rule_set_json", JSONArray(sets.map {
            when {
                it.startsWith("geoip:") -> "geoip-" + it.removePrefix("geoip:")
                it.startsWith("geosite:") -> "geosite-" + it.removePrefix("geosite:")
                else -> it
            }
        }).toString())
        put("source_ip_cidr_json", JSONArray(lines(rule.source)).toString())
        for ((raw, prefix) in listOf(rule.port to "port", rule.sourcePort to "source_port")) {
            val values = lines(raw)
            require(values.all { ':' in it || it.toIntOrNull() in 1..65535 })
            put("${prefix}_json", JSONArray(values.filterNot { ':' in it }).toString())
            put("${prefix}_range_json", JSONArray(values.filter { ':' in it }).toString())
        }
        put("protocol", rule.protocol.trim())
        put("network", rule.network.trim())
        put("network_type_json", JSONArray(rule.networkType.toList()).toString())
        put("package_name_json", JSONArray(rule.packages.toList()).toString())
        if (RuleEntity.isWifiIdentityVisible(rule.networkType)) {
            put("wifi_ssid_json", JSONArray(RuleEntity.normalizeWifiSsidList(rule.wifiSsid)).toString())
            put("wifi_bssid_json", JSONArray(RuleEntity.normalizeWifiBssidList(rule.wifiBssid)).toString())
        }
    }
}
