package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.HTTPClientOptions
import moe.matsuri.nb4a.SingBoxOptions.MyOptions
import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions

private const val RULE_SET_HTTP_CLIENT = "ruleset-download"
private val ROUTE_DESTINATION_FIELDS = setOf(
    "domain",
    "domain_suffix",
    "domain_keyword",
    "domain_regex",
    "geosite",
    "geoip",
    "ip_cidr",
    "ip_is_private",
)

/** Applies global sing-box 1.14 wiring that should not live in the profile builder. */
internal fun MyOptions.applySingBox114Config(ruleSetDetour: String) {
    val remoteRuleSets = route?.rule_set?.filter { it.type == "remote" }.orEmpty()
    if (remoteRuleSets.isEmpty()) return

    remoteRuleSets.forEach { it.http_client = RULE_SET_HTTP_CLIENT }
    route.default_http_client = RULE_SET_HTTP_CLIENT
    http_clients = mutableListOf(
        HTTPClientOptions().apply {
            tag = RULE_SET_HTTP_CLIENT
            detour = ruleSetDetour
        },
    )
}

/**
 * Preserves the app's destination matcher semantics across the sing-box 1.14 beta.1 rule-set
 * change. A multi-rule rule-set is now an independent predicate, while the routing editor treats
 * inline destination matchers and destination rule-sets as alternatives.
 */
internal fun splitRouteRuleSetSemantics114(
    rule: Rule_DefaultOptions,
): List<Rule_DefaultOptions> {
    val ruleMap = rule.asMap()
    val ruleSets = ruleMap["rule_set"]
    val hasRuleSet =
        when (ruleSets) {
            is Collection<*> -> ruleSets.isNotEmpty()
            is String -> ruleSets.isNotBlank()
            else -> false
        }
    val hasInlineDestination = ROUTE_DESTINATION_FIELDS.any { field ->
        when (val value = ruleMap[field]) {
            is Collection<*> -> value.isNotEmpty()
            is String -> value.isNotBlank()
            is Boolean -> value
            else -> value != null
        }
    }
    if (
        !hasRuleSet ||
        !hasInlineDestination ||
        ruleMap["type"] == "logical" ||
        ruleMap["invert"] == true ||
        ruleMap["rule_set_ip_cidr_match_source"] == true ||
        ruleMap["rule_set_ipcidr_match_source"] == true
    ) {
        return listOf(rule)
    }

    val inlineRule = LinkedHashMap(ruleMap).apply {
        remove("rule_set")
        remove("rule_set_ip_cidr_match_source")
        remove("rule_set_ipcidr_match_source")
    }
    val ruleSetRule = LinkedHashMap(ruleMap).apply {
        ROUTE_DESTINATION_FIELDS.forEach(::remove)
    }
    return listOf(inlineRule, ruleSetRule).map { map ->
        Rule_DefaultOptions().apply {
            _hack_config_map = map
        }
    }
}

private fun Map<String, Any?>.hasEffectiveDNSRouteOptions(): Boolean {
    if (this["disable_cache"] == true || this["disable_optimistic_cache"] == true) return true
    if (this["rewrite_ttl"] != null) return true
    if ((this["timeout"] as? String).orEmpty().isNotBlank()) return true
    if ((this["client_subnet"] as? String).orEmpty().isNotBlank()) return true
    return false
}

private fun Map<*, *>.withoutLegacyDNSRuleStrategy(): MutableMap<String, Any?> {
    val sanitized = linkedMapOf<String, Any?>()
    forEach { (key, value) ->
        if (key is String && key != "strategy") sanitized[key] = value
    }
    val nestedRules = sanitized["rules"] as? List<*> ?: return sanitized
    sanitized["rules"] = nestedRules.map { nestedRule ->
        (nestedRule as? Map<*, *>)?.withoutLegacyDNSRuleStrategy() ?: nestedRule
    }
    return sanitized
}

/** Applies required DNS policy and removes sing-box 1.14 legacy rule strategies. */
internal fun MutableMap<String, Any?>.sanitizeDNSRules114(
    forcedStrategy: String? = null,
    firstRule: Map<String, Any?>? = null,
) {
    @Suppress("UNCHECKED_CAST")
    val dns = this["dns"] as? MutableMap<String, Any?> ?: return
    if (forcedStrategy != null) dns["strategy"] = forcedStrategy
    val rules = dns["rules"] as? List<*> ?: if (firstRule == null) return else emptyList<Any?>()
    val sanitizedRules = rules.mapNotNull { rawRule ->
        val rule = (rawRule as? Map<*, *>)?.withoutLegacyDNSRuleStrategy() ?: return@mapNotNull rawRule
        if (rule["action"] == "route-options" && !rule.hasEffectiveDNSRouteOptions()) {
            null
        } else {
            rule
        }
    }
    dns["rules"] = if (firstRule == null) sanitizedRules else listOf(firstRule) + sanitizedRules
}
