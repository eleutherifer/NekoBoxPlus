package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import moe.matsuri.nb4a.makeSingBoxRule

internal fun shouldCreateBaseRouteDnsRule(
    uidList: List<Int>,
    domainList: List<String>?,
    hasIpCriteria: Boolean,
    hasDomainRuleset: Boolean,
    hasOtherRouteCriteria: Boolean,
    clashMode: String,
): Boolean {
    if (!domainList.isNullOrEmpty() || clashMode.isNotBlank()) return true
    return uidList.isNotEmpty() &&
            !hasIpCriteria &&
            !hasDomainRuleset &&
            !hasOtherRouteCriteria
}

internal fun hasGeoIpOrRsipMatcher(
    ipList: List<String>?,
    rulesetList: List<String>?,
): Boolean =
    ipList.orEmpty().any { it.startsWith("geoip:") } ||
        rulesetList.orEmpty().any { it.startsWith("rsip:") }

fun buildRouteDnsRules(
    createDnsRule: Boolean,
    hasGeoIpOrRsipMatcher: Boolean = false,
    createBaseDnsRule: Boolean = true,
    outbound: Long,
    uidList: List<Int>,
    domainList: List<String>?,
    ruleSet: List<String>?,
    rulesetTags: List<Pair<String, Boolean>>,
    useFakeDns: Boolean,
    clashMode: String = "",
): List<DNSRule_DefaultOptions> {
    if (!createDnsRule || hasGeoIpOrRsipMatcher) return emptyList()

    val dnsRules = mutableListOf<DNSRule_DefaultOptions>()

    fun DNSRule_DefaultOptions.hasRouteDnsMatcher(): Boolean {
        if (rule_set?.isNotEmpty() == true) return true
        if (domain?.isNotEmpty() == true) return true
        if (domain_suffix?.isNotEmpty() == true) return true
        if (domain_regex?.isNotEmpty() == true) return true
        if (domain_keyword?.isNotEmpty() == true) return true
        if (user_id?.isNotEmpty() == true) return true
        if (!clash_mode.isNullOrBlank()) return true
        return false
    }

    fun makeDnsRuleObj(): DNSRule_DefaultOptions {
        return DNSRule_DefaultOptions().apply {
            if (uidList.isNotEmpty()) user_id = uidList
            domainList?.let { makeSingBoxRule(it) }
            if (clashMode.isNotBlank()) clash_mode = clashMode
        }
    }

    fun addBaseDnsRule(configure: DNSRule_DefaultOptions.() -> Unit) {
        if (!createBaseDnsRule) return
        val rule = makeDnsRuleObj()
        if (!rule.hasRouteDnsMatcher()) return
        dnsRules += rule.apply(configure)
    }

    fun DNSRule_DefaultOptions.routeTo(server: String) {
        this.server = server
    }

    fun DNSRule_DefaultOptions.blockWithSuccessResponse() {
        action = "predefined"
        rcode = "NOERROR"
    }

    fun addRuleSetDnsRules(
        server: String?,
        configure: DNSRule_DefaultOptions.() -> Unit = {},
    ) {
        val routeRuleSet = ruleSet ?: return
        if (rulesetTags.isEmpty()) return

        for (tag in routeRuleSet) {
            val tagInfo = rulesetTags.find { it.first == tag }
            if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                dnsRules += DNSRule_DefaultOptions().apply {
                    rule_set = mutableListOf(tag)
                    if (server != null) {
                        routeTo(server)
                    }
                    if (clashMode.isNotBlank()) clash_mode = clashMode
                    configure()
                }
            }
        }
    }

    when (outbound) {
        -1L -> {
            addBaseDnsRule { routeTo("dns-direct") }
            addRuleSetDnsRules("dns-direct")
        }

        0L -> {
            if (useFakeDns) {
                addBaseDnsRule {
                    routeTo("dns-fake")
                    inbound = listOf(TAG_TUN)
                    query_type = listOf("A", "AAAA")
                }
                addRuleSetDnsRules("dns-fake") {
                    inbound = listOf(TAG_TUN)
                    query_type = listOf("A", "AAAA")
                }
            } else {
                addBaseDnsRule {
                    routeTo("dns-remote")
                }
                addRuleSetDnsRules("dns-remote")
            }
        }

        -2L -> {
            addBaseDnsRule {
                blockWithSuccessResponse()
            }
            addRuleSetDnsRules(null) {
                blockWithSuccessResponse()
            }
        }
    }

    return dnsRules
}
