package io.nekohasekai.sagernet.fmt.wireguard

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.toStringPretty
import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val AMNEZIAWG_SCHEME = "amneziawg://"
private const val AMNEZIAWG_SHORT_SCHEME = "awg://"

private val configNameRegex = Regex(
    """(?im)^\s*[#;]\s*Name\s*=\s*(.+?)\s*$""",
)

private val throneAmneziaParameters = setOf(
    "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
    "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
    "header_protection_key", "content_padding_addition", "rekey_after_time",
    "rekey_timeout", "reject_after_time", "keepalive_timeout", "max_handshake_attempts",
    "random_trailers", "disable_cookies",
)

fun parseThroneWireGuardUri(url: String): AbstractBean {
    requireNotNull(url.replaceBefore("://", "https").toHttpUrlOrNull()) {
        "Invalid WireGuard link"
    }
    val wireGuard = parseWireGuardUri(url)
    val isAmnezia = url.throneQueryParameter("enable_amnezia") == "true" ||
        throneAmneziaParameters.any { url.throneQueryParameter(it) != null }
    if (!isAmnezia) return wireGuard

    return AmneziaWGBean().apply {
        initializeDefaultValues()
        name = wireGuard.name
        serverAddress = wireGuard.serverAddress
        serverPort = wireGuard.serverPort
        localAddress = wireGuard.localAddress
        privateKey = wireGuard.privateKey
        peerPublicKey = wireGuard.peerPublicKey
        peerPreSharedKey = wireGuard.peerPreSharedKey
        peerPersistentKeepalive = url.throneQueryParameter("persistent_keepalive_interval") ?: "0"
        mtu = wireGuard.mtu
        reserved = wireGuard.reserved
        url.throneQueryParameter("jc")?.toIntOrNull()?.let { jc = it }
        url.throneQueryParameter("jmin")?.toIntOrNull()?.let { jmin = it }
        url.throneQueryParameter("jmax")?.toIntOrNull()?.let { jmax = it }
        url.throneQueryParameter("s1")?.toIntOrNull()?.let { s1 = it }
        url.throneQueryParameter("s2")?.toIntOrNull()?.let { s2 = it }
        url.throneQueryParameter("s3")?.toIntOrNull()?.let { s3 = it }
        url.throneQueryParameter("s4")?.toIntOrNull()?.let { s4 = it }
        url.throneQueryParameter("h1")?.let { h1 = it }
        url.throneQueryParameter("h2")?.let { h2 = it }
        url.throneQueryParameter("h3")?.let { h3 = it }
        url.throneQueryParameter("h4")?.let { h4 = it }
        url.throneQueryParameter("i1")?.let { i1 = it }
        url.throneQueryParameter("i2")?.let { i2 = it }
        url.throneQueryParameter("i3")?.let { i3 = it }
        url.throneQueryParameter("i4")?.let { i4 = it }
        url.throneQueryParameter("i5")?.let { i5 = it }
        url.throneQueryParameter("header_protection_key")?.let { headerProtectionKey = it }
        url.throneQueryParameter("content_padding_addition")?.let { contentPaddingAddition = it }
        url.throneQueryParameter("rekey_after_time")?.let { rekeyAfterTime = it }
        url.throneQueryParameter("rekey_timeout")?.let { rekeyTimeout = it }
        url.throneQueryParameter("reject_after_time")?.let { rejectAfterTime = it }
        url.throneQueryParameter("keepalive_timeout")?.let { keepaliveTimeout = it }
        url.throneQueryParameter("max_handshake_attempts")?.let { maxHandshakeAttempts = it }
        parseAmneziaWGToggle(url.throneQueryParameter("random_trailers"))?.let { randomTrailers = it }
        parseAmneziaWGToggle(url.throneQueryParameter("disable_cookies"))?.let { disableCookies = it }
    }
}

internal fun parseAmneziaWGToggle(value: String?): Boolean? =
    when (value?.trim()?.lowercase(Locale.ROOT)) {
        "1", "true", "yes", "on", "enabled" -> true
        "0", "false", "no", "off", "disabled" -> false
        else -> null
    }

internal fun AmneziaWGBean.applyAmneziaWG3Options(option: (String) -> String?) {
    option("HeaderProtectionKey")?.let { headerProtectionKey = it }
    option("ContentPaddingAddition")?.let { contentPaddingAddition = it }
    option("RekeyAfterTime")?.let { rekeyAfterTime = it }
    option("RekeyTimeout")?.let { rekeyTimeout = it }
    option("RejectAfterTime")?.let { rejectAfterTime = it }
    option("KeepaliveTimeout")?.let { keepaliveTimeout = it }
    option("MaxHandshakeAttempts")?.let { maxHandshakeAttempts = it }
    parseAmneziaWGToggle(option("RandomTrailers"))?.let { randomTrailers = it }
    parseAmneziaWGToggle(option("DisableCookies"))?.let { disableCookies = it }
}

fun parseAmneziaWGUri(link: String): List<AmneziaWGBean> {
    val scheme = when {
        link.startsWith(AMNEZIAWG_SCHEME, ignoreCase = true) -> AMNEZIAWG_SCHEME
        link.startsWith(AMNEZIAWG_SHORT_SCHEME, ignoreCase = true) -> AMNEZIAWG_SHORT_SCHEME
        else -> error("Invalid AmneziaWG link")
    }
    val encodedPart = link.substring(scheme.length)
    val encodedConfig = encodedPart.substringBefore('#')
    require(encodedConfig.isNotBlank()) { "Missing AmneziaWG config" }
    val config = decodeUrlSafeBase64(encodedConfig)
    val fragmentName = encodedPart.substringAfter('#', "")
        .takeIf(String::isNotBlank)
        ?.let(::decodeFragment)
    return parseNamedAmneziaWGConfig(config, fragmentName)
}

fun AmneziaWGBean.toAmneziaWGUri(): String {
    val encoded = encodeUrlSafeBase64(buildAmneziaWGConfig())
    return buildString {
        append(AMNEZIAWG_SCHEME).append(encoded)
        if (name.isNotBlank()) append('#').append(encodeFragment(name))
    }
}

fun parseAmneziaWGJsonContainer(json: JSONObject): List<AmneziaWGBean> {
    require(json.optString("type") == "amneziawg") { "Invalid AmneziaWG JSON container" }
    val servers = json.optJSONArray("servers") ?: error("Missing AmneziaWG servers")
    val results = mutableListOf<AmneziaWGBean>()
    val seenConfigs = mutableSetOf<String>()
    for (index in 0 until servers.length()) {
        val server = servers.optJSONObject(index) ?: continue
        val encoded = server.optString("config").takeIf(String::isNotBlank) ?: continue
        runCatching {
            val config = decodeUrlSafeBase64(encoded)
            if (seenConfigs.add(config)) {
                results += parseNamedAmneziaWGConfig(
                    config,
                    server.optString("name").takeIf(String::isNotBlank),
                )
            }
        }
    }
    return results
}

fun buildAmneziaWGJsonContainer(beans: List<AmneziaWGBean>): String {
    val servers = JSONArray()
    beans.forEach { bean ->
        servers.put(
            JSONObject()
                .put("name", bean.displayName())
                .put("config", encodeUrlSafeBase64(bean.buildAmneziaWGConfig())),
        )
    }
    return JSONObject()
        .put("type", "amneziawg")
        .put("version", 1)
        .put("servers", servers)
        .toStringPretty()
}

private fun parseNamedAmneziaWGConfig(
    config: String,
    explicitName: String?,
): List<AmneziaWGBean> {
    val commentName = configNameRegex.find(config)?.groupValues?.get(1)?.trim()
    return RawUpdater.parseAmneziaWG(config).onEach { bean ->
        bean.name = explicitName ?: commentName ?: bean.serverAddress
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun encodeUrlSafeBase64(value: String): String =
    Base64.UrlSafe.encode(value.toByteArray()).trimEnd('=')

@OptIn(ExperimentalEncodingApi::class)
private fun decodeUrlSafeBase64(value: String): String {
    val padded = value.padEnd(value.length + (4 - value.length % 4) % 4, '=')
    return Base64.UrlSafe.decode(padded).toString(Charsets.UTF_8)
}

private fun encodeFragment(value: String): String = HttpUrl.Builder()
    .scheme("https")
    .host("fragment.invalid")
    .fragment(value)
    .build()
    .encodedFragment
    .orEmpty()

private fun decodeFragment(value: String): String =
    "https://fragment.invalid/#$value".toHttpUrlOrNull()?.fragment
        ?: error("Invalid AmneziaWG profile name")

fun AmneziaWGBean.buildAmneziaWGConfig(): String = buildString {
    append("[Interface]\n")
    normalizeWireGuardAddressList(localAddress).forEach {
        append("Address = ").append(it).append('\n')
    }
    append("PrivateKey = ").append(privateKey).append('\n')
    if (mtu > 0) append("MTU = ").append(mtu).append('\n')
    if (jc != 0) append("Jc = ").append(jc).append('\n')
    if (jmin != 0) append("Jmin = ").append(jmin).append('\n')
    if (jmax != 0) append("Jmax = ").append(jmax).append('\n')
    if (s1 != 0) append("S1 = ").append(s1).append('\n')
    if (s2 != 0) append("S2 = ").append(s2).append('\n')
    if (h1.isNotBlank()) append("H1 = ").append(h1).append('\n')
    if (h2.isNotBlank()) append("H2 = ").append(h2).append('\n')
    if (h3.isNotBlank()) append("H3 = ").append(h3).append('\n')
    if (h4.isNotBlank()) append("H4 = ").append(h4).append('\n')
    if (i1.isNotBlank()) append("I1 = ").append(i1).append('\n')
    if (i2.isNotBlank()) append("I2 = ").append(i2).append('\n')
    if (i3.isNotBlank()) append("I3 = ").append(i3).append('\n')
    if (i4.isNotBlank()) append("I4 = ").append(i4).append('\n')
    if (i5.isNotBlank()) append("I5 = ").append(i5).append('\n')
    if (s3 != 0) append("S3 = ").append(s3).append('\n')
    if (s4 != 0) append("S4 = ").append(s4).append('\n')
    if (headerProtectionKey.isNotBlank()) {
        append("HeaderProtectionKey = ").append(headerProtectionKey).append('\n')
    }
    if (contentPaddingAddition.isNotBlank()) {
        append("ContentPaddingAddition = ").append(contentPaddingAddition).append('\n')
    }
    if (rekeyAfterTime.isNotBlank()) append("RekeyAfterTime = ").append(rekeyAfterTime).append('\n')
    if (rekeyTimeout.isNotBlank()) append("RekeyTimeout = ").append(rekeyTimeout).append('\n')
    if (rejectAfterTime.isNotBlank()) append("RejectAfterTime = ").append(rejectAfterTime).append('\n')
    if (keepaliveTimeout.isNotBlank()) append("KeepaliveTimeout = ").append(keepaliveTimeout).append('\n')
    if (maxHandshakeAttempts.isNotBlank()) {
        append("MaxHandshakeAttempts = ").append(maxHandshakeAttempts).append('\n')
    }
    if (randomTrailers) append("RandomTrailers = on\n")
    if (disableCookies) append("DisableCookies = on\n")
    append('\n')
    append("[Peer]\n")
    append("PublicKey = ").append(peerPublicKey).append('\n')
    if (peerPreSharedKey.isNotBlank()) {
        append("PresharedKey = ").append(peerPreSharedKey).append('\n')
    }
    append("Endpoint = ").append(serverAddress.wrapIPV6Host()).append(':').append(serverPort).append('\n')
    append("AllowedIPs = 0.0.0.0/0, ::/0\n")
    if (peerPersistentKeepalive.isNotBlank() && peerPersistentKeepalive != "0") {
        append("PersistentKeepalive = ").append(peerPersistentKeepalive).append('\n')
    }
    if (reserved.isNotBlank()) {
        append("Reserved = ").append(reserved).append('\n')
    }
}

fun buildSingBoxEndpointAwgBean(bean: AmneziaWGBean): SingBoxOptions.AwgEndpointOptions {
    return SingBoxOptions.AwgEndpointOptions().apply {
        type = "awg"
        address = normalizeWireGuardAddressList(bean.localAddress)
        private_key = bean.privateKey
        mtu = bean.mtu

        val peer = SingBoxOptions.AwgPeer().apply {
            address = bean.serverAddress.wrapIPV6Host()
            port = bean.serverPort
            public_key = bean.peerPublicKey
            if (bean.peerPreSharedKey.isNotBlank()) preshared_key = bean.peerPreSharedKey
            if (bean.peerPersistentKeepalive.isNotBlank() && bean.peerPersistentKeepalive != "0") {
                persistent_keepalive_interval = bean.peerPersistentKeepalive
            }
            allowed_ips = listOf("0.0.0.0/0", "::/0")
            if (bean.reserved.isNotBlank()) {
                parseReservedValues(bean.reserved.trim())?.let { reserved = it }
            }
        }
        peers = listOf(peer)

        // AWG 1.0 obfuscation parameters
        if (bean.jc != 0) jc = bean.jc
        if (bean.jmin != 0) jmin = bean.jmin
        if (bean.jmax != 0) jmax = bean.jmax
        if (bean.s1 != 0) s1 = bean.s1
        if (bean.s2 != 0) s2 = bean.s2
        if (bean.h1.isNotBlank()) h1 = bean.h1
        if (bean.h2.isNotBlank()) h2 = bean.h2
        if (bean.h3.isNotBlank()) h3 = bean.h3
        if (bean.h4.isNotBlank()) h4 = bean.h4

        // AWG 1.5 signature chain parameters
        if (bean.i1.isNotBlank()) i1 = bean.i1
        if (bean.i2.isNotBlank()) i2 = bean.i2
        if (bean.i3.isNotBlank()) i3 = bean.i3
        if (bean.i4.isNotBlank()) i4 = bean.i4
        if (bean.i5.isNotBlank()) i5 = bean.i5

        // AWG 2.0 additional packet padding parameters
        if (bean.s3 != 0) s3 = bean.s3
        if (bean.s4 != 0) s4 = bean.s4

        // AWG 3.0 parameters
        if (bean.headerProtectionKey.isNotBlank()) {
            header_protection_key = bean.headerProtectionKey
        }
        if (bean.contentPaddingAddition.isNotBlank()) {
            content_padding_addition = bean.contentPaddingAddition
        }
        if (bean.rekeyAfterTime.isNotBlank()) rekey_after_time = bean.rekeyAfterTime
        if (bean.rekeyTimeout.isNotBlank()) rekey_timeout = bean.rekeyTimeout
        if (bean.rejectAfterTime.isNotBlank()) reject_after_time = bean.rejectAfterTime
        if (bean.keepaliveTimeout.isNotBlank()) keepalive_timeout = bean.keepaliveTimeout
        if (bean.maxHandshakeAttempts.isNotBlank()) {
            max_handshake_attempts = bean.maxHandshakeAttempts
        }
        if (bean.randomTrailers) random_trailers = true
        if (bean.disableCookies) disable_cookies = true
    }
}

fun AmneziaWGBean.hasAmneziaWG31Options(): Boolean =
    randomTrailers == true || disableCookies == true

fun AmneziaWGBean.hasAmneziaWG3Options(): Boolean =
    !headerProtectionKey.isNullOrBlank() ||
        !contentPaddingAddition.isNullOrBlank() ||
        !rekeyAfterTime.isNullOrBlank() ||
        !rekeyTimeout.isNullOrBlank() ||
        !rejectAfterTime.isNullOrBlank() ||
        !keepaliveTimeout.isNullOrBlank() ||
        !maxHandshakeAttempts.isNullOrBlank() ||
        peerPersistentKeepalive?.let {
            it.contains('-') || (it.toULongOrNull()?.let { value -> value > 65_535u } == true)
        } == true
