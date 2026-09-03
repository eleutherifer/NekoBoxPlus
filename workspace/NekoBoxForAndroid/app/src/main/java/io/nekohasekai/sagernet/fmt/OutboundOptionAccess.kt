package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption

private fun SingBoxOption.typedOptionField(name: String): java.lang.reflect.Field? {
    var current: Class<*>? = javaClass
    while (current != null) {
        try {
            return current.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            current = current.superclass
        }
    }
    return null
}

private fun SingBoxOption.stringTypedOptionField(name: String): String? =
    typedOptionField(name)?.get(this) as? String

internal fun SingBoxOption.setGeneratedOptionField(name: String, value: Any?) {
    _hack_config_map[name] = value
}

internal fun SingBoxOption.optionType(): String? =
    stringTypedOptionField("type") ?: _hack_config_map["type"] as? String ?: asMap()["type"] as? String

internal fun SingBoxOption.optionTag(): String? =
    stringTypedOptionField("tag") ?: _hack_config_map["tag"] as? String ?: asMap()["tag"] as? String

internal fun remoteDnsDetourTag(
    mainProxyTag: String,
    outbounds: List<SingBoxOption>,
): String? {
    val mainOutbound = outbounds.firstOrNull { it.optionTag() == mainProxyTag } ?: return mainProxyTag
    val fields = mainOutbound.asMap()
    val isEmptyDirect = fields["type"] == "direct" && fields.keys.all { it == "type" || it == "tag" }
    return if (isEmptyDirect) null else mainProxyTag
}

internal fun customDnsDetourTag(
    detour: String,
    mainProxyTag: String,
    outbounds: List<SingBoxOption>,
): String {
    if (normalizeCustomDnsDetour(detour) == TAG_DIRECT) return ""
    val mainOutbound = outbounds.firstOrNull { it.optionTag() == mainProxyTag }
    return if (mainOutbound?.optionType() == TAG_DIRECT) "" else mainProxyTag
}
