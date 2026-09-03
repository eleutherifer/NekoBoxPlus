package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import moe.matsuri.nb4a.SingBoxOptions.DNSOptions
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.JavaUtil.gson

internal data class UrlTestDnsDefaults(
    val addresses: List<String>,
    val deadline: String,
    val strategy: String? = null,
)

internal object RawCustomConfigRenderer {
    fun render(
        proxy: ProxyEntity,
        forTest: Boolean,
        dnsDefaults: UrlTestDnsDefaults,
    ): ConfigBuildResult? {
        if (proxy.type != TYPE_CONFIG) return null
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type != 0) return null

        val tag = proxy.displayName()
        var config = bean.config
        if (forTest) {
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(config, MutableMap::class.java) as MutableMap<String, Any?>
            if (map["dns"] == null) {
                val dns = DNSOptions().apply {
                    servers = buildUrlTestDnsServers(
                        dnsDefaults.addresses,
                        dnsDefaults.deadline,
                        customConfigUrlTestDetourTag(map),
                    )
                    rules = emptyList()
                    final_ = "dns-remote"
                    dnsDefaults.strategy?.takeIf(String::isNotBlank)?.let { strategy = it }
                }
                if (injectUrlTestDnsIfMissing(map, dns.asMap())) config = gson.toJson(map)
            }
        }
        return ConfigBuildResult(
            config = config,
            externalIndex = emptyList(),
            mainEntId = proxy.id,
            trafficMap = mapOf(tag to listOf(proxy)),
            profileTagMap = mapOf(proxy.id to tag),
            selectorGroupId = -1L,
        )
    }
}

internal fun customConfigUrlTestDetourTag(config: Map<String, Any?>): String? {
    val route = config["route"] as? Map<*, *>
    val routeFinal = (route?.get("final") as? String)?.takeIf(String::isNotBlank)
    val indexedOutbounds = (config["outbounds"] as? List<*>)
        ?.mapIndexedNotNull { index, value ->
            val outbound = value as? Map<*, *> ?: return@mapIndexedNotNull null
            val tag = (outbound["tag"] as? String)?.takeIf(String::isNotBlank) ?: index.toString()
            tag to outbound
        }.orEmpty()
    val selectedTag = routeFinal ?: indexedOutbounds.firstOrNull()?.first ?: return null
    val selectedOutbound = indexedOutbounds.firstOrNull { (tag) -> tag == selectedTag }?.second
    val isEmptyDirect = selectedOutbound?.let { outbound ->
        outbound["type"] == "direct" && outbound.keys.all { it == "type" || it == "tag" }
    } == true
    return if (isEmptyDirect) null else selectedTag
}
