package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.internal.setEmbeddedProfiles
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.XhttpExtraConverter
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isIpAddressV6
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.Base64
import java.util.Locale

/**
 * Imports the client-outbound subset of the Xray JSON format.
 *
 * A nullable return value deliberately means "not Xray"; an empty list means
 * that Xray was recognized but contained no runnable, valid remote outbounds.
 */
internal object XrayParser {
    private val supportedProtocols =
        setOf("http", "socks", "shadowsocks", "vmess", "vless", "trojan", "hysteria", "wireguard")
    private val convertibleBalancerStrategies = setOf("random", "leastping", "roundrobin", "leastload")
    private val durationPattern = Regex("^(?:\\d+(?:\\.\\d+)?(?:ns|us|µs|ms|s|m|h))+$")
    private const val superscriptDigits = "⁰¹²³⁴⁵⁶⁷⁸⁹"

    private data class ParsedOutbound(
        val source: JSONObject,
        val tag: String,
        val beans: List<AbstractBean>,
    )

    private data class ProbeSettings(
        val url: String?,
        val interval: String?,
    )

    fun parse(text: String): List<AbstractBean>? {
        val root = runCatching { JSONTokener(text).nextValue() }.getOrNull() ?: return null
        val configs =
            when (root) {
                is JSONObject -> listOf(root)
                is JSONArray -> root.objectsOrNull() ?: return null
                else -> return null
            }
        if (configs.isEmpty()) return null

        val outboundsByConfig = mutableListOf<Pair<JSONObject, List<JSONObject>>>()
        var sawProtocol = false
        for (config in configs) {
            val outbounds = config.optJSONArray("outbounds")?.objectsOrNull() ?: return null
            if (outbounds.any { it.has("type") }) return null
            if (outbounds.any { it.has("protocol") }) sawProtocol = true
            outboundsByConfig += config to outbounds
        }
        if (!sawProtocol) return null

        return buildList {
            for ((config, outbounds) in outboundsByConfig) {
                val remarks = config.optString("remarks").takeIf(String::isNotBlank)
                val parsedOutbounds = outbounds.map { outbound ->
                    val protocol = outbound.optString("protocol").lowercase(Locale.ROOT)
                    val realName = remarks ?: "Xray ${protocol.replaceFirstChar { it.uppercase() }}"
                    val beans =
                        if (protocol !in supportedProtocols) {
                            emptyList()
                        } else runCatching { parseOutbound(outbound, protocol) }
                            .onFailure {
                                Logs.w("Skipping invalid Xray $protocol outbound ${outbound.optString("tag")}")
                            }.getOrNull()
                            .orEmpty()
                    beans.forEach { it.name = realName }
                    ParsedOutbound(outbound, outbound.optString("tag"), beans)
                }

                val parsedBeans = parsedOutbounds.flatMap(ParsedOutbound::beans)
                parsedBeans.forEachIndexed { index, bean ->
                    val realName = bean.name
                    bean.name = if (parsedBeans.size == 1) realName else "${(index + 1).toSuperscript()} $realName"
                    bean.initializeDefaultValues()
                }

                val duplicateTags = parsedOutbounds.filter { it.tag.isNotBlank() }
                    .groupingBy(ParsedOutbound::tag)
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
                val taggedOutbounds = parsedOutbounds.filter { it.tag.isNotBlank() }.sortedBy(ParsedOutbound::tag)
                val convertedTags = mutableSetOf<String>()
                val retainedByUnsafeBalancer = mutableSetOf<String>()
                var retainAllForUnsafeBalancer = false
                val converted = mutableListOf<ProxySetBean>()
                val balancers = config.optJSONObject("routing")
                    ?.optJSONArray("balancers")
                    ?.objectsOrNull()
                    .orEmpty()
                val duplicateBalancerTags = balancers.map { it.optString("tag") }
                    .filter(String::isNotBlank)
                    .groupingBy { it }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys

                for (balancer in balancers) {
                    val selectors = balancer.optJSONArray("selector")?.strings().orEmpty()
                    val selected = if (selectors.isEmpty()) {
                        emptyList()
                    } else {
                        taggedOutbounds.filter { outbound -> selectors.any(outbound.tag::startsWith) }
                    }
                    val strategyObject = balancer.optJSONObject("strategy")
                    val strategy = strategyObject
                        ?.optString("type")
                        .orEmpty()
                        .lowercase(Locale.ROOT)
                        .replace("-", "")
                        .replace("_", "")
                        .ifBlank { "random" }
                    val fallbackTag = balancer.optString("fallbackTag")
                    val fallback = fallbackTag.takeIf(String::isNotBlank)?.let { tag ->
                        taggedOutbounds.singleOrNull { it.tag == tag }
                    }
                    val members = if (fallback == null || fallback in selected) selected else selected + fallback
                    val safe =
                        balancer.optString("tag").isNotBlank() &&
                            balancer.optString("tag") !in duplicateBalancerTags &&
                            (!balancer.has("strategy") || balancer.isNull("strategy") || strategyObject != null) &&
                            selectors.isNotEmpty() &&
                            selected.isNotEmpty() &&
                            strategy in convertibleBalancerStrategies &&
                            (fallbackTag.isBlank() || fallback != null) &&
                            members.none { it.tag in duplicateTags } &&
                            members.all { it.source.optString("protocol").lowercase(Locale.ROOT) in supportedProtocols && it.beans.isNotEmpty() }

                    if (!safe) {
                        if (selectors.isEmpty()) retainAllForUnsafeBalancer = true
                        retainedByUnsafeBalancer += selected.map(ParsedOutbound::tag)
                        continue
                    }

                    val memberBeans = members.flatMap(ParsedOutbound::beans)
                    convertedTags += members.map(ParsedOutbound::tag)
                    converted += ProxySetBean().apply {
                        name = remarks ?: balancer.optString("tag")
                        mode = ProxySetBean.MODE_URL_TEST
                        type = ProxySetBean.TYPE_LIST
                        setEmbeddedProfiles(memberBeans)
                        applyProbeSettings(config, members.map(ParsedOutbound::tag))
                        initializeDefaultValues()
                    }
                }

                addAll(converted)
                parsedOutbounds.forEach { outbound ->
                    if (
                        retainAllForUnsafeBalancer ||
                        outbound.tag.isBlank() ||
                        outbound.tag !in convertedTags ||
                        outbound.tag in retainedByUnsafeBalancer
                    ) {
                        addAll(outbound.beans)
                    }
                }
            }
        }
    }

    private fun ProxySetBean.applyProbeSettings(config: JSONObject, memberTags: List<String>) {
        val candidates = buildList {
            config.optJSONObject("observatory")?.let { observatory ->
                probeSettings(
                    observatory.optJSONArray("subjectSelector")?.strings().orEmpty(),
                    observatory.optString("probeURL").ifBlank { observatory.optString("probeUrl") },
                    observatory.optString("probeInterval"),
                    memberTags,
                )?.let(::add)
            }
            config.optJSONObject("burstObservatory")?.let { observatory ->
                val ping = observatory.optJSONObject("pingConfig")
                probeSettings(
                    observatory.optJSONArray("subjectSelector")?.strings().orEmpty(),
                    ping?.optString("destination").orEmpty(),
                    ping?.optString("interval").orEmpty(),
                    memberTags,
                )?.let(::add)
            }
        }.distinct()
        if (candidates.size != 1) return
        candidates.single().url?.let { testURL = it }
        candidates.single().interval?.let { testInterval = it }
    }

    private fun probeSettings(
        selectors: List<String>,
        url: String,
        interval: String,
        memberTags: List<String>,
    ): ProbeSettings? {
        if (selectors.isEmpty() || memberTags.any { tag -> selectors.none(tag::startsWith) }) return null
        val mappedURL = url.takeIf(::isHttpURL)
        val mappedInterval = interval.takeIf { durationPattern.matches(it) }
        return if (mappedURL == null && mappedInterval == null) null else ProbeSettings(mappedURL, mappedInterval)
    }

    private fun isHttpURL(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun parseOutbound(outbound: JSONObject, protocol: String): List<AbstractBean> =
        when (protocol) {
            "http" -> listOf(parseHttp(outbound))
            "socks" -> listOf(parseSocks(outbound))
            "shadowsocks" -> listOf(parseShadowsocks(outbound))
            "vmess" -> listOf(parseVMess(outbound))
            "vless" -> listOf(parseVLESS(outbound))
            "trojan" -> listOf(parseTrojan(outbound))
            "hysteria" -> listOf(parseHysteria(outbound))
            "wireguard" -> parseWireGuard(outbound)
            else -> emptyList()
        }

    private fun parseHttp(outbound: JSONObject): HttpBean {
        val settings = outbound.requiredObject("settings")
        val endpoint = settings.endpoint("servers")
        val user = endpoint.optJSONArray("users")?.optJSONObject(0)
        return HttpBean().apply {
            serverAddress = endpoint.requiredString("address")
            serverPort = endpoint.requiredPort()
            username = user?.optString("user").orEmpty().ifBlank { settings.optString("user") }
            password = user?.optString("pass").orEmpty().ifBlank { settings.optString("pass") }
            applyStreamSettings(this, outbound)
            applyCommonOptions(this, outbound)
        }
    }

    private fun parseSocks(outbound: JSONObject): SOCKSBean {
        val settings = outbound.requiredObject("settings")
        val endpoint = settings.endpoint("servers")
        val user = endpoint.optJSONArray("users")?.optJSONObject(0)
        return SOCKSBean().apply {
            protocol = SOCKSBean.PROTOCOL_SOCKS5
            serverAddress = endpoint.requiredString("address")
            serverPort = endpoint.requiredPort()
            username = user?.optString("user").orEmpty().ifBlank { settings.optString("user") }
            password = user?.optString("pass").orEmpty().ifBlank { settings.optString("pass") }
            applyCommonOptions(this, outbound)
        }
    }

    private fun parseShadowsocks(outbound: JSONObject): ShadowsocksBean {
        val settings = outbound.requiredObject("settings")
        val endpoint = settings.endpoint("servers")
        return ShadowsocksBean().apply {
            serverAddress = endpoint.requiredString("address")
            serverPort = endpoint.requiredPort()
            method = endpoint.optString("method").ifBlank { settings.optString("method") }
            password = endpoint.optString("password").ifBlank { settings.optString("password") }
            require(method.isNotBlank()) { "missing Shadowsocks method" }
            require(password.isNotBlank()) { "missing Shadowsocks password" }
            if (endpoint.optBoolean("uot") || settings.optBoolean("uot")) sUoT = true
            applyMux(this, outbound.optJSONObject("mux"))
            applyCommonOptions(this, outbound)
        }
    }

    private fun parseVMess(outbound: JSONObject): VMessBean {
        val settings = outbound.requiredObject("settings")
        val endpoint = settings.endpoint("vnext")
        val user = endpoint.optJSONArray("users")?.requiredObject(0) ?: settings
        return VMessBean().apply {
            serverAddress = endpoint.requiredString("address")
            serverPort = endpoint.requiredPort()
            uuid = user.requiredString("id")
            alterId = user.optInt("alterId")
            encryption = user.optString("security").ifBlank { "auto" }
            applyStreamSettings(this, outbound)
            applyCommonOptions(this, outbound)
        }
    }

    private fun parseVLESS(outbound: JSONObject): VMessBean {
        val settings = outbound.requiredObject("settings")
        val endpoint = settings.endpoint("vnext")
        val user = endpoint.optJSONArray("users")?.requiredObject(0) ?: settings
        return VMessBean().apply {
            alterId = -1
            packetEncoding = StandardV2RayBean.PACKET_ENCODING_NOT_SPECIFIED
            serverAddress = endpoint.requiredString("address")
            serverPort = endpoint.requiredPort()
            uuid = user.requiredString("id")
            encryption = user.optString("flow").removeSuffix("-udp443")
            vlessEncryption = user.optString("encryption").ifBlank { "none" }
            applyStreamSettings(this, outbound)
            applyCommonOptions(this, outbound)
        }
    }

    private fun parseTrojan(outbound: JSONObject): TrojanBean {
        val settings = outbound.requiredObject("settings")
        val endpoint = settings.endpoint("servers")
        return TrojanBean().apply {
            serverAddress = endpoint.requiredString("address")
            serverPort = endpoint.requiredPort()
            password = endpoint.optString("password").ifBlank { settings.optString("password") }
            require(password.isNotBlank()) { "missing Trojan password" }
            applyStreamSettings(this, outbound)
            applyCommonOptions(this, outbound)
        }
    }

    private fun parseHysteria(outbound: JSONObject): HysteriaBean {
        val settings = outbound.requiredObject("settings")
        val stream = outbound.optJSONObject("streamSettings")
        val hysteria = stream?.optJSONObject("hysteriaSettings")
        val tls = stream?.optJSONObject("tlsSettings")
        val port = settings.requiredPort()
        return HysteriaBean().apply {
            protocolVersion = 2
            serverAddress = settings.requiredString("address")
            serverPort = port
            serverPorts = port.toString()
            authPayload = hysteria?.optString("auth").orEmpty()
            sni = stream?.serverName().orEmpty()
            allowInsecure = tls?.optBoolean("allowInsecure") == true
            tlsCurvePreferences = normalizeCurvePreferences(tls).joinToString("\n")
            tlsXrayCertificateSha256 = parseXrayCertificatePins(tls).joinToString("\n")
            applyCommonOptions(this, outbound)
        }
    }

    private fun parseWireGuard(outbound: JSONObject): List<WireGuardBean> {
        val settings = outbound.requiredObject("settings")
        val peers = settings.optJSONArray("peers")?.objectsOrNull().orEmpty()
        require(peers.isNotEmpty()) { "missing WireGuard peers" }
        val localAddress =
            settings.optJSONArray("address")?.strings().orEmpty().joinToString("\n").ifBlank {
                settings.optString("address")
            }
        val privateKey = settings.requiredString("secretKey")
        val mtu = settings.optInt("mtu", 1420)
        val reserved = settings.optJSONArray("reserved")?.strings()?.joinToString("\n").orEmpty()
        return peers.map { peer ->
            val endpoint = peer.requiredString("endpoint").parseEndpoint()
            WireGuardBean().apply {
                serverAddress = endpoint.first
                serverPort = endpoint.second
                this.localAddress = localAddress
                this.privateKey = privateKey
                this.mtu = mtu
                peerPublicKey = peer.requiredString("publicKey")
                peerPreSharedKey = peer.optString("preSharedKey")
                peerPersistentKeepalive = peer.optInt("keepAlive")
                this.reserved = reserved
                applyCommonOptions(this, outbound)
            }
        }
    }

    private fun applyStreamSettings(bean: StandardV2RayBean, outbound: JSONObject) {
        val stream = outbound.optJSONObject("streamSettings")
        bean.type = stream?.transportType().orEmpty().ifBlank { "tcp" }
        bean.host = ""
        bean.path = ""
        bean.headerType = "none"
        when (bean.type) {
            "ws" -> stream?.optJSONObject("wsSettings")?.let {
                bean.path = it.optString("path")
                bean.host = it.optJSONObject("headers")?.optString("Host").orEmpty()
                bean.wsMaxEarlyData = it.optInt("maxEarlyData")
                bean.earlyDataHeaderName = it.optString("earlyDataHeaderName")
            }
            "httpupgrade" -> stream?.optJSONObject("httpupgradeSettings")?.let {
                bean.host = it.optString("host")
                bean.path = it.optString("path")
            }
            "grpc" -> stream?.optJSONObject("grpcSettings")?.let {
                bean.path = it.optString("serviceName")
                it.optString("authority").takeIf(String::isNotBlank)?.let { authority ->
                    mergeCustom(bean, JSONObject().put("transport", JSONObject().put("authority", authority)))
                }
            }
            "xhttp" -> (stream?.optJSONObject("xhttpSettings")
                ?: stream?.optJSONObject("splithttpSettings"))?.let {
                bean.host = it.optString("host")
                bean.path = it.optString("path")
                bean.xhttpMode = it.optString("mode")
                it.opt("extra")
                    ?.takeUnless { extra -> extra == JSONObject.NULL }
                    ?.let { extra -> bean.xhttpExtra = XhttpExtraConverter.xrayToSingBox(extra.toString()) }
            }
            "kcp" -> stream?.optJSONObject("kcpSettings")?.let {
                bean.mKcpSeed = it.optString("seed")
                bean.kcpMtu = it.optInt("mtu")
                bean.kcpTti = it.optInt("tti")
                bean.headerType = it.optJSONObject("header")?.optString("type").orEmpty().ifBlank { "none" }
            }
            "http" -> {
                val http = stream?.optJSONObject("httpSettings")
                if (http != null) {
                    bean.path = http.optString("path")
                    bean.host = http.optJSONArray("host")?.strings()?.joinToString(",").orEmpty()
                } else {
                    stream
                        ?.optJSONObject("tcpSettings")
                        ?.optJSONObject("header")
                        ?.optJSONObject("request")
                        ?.let { request ->
                            bean.path = request.optJSONArray("path")?.optString(0).orEmpty()
                            bean.host =
                                request.optJSONObject("headers")?.optJSONArray("Host")?.optString(0).orEmpty()
                            bean.headerType = "http"
                        }
                }
            }
        }
        applySecurity(bean, stream)
        applyMux(bean, outbound.optJSONObject("mux"))
    }

    private fun applySecurity(bean: StandardV2RayBean, stream: JSONObject?) {
        if (stream == null) return
        when (stream.optString("security").lowercase(Locale.ROOT)) {
            "tls" -> {
                val tls = stream.optJSONObject("tlsSettings")
                bean.security = "tls"
                bean.sni = tls?.optString("serverName").orEmpty()
                bean.alpn = tls?.optJSONArray("alpn")?.strings()?.joinToString("\n").orEmpty()
                bean.allowInsecure = tls?.optBoolean("allowInsecure") == true
                bean.utlsFingerprint =
                    tls?.optString("fingerprint").orEmpty().takeUnless { it.equals("unsafe", ignoreCase = true) }.orEmpty()
                bean.tlsCurvePreferences = normalizeCurvePreferences(tls).joinToString("\n")
                bean.tlsXrayCertificateSha256 = parseXrayCertificatePins(tls).joinToString("\n")
                applyECH(bean, tls?.optString("echConfigList").orEmpty())
            }
            "reality" -> {
                val reality = stream.optJSONObject("realitySettings")
                bean.security = "reality"
                bean.sni = reality?.optString("serverName").orEmpty()
                bean.utlsFingerprint = reality?.optString("fingerprint").orEmpty()
                bean.realityPubKey = reality?.optString("publicKey").orEmpty()
                bean.realityShortId = reality?.optString("shortId").orEmpty()
            }
        }
    }

    private fun applyMux(bean: AbstractBean, mux: JSONObject?) {
        if (mux == null || !mux.optBoolean("enabled", true)) return
        when (bean) {
            is StandardV2RayBean -> {
                bean.enableMux = true
                mux.optInt("concurrency").takeIf { it != 0 }?.let { bean.muxConcurrency = it }
            }
            is ShadowsocksBean -> {
                bean.enableMux = true
                mux.optInt("concurrency").takeIf { it != 0 }?.let { bean.muxConcurrency = it }
            }
        }
    }

    private fun applyCommonOptions(bean: AbstractBean, outbound: JSONObject) {
        val custom = JSONObject()
        outbound.optString("sendThrough").takeIf(String::isNotBlank)?.let {
            custom.put(if (it.isIpAddressV6()) "inet6_bind_address" else "inet4_bind_address", it)
        }
        outbound.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.let { socket ->
            socket.opt("tcpFastOpen").takeUnless { it == null || it == JSONObject.NULL }?.let {
                bean.tcpFastOpen = when (it) {
                    is Boolean -> it
                    is Number -> it.toInt() > 0
                    else -> it.toString().toBooleanStrictOrNull() ?: false
                }
            }
            socket.opt("tcpMptcp").takeUnless { it == null || it == JSONObject.NULL }?.let {
                bean.tcpMultiPath = when (it) {
                    is Boolean -> it
                    is Number -> it.toInt() > 0
                    else -> it.toString().toBooleanStrictOrNull() ?: false
                }
            }
            copy(socket, custom, "mark", "routing_mark")
            copy(socket, custom, "interface", "bind_interface")
            socket.optInt("tcpKeepAliveIdle").takeIf { it > 0 }?.let {
                bean.tcpKeepAlive = "${it}s"
            }
            socket.optInt("tcpKeepAliveInterval").takeIf { it > 0 }?.let {
                bean.tcpKeepAliveInterval = "${it}s"
            }
            when (socket.optString("domainStrategy").lowercase(Locale.ROOT)) {
                "useipv4", "forceipv4" -> custom.put("domain_strategy", "ipv4_only")
                "useipv6", "forceipv6" -> custom.put("domain_strategy", "ipv6_only")
                "useipv4v6", "forceipv4v6" -> custom.put("domain_strategy", "prefer_ipv4")
                "useipv6v4", "forceipv6v4" -> custom.put("domain_strategy", "prefer_ipv6")
            }
        }
        if (custom.length() > 0) mergeCustom(bean, custom)
    }

    private fun mergeCustom(bean: AbstractBean, addition: JSONObject) {
        val merged =
            bean.customOutboundJson
                ?.takeIf(String::isNotBlank)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject()
        addition.keys().forEach { merged.put(it, addition.get(it)) }
        bean.customOutboundJson = merged.toString()
    }

    private fun copy(source: JSONObject, target: JSONObject, sourceKey: String, targetKey: String) {
        if (source.has(sourceKey) && !source.isNull(sourceKey)) target.put(targetKey, source.get(sourceKey))
    }

    private fun Int.toSuperscript(): String = toString().map { superscriptDigits[it.digitToInt()] }.joinToString("")

    private fun normalizeCurvePreferences(tls: JSONObject?): List<String> =
        tls
            ?.optJSONArray("curvePreferences")
            ?.strings()
            .orEmpty()
            .mapNotNull {
                when (it.lowercase(Locale.ROOT)) {
                    "curvep256", "p256" -> "P256"
                    "curvep384", "p384" -> "P384"
                    "curvep521", "p521" -> "P521"
                    "x25519" -> "X25519"
                    "x25519mlkem768" -> "X25519MLKEM768"
                    else -> null
                }
            }

    @SuppressLint("NewApi") // java.util.Base64 is provided below API 26 by core library desugaring.
    private fun parseXrayCertificatePins(tls: JSONObject?): List<String> {
        val value = tls?.optString("pinnedPeerCertSha256").orEmpty()
        if (value.isBlank()) return emptyList()
        return value.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) }.map { pin ->
            val hex = pin.replace(":", "")
            require(hex.length == 64 && hex.all(::isHexDigit)) { "invalid pinnedPeerCertSha256" }
            val bytes = ByteArray(32) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            Base64.getEncoder().encodeToString(bytes)
        }
    }

    @SuppressLint("NewApi") // java.util.Base64 is provided below API 26 by core library desugaring.
    private fun applyECH(bean: StandardV2RayBean, value: String) {
        if (value.isBlank()) return
        bean.enableECH = true
        if (value.contains("://")) {
            val separator = value.indexOf('+')
            if (separator > 0 && value.substring(separator + 1).contains("://")) {
                bean.echQueryServerName = value.substring(0, separator).trim()
            }
            bean.echConfig = ""
            return
        }
        val decoded = Base64.getDecoder().decode(value.filterNot(Char::isWhitespace))
        require(decoded.isNotEmpty()) { "empty echConfigList" }
        val encoded = Base64.getEncoder().encodeToString(decoded)
        bean.echConfig = encoded.chunked(64).joinToString(
            separator = "\n",
            prefix = "-----BEGIN ECH CONFIGS-----\n",
            postfix = "\n-----END ECH CONFIGS-----",
        )
    }

    private fun isHexDigit(value: Char): Boolean =
        value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'

    private fun JSONObject.endpoint(arrayKey: String): JSONObject =
        optJSONArray(arrayKey)?.requiredObject(0) ?: this

    private fun JSONObject.requiredObject(key: String): JSONObject =
        optJSONObject(key) ?: error("missing $key")

    private fun JSONObject.requiredString(key: String): String =
        optString(key).takeIf(String::isNotBlank) ?: error("missing $key")

    private fun JSONObject.requiredPort(): Int =
        optInt("port").takeIf { it in 1..65535 } ?: error("invalid port")

    private fun JSONArray.requiredObject(index: Int): JSONObject =
        optJSONObject(index) ?: error("missing object at $index")

    private fun JSONArray.objectsOrNull(): List<JSONObject>? =
        buildList {
            for (index in 0 until length()) add(optJSONObject(index) ?: return null)
        }

    private fun JSONArray.strings(): List<String> =
        buildList {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }

    private fun JSONObject.transportType(): String =
        when (optString("network").lowercase(Locale.ROOT)) {
            "", "raw", "tcp" -> {
                if (optJSONObject("tcpSettings")?.optJSONObject("header")?.optString("type") == "http") {
                    "http"
                } else {
                    "tcp"
                }
            }
            "splithttp" -> "xhttp"
            "mkcp" -> "kcp"
            "h2" -> "http"
            else -> optString("network").lowercase(Locale.ROOT)
        }

    private fun JSONObject.serverName(): String =
        when (optString("security").lowercase(Locale.ROOT)) {
            "tls" -> optJSONObject("tlsSettings")?.optString("serverName").orEmpty()
            "reality" -> optJSONObject("realitySettings")?.optString("serverName").orEmpty()
            else -> ""
        }

    private fun String.parseEndpoint(): Pair<String, Int> {
        val value = trim()
        val separator =
            if (value.startsWith("[")) {
                value.indexOf("]:").takeIf { it >= 0 }?.plus(1) ?: -1
            } else {
                value.lastIndexOf(':')
            }
        require(separator > 0) { "invalid endpoint" }
        val host = value.substring(0, separator).removePrefix("[").removeSuffix("]")
        val port = value.substring(separator + 1).toIntOrNull()
        require(host.isNotBlank() && port != null && port in 1..65535) { "invalid endpoint" }
        return host to port
    }
}
