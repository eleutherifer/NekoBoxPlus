package io.nekohasekai.sagernet.ktx

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.applyAmneziaWG3Options
import io.nekohasekai.sagernet.fmt.wireguard.parseAmneziaWGUri
import io.nekohasekai.sagernet.fmt.wireguard.parseThroneWireGuardUri
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.mieru.parseMieru
import io.nekohasekai.sagernet.fmt.naive.parseNaive
import io.nekohasekai.sagernet.fmt.openvpn.parseOpenVPNConfig
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.shadowsocksr.parseShadowsocksR
import io.nekohasekai.sagernet.fmt.snell.parseSnell
import io.nekohasekai.sagernet.fmt.ssh.parseSSH
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.trusttunnel.parseTrustTunnel
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.fmt.juicity.parseJuicity
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.proxy.anytls.parseStormDns
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.group.GroupUpdater
import libcore.Libcore
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.InflaterOutputStream

// JSON & Base64

fun JSONObject.toStringPretty(): String {
    return gson.toJson(JsonParser.parseString(this.toString()))
}

inline fun <reified T : Any> JSONArray.filterIsInstance(): List<T> {
    val list = mutableListOf<T>()
    for (i in 0 until this.length()) {
        if (this[i] is T) list.add(this[i] as T)
    }
    return list
}

inline fun JSONArray.forEach(action: (Int, Any) -> Unit) {
    for (i in 0 until this.length()) {
        action(i, this[i])
    }
}

inline fun JSONObject.forEach(action: (String, Any) -> Unit) {
    for (k in this.keys()) {
        action(k, this.get(k))
    }
}

fun isJsonObjectValid(j: Any): Boolean {
    if (j is JSONObject) return true
    if (j is JSONArray) return true
    try {
        JSONObject(j as String)
    } catch (ex: JSONException) {
        try {
            JSONArray(j)
        } catch (ex1: JSONException) {
            return false
        }
    }
    return true
}

// wtf hutool
fun JSONObject.getStr(name: String): String? {
    val obj = this.opt(name) ?: return null
    if (obj is String) {
        if (obj.isBlank()) {
            return null
        }
        return obj
    } else {
        return null
    }
}

fun JSONObject.getBool(name: String): Boolean? {
    return try {
        getBoolean(name)
    } catch (ignored: Exception) {
        null
    }
}


// 重名了喵
fun JSONObject.getIntNya(name: String): Int? {
    return try {
        getInt(name)
    } catch (ignored: Exception) {
        null
    }
}


fun String.decodeBase64UrlSafe(): String {
    return String(Util.b64Decode(this))
}

// Sub

class SubscriptionFoundException(val link: String) : RuntimeException()
class AmneziaApiKeyUnsupportedException : RuntimeException("Amnezia API VPN keys are not supported")

suspend fun parseProxies(text: String): List<AbstractBean> {
    if (text.startsWith("openvpn://import-profile/", ignoreCase = true)) {
        val nested = text.substringAfter("openvpn://import-profile/")
        val decoded = if (nested.startsWith("https://", ignoreCase = true)) nested
            else URLDecoder.decode(nested.replace("+", "%2B"), Charsets.UTF_8.name())
        require(decoded.startsWith("https://", ignoreCase = true)) { "OpenVPN import profile URL must use HTTPS" }
        val client = Libcore.newHttpClient().apply {
            setTimeoutMillis(GroupUpdater.SUBSCRIPTION_UPDATE_TIMEOUT_MILLIS)
            tryH3Direct()
        }
        try {
            val response = client.newRequest().apply {
                setURL(decoded)
                setUserAgent(USER_AGENT)
            }.execute()
            val content = Util.getStringBox(response.contentString)
            require(content.toByteArray().size <= 4 * 1024 * 1024) { "OpenVPN profile is too large" }
            return listOf(parseOpenVPNConfig(content))
        } finally {
            client.close()
        }
    }
    val linksByLine = text.split('\n').map { it.trim() }
    fun String.looksLikeLink(): Boolean {
        val schemeEnd = indexOf("://")
        if (schemeEnd <= 0) return false
        return take(schemeEnd).withIndex().all { (index, c) ->
            c in 'a'..'z' || c in 'A'..'Z' ||
                    (index > 0 && (c in '0'..'9' || c == '+' || c == '.' || c == '-'))
        }
    }
    val links = linksByLine.flatMap { line ->
        val words = line.split(' ')
        val result = ArrayList<String>()
        var index = 0
        while (index < words.size) {
            val word = words[index]
            if (word.looksLikeLink() && word.contains('#')) {
                val merged = StringBuilder(word)
                index++
                while (index < words.size && !words[index].looksLikeLink()) {
                    merged.append(' ').append(words[index])
                    index++
                }
                result.add(merged.toString())
            } else {
                result.add(word)
                index++
            }
        }
        result
    }

    val entities = ArrayList<AbstractBean>()
    val entitiesByLine = ArrayList<AbstractBean>()
    var subscriptionException: SubscriptionFoundException? = null

    fun String.parseLink(entities: ArrayList<AbstractBean>) {
        if (startsWith("clash://install-config?") || startsWith("sn://subscription?")) {
            throw SubscriptionFoundException(this)
        }

        if (startsWith("sn://")) {
            Logs.d("Try parse universal link: $this")
            runCatching {
                entities.add(parseUniversal(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("socks://") || startsWith("socks4://") || startsWith("socks4a://") || startsWith(
                "socks5://"
            )
        ) {
            Logs.d("Try parse socks link: $this")
            runCatching {
                entities.add(parseSOCKS(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (matches("(http|https)://.*".toRegex())) {
            Logs.d("Try parse http link: $this")
            runCatching {
                entities.add(parseHttp(this))
            }.onFailure {
                Logs.w(it)
                if (subscriptionException == null) {
                    val clashUrl = HttpUrl.Builder()
                        .scheme("https")
                        .host("install-config")
                        .addQueryParameter("url", this)
                        .build()
                        .toString()
                        .replaceFirst("https://", "clash://")
                    subscriptionException = SubscriptionFoundException(clashUrl)
                }
            }
        } else if (startsWith("vmess://")) {
            Logs.d("Try parse v2ray link: $this")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("vless://")) {
            Logs.d("Try parse vless link: $this")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("trojan://")) {
            Logs.d("Try parse trojan link: $this")
            runCatching {
                entities.add(parseTrojan(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("trojan-go://")) {
            Logs.d("Try parse trojan-go link: $this")
            runCatching {
                entities.add(parseTrojanGo(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("ss://")) {
            Logs.d("Try parse shadowsocks link: $this")
            runCatching {
                entities.add(parseShadowsocks(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("ssr://")) {
            Logs.d("Try parse shadowsocksr link: $this")
            runCatching {
                entities.add(parseShadowsocksR(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("snell://")) {
            Logs.d("Try parse Snell link: $this")
            runCatching {
                entities.add(parseSnell(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("ssh://", ignoreCase = true)) {
            Logs.d("Try parse SSH link: $this")
            runCatching {
                entities.add(parseSSH(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("naive+")) {
            Logs.d("Try parse naive link: $this")
            runCatching {
                entities.add(parseNaive(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("hysteria://")) {
            Logs.d("Try parse hysteria1 link: $this")
            runCatching {
                entities.add(parseHysteria1(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("hysteria2://") || startsWith("hy2://")) {
            Logs.d("Try parse hysteria2 link: $this")
            runCatching {
                entities.add(parseHysteria2(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("tuic://")) {
            Logs.d("Try parse TUIC link: $this")
            runCatching {
                entities.add(parseTuic(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("juicity://")) {
            Logs.d("Try parse Juicity link: $this")
            runCatching {
                entities.add(parseJuicity(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("mierus://", ignoreCase = true)) {
            Logs.d("Try parse Mieru link: $this")
            runCatching {
                entities.addAll(parseMieru(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("tt://")) {
            Logs.d("Try parse TrustTunnel link: $this")
            runCatching {
                entities.add(parseTrustTunnel(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("anytls://")) {
            Logs.d("Try parse anytls link: $this")
            runCatching {
                entities.add(parseAnytls(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("stormdns://")) {
            Logs.d("Try parse stormdns link: $this")
            runCatching {
                entities.add(parseStormDns(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("wg://", ignoreCase = true) ||
            startsWith("wireguard://", ignoreCase = true)
        ) {
            Logs.d("Try parse WireGuard link")
            runCatching {
                entities.add(parseThroneWireGuardUri(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("amneziawg://", ignoreCase = true) ||
            startsWith("awg://", ignoreCase = true)
        ) {
            Logs.d("Try parse AmneziaWG link")
            runCatching {
                entities.addAll(parseAmneziaWGUri(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("vpn://")) {
            Logs.d("Try parse AmneziaVPN vpn:// link: $this")
            runCatching {
                entities.addAll(parseAmneziaVpnLink(this))
            }.onFailure {
                if (it is AmneziaApiKeyUnsupportedException) throw it
                Logs.w(it)
            }
        }
    }

    for (link in links) {
        link.parseLink(entities)
    }
    for (link in linksByLine) {
        link.parseLink(entitiesByLine)
    }

    // Only signal a subscription URL if no proxies were parsed at all.
    // This preserves user-facing behavior (single HTTP subscription URL pasted/scanned)
    // while allowing bulk content with incidental HTTP URLs to succeed.
    if (entities.isEmpty() && entitiesByLine.isEmpty()) {
        subscriptionException?.let { throw it }
    }

//    var isBadLink = false
    if (entities.onEach { it.initializeDefaultValues() }.size == entitiesByLine.onEach { it.initializeDefaultValues() }.size) run test@{
        entities.forEachIndexed { index, bean ->
            val lineBean = entitiesByLine[index]
            if (bean == lineBean && bean.displayName() != lineBean.displayName()) {
//                isBadLink = true
                return@test
            }
        }
    }
    val parsed = if (entities.size > entitiesByLine.size) entities else entitiesByLine
    val seenAmnezia = mutableSetOf<String>()
    return parsed.filter { bean ->
        bean !is AmneziaWGBean || seenAmnezia.add(bean.hash)
    }
}

/**
 * Parses an AmneziaVPN vpn:// link.
 *
 * Format: vpn:// + base64( 4-byte-big-endian-length + zlib-compressed-json )
 *
 * The decoded JSON has a "containers" array. Supported Amnezia containers are
 * imported only when they map to native Nekobox profile types.
 *
 * Only manual import is supported (not subscriptions).
 */
fun parseAmneziaVpnLink(link: String): List<AbstractBean> {
    val encoded = link.removePrefix("vpn://")
    return parseAmneziaVpnPayload(Util.b64Decode(encoded))
}

fun parseAmneziaVpnQrPayload(data: ByteArray): List<AbstractBean> {
    return runCatching {
        parseAmneziaVpnPayload(data)
    }.getOrElse {
        if (it is AmneziaApiKeyUnsupportedException) throw it
        parseAmneziaVpnPayload(Util.b64Decode(data.toString(Charsets.UTF_8).trim().removePrefix("vpn://")))
    }
}

fun parseAmneziaVpnPayload(decoded: ByteArray): List<AbstractBean> {
    if (decoded.size < 4) error("vpn:// link too short")

    val isApiKey = decoded[0] == 0.toByte()
            && decoded[1] == 0.toByte()
            && decoded[2] == 0.toByte()
            && decoded[3] == 0xff.toByte()

    val compressed = decoded.copyOfRange(4, decoded.size)

    val out = ByteArrayOutputStream()
    InflaterOutputStream(out).use { it.write(compressed) }
    val json = JSONObject(out.toString("UTF-8"))

    if (isApiKey || json.has("api_key") || json.has("auth_data")) {
        throw AmneziaApiKeyUnsupportedException()
    }

    val serverTitle = json.optAmneziaServerTitle()
    val containers = json.optJSONArray("containers") ?: error("No containers in vpn:// link")
    val results = mutableListOf<AbstractBean>()

    for (i in 0 until containers.length()) {
        val container = containers.getJSONObject(i)
        when (container.optString("container")) {
            "amnezia-wireguard" -> {
                val wgConfig = container.optJSONObject("wireguard") ?: continue
                results.addAll(parseAmneziaWireGuardContainer(wgConfig))
            }
            "amnezia-awg" -> {
                val awgConfig = container.optJSONObject("awg")
                    ?: container.optJSONObject("awg2")
                    ?: continue
                val lastConfig = awgConfig.optString("last_config").takeIf { it.isNotBlank() }
                    ?: continue
                results.addAll(parseAmneziaAwgLastConfig(lastConfig))
            }
            "amnezia-awg2" -> {
                val awgConfig = container.optJSONObject("awg") ?: continue
                val lastConfigStr = awgConfig.optString("last_config").takeIf { it.isNotBlank() } ?: continue
                results.addAll(parseAmneziaAwgLastConfig(lastConfigStr))
            }
            "amnezia-xray" -> {
                val xrayConfig = container.optJSONObject("xray") ?: continue
                parseAmneziaXrayContainer(xrayConfig)?.let { results.add(it) }
            }
            "amnezia-ssxray" -> {
                val ssConfig = container.optJSONObject("ssxray")
                    ?: container.optJSONObject("shadowsocks")
                    ?: continue
                parseAmneziaShadowsocksContainer(ssConfig)?.let { results.add(it) }
            }
            "amnezia-shadowsocks" -> {
                val ssConfig = container.optJSONObject("shadowsocks") ?: continue
                parseAmneziaShadowsocksContainer(ssConfig)?.let { results.add(it) }
            }
        }
    }

    if (results.isEmpty()) error("No supported Nekobox configuration found in vpn:// link")
    serverTitle?.let { title ->
        results.forEach { it.name = title }
    }
    return results
}

private fun JSONObject.optAmneziaServerTitle(): String? {
    return optString("name").takeIf { it.isNotBlank() }
        ?: optString("description").takeIf { it.isNotBlank() }
}

private fun parseAmneziaWireGuardContainer(wgConfig: JSONObject): List<WireGuardBean> {
    val lastConfig = wgConfig.optString("last_config").takeIf { it.isNotBlank() } ?: return emptyList()
    val lc = runCatching { JSONObject(lastConfig) }.getOrNull()
    val config = lc?.optString("config")?.takeIf { it.isNotBlank() } ?: lastConfig
    return runCatching { RawUpdater.parseWireGuard(config) }.getOrElse {
        Logs.w(it)
        emptyList()
    }
}

private fun parseAmneziaAwgLastConfig(lastConfig: String): List<AmneziaWGBean> {
    val lastConfigJson = runCatching { JSONObject(lastConfig) }.getOrNull()
    val config = lastConfigJson?.let { lc ->
        val nativeConfig = lc.optString("config").takeIf { it.isNotBlank() } ?: return emptyList()
        val peerSection = Regex("(?im)^\\s*\\[Peer]\\s*$").find(nativeConfig)
        val interfaceIni = peerSection?.let { nativeConfig.substring(0, it.range.first) } ?: nativeConfig
        val hostName = lc.optString("hostName").takeIf { it.isNotBlank() } ?: return emptyList()
        val port = lc.optString("port").takeIf { it.isNotBlank() } ?: return emptyList()
        val serverPubKey = lc.optString("server_pub_key").takeIf { it.isNotBlank() } ?: return emptyList()
        val pskKey = lc.optString("psk_key")
        val keepAlive = lc.optString("persistent_keep_alive")
        val allowedIpsArr = lc.optJSONArray("allowed_ips")
        val allowedIps = if (allowedIpsArr != null && allowedIpsArr.length() > 0) {
            (0 until allowedIpsArr.length()).joinToString(", ") { allowedIpsArr.getString(it) }
        } else {
            "0.0.0.0/0, ::/0"
        }

        buildString {
            append(interfaceIni.trimEnd())
            append("\n\n[Peer]\n")
            append("PublicKey = $serverPubKey\n")
            if (pskKey.isNotBlank()) append("PresharedKey = $pskKey\n")
            append("Endpoint = $hostName:$port\n")
            append("AllowedIPs = $allowedIps\n")
            if (keepAlive.isNotBlank()) append("PersistentKeepalive = $keepAlive\n")
        }
    } ?: lastConfig

    return runCatching { RawUpdater.parseAmneziaWG(config) }.getOrElse {
        Logs.w(it)
        emptyList()
    }.onEach { bean ->
        lastConfigJson?.let { json ->
            bean.applyAmneziaWG3Options { key ->
                json.optString(key).takeIf(String::isNotBlank)
            }
        }
    }
}

private fun parseAmneziaXrayContainer(xrayConfig: JSONObject): VMessBean? {
    val lastConfig = xrayConfig.optString("last_config").takeIf { it.isNotBlank() } ?: return null
    val config = runCatching { JSONObject(lastConfig) }.getOrNull() ?: return null
    val outbound = config.optJSONArray("outbounds")?.let { outbounds ->
        (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("protocol") == "vless" }
    } ?: return null
    val server = outbound.optJSONObject("settings")
        ?.optJSONArray("vnext")
        ?.optJSONObject(0)
        ?: return null
    val user = server.optJSONArray("users")?.optJSONObject(0) ?: return null
    val streamSettings = outbound.optJSONObject("streamSettings") ?: JSONObject()
    val realitySettings = streamSettings.optJSONObject("realitySettings") ?: JSONObject()

    return VMessBean().applyDefaultValues().apply {
        alterId = -1
        name = "AmneziaVPN"
        serverAddress = server.optString("address").takeIf { it.isNotBlank() } ?: return null
        serverPort = server.optInt("port").takeIf { it > 0 } ?: return null
        uuid = user.optString("id").takeIf { it.isNotBlank() } ?: return null
        encryption = user.optString("flow")
        vlessEncryption = user.optString("encryption", "none")
        type = streamSettings.optString("network", "tcp")
        if (streamSettings.optString("security") == "reality") {
            security = "tls"
            sni = realitySettings.optString("serverName")
            realityPubKey = realitySettings.optString("publicKey")
            realityShortId = realitySettings.optString("shortId")
            utlsFingerprint = realitySettings.optString("fingerprint", "chrome")
            path = realitySettings.optString("spiderX")

            if (!realityPubKey.isNullOrBlank()) {
                security = "reality"
            }
        } else {
            security = streamSettings.optString("security", "none")
        }
    }
}

private fun parseAmneziaShadowsocksContainer(ssConfig: JSONObject): ShadowsocksBean? {
    val lastConfig = ssConfig.optString("last_config").takeIf { it.isNotBlank() } ?: return null
    val config = runCatching { JSONObject(lastConfig) }.getOrNull() ?: return null
    return config.parseAmneziaShadowsocks() ?: config.parseAmneziaXrayShadowsocks()
}

private fun JSONObject.parseAmneziaShadowsocks(): ShadowsocksBean? {
    return ShadowsocksBean().applyDefaultValues().apply {
        name = "AmneziaVPN"
        serverAddress = optString("server").takeIf { it.isNotBlank() } ?: return null
        serverPort = (optInt("server_port").takeIf { it > 0 }
            ?: optInt("port").takeIf { it > 0 })
            ?: return null
        method = optString("method").takeIf { it.isNotBlank() } ?: return null
        password = optString("password").takeIf { it.isNotBlank() } ?: return null
    }
}

private fun JSONObject.parseAmneziaXrayShadowsocks(): ShadowsocksBean? {
    val outbound = optJSONArray("outbounds")?.let { outbounds ->
        (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("protocol") == "shadowsocks" }
    } ?: return null
    val server = outbound.optJSONObject("settings")
        ?.optJSONArray("servers")
        ?.optJSONObject(0)
        ?: return null
    return server.parseAmneziaShadowsocks()
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}
