package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
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
                val candidates = outbounds.filter { it.optString("protocol").lowercase(Locale.ROOT) in supportedProtocols }
                val remarks = config.optString("remarks").takeIf(String::isNotBlank)
                for ((index, outbound) in candidates.withIndex()) {
                    val protocol = outbound.optString("protocol").lowercase(Locale.ROOT)
                    val baseName = proxyName(outbound, remarks, index, candidates.size, protocol)
                    val beans =
                        runCatching { parseOutbound(outbound, protocol) }
                            .onFailure {
                                Logs.w("Skipping invalid Xray $protocol outbound ${outbound.optString("tag")}")
                            }.getOrNull()
                            .orEmpty()
                    beans.forEachIndexed { beanIndex, bean ->
                        bean.name =
                            if (beans.size == 1) {
                                baseName
                            } else {
                                "$baseName #${beanIndex + 1}"
                            }
                        bean.initializeDefaultValues()
                        add(bean)
                    }
                }
            }
        }
    }

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
            encryption = user.optString("flow")
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
        val port = settings.requiredPort()
        return HysteriaBean().apply {
            protocolVersion = 2
            serverAddress = settings.requiredString("address")
            serverPort = port
            serverPorts = port.toString()
            authPayload = hysteria?.optString("auth").orEmpty()
            sni = stream?.serverName().orEmpty()
            allowInsecure = stream?.optJSONObject("tlsSettings")?.optBoolean("allowInsecure") == true
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
            "http" -> stream
                ?.optJSONObject("tcpSettings")
                ?.optJSONObject("header")
                ?.optJSONObject("request")
                ?.let { request ->
                    bean.path = request.optJSONArray("path")?.optString(0).orEmpty()
                    bean.host = request.optJSONObject("headers")?.optJSONArray("Host")?.optString(0).orEmpty()
                    bean.headerType = "http"
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
                bean.utlsFingerprint = tls?.optString("fingerprint").orEmpty()
                bean.tlsCurvePreferences = tls?.optJSONArray("curvePreferences")?.strings()?.joinToString("\n").orEmpty()
                bean.tlsCertificatePublicKeySha256 = tls?.optString("pinnedPeerCertSha256").orEmpty()
                bean.enableECH = !tls?.optString("echConfigList").isNullOrBlank()
                bean.echConfig = tls?.optString("echConfigList").orEmpty()
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
            copy(socket, custom, "tcpFastOpen", "tcp_fast_open")
            copy(socket, custom, "tcpMptcp", "tcp_multi_path")
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

    private fun proxyName(
        outbound: JSONObject,
        remarks: String?,
        index: Int,
        total: Int,
        protocol: String,
    ): String {
        val tag = outbound.optString("tag").takeIf(String::isNotBlank)
        return when {
            !remarks.isNullOrBlank() && total == 1 -> remarks
            !remarks.isNullOrBlank() && tag != null -> "$remarks [$tag]"
            !remarks.isNullOrBlank() -> "$remarks #${index + 1}"
            tag != null -> tag
            else -> "Xray ${protocol.replaceFirstChar { it.uppercase() }}"
        }
    }

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
