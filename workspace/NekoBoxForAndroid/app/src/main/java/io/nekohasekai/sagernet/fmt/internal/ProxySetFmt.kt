package io.nekohasekai.sagernet.fmt.internal

import android.annotation.SuppressLint
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.isInsecureProfile
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.TypeMap
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.SingBoxOptions
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

fun ProxySetBean.setEmbeddedProfiles(profiles: List<AbstractBean>) {
    embeddedProfilesJson = JSONArray().apply {
        profiles.forEach { put(it.toEmbeddedUniversalLink()) }
    }.toString()
}

fun ProxySetBean.hasEmbeddedProfiles(): Boolean =
    runCatching { JSONArray(embeddedProfilesJson).length() > 0 }.getOrDefault(false)

fun ProxySetBean.decodeEmbeddedProfiles(): List<ProxyEntity> {
    val entries = runCatching { JSONArray(embeddedProfilesJson) }
        .onFailure { Logs.w(it) }
        .getOrNull() ?: return emptyList()
    return buildList {
        for (index in 0 until entries.length()) {
            val link = entries.optString(index).takeIf { it.isNotBlank() } ?: continue
            runCatching {
                embeddedUniversalLinkToProxy(link).apply { id = -(index + 1L) }
            }.onFailure { Logs.w(it) }
                .getOrNull()
                ?.let(::add)
        }
    }
}

@SuppressLint("NewApi") // java.util.Base64 is provided below API 26 by core library desugaring.
private fun AbstractBean.toEmbeddedUniversalLink(): String {
    val type = TypeMap.reversed[ProxyEntity().putBean(this).type]
        ?: error("Profile is not exportable as SN link")
    val compressed = ByteArrayOutputStream().use { output ->
        DeflaterOutputStream(output, Deflater(9)).use { it.write(KryoConverters.serialize(this)) }
        output.toByteArray()
    }
    return "sn://$type?" + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
}

@SuppressLint("NewApi") // java.util.Base64 is provided below API 26 by core library desugaring.
private fun embeddedUniversalLinkToProxy(link: String): ProxyEntity {
    require(link.startsWith("sn://") && '?' in link) { "Invalid embedded SN link" }
    val type = link.substringAfter("sn://").substringBefore('?')
    val profileType = TypeMap[type] ?: error("Type $type not found")
    val compressed = Base64.getUrlDecoder().decode(link.substringAfter('?'))
    val serialized = InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
    return ProxyEntity(type = profileType).apply { putByteArray(serialized) }
}

fun ProxySetBean.filterInsecureProfiles(
    profiles: List<ProxyEntity>,
    globalAllowInsecure: Boolean,
): List<ProxyEntity> {
    if (!skipInsecureProfiles) return profiles
    return profiles.filterNot { it.isInsecureProfile(globalAllowInsecure) }
}

fun buildSingBoxOutboundProxySetBean(
    bean: ProxySetBean,
    outboundsByProfileId: Map<Long, String>,
): SingBoxOptions.Outbound {
    val outbounds = outboundsByProfileId.values.toList()
    require(outbounds.isNotEmpty()) { "Proxy set has no eligible profiles" }
    if (bean.mode == ProxySetBean.MODE_SELECTOR) {
        return SingBoxOptions.Outbound_SelectorOptions().apply {
            type = "selector"
            this.outbounds = outbounds
            default_ = outboundsByProfileId[bean.defaultOutbound]
            interrupt_exist_connections = bean.interruptExistConnections
        }
    }
    return SingBoxOptions.Outbound_URLTestOptions().apply {
        type = "urltest"
        this.outbounds = outbounds
        url = bean.testURL
        interval = bean.testInterval
        idle_timeout = bean.testIdleTimeout
        tolerance = bean.testTolerance
        interrupt_exist_connections = bean.interruptExistConnections
    }
}
