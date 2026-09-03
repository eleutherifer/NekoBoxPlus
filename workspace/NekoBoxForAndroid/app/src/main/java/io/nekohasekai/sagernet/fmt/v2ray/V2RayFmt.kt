package io.nekohasekai.sagernet.fmt.v2ray

import android.text.TextUtils
import com.google.gson.Gson
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.applySharedTLSOptions
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.utils.NGUtil
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

private val supportedKcpHeaderType = arrayOf(
    "none", "srtp", "utp", "wechat-video", "dtls", "wireguard", "dns"
)

private fun normalizePacketEncoding(value: String?): Int? {
    return when (value?.trim()?.lowercase()) {
        "packet", "packetaddr" -> StandardV2RayBean.PACKET_ENCODING_PACKETADDR
        "xudp" -> StandardV2RayBean.PACKET_ENCODING_XUDP
        "", "none", "0", "false", "off", "disabled" -> StandardV2RayBean.PACKET_ENCODING_NONE
        else -> null
    }
}

private fun normalizeXudpParameter(value: String?): Int? {
    return when (value?.trim()?.lowercase()) {
        "1", "true", "on", "yes", "xudp" -> StandardV2RayBean.PACKET_ENCODING_XUDP
        "", "0", "false", "off", "no", "none", "disabled" -> StandardV2RayBean.PACKET_ENCODING_NONE
        else -> null
    }
}

internal fun normalizeUTLSFingerprint(value: String): String {
    return value.trim().lowercase().removePrefix("hello")
}

/** Builds an xmux JSONObject from bean Tier-2 xmux fields, or null if none are set. */
private fun buildXmuxJsonObject(bean: StandardV2RayBean): JSONObject? {
    val obj = JSONObject()
    bean.xhttpXmuxMaxConcurrency?.takeIf { it.isNotBlank() }?.let { obj.put("max_concurrency", it) }
    bean.xhttpXmuxMaxConnections?.takeIf { it.isNotBlank() }?.let { obj.put("max_connections", it) }
    bean.xhttpXmuxCMaxReuseTimes?.takeIf { it.isNotBlank() }?.let { obj.put("c_max_reuse_times", it) }
    bean.xhttpXmuxHMaxRequestTimes?.takeIf { it.isNotBlank() }?.let { obj.put("h_max_request_times", it) }
    bean.xhttpXmuxHMaxReusableSecs?.takeIf { it.isNotBlank() }?.let { obj.put("h_max_reusable_secs", it) }
    bean.xhttpXmuxHKeepAlivePeriod?.trim()?.toLongOrNull()?.let { obj.put("h_keep_alive_period", it) }
    return if (obj.length() > 0) obj else null
}

private fun JSONObject.mergeFrom(other: JSONObject) {
    other.keys().forEach { key -> put(key, other.get(key)) }
}

private fun String?.hasXhttpExtraValue(): Boolean {
    return !isNullOrBlank() && !trim().equals("null", ignoreCase = true)
}

private fun String.hasInvalidPercentEncoding(): Boolean {
    forEachIndexed { index, c ->
        if (c == '%' && (index + 2 >= length ||
                    !this[index + 1].isDigitOrHexLetter() ||
                    !this[index + 2].isDigitOrHexLetter())
        ) {
            return true
        }
    }
    return false
}

private fun Char.isDigitOrHexLetter(): Boolean {
    return this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

private fun String.hasUnencodedFragmentCharacters(): Boolean {
    return any { it.code <= 0x20 || it.code >= 0x7F || it == '[' || it == ']' } ||
            hasInvalidPercentEncoding()
}

private fun parseV2RayName(link: String, url: HttpUrl): String? {
    val rawFragment = link.substringAfter('#', missingDelimiterValue = "")
    if (rawFragment.isEmpty()) return url.fragment
    if (!rawFragment.hasUnencodedFragmentCharacters()) return url.fragment
    return rawFragment.unUrlSafe()
}

private fun normalizeV2RayLinkForHttpUrl(link: String): String {
    val url = link.replace("vmess://", "https://").replace("vless://", "https://")
    val fragmentIndex = url.indexOf('#')
    if (fragmentIndex < 0) return url

    val rawFragment = url.substring(fragmentIndex + 1)
    if (rawFragment.isEmpty() || !rawFragment.hasUnencodedFragmentCharacters()) return url
    return url.substring(0, fragmentIndex + 1) + rawFragment.urlSafe()
}

fun xhttpHeadersToMap(headers: String?): LinkedHashMap<String, String> {
    return headers
        .orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val separatorIndex = line.indexOf(':')
            if (separatorIndex <= 0) return@mapNotNull null
            val name = line.substring(0, separatorIndex).trim()
            val value = line.substring(separatorIndex + 1).trim()
            if (name.isEmpty()) return@mapNotNull null
            name to value
        }
        .toMap(LinkedHashMap())
}

fun xhttpHeadersToJsonObject(headers: String?): JSONObject? {
    val headersMap = xhttpHeadersToMap(headers)
    if (headersMap.isEmpty()) return null
    return JSONObject().apply {
        headersMap.forEach { (name, value) -> put(name, value) }
    }
}

fun jsonObjectToXhttpHeaders(headers: JSONObject): String {
    return headers.keys().asSequence()
        .map { key -> "$key: ${headers.optString(key)}" }
        .joinToString("\n")
}

fun mapToXhttpHeaders(headers: Map<*, *>): String {
    return headers.entries.asSequence()
        .mapNotNull { (key, value) ->
            val name = key?.toString()?.trim().orEmpty()
            if (name.isEmpty() || value == null) return@mapNotNull null
            "$name: ${value.toString().trim()}"
        }
        .joinToString("\n")
}

fun applyClashXhttpOptions(bean: StandardV2RayBean, xhttpOpts: Map<*, *>) {
    xhttpOpts["host"]?.toString()?.let {
        bean.host = it
    }
    xhttpOpts["path"]?.toString()?.let {
        bean.path = it
    }
    xhttpOpts["mode"]?.toString()?.let {
        bean.xhttpMode = when (it) {
            "auto", "packet-up", "stream-up", "stream-one" -> it
            "" -> "auto"
            else -> bean.xhttpMode
        }
    }
    (xhttpOpts["headers"] as? Map<*, *>)?.let { headers ->
        bean.xhttpHeaders = mapToXhttpHeaders(headers)
    }

    val extra = JSONObject()
    putClashXhttpField(extra, xhttpOpts, "no-grpc-header", "no_grpc_header")
    putClashXhttpField(extra, xhttpOpts, "no-sse-header", "no_sse_header")
    putClashXhttpField(extra, xhttpOpts, "x-padding-bytes", "x_padding_bytes")
    putClashXhttpField(extra, xhttpOpts, "sc-max-each-post-bytes", "sc_max_each_post_bytes")
    putClashXhttpField(extra, xhttpOpts, "sc-min-posts-interval-ms", "sc_min_posts_interval_ms")
    putClashXhttpField(extra, xhttpOpts, "sc-max-buffered-posts", "sc_max_buffered_posts")
    putClashXhttpField(extra, xhttpOpts, "sc-stream-up-server-secs", "sc_stream_up_server_secs")
    putClashXhttpField(extra, xhttpOpts, "server-max-header-bytes", "server_max_header_bytes")
    putClashXhttpField(extra, xhttpOpts, "x-padding-obfs-mode", "x_padding_obfs_mode")
    putClashXhttpField(extra, xhttpOpts, "x-padding-key", "x_padding_key")
    putClashXhttpField(extra, xhttpOpts, "x-padding-header", "x_padding_header")
    putClashXhttpField(extra, xhttpOpts, "x-padding-placement", "x_padding_placement")
    putClashXhttpField(extra, xhttpOpts, "x-padding-method", "x_padding_method")
    putClashXhttpField(extra, xhttpOpts, "uplink-http-method", "uplink_http_method")
    putClashXhttpField(extra, xhttpOpts, "session-placement", "session_placement")
    putClashXhttpField(extra, xhttpOpts, "session-key", "session_key")
    putClashXhttpField(extra, xhttpOpts, "seq-placement", "seq_placement")
    putClashXhttpField(extra, xhttpOpts, "seq-key", "seq_key")
    putClashXhttpField(extra, xhttpOpts, "uplink-data-placement", "uplink_data_placement")
    putClashXhttpField(extra, xhttpOpts, "uplink-data-key", "uplink_data_key")
    putClashXhttpField(extra, xhttpOpts, "uplink-chunk-size", "uplink_chunk_size")
    putClashXhttpReuseSettings(extra, xhttpOpts["reuse-settings"] as? Map<*, *>)
    putClashXhttpDownloadSettings(extra, xhttpOpts["download-settings"] as? Map<*, *>)

    if (extra.length() > 0) {
        bean.xhttpExtra = XhttpExtraConverter.extractSupportedToGui(bean, extra.toString(2))
    }
}

private fun putClashXhttpField(target: JSONObject, source: Map<*, *>, clashKey: String, singBoxKey: String) {
    if (!source.containsKey(clashKey)) return
    source[clashKey]?.let {
        target.put(singBoxKey, it.toJsonValue())
    }
}

private fun putClashXhttpReuseSettings(target: JSONObject, reuseSettings: Map<*, *>?) {
    val xmux = clashXhttpReuseSettingsToJson(reuseSettings) ?: return
    target.put("xmux", xmux)
}

private fun clashXhttpReuseSettingsToJson(reuseSettings: Map<*, *>?): JSONObject? {
    if (reuseSettings == null) return null
    val xmux = JSONObject()
    putClashXhttpField(xmux, reuseSettings, "max-connections", "max_connections")
    putClashXhttpField(xmux, reuseSettings, "max-concurrency", "max_concurrency")
    putClashXhttpField(xmux, reuseSettings, "c-max-reuse-times", "c_max_reuse_times")
    putClashXhttpField(xmux, reuseSettings, "h-max-request-times", "h_max_request_times")
    putClashXhttpField(xmux, reuseSettings, "h-max-reusable-secs", "h_max_reusable_secs")
    putClashXhttpField(xmux, reuseSettings, "h-keep-alive-period", "h_keep_alive_period")
    return if (xmux.length() > 0) xmux else null
}

private fun putClashXhttpDownloadSettings(target: JSONObject, downloadSettings: Map<*, *>?) {
    if (downloadSettings == null) return
    val download = JSONObject()
    putClashXhttpField(download, downloadSettings, "host", "host")
    putClashXhttpField(download, downloadSettings, "path", "path")
    putClashXhttpField(download, downloadSettings, "server", "server")
    putClashXhttpField(download, downloadSettings, "port", "server_port")
    (downloadSettings["headers"] as? Map<*, *>)?.takeIf { it.isNotEmpty() }?.let { headers ->
        download.put("headers", headers.toJsonValue())
    }
    clashXhttpReuseSettingsToJson(downloadSettings["reuse-settings"] as? Map<*, *>)?.let {
        download.put("xmux", it)
    }
    clashXhttpDownloadTlsToJson(downloadSettings)?.let {
        download.put("tls", it)
    }
    if (download.length() > 0) {
        target.put("download", download)
    }
}

private fun clashXhttpDownloadTlsToJson(downloadSettings: Map<*, *>): JSONObject? {
    val realitySettings = downloadSettings["reality-opts"] as? Map<*, *>
    val tlsEnabled = downloadSettings["tls"].toBooleanOrNull() == true || realitySettings != null
    if (!tlsEnabled) return null

    val tls = JSONObject().put("enabled", true)
    (downloadSettings["servername"] ?: downloadSettings["sni"])?.toString()?.takeIf { it.isNotBlank() }?.let {
        tls.put("server_name", it)
    }
    downloadSettings["alpn"]?.let {
        tls.put("alpn", it.toJsonValue())
    }
    downloadSettings["skip-cert-verify"]?.toBooleanOrNull()?.let {
        tls.put("insecure", it)
    }
    downloadSettings["client-fingerprint"]?.toString()?.takeIf { it.isNotBlank() }?.let {
        tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
    }
    realitySettings?.let { realityOpts ->
        val reality = JSONObject().put("enabled", true)
        putClashXhttpField(reality, realityOpts, "public-key", "public_key")
        putClashXhttpField(reality, realityOpts, "short-id", "short_id")
        tls.put("reality", reality)
    }
    return tls
}

private fun Any?.toBooleanOrNull(): Boolean? {
    return when (this) {
        is Boolean -> this
        is Number -> toInt() != 0
        is String -> when {
            equals("true", ignoreCase = true) -> true
            equals("false", ignoreCase = true) -> false
            this == "1" -> true
            this == "0" -> false
            else -> null
        }
        else -> null
    }
}

private fun Any.toJsonValue(): Any {
    return when (this) {
        is Map<*, *> -> JSONObject().also { json ->
            entries.forEach { (key, value) ->
                if (key != null && value != null) json.put(key.toString(), value.toJsonValue())
            }
        }
        is Iterable<*> -> JSONArray().also { array ->
            forEach { value ->
                if (value != null) array.put(value.toJsonValue())
            }
        }
        is Array<*> -> JSONArray().also { array ->
            forEach { value ->
                if (value != null) array.put(value.toJsonValue())
            }
        }
        else -> this
    }
}

private fun applyGuiXhttpFields(
    bean: StandardV2RayBean,
    json: JSONObject,
) {
    xhttpHeadersToJsonObject(bean.xhttpHeaders)?.let {
        json.put("headers", it)
    }
    if (bean.xhttpUplinkDataPlacement?.isNotBlank() == true)
        json.put("uplink_data_placement", bean.xhttpUplinkDataPlacement)
    if (bean.xhttpSessionPlacement?.isNotBlank() == true)
        json.put("session_id_placement", bean.xhttpSessionPlacement)
    if (bean.xhttpSessionPlacementOld?.isNotBlank() == true)
        json.put("session_placement", bean.xhttpSessionPlacementOld)
    if (bean.xhttpPaddingMethod?.isNotBlank() == true)
        json.put("x_padding_method", bean.xhttpPaddingMethod)
    if (bean.xhttpPaddingObfsMode == true)
        json.put("x_padding_obfs_mode", true)
    if (bean.xhttpNoGrpcHeader == true)
        json.put("no_grpc_header", true)
    if (bean.xhttpNoSseHeader == true)
        json.put("no_sse_header", true)
    if (bean.xhttpXPaddingBytes?.isNotBlank() == true)
        json.put("x_padding_bytes", bean.xhttpXPaddingBytes)
    if (bean.xhttpScMaxEachPostBytes?.isNotBlank() == true)
        json.put("sc_max_each_post_bytes", bean.xhttpScMaxEachPostBytes)
    if (bean.xhttpScMinPostsIntervalMs?.isNotBlank() == true)
        json.put("sc_min_posts_interval_ms", bean.xhttpScMinPostsIntervalMs)
    bean.xhttpScMaxBufferedPosts?.trim()?.toLongOrNull()?.let {
        json.put("sc_max_buffered_posts", it)
    }
    if (bean.xhttpScStreamUpServerSecs?.isNotBlank() == true)
        json.put("sc_stream_up_server_secs", bean.xhttpScStreamUpServerSecs)
    if (bean.xhttpUplinkChunkSize?.isNotBlank() == true)
        json.put("uplink_chunk_size", bean.xhttpUplinkChunkSize)
    bean.xhttpServerMaxHeaderBytes?.trim()?.toIntOrNull()?.let {
        json.put("server_max_header_bytes", it)
    }
    if (bean.xhttpCongestionController?.isNotBlank() == true)
        json.put("congestion_controller", bean.xhttpCongestionController)
    bean.xhttpCwnd?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let {
        json.put("cwnd", it)
    }
    if (bean.xhttpXPaddingKey?.isNotBlank() == true)
        json.put("x_padding_key", bean.xhttpXPaddingKey)
    if (bean.xhttpXPaddingHeader?.isNotBlank() == true)
        json.put("x_padding_header", bean.xhttpXPaddingHeader)
    if (bean.xhttpXPaddingPlacement?.isNotBlank() == true)
        json.put("x_padding_placement", bean.xhttpXPaddingPlacement)
    if (bean.xhttpUplinkHttpMethod?.isNotBlank() == true)
        json.put("uplink_http_method", bean.xhttpUplinkHttpMethod)
    if (bean.xhttpUplinkDataKey?.isNotBlank() == true)
        json.put("uplink_data_key", bean.xhttpUplinkDataKey)
    if (bean.xhttpSessionKey?.isNotBlank() == true)
        json.put("session_id_key", bean.xhttpSessionKey)
    if (bean.xhttpSessionKeyOld?.isNotBlank() == true)
        json.put("session_key", bean.xhttpSessionKeyOld)
    if (bean.xhttpSessionIdTable?.isNotBlank() == true)
        json.put("session_id_table", bean.xhttpSessionIdTable)
    if (bean.xhttpSessionIdLength?.isNotBlank() == true)
        json.put("session_id_length", bean.xhttpSessionIdLength)
    if (bean.xhttpSeqPlacement?.isNotBlank() == true)
        json.put("seq_placement", bean.xhttpSeqPlacement)
    if (bean.xhttpSeqKey?.isNotBlank() == true)
        json.put("seq_key", bean.xhttpSeqKey)
    buildXmuxJsonObject(bean)?.let { guiXmux ->
        val mergedXmux = json.optJSONObject("xmux") ?: JSONObject()
        mergedXmux.mergeFrom(guiXmux)
        json.put("xmux", mergedXmux)
    }
}

private fun buildXhttpExtraForLink(bean: StandardV2RayBean): String {
    val merged = JSONObject()
    if (bean.xhttpExtra.hasXhttpExtraValue()) {
        runCatching {
            merged.mergeFrom(JSONObject(XhttpExtraConverter.xrayToSingBox(bean.xhttpExtra)))
        }.onFailure {
            return XhttpExtraConverter.singBoxToXray(bean.xhttpExtra)
        }
    }
    applyGuiXhttpFields(bean, merged)
    return if (merged.length() == 0) "" else XhttpExtraConverter.singBoxToXray(merged.toString())
}

data class VmessQRCode(
    var v: String = "",
    var ps: String = "",
    var add: String = "",
    var port: String = "",
    var id: String = "",
    var aid: String = "0",
    var scy: String = "",
    var net: String = "",
    var type: String = "",
    var host: String = "",
    var path: String = "",
    var tls: String = "",
    var sni: String = "",
    var alpn: String = "",
    var fp: String = "",
)

fun StandardV2RayBean.isTLS(): Boolean {
    return security == "tls" || security == "reality"
}

fun StandardV2RayBean.setTLS(boolean: Boolean) {
    security = if (boolean) "tls" else ""
}

fun parseV2Ray(link: String): StandardV2RayBean {
    // Try parse stupid formats first

    if (link.startsWith("vmess://") && !link.contains("?")) {
        try {
            return parseV2RayN(link)
        } catch (e: Exception) {
            Logs.i("try v2rayN: " + e.readableMessage)
        }
    }

    if (link.startsWith("vmess://")) {
        try {
            return tryResolveVmess4Kitsunebi(link)
        } catch (e: Exception) {
            Logs.i("try Kitsunebi: " + e.readableMessage)
        }
    }

    // "std" format

    val bean = VMessBean().apply { if (link.startsWith("vless://")) alterId = -1 }
    val url = normalizeV2RayLinkForHttpUrl(link).toHttpUrl()
    val name = parseV2RayName(link, url)

    if (url.password.isNotBlank()) {
        // https://github.com/v2fly/v2fly-github-io/issues/26 (rarely use)
        bean.serverAddress = url.host
        bean.serverPort = url.port
        bean.name = name

        var protocol = url.username
        bean.type = protocol
        bean.alterId = url.password.substringAfterLast('-').toInt()
        bean.uuid = url.password.substringBeforeLast('-')

        if (protocol.endsWith("+tls")) {
            bean.security = "tls"
            protocol = protocol.substring(0, protocol.length - 4)

            url.queryParameter("tlsServerName")?.let {
                if (it.isNotBlank()) {
                    bean.sni = it
                }
            }
        }

        when (protocol) {
//            "tcp" -> {
//                url.queryParameter("type")?.let { type ->
//                    if (type == "http") {
//                        bean.headerType = "http"
//                        url.queryParameter("host")?.let {
//                            bean.host = it
//                        }
//                    }
//                }
//            }
            "http" -> {
                url.queryParameter("path")?.let {
                    bean.path = it
                }
                url.queryParameter("host")?.let {
                    bean.host = it.split("|").joinToString(",")
                }
            }

            "ws" -> {
                url.queryParameter("path")?.let {
                    bean.path = it
                }
                url.queryParameter("host")?.let {
                    bean.host = it
                }
            }

            "grpc" -> {
                url.queryParameter("serviceName")?.let {
                    bean.path = it
                }
            }

            "httpupgrade" -> {
                url.queryParameter("path")?.let {
                    bean.path = it
                }
                url.queryParameter("host")?.let {
                    bean.host = it
                }
            }

            "xhttp" -> {
                url.queryParameter("host")?.let {
                    bean.host = it
                }
                url.queryParameter("path")?.let {
                    bean.path = it
                }
                url.queryParameter("mode")?.let {
                    bean.xhttpMode = it
                }
                url.queryParameter("extra")?.takeIf { it.hasXhttpExtraValue() }?.let {
                    bean.xhttpExtra = XhttpExtraConverter.extractSupportedToGui(bean, it)
                }
            }
        }
    } else {
        // also vless format
        bean.parseDuckSoft(url, name)
    }

    return bean
}

// https://github.com/XTLS/Xray-core/issues/91
fun StandardV2RayBean.parseDuckSoft(url: HttpUrl, resolvedName: String? = url.fragment) {
    serverAddress = url.host
    serverPort = url.port
    name = resolvedName

    if (this is TrojanBean) {
        password = url.username
    } else {
        uuid = url.username
    }

    // not ducksoft fmt path
    if (url.pathSegments.size > 1 || url.pathSegments[0].isNotBlank()) {
        path = url.pathSegments.joinToString("/")
    }

    type = url.queryParameter("type") ?: "tcp"
    if (type == "h2" || url.queryParameter("headerType") == "http") type = "http"

    security = url.queryParameter("security")
    if (security.isNullOrBlank()) {
        security = if (this is TrojanBean) "tls" else "none"
    }

    when (security) {
        "tls", "reality" -> {
            security = "tls"
            url.queryParameter("allowInsecure")?.let {
                allowInsecure = it == "1" || it == "true"
            }
            url.queryParameter("sni")?.let {
                sni = it
            }
            url.queryParameter("host")?.let {
                if (sni.isNullOrBlank()) sni = it
            }
            url.queryParameter("alpn")?.let {
                if (it != "none") alpn = it
            }
            url.queryParameter("cert")?.let {
                certificates = it
            }
            url.queryParameter("pbk")?.let {
                realityPubKey = it
            }
            url.queryParameter("sid")?.let {
                realityShortId = it
            }
            if (!realityPubKey.isNullOrBlank()) {
                security = "reality"
            }
        }
    }

    when (type) {
        "http" -> {
            url.queryParameter("host")?.let {
                host = it
            }
            url.queryParameter("path")?.let {
                path = it
            }
        }

        "kcp" -> {
            url.queryParameter("seed")?.let {
                mKcpSeed = it
            }
            url.queryParameter("headerType")?.let {
                if (it.isNotBlank()) {
                    if (it !in supportedKcpHeaderType) error("unsupported headerType")
                    headerType = it
                }
            }
            url.queryParameter("mtu")?.let {
                kcpMtu = it.toIntOrNull()
            }
            url.queryParameter("tti")?.let {
                kcpTti = it.toIntOrNull()
            }
            url.queryParameter("cwnd")?.let {
                kcpCwndMultiplier = it.toIntOrNull()
            }
        }

        "ws" -> {
            url.queryParameter("host")?.let {
                host = it
            }
            url.queryParameter("path")?.let {
                path = it
            }
            url.queryParameter("ed")?.let { ed ->
                wsMaxEarlyData = ed.toInt()

                url.queryParameter("eh")?.let {
                    earlyDataHeaderName = it
                }
            }
        }

        "grpc" -> {
            url.queryParameter("serviceName")?.let {
                path = it
            }
        }

        "httpupgrade" -> {
            url.queryParameter("host")?.let {
                host = it
            }
            url.queryParameter("path")?.let {
                path = it
            }
        }

        "xhttp" -> {
            url.queryParameter("host")?.let {
                host = it
            }
            url.queryParameter("path")?.let {
                path = it
            }
            url.queryParameter("mode")?.let {
                xhttpMode = it
            }
            url.queryParameter("extra")?.takeIf { it.hasXhttpExtraValue() }?.let {
                xhttpExtra = XhttpExtraConverter.extractSupportedToGui(this, it)
            }
        }
    }

    // maybe from matsuri vmess exoprt
    if (this is VMessBean && !isVLESS) {
        url.queryParameter("encryption")?.let {
            encryption = it
        }
    }

    val packetEncodingParameter = url.queryParameter("packetEncoding")
    val parsedPacketEncoding = normalizePacketEncoding(packetEncodingParameter)
    if (parsedPacketEncoding != null) {
        packetEncoding = parsedPacketEncoding
    } else if (isVLESS) {
        packetEncoding = if (packetEncodingParameter == null) {
            normalizeXudpParameter(url.queryParameter("xudp"))
                ?: StandardV2RayBean.PACKET_ENCODING_NOT_SPECIFIED
        } else {
            StandardV2RayBean.PACKET_ENCODING_NOT_SPECIFIED
        }
    }

    url.queryParameter("flow")?.let {
        if (isVLESS) {
            encryption = it.removeSuffix("-udp443")
        }
    }

    // VLESS encryption (ML-KEM-768)
    url.queryParameter("encryption")?.let {
        if (isVLESS && it != "none") {
            vlessEncryption = it
        }
    }

    url.queryParameter("fp")?.let {
        utlsFingerprint = normalizeUTLSFingerprint(it)
    }
}

// 不确定是谁的格式
private fun tryResolveVmess4Kitsunebi(server: String): VMessBean {
    // vmess://YXV0bzo1YWY1ZDBlYy02ZWEwLTNjNDMtOTNkYi1jYTMwMDg1MDNiZGJAMTgzLjIzMi41Ni4xNjE6MTIwMg
    // ?remarks=*%F0%9F%87%AF%F0%9F%87%B5JP%20-355%20TG@moon365free&obfsParam=%7B%22Host%22:%22183.232.56.161%22%7D&path=/v2ray&obfs=websocket&alterId=0

    var result = server.replace("vmess://", "")
    val indexSplit = result.indexOf("?")
    if (indexSplit > 0) {
        result = result.substring(0, indexSplit)
    }
    result = NGUtil.decode(result)

    val arr1 = result.split('@')
    if (arr1.count() != 2) {
        throw IllegalStateException("invalid kitsunebi format")
    }
    val arr21 = arr1[0].split(':')
    val arr22 = arr1[1].split(':')
    if (arr21.count() != 2) {
        throw IllegalStateException("invalid kitsunebi format")
    }

    return VMessBean().apply {
        serverAddress = arr22[0]
        serverPort = NGUtil.parseInt(arr22[1])
        uuid = arr21[1]
        encryption = arr21[0]
        if (indexSplit < 0) return@apply

        val url = ("https://localhost/path?" + server.substringAfter("?")).toHttpUrl()
        url.queryParameter("remarks")?.apply { name = this }
        url.queryParameter("alterId")?.apply { alterId = this.toInt() }
        url.queryParameter("path")?.apply { path = this }
        url.queryParameter("tls")?.apply { security = "tls" }
        url.queryParameter("allowInsecure")
            ?.apply { if (this == "1" || this == "true") allowInsecure = true }
        url.queryParameter("obfs")?.apply {
            type = this.replace("websocket", "ws").replace("none", "tcp")
            if (type == "ws") {
                url.queryParameter("obfsParam")?.apply {
                    if (this.startsWith("{")) {
                        host = JSONObject(this).getStr("Host")
                    } else if (security == "tls") {
                        sni = this
                    }
                }
            }
        }
    }
}

// SagerNet's
// Do not support some format and then throw exception
fun parseV2RayN(link: String): VMessBean {
    val result = link.substringAfter("vmess://").decodeBase64UrlSafe()
    if (result.contains("= vmess")) {
        return parseCsvVMess(result)
    }
    val bean = VMessBean()
    val vmessQRCode = Gson().fromJson(result, VmessQRCode::class.java)

    // Although VmessQRCode fields are non null, looks like Gson may still create null fields
    if (TextUtils.isEmpty(vmessQRCode.add)
        || TextUtils.isEmpty(vmessQRCode.port)
        || TextUtils.isEmpty(vmessQRCode.id)
        || TextUtils.isEmpty(vmessQRCode.net)
    ) {
        throw Exception("invalid VmessQRCode")
    }

    bean.name = vmessQRCode.ps
    bean.serverAddress = vmessQRCode.add
    bean.serverPort = vmessQRCode.port.toIntOrNull()
    bean.encryption = vmessQRCode.scy
    bean.uuid = vmessQRCode.id
    bean.alterId = vmessQRCode.aid.toIntOrNull()
    bean.type = vmessQRCode.net
    bean.host = vmessQRCode.host
    bean.path = vmessQRCode.path
    val headerType = vmessQRCode.type

    when (bean.type) {
        "kcp" -> {
            bean.mKcpSeed = vmessQRCode.path
            bean.headerType = headerType.takeIf { it.isNotBlank() } ?: "none"
        }
        "tcp" -> {
            if (headerType == "http") {
                bean.type = "http"
            }
        }
    }
    when (vmessQRCode.tls) {
        "tls", "reality" -> {
            bean.security = "tls"
            bean.sni = vmessQRCode.sni
            if (bean.sni.isNullOrBlank()) bean.sni = bean.host
            if (vmessQRCode.alpn != "none") bean.alpn = vmessQRCode.alpn
            bean.utlsFingerprint = vmessQRCode.fp
        }
    }

    return bean
}

private fun parseCsvVMess(csv: String): VMessBean {

    val args = csv.split(",")

    val bean = VMessBean()

    bean.serverAddress = args[1]
    bean.serverPort = args[2].toInt()
    bean.encryption = args[3]
    bean.uuid = args[4].replace("\"", "")

    args.subList(5, args.size).forEach {

        when {
            it == "over-tls=true" -> bean.security = "tls"
            it.startsWith("tls-host=") -> bean.host = it.substringAfter("=")
            it.startsWith("obfs=") -> bean.type = it.substringAfter("=")
            it.startsWith("obfs-path=") || it.contains("Host:") -> {
                runCatching {
                    bean.path = it.substringAfter("obfs-path=\"").substringBefore("\"obfs")
                }
                runCatching {
                    bean.host = it.substringAfter("Host:").substringBefore("[")
                }

            }

        }

    }

    return bean

}

fun VMessBean.toV2rayN(): String {
    val bean = this
    return "vmess://" + VmessQRCode().apply {
        v = "2"
        ps = bean.name
        add = bean.serverAddress
        port = bean.serverPort.toString()
        id = bean.uuid
        aid = bean.alterId.toString()
        net = bean.type
        host = bean.host
        path = bean.path

        when (net) {
            "http" -> {
                if (!isTLS()) {
                    type = "http"
                    net = "tcp"
                }
            }
            "kcp" -> {
                type = bean.headerType
                path = bean.mKcpSeed
            }
        }

        if (isTLS()) {
            tls = "tls"
            if (bean.realityPubKey.isNotBlank()) {
                tls = "reality"
            }
        }

        scy = bean.encryption
        sni = bean.sni
        alpn = bean.alpn.replace("\n", ",")
        fp = bean.utlsFingerprint
    }.let {
        NGUtil.encode(Gson().toJson(it))
    }
}

fun StandardV2RayBean.toUriVMessVLESSTrojan(isTrojan: Boolean): String {
    // VMess
    if (this is VMessBean && !isVLESS && type != "xhttp") {
        return toV2rayN()
    }

    // VLESS & Trojan (ducksoft fmt)
    val builder = linkBuilder()
        .username(if (this is TrojanBean) password else uuid)
        .host(serverAddress)
        .port(serverPort)
        .addQueryParameter("type", type)

    if (isVLESS) {
        // Add encryption if configured
        if (vlessEncryption.isNotBlank() && vlessEncryption != "none") {
            builder.addQueryParameter("encryption", vlessEncryption)
        } else {
            builder.addQueryParameter("encryption", "none")
        }

        if (encryption != "auto") builder.addQueryParameter("flow", encryption)
    } else if (this is VMessBean && encryption.isNotBlank() && encryption != "auto") {
        builder.addQueryParameter("encryption", encryption)
    }

    when (type) {
        "tcp" -> {}
        "ws", "http", "httpupgrade" -> {
            if (host.isNotBlank()) {
                builder.addQueryParameter("host", host)
            }
            if (path.isNotBlank()) {
                builder.addQueryParameter("path", path)
            }
            if (type == "ws") {
                if (wsMaxEarlyData > 0) {
                    builder.addQueryParameter("ed", "$wsMaxEarlyData")
                    if (earlyDataHeaderName.isNotBlank()) {
                        builder.addQueryParameter("eh", earlyDataHeaderName)
                    }
                }
            } else if (type == "http" && !isTLS()) {
                builder.setQueryParameter("type", "tcp")
                builder.addQueryParameter("headerType", "http")
            }
        }

        "kcp" -> {
            if (headerType.isNotBlank() && headerType != "none") {
                builder.addQueryParameter("headerType", headerType)
            }
            if (mKcpSeed.isNotBlank()) {
                builder.addQueryParameter("seed", mKcpSeed)
            }
            if (kcpMtu != null && kcpMtu!! > 0) {
                builder.addQueryParameter("mtu", kcpMtu.toString())
            }
            if (kcpTti != null && kcpTti!! > 0) {
                builder.addQueryParameter("tti", kcpTti.toString())
            }
            if (kcpCwndMultiplier != null && kcpCwndMultiplier!! > 0) {
                builder.addQueryParameter("cwnd", kcpCwndMultiplier.toString())
            }
        }

        "xhttp" -> {
            if (host.isNotBlank()) {
                builder.addQueryParameter("host", host)
            }
            if (path.isNotBlank()) {
                builder.addQueryParameter("path", path)
            }
            if (xhttpMode.isNotBlank()) {
                builder.addQueryParameter("mode", xhttpMode)
            }
            val linkExtra = buildXhttpExtraForLink(this)
            if (linkExtra.isNotBlank()) {
                builder.addQueryParameter("extra", linkExtra)
            }
        }

        "grpc" -> {
            if (path.isNotBlank()) {
                builder.setQueryParameter("serviceName", path)
            }
        }
    }

    val effectiveSecurity = security.takeIf { it.isNotBlank() && (it != "none" || isTrojan) } ?: ""
    if (effectiveSecurity.isNotBlank()) {
        builder.addQueryParameter("security", effectiveSecurity)
        when (effectiveSecurity) {
            "tls", "reality" -> {
                if (sni.isNotBlank()) {
                    builder.addQueryParameter("sni", sni)
                }
                if (alpn.isNotBlank()) {
                    builder.addQueryParameter("alpn", alpn.replace("\n", ","))
                }
                if (certificates.isNotBlank()) {
                    builder.addQueryParameter("cert", certificates)
                }
                if (allowInsecure) {
                    builder.addQueryParameter("allowInsecure", "1")
                }
                if (utlsFingerprint.isNotBlank()) {
                    builder.addQueryParameter("fp", utlsFingerprint)
                }
                if (realityPubKey.isNotBlank()) {
                    builder.setQueryParameter("security", "reality")
                    builder.addQueryParameter("pbk", realityPubKey)
                    builder.addQueryParameter("sid", realityShortId)
                }
            }
        }
    }

    when (packetEncoding) {
        StandardV2RayBean.PACKET_ENCODING_NONE -> {
            if (isVLESS) builder.addQueryParameter("packetEncoding", "none")
        }

        StandardV2RayBean.PACKET_ENCODING_PACKETADDR -> {
            builder.addQueryParameter("packetEncoding", "packetaddr")
        }

        StandardV2RayBean.PACKET_ENCODING_XUDP -> {
            builder.addQueryParameter("packetEncoding", "xudp")
        }
    }

    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    val scheme = when {
        isTrojan -> "trojan"
        this is VMessBean && !isVLESS -> "vmess"
        else -> "vless"
    }
    return builder.toLink(scheme)
}

fun buildSingBoxOutboundStreamSettings(bean: StandardV2RayBean): V2RayTransportOptions? {
    when (bean.type) {
        "tcp" -> {
            return null
        }

        "ws" -> {
            return V2RayTransportOptions_WebsocketOptions().apply {
                type = "ws"
                headers = mutableMapOf()

                if (bean.host.isNotBlank()) {
                    headers["Host"] = bean.host
                }

                if (bean.path.contains("?ed=")) {
                    path = bean.path.substringBefore("?ed=")
                    max_early_data = bean.path.substringAfter("?ed=").toIntOrNull() ?: 2048
                    early_data_header_name = "Sec-WebSocket-Protocol"
                } else {
                    path = bean.path.takeIf { it.isNotBlank() } ?: "/"
                }

                if (bean.wsMaxEarlyData > 0) {
                    max_early_data = bean.wsMaxEarlyData
                }

                if (bean.earlyDataHeaderName.isNotBlank()) {
                    early_data_header_name = bean.earlyDataHeaderName
                }
            }
        }

        "kcp" -> {
            return V2RayTransportOptions_KCPOptions().apply {
                type = "kcp"
                mtu = if (bean.kcpMtu != null && bean.kcpMtu!! > 0) bean.kcpMtu!! else 1350
                tti = if (bean.kcpTti != null && bean.kcpTti!! > 0) bean.kcpTti!! else 50
                uplink_capacity = 12
                downlink_capacity = 100
                congestion = false
                read_buffer_size = 1
                write_buffer_size = 1
                if (bean.kcpCwndMultiplier != null && bean.kcpCwndMultiplier!! > 0) {
                    cwnd_multiplier = bean.kcpCwndMultiplier!!
                }
                if (bean.kcpMaxSendingWindow != null && bean.kcpMaxSendingWindow!! > 0) {
                    max_sending_window = bean.kcpMaxSendingWindow!!
                }
                header_type = bean.headerType.takeIf { it.isNotBlank() } ?: "none"
                if (bean.mKcpSeed.isNotBlank()) {
                    seed = bean.mKcpSeed
                }
            }
        }

        "http" -> {
            return V2RayTransportOptions_HTTPOptions().apply {
                type = "http"
                if (!bean.isTLS()) method = "GET" // v2ray tcp header
                if (bean.host.isNotBlank()) {
                    host = bean.host.split(",")
                }
                path = bean.path.takeIf { it.isNotBlank() } ?: "/"
            }
        }

        "quic" -> {
            return V2RayTransportOptions().apply {
                type = "quic"
            }
        }

        "grpc" -> {
            return V2RayTransportOptions_GRPCOptions().apply {
                type = "grpc"
                service_name = bean.path
            }
        }

        "httpupgrade" -> {
            return V2RayTransportOptions_HTTPUpgradeOptions().apply {
                type = "httpupgrade"
                host = bean.host
                path = bean.path
            }
        }

        "xhttp" -> {
            val baseConfig = V2RayTransportOptions_XHTTPOptions().apply {
                type = "xhttp"
                mode = bean.xhttpMode.takeIf { it.isNotBlank() } ?: "auto"
                host = bean.host.takeIf { it.isNotBlank() }
                path = bean.path.takeIf { it.isNotBlank() } ?: "/"
            }

            // Merge xhttpExtra JSON config if present
            if (bean.xhttpExtra.hasXhttpExtraValue()) {
                try {
                    val gson = Gson()
                    // Convert base config to JSON
                    val baseJson = JSONObject(gson.toJson(baseConfig))
                    // Parse extra config
                    val extraJson = JSONObject(bean.xhttpExtra)
                    // Merge allowed extra fields into base config
                    val allowedKeys = arrayOf(
                        "download", "xmux", "x_padding_bytes", "no_grpc_header",
                        "sc_max_each_post_bytes", "sc_min_posts_interval_ms",
                        "no_sse_header", "sc_max_buffered_posts", "sc_stream_up_server_secs",
                        "uplink_data_placement", "uplink_data_key", "uplink_chunk_size",
                        "uplink_http_method", "domain_strategy", "trusted_x_forwarded_for", "headers",
                        "session_placement", "session_key", "session_id_placement",
                        "session_id_key", "session_id_table", "session_id_length",
                        "seq_placement", "seq_key",
                        "x_padding_obfs_mode", "x_padding_key", "x_padding_header",
                        "x_padding_placement", "x_padding_method",
                        "server_max_header_bytes", "congestion_controller", "cwnd"
                    )
                    allowedKeys.forEach { key ->
                        if (extraJson.has(key) && !extraJson.isNull(key)) {
                            baseJson.put(key, extraJson.get(key))
                        }
                    }
                    applyGuiXhttpFields(bean, baseJson)
                    // Convert merged JSON back to object
                    return gson.fromJson(baseJson.toString(), V2RayTransportOptions_XHTTPOptions::class.java)
                } catch (e: Exception) {
                    // If parsing fails, apply Tier-1 fields directly
                    e.printStackTrace()
                    baseConfig.apply {
                        xhttpHeadersToMap(bean.xhttpHeaders).takeIf { it.isNotEmpty() }?.let {
                            headers = it
                        }
                        if (bean.xhttpUplinkDataPlacement?.isNotBlank() == true)
                            uplink_data_placement = bean.xhttpUplinkDataPlacement
                        if (bean.xhttpSessionPlacement?.isNotBlank() == true)
                            session_id_placement = bean.xhttpSessionPlacement
                        if (bean.xhttpSessionPlacementOld?.isNotBlank() == true)
                            session_placement = bean.xhttpSessionPlacementOld
                        if (bean.xhttpPaddingMethod?.isNotBlank() == true)
                            x_padding_method = bean.xhttpPaddingMethod
                        if (bean.xhttpPaddingObfsMode == true)
                            x_padding_obfs_mode = true
                        if (bean.xhttpNoGrpcHeader == true)
                            no_grpc_header = com.google.gson.JsonPrimitive(true)
                        if (bean.xhttpNoSseHeader == true)
                            no_sse_header = com.google.gson.JsonPrimitive(true)
                        if (bean.xhttpXPaddingBytes?.isNotBlank() == true)
                            x_padding_bytes = com.google.gson.JsonPrimitive(bean.xhttpXPaddingBytes)
                        if (bean.xhttpScMaxEachPostBytes?.isNotBlank() == true)
                            sc_max_each_post_bytes = com.google.gson.JsonPrimitive(bean.xhttpScMaxEachPostBytes)
                        if (bean.xhttpScMinPostsIntervalMs?.isNotBlank() == true)
                            sc_min_posts_interval_ms = com.google.gson.JsonPrimitive(bean.xhttpScMinPostsIntervalMs)
                        bean.xhttpScMaxBufferedPosts?.trim()?.toLongOrNull()?.let {
                            sc_max_buffered_posts = com.google.gson.JsonPrimitive(it)
                        }
                        if (bean.xhttpScStreamUpServerSecs?.isNotBlank() == true)
                            sc_stream_up_server_secs = com.google.gson.JsonPrimitive(bean.xhttpScStreamUpServerSecs)
                        if (bean.xhttpUplinkChunkSize?.isNotBlank() == true)
                            uplink_chunk_size = com.google.gson.JsonPrimitive(bean.xhttpUplinkChunkSize)
                        bean.xhttpServerMaxHeaderBytes?.trim()?.toIntOrNull()?.let {
                            server_max_header_bytes = it
                        }
                        if (bean.xhttpCongestionController?.isNotBlank() == true)
                            congestion_controller = bean.xhttpCongestionController
                        bean.xhttpCwnd?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let {
                            cwnd = it
                        }
                        if (bean.xhttpXPaddingKey?.isNotBlank() == true)
                            x_padding_key = bean.xhttpXPaddingKey
                        if (bean.xhttpXPaddingHeader?.isNotBlank() == true)
                            x_padding_header = bean.xhttpXPaddingHeader
                        if (bean.xhttpXPaddingPlacement?.isNotBlank() == true)
                            x_padding_placement = bean.xhttpXPaddingPlacement
                        if (bean.xhttpUplinkHttpMethod?.isNotBlank() == true)
                            uplink_http_method = bean.xhttpUplinkHttpMethod
                        if (bean.xhttpUplinkDataKey?.isNotBlank() == true)
                            uplink_data_key = bean.xhttpUplinkDataKey
                        if (bean.xhttpSessionKey?.isNotBlank() == true)
                            session_id_key = bean.xhttpSessionKey
                        if (bean.xhttpSessionKeyOld?.isNotBlank() == true)
                            session_key = bean.xhttpSessionKeyOld
                        if (bean.xhttpSessionIdTable?.isNotBlank() == true)
                            session_id_table = bean.xhttpSessionIdTable
                        if (bean.xhttpSessionIdLength?.isNotBlank() == true)
                            session_id_length = com.google.gson.JsonPrimitive(bean.xhttpSessionIdLength)
                        if (bean.xhttpSeqPlacement?.isNotBlank() == true)
                            seq_placement = bean.xhttpSeqPlacement
                        if (bean.xhttpSeqKey?.isNotBlank() == true)
                            seq_key = bean.xhttpSeqKey
                        buildXmuxJsonObject(bean)?.let {
                            xmux = com.google.gson.JsonParser.parseString(it.toString())
                        }
                    }
                    return baseConfig
                }
            }
            // No xhttpExtra: apply Tier-1/Tier-2 fields directly
            baseConfig.apply {
                xhttpHeadersToMap(bean.xhttpHeaders).takeIf { it.isNotEmpty() }?.let {
                    headers = it
                }
                if (bean.xhttpUplinkDataPlacement?.isNotBlank() == true)
                    uplink_data_placement = bean.xhttpUplinkDataPlacement
                if (bean.xhttpSessionPlacement?.isNotBlank() == true)
                    session_id_placement = bean.xhttpSessionPlacement
                if (bean.xhttpSessionPlacementOld?.isNotBlank() == true)
                    session_placement = bean.xhttpSessionPlacementOld
                if (bean.xhttpPaddingMethod?.isNotBlank() == true)
                    x_padding_method = bean.xhttpPaddingMethod
                if (bean.xhttpPaddingObfsMode == true)
                    x_padding_obfs_mode = true
                if (bean.xhttpNoGrpcHeader == true)
                    no_grpc_header = com.google.gson.JsonPrimitive(true)
                if (bean.xhttpNoSseHeader == true)
                    no_sse_header = com.google.gson.JsonPrimitive(true)
                if (bean.xhttpXPaddingBytes?.isNotBlank() == true)
                    x_padding_bytes = com.google.gson.JsonPrimitive(bean.xhttpXPaddingBytes)
                if (bean.xhttpScMaxEachPostBytes?.isNotBlank() == true)
                    sc_max_each_post_bytes = com.google.gson.JsonPrimitive(bean.xhttpScMaxEachPostBytes)
                if (bean.xhttpScMinPostsIntervalMs?.isNotBlank() == true)
                    sc_min_posts_interval_ms = com.google.gson.JsonPrimitive(bean.xhttpScMinPostsIntervalMs)
                bean.xhttpScMaxBufferedPosts?.trim()?.toLongOrNull()?.let {
                    sc_max_buffered_posts = com.google.gson.JsonPrimitive(it)
                }
                if (bean.xhttpScStreamUpServerSecs?.isNotBlank() == true)
                    sc_stream_up_server_secs = com.google.gson.JsonPrimitive(bean.xhttpScStreamUpServerSecs)
                if (bean.xhttpUplinkChunkSize?.isNotBlank() == true)
                    uplink_chunk_size = com.google.gson.JsonPrimitive(bean.xhttpUplinkChunkSize)
                bean.xhttpServerMaxHeaderBytes?.trim()?.toIntOrNull()?.let {
                    server_max_header_bytes = it
                }
                if (bean.xhttpCongestionController?.isNotBlank() == true)
                    congestion_controller = bean.xhttpCongestionController
                bean.xhttpCwnd?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let {
                    cwnd = it
                }
                if (bean.xhttpXPaddingKey?.isNotBlank() == true)
                    x_padding_key = bean.xhttpXPaddingKey
                if (bean.xhttpXPaddingHeader?.isNotBlank() == true)
                    x_padding_header = bean.xhttpXPaddingHeader
                if (bean.xhttpXPaddingPlacement?.isNotBlank() == true)
                    x_padding_placement = bean.xhttpXPaddingPlacement
                if (bean.xhttpUplinkHttpMethod?.isNotBlank() == true)
                    uplink_http_method = bean.xhttpUplinkHttpMethod
                if (bean.xhttpUplinkDataKey?.isNotBlank() == true)
                    uplink_data_key = bean.xhttpUplinkDataKey
                if (bean.xhttpSessionKey?.isNotBlank() == true)
                    session_id_key = bean.xhttpSessionKey
                if (bean.xhttpSessionKeyOld?.isNotBlank() == true)
                    session_key = bean.xhttpSessionKeyOld
                if (bean.xhttpSessionIdTable?.isNotBlank() == true)
                    session_id_table = bean.xhttpSessionIdTable
                if (bean.xhttpSessionIdLength?.isNotBlank() == true)
                    session_id_length = com.google.gson.JsonPrimitive(bean.xhttpSessionIdLength)
                if (bean.xhttpSeqPlacement?.isNotBlank() == true)
                    seq_placement = bean.xhttpSeqPlacement
                if (bean.xhttpSeqKey?.isNotBlank() == true)
                    seq_key = bean.xhttpSeqKey
                buildXmuxJsonObject(bean)?.let {
                    xmux = com.google.gson.JsonParser.parseString(it.toString())
                }
            }
            return baseConfig
        }
    }

    return null
}

fun buildSingBoxOutboundTLS(bean: StandardV2RayBean): OutboundTLSOptions? {
    if (bean.security != "tls" && bean.security != "reality") return null
    return OutboundTLSOptions().apply {
        enabled = true
        insecure = bean.allowInsecure || DataStore.globalAllowInsecure
        if (bean.sni.isNotBlank()) server_name = bean.sni
        if (bean.alpn.isNotBlank()) {
            // 当传输协议为WebSocket时，过滤掉h2和h3
            val alpnList = bean.alpn.listByLineOrComma()
            if (bean.type == "ws") {
                val filtered = alpnList.filter { it == "http/1.1" }
                if (filtered.isNotEmpty()) alpn = filtered
            } else {
                alpn = alpnList
            }
        }
        if (bean.certificates.isNotBlank()) certificate = bean.certificates
        var fp = bean.utlsFingerprint
        if (bean.realityPubKey.isNotBlank()) {
            reality = OutboundRealityOptions().apply {
                enabled = true
                public_key = bean.realityPubKey
                short_id = bean.realityShortId
            }
            if (fp.isNullOrBlank()) fp = "chrome"
        }
        if (fp.isNotBlank()) {
            utls = OutboundUTLSOptions().apply {
                enabled = true
                fingerprint = fp
            }
        }
        if (bean.enableECH) {
            ech = OutboundECHOptions().apply {
                enabled = true
                if (bean.echConfig.isNotBlank()) {
                    config = bean.echConfig.lines()
                }
            }
        }
        applySharedTLSOptions(bean)
    }
}

fun buildSingBoxOutboundStandardV2RayBean(bean: StandardV2RayBean): Outbound {
    when (bean) {
        is HttpBean -> {
            return Outbound_HTTPOptions().apply {
                type = "http"
                server = bean.serverAddress
                server_port = bean.serverPort
                username = bean.username
                password = bean.password
                tls = buildSingBoxOutboundTLS(bean)
            }
        }

        is VMessBean -> {
            if (bean.isVLESS) return Outbound_VLESSOptions().apply {
                type = "vless"
                server = bean.serverAddress
                server_port = bean.serverPort
                uuid = bean.uuid
                if (bean.encryption.isNotBlank() && bean.encryption != "auto") {
                    flow = bean.encryption
                }
                if (bean.vlessEncryption.isNotBlank() && bean.vlessEncryption != "none") {
                    encryption = bean.vlessEncryption
                }
                when (bean.packetEncoding) {
                    StandardV2RayBean.PACKET_ENCODING_NONE -> packet_encoding = ""
                    StandardV2RayBean.PACKET_ENCODING_PACKETADDR -> packet_encoding = "packetaddr"
                    StandardV2RayBean.PACKET_ENCODING_XUDP -> packet_encoding = "xudp"
                }
                tls = buildSingBoxOutboundTLS(bean)
                transport = buildSingBoxOutboundStreamSettings(bean)
            }
            return Outbound_VMessOptions().apply {
                type = "vmess"
                server = bean.serverAddress
                server_port = bean.serverPort
                uuid = bean.uuid
                alter_id = bean.alterId
                security = bean.encryption.takeIf { it.isNotBlank() } ?: "auto"
                when (bean.packetEncoding) {
                    StandardV2RayBean.PACKET_ENCODING_NONE -> packet_encoding = ""
                    StandardV2RayBean.PACKET_ENCODING_PACKETADDR -> packet_encoding = "packetaddr"
                    StandardV2RayBean.PACKET_ENCODING_XUDP -> packet_encoding = "xudp"
                }
                tls = buildSingBoxOutboundTLS(bean)
                transport = buildSingBoxOutboundStreamSettings(bean)
            }
        }

        is TrojanBean -> {
            return Outbound_TrojanOptions().apply {
                type = "trojan"
                server = bean.serverAddress
                server_port = bean.serverPort
                password = bean.password
                tls = buildSingBoxOutboundTLS(bean)
                transport = buildSingBoxOutboundStreamSettings(bean)
            }
        }

        else -> throw IllegalStateException("can't reach")
    }
}
