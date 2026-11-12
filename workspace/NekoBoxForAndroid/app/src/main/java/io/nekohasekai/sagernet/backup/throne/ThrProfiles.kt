package io.nekohasekai.sagernet.backup.throne

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.hysteria.*
import io.nekohasekai.sagernet.fmt.internal.*
import io.nekohasekai.sagernet.fmt.juicity.*
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.mieru.*
import io.nekohasekai.sagernet.fmt.openvpn.*
import io.nekohasekai.sagernet.fmt.openconnect.*
import io.nekohasekai.sagernet.fmt.naive.*
import io.nekohasekai.sagernet.fmt.shadowsocks.*
import io.nekohasekai.sagernet.fmt.snell.*
import io.nekohasekai.sagernet.fmt.socks.*
import io.nekohasekai.sagernet.fmt.ssh.*
import io.nekohasekai.sagernet.fmt.trojan_go.*
import io.nekohasekai.sagernet.fmt.trusttunnel.*
import io.nekohasekai.sagernet.fmt.tuic.*
import io.nekohasekai.sagernet.fmt.v2ray.*
import io.nekohasekai.sagernet.fmt.wireguard.*
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import moe.matsuri.nb4a.proxy.anytls.*
import moe.matsuri.nb4a.proxy.direct.DirectBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.*
import org.json.JSONArray
import org.json.JSONObject

/** Persisted Throne JSON is close to, but deliberately not identical to, sing-box's runtime JSON. */
internal object ThrProfiles {
    class Unsupported(val reason: String, val field: String? = null) : Exception()

    data class DialOptions(val tcpFastOpen: Boolean = false, val tcpMultiPath: Boolean = false, val udpFragment: String = "")

    fun convert(profile: ProxyEntity, group: ProxyGroup?, dialOptions: DialOptions? = null, omissions: MutableList<ThrOmission> = mutableListOf()): JSONObject {
        val bean = profile.requireBean()
        if (!bean.customOutboundJson.isNullOrBlank() || !bean.customConfigJson.isNullOrBlank()) {
            throw Unsupported("custom_json")
        }
        val option: SingBoxOption = when (bean) {
            is MasterDnsVPNBean -> return masterDns(bean, profile.id, omissions)
            is ConfigBean -> return rawConfig(bean)
            is DirectBean -> return JSONObject().put("type", "direct").put("tag", bean.name)
            is ShadowTLSBean -> buildSingBoxOutboundShadowTLSBean(bean)
            is StandardV2RayBean -> buildSingBoxOutboundStandardV2RayBean(bean)
            is SOCKSBean -> buildSingBoxOutboundSocksBean(bean)
            is ShadowsocksBean -> buildSingBoxOutboundShadowsocksBean(bean)
            is HysteriaBean -> buildSingBoxOutboundHysteriaBean(bean)
            is TrojanGoBean -> {
                if (!bean.canUseSingBox()) throw Unsupported("profile_options")
                buildSingBoxOutboundTrojanGoBean(bean)
            }
            is TuicBean -> {
                if (bean.protocolVersion != 5) throw Unsupported("profile_options")
                buildSingBoxOutboundTuicBean(bean)
            }
            is JuicityBean -> buildSingBoxOutboundJuicityBean(bean)
            is TrustTunnelBean -> buildSingBoxOutboundTrustTunnelBean(bean)
            is AnyTLSBean -> buildSingBoxOutboundAnyTLSBean(bean)
            is MieruBean -> buildSingBoxOutboundMieruBean(bean)
            is SnellBean -> buildSingBoxOutboundSnellBean(bean)
            is SSHBean -> buildSingBoxOutboundSSHBean(bean)
            is NaiveBean -> buildSingBoxOutboundNaiveBean(bean)
            is WireGuardBean -> buildSingBoxEndpointWireguardBean(bean)
            is AmneziaWGBean -> buildSingBoxEndpointAwgBean(bean)
            is OpenVPNBean -> {
                if (bean.expandPushedSearchDomains || bean.acceptPushedDefaultResolvers) throw Unsupported("profile_options")
                buildSingBoxEndpointOpenVPNBean(bean)
            }
            is OpenConnectBean -> {
                if (bean.expandPushedSearchDomains || bean.acceptPushedDefaultResolvers) throw Unsupported("profile_options")
                buildSingBoxEndpointOpenConnectBean(bean)
            }
            is MasqueBean -> return masque(bean, DataStore.globalAllowInsecure)
            // Endpoints with pushed DNS and external plugins require more than an outbound conversion.
            else -> throw Unsupported("profile_type")
        }
        val dial = dialOptions ?: DialOptions(DataStore.globalTcpFastOpen, DataStore.globalTcpMultiPath, DataStore.globalUdpFragment)
        option.applyConfiguredDialOptions(bean, dial.tcpFastOpen, dial.tcpMultiPath, dial.udpFragment)
        val json = JSONObject(option.asMap())
        if (bean is OpenVPNBean) {
            json.put("type", "openvpn").put("only_advertised_routes", false)
                .put("tunnel_dns", if (bean.usePushedDNS) "prefer" else "none")
        }
        if (bean is OpenConnectBean) {
            val uri = java.net.URI(json.getString("server"))
            json.put("server", uri.host).put("server_port", uri.port.takeIf { it > 0 } ?: 443)
                .put("server_path", uri.rawPath.orEmpty()).put("only_advertised_routes", false)
                .put("tunnel_dns", if (bean.usePushedDNS) "prefer" else "none")
        }
        if (bean is WireGuardBean || bean is AmneziaWGBean) json.put("type", "wireguard")
        if (bean is AmneziaWGBean) {
            val amnezia = JSONObject().put("enabled", true)
            amneziaKeys.forEach { key -> if (json.has(key)) amnezia.put(key, json.remove(key)) }
            json.put("amnezia_wg", amnezia)
        }
        json.optJSONArray("peers")?.let { peers ->
            for (i in 0 until peers.length()) {
                val peer = peers.getJSONObject(i)
                peer.remove("allowed_ips") // Throne always builds a full-tunnel peer.
                if (peer.has("preshared_key")) peer.put("pre_shared_key", peer.remove("preshared_key"))
            }
        }
        val mux = resolveMuxApplication(profile, false) { group }?.options?.takeIf { it.enabled == true }
        mux?.let { json.put("multiplex", JSONObject(it.asMap())) }
        group?.forceUTLS?.takeIf { it.isNotBlank() }?.let { fingerprint ->
            json.optJSONObject("tls")?.takeIf { it.optBoolean("enabled") }?.put(
                "utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint),
            )
        }
        json.put("tag", bean.name.orEmpty())
        return normalize(json)
    }

    internal fun masterDns(bean: MasterDnsVPNBean, id: Long, omissions: MutableList<ThrOmission>): JSONObject {
        val mapped = setOf("domains", "resolvers", "dataEncryptionMethod", "encryptionKey", "baseEncodeData",
            "uploadCompressionType", "downloadCompressionType", "resolverBalancingStrategy", "packetDuplicationCount",
            "setupPacketDuplicationCount", "minUploadMTU", "maxUploadMTU", "minDownloadMTU", "maxDownloadMTU",
            "mtuTestRetries", "mtuTestTimeout", "mtuTestParallelism", "autoRemoveLowMTUServers")
        val defaults = MasterDnsVPNBean().apply { initializeDefaultValues() }
        for (field in MasterDnsVPNBean::class.java.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers) || field.name in mapped) continue
            if (field.get(bean) != field.get(defaults)) omissions += ThrOmission("profile", id, "unsupported_setting", field.name)
        }
        fun choice(index: Int, choices: List<String>) = choices.getOrNull(index) ?: throw Unsupported("profile_options")
        return JSONObject().put("type", "masterdns").put("tag", bean.name)
            .put("domain", JSONArray(lines(bean.domains))).put("resolvers", JSONArray(lines(bean.resolvers)))
            .put("mode", "socks5").put("encryption_method", choice(bean.dataEncryptionMethod,
                listOf("none", "xor", "chacha20", "aes-128-gcm", "aes-192-gcm", "aes-256-gcm")))
            .put("encryption_key", bean.encryptionKey).put("base64_response", bean.baseEncodeData)
            .put("upload_compression", choice(bean.uploadCompressionType, listOf("off", "zstd", "lz4", "zlib")))
            .put("download_compression", choice(bean.downloadCompressionType, listOf("off", "zstd", "lz4", "zlib")))
            .put("balancing_strategy", choice(bean.resolverBalancingStrategy, listOf("round_robin", "random", "least_loss",
                "lowest_latency", "hybrid", "loss_then_latency", "least_loss_top_random", "least_loss_top_round_robin")))
            .put("packet_duplication", bean.packetDuplicationCount).put("setup_packet_duplication", bean.setupPacketDuplicationCount)
            .put("mtu", JSONObject().put("min_upload", bean.minUploadMTU).put("max_upload", bean.maxUploadMTU)
                .put("min_download", bean.minDownloadMTU).put("max_download", bean.maxDownloadMTU)
                .put("test_retries", bean.mtuTestRetries).put("test_timeout", "${bean.mtuTestTimeout}s")
                .put("test_parallelism", bean.mtuTestParallelism).put("auto_remove_low", bean.autoRemoveLowMTUServers))
    }

    internal fun normalize(json: JSONObject): JSONObject {
        val type = json.getString("type")
        if (type == "vless" && json.optJSONObject("transport")?.optString("type") == "xhttp") {
            return xrayVless(json)
        }
        if (type !in ThrProfileFields.types) throw Unsupported("profile_type")
        if (type == "socks" && json.has("udp_over_tcp")) {
            val value = json.remove("udp_over_tcp")
            json.put("uot", (value as? JSONObject)?.optBoolean("enabled") ?: value)
        }
        if (type == "wireguard" && json.has("workers")) json.put("worker_count", json.remove("workers"))
        if (type == "vless" && json.optString("encryption").let { it.isNotEmpty() && it != "none" }) {
            throw Unsupported("profile_options")
        }
        json.remove("encryption")
        json.optJSONObject("transport")?.let { transport ->
            if (transport.optString("type") !in setOf("http", "ws", "quic", "grpc", "httpupgrade")) {
                throw Unsupported("profile_options")
            }
        }
        checkFields(json, ThrProfileFields.common + ThrProfileFields.types.getValue(type))
        json.optJSONObject("tls")?.let { if (it.has("xray_certificate_sha256")) it.put("certificate_sha256", it.remove("xray_certificate_sha256")) }
        json.optJSONObject("tls")?.let { checkFields(it, if (type == "openvpn") ThrProfileFields.openVpnTls else ThrProfileFields.tls) }
        json.optJSONObject("tls")?.let { tls ->
            tls.optJSONObject("ech")?.let { checkFields(it, setOf("enabled", "config", "config_path", "query_server_name", "resolver"), "tls.ech") }
            tls.optJSONObject("utls")?.let { checkFields(it, setOf("enabled", "fingerprint"), "tls.utls") }
            tls.optJSONObject("reality")?.let { checkFields(it, setOf("enabled", "public_key", "short_id"), "tls.reality") }
            tls.optJSONObject("tls_tricks")?.let { checkFields(it, setOf("mixedcase_sni"), "tls.tls_tricks") }
            if (tls.has("xray_certificate_sha256")) tls.put("certificate_sha256", tls.remove("xray_certificate_sha256"))
        }
        json.optJSONObject("transport")?.let { checkFields(it, ThrProfileFields.transport, "transport") }
        json.optJSONObject("multiplex")?.let {
            checkFields(it, ThrProfileFields.multiplex, "multiplex")
            it.optJSONObject("brutal")?.let { brutal -> checkFields(brutal, setOf("enabled", "up_mbps", "down_mbps"), "multiplex.brutal") }
            if (it.optString("protocol") == "mux.cool") throw Unsupported("profile_options")
        }
        if (type == "wireguard") {
            json.optJSONObject("amnezia_wg")?.let { checkFields(it, amneziaKeys + "enabled", "amnezia_wg") }
            json.optJSONArray("peers")?.let { peers ->
                require(peers.length() <= 1)
                for (index in 0 until peers.length()) checkFields(peers.getJSONObject(index),
                    setOf("address", "port", "public_key", "pre_shared_key", "persistent_keepalive_interval", "reserved"), "peers")
            }
        }
        return json
    }

    /** XHTTP is an Xray VLESS profile in Throne, rather than a sing-box VLESS transport. */
    private fun xrayVless(json: JSONObject): JSONObject {
        checkFields(json, setOf("type", "tag", "server", "server_port", "uuid", "flow", "packet_encoding",
            "encryption", "tls", "transport"))
        if (json.optString("packet_encoding") !in setOf("", "xudp")) throw Unsupported("profile_options")
        val tls = json.optJSONObject("tls") ?: JSONObject()
        checkFields(tls, setOf("enabled", "server_name", "alpn", "utls", "reality"))
        val transport = JSONObject(json.getJSONObject("transport").toString())
        if (transport.has("congestion_controller") || transport.has("cwnd")) throw Unsupported("profile_options")
        val xhttp = JSONObject()
        for (key in listOf("host", "path", "mode")) if (transport.has(key)) xhttp.put(key, transport.remove(key))
        transport.remove("type")
        if (transport.length() > 0) xhttp.put("extra", JSONObject(XhttpExtraConverter.singBoxToXray(transport.toString())))
        val stream = JSONObject().put("network", "xhttp").put("security", "none").put("xhttpSettings", xhttp)
        if (tls.optBoolean("enabled")) {
            val security = JSONObject().put("serverName", tls.optString("server_name"))
            tls.optJSONArray("alpn")?.let { security.put("alpn", it) }
            tls.optJSONObject("utls")?.takeIf { it.optBoolean("enabled") }?.let {
                security.put("fingerprint", it.optString("fingerprint"))
            }
            val reality = tls.optJSONObject("reality")
            if (reality?.optBoolean("enabled") == true) {
                checkFields(reality, setOf("enabled", "public_key", "short_id"))
                security.put("publicKey", reality.optString("public_key")).put("shortId", reality.optString("short_id"))
                stream.put("security", "reality").put("realitySettings", security)
            } else stream.put("security", "tls").put("tlsSettings", security)
        }
        return JSONObject().put("protocol", "vless").put("tag", json.optString("tag"))
            .put("settings", JSONObject().put("address", json.getString("server"))
                .put("port", json.getInt("server_port")).put("id", json.getString("uuid"))
                .put("encryption", json.optString("encryption").ifBlank { "none" })
                .put("flow", json.optString("flow")))
            .put("streamSettings", stream)
    }

    private fun rawConfig(bean: ConfigBean): JSONObject {
        val config = JSONObject(bean.config)
        if (bean.type == 1) return normalize(config).put("tag", bean.name)
        // A custom full config runs verbatim on both apps. Refuse source-only core types and clash modes.
        fun check(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (value.has("clash_mode")) throw Unsupported("profile_options")
                    value.keys().forEach { check(value.get(it)) }
                }
                is JSONArray -> for (index in 0 until value.length()) check(value.get(index))
            }
        }
        check(config)
        for (section in listOf("outbounds", "endpoints")) {
            val entries = config.optJSONArray(section) ?: continue
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                if (entry.optString("type") !in ThrProfileFields.types.keys + setOf("selector", "urltest", "block", "dns", "masque", "openvpn-client")) {
                    throw Unsupported("profile_type")
                }
                if (entry.optJSONObject("transport")?.optString("type") == "xhttp") throw Unsupported("profile_options")
            }
        }
        return JSONObject().put("type", "custom").put("name", bean.name)
            .put("subtype", "fullconfig").put("config", bean.config)
    }

    private fun checkFields(json: JSONObject, allowed: Set<String>, path: String = "") {
        for (key in json.keys().asSequence().toList()) {
            if (key in allowed) continue
            val value = json.get(key)
            // Empty defaults emitted by the source generator carry no extra behavior.
            if (value == false || value == "" || (value is Number && value.toDouble() == 0.0) ||
                (value is JSONArray && value.length() == 0) || (value is JSONObject && value.length() == 0)) {
                json.remove(key)
            } else throw Unsupported("profile_options", listOf(path, key).filter(String::isNotEmpty).joinToString("."))
        }
    }

    internal fun masque(bean: MasqueBean, allowInsecure: Boolean): JSONObject {
        if (bean.profileRecreate == true || bean.configPrivateKey.isNullOrBlank() ||
            bean.configEndpointPubKey.isNullOrBlank() || (bean.configIPv4.isNullOrBlank() && bean.configIPv6.isNullOrBlank())) {
            throw Unsupported("masque_provisioning")
        }
        // Provisioning/session knobs are not fields of Throne's peer-based MASQUE.
        if (!bean.allowedIPs.isNullOrBlank() || bean.udpTimeout !in listOf("", "5m0s") ||
            bean.reconnectDelay !in listOf("", "5s")) throw Unsupported("profile_options")
        val endpoint = if (bean.useHTTP2) {
            if (bean.useIPv6) bean.configEndpointH2V6.ifBlank { bean.configEndpointV6 }
            else bean.configEndpointH2V4.ifBlank { "162.159.198.2" } // Source applyMASQUEConfigDefaults.
        } else {
            if (bean.useIPv6) bean.configEndpointV6 else bean.configEndpointV4
        }
        val authority = if (endpoint.count { it == ':' } > 1 && !endpoint.startsWith("[")) "[$endpoint]" else endpoint
        val uri = java.net.URI("https://$authority")
        if (uri.host.isNullOrBlank()) throw Unsupported("profile_options")
        // NekoBox stores the endpoint key as PEM SubjectPublicKeyInfo; Throne's peer_public_key is Base64 DER.
        val peerKey = bean.configEndpointPubKey.trim().removePrefix("-----BEGIN PUBLIC KEY-----")
            .removeSuffix("-----END PUBLIC KEY-----").filterNot(Char::isWhitespace)
        val peerDer = com.google.common.io.BaseEncoding.base64().decode(peerKey)
        java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(peerDer))
        val tls = JSONObject().put("enabled", true).put("server_name", bean.tlsSNI)
            .put("insecure", bean.tlsInsecure || allowInsecure)
            .put("fragment", bean.tlsFragment).put("record_fragment", bean.tlsRecordFragment)
        if (!bean.tlsFragmentFallbackDelay.isNullOrBlank()) tls.put("fragment_fallback_delay", bean.tlsFragmentFallbackDelay)
        if (!bean.tlsCipherSuites.isNullOrBlank()) tls.put("cipher_suites", JSONArray(lines(bean.tlsCipherSuites)))
        if (!bean.tlsCurvePreferences.isNullOrBlank()) tls.put("curve_preferences", JSONArray(lines(bean.tlsCurvePreferences)))
        if (bean.tlsKernelTx || bean.tlsKernelRx) throw Unsupported("profile_options")
        return JSONObject().put("type", "masque").put("tag", bean.name)
            .put("server", uri.host.removeSurrounding("[", "]")).put("server_port", uri.port.takeIf { it > 0 } ?: 443)
            .put("private_key", bean.configPrivateKey).put("peer_public_key", peerKey)
            .put("address", JSONArray(listOf(bean.configIPv4, bean.configIPv6).filter { !it.isNullOrBlank() }.map {
                if ('/' in it) it else it + if (':' in it) "/128" else "/32"
            }))
            .put("http_version", if (bean.useHTTP2) 2 else 3).put("disable_version_fallback", true).put("tls", tls)
            .apply {
                if (!bean.udpKeepalivePeriod.isNullOrBlank()) put("keep_alive_period", bean.udpKeepalivePeriod)
                if (bean.udpInitialPacketSize > 0) put("initial_packet_size", bean.udpInitialPacketSize)
            }
    }

    private val amneziaKeys = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4",
        "i1", "i2", "i3", "i4", "i5", "header_protection_key", "content_padding_addition", "rekey_after_time",
        "rekey_timeout", "reject_after_time", "keepalive_timeout", "max_handshake_attempts", "random_trailers", "disable_cookies")
}

internal fun lines(value: String): List<String> = value.split('\n', ',').map(String::trim).filter(String::isNotEmpty)
