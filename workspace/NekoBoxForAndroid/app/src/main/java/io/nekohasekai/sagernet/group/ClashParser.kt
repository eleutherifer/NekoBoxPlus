package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseClashHysteria
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.parseClashSnell
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.parseClashSSH
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.parseClashTuic
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.applyClashXhttpOptions
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.isIpAddressV6
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.TypeDescription
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.util.Locale

/**
 * Imports the remote-proxy subset accepted by mihomo's adapter.ParseProxy.
 */
internal object ClashParser {
    private val supportedTypes =
        setOf(
            "socks5",
            "http",
            "ss",
            "ssr",
            "vmess",
            "vless",
            "trojan",
            "snell",
            "hysteria",
            "hysteria2",
            "wireguard",
            "tuic",
            "ssh",
            "mieru",
            "anytls",
            "masque",
            "trusttunnel",
            "tailscale",
            "openvpn",
        )

    fun parse(text: String): List<AbstractBean>? {
        val yaml = loadYaml(text) ?: return null
        if (!yaml.containsKey("proxies")) return null
        val rawProxies = yaml["proxies"] as? List<*> ?: return emptyList()
        val globalFingerprint = yaml.value("global-client-fingerprint")
        return buildList {
            rawProxies.forEach { raw ->
                val proxy = raw.stringMap() ?: return@forEach
                val type = proxy.value("type").lowercase(Locale.ROOT)
                if (type !in supportedTypes) return@forEach
                runCatching { parseProxy(type, proxy, globalFingerprint) }
                    .onFailure { Logs.w("Skipping invalid Clash $type proxy ${proxy.value("name")}") }
                    .getOrNull()
                    ?.also {
                        it.initializeDefaultValues()
                        add(it)
                    }
            }
        }
    }

    private fun parseProxy(
        type: String,
        proxy: Map<String, Any?>,
        globalFingerprint: String,
    ): AbstractBean {
        val bean =
            when (type) {
                "socks5" -> parseSocks(proxy)
                "http" -> parseHttp(proxy, globalFingerprint)
                "ss" -> parseShadowsocks(proxy)
                "ssr" -> parseShadowsocksR(proxy)
                "vmess", "vless", "trojan" -> parseV2Ray(type, proxy, globalFingerprint)
                "snell" -> parseClashSnell(proxy)
                "hysteria" -> parseClashHysteria(proxy, 1)
                "hysteria2" -> parseClashHysteria(proxy, 2)
                "wireguard" -> parseWireGuard(proxy)
                "tuic" -> parseClashTuic(proxy)
                "ssh" -> parseClashSSH(proxy)
                "mieru" -> parseMieru(proxy)
                "anytls" -> parseAnyTLS(proxy, globalFingerprint)
                "masque" -> parseMasque(proxy)
                "trusttunnel" -> parseTrustTunnel(proxy, globalFingerprint)
                "tailscale" -> parseTailscale(proxy)
                "openvpn" -> parseOpenVPN(proxy)
                else -> error("unsupported type")
            }
        bean.name = proxy.requiredValue("name")
        applyCommonOptions(bean, proxy)
        return bean
    }

    private fun parseSocks(proxy: Map<String, Any?>) =
        SOCKSBean().apply {
            protocol = SOCKSBean.PROTOCOL_SOCKS5
            serverAddress = proxy.requiredValue("server")
            serverPort = proxy.requiredPort()
            username = proxy.valueOrNull("username")
            password = proxy.valueOrNull("password")
        }

    private fun parseHttp(proxy: Map<String, Any?>, globalFingerprint: String) =
        HttpBean().apply {
            serverAddress = proxy.requiredValue("server")
            serverPort = proxy.requiredPort()
            username = proxy.valueOrNull("username")
            password = proxy.valueOrNull("password")
            security = if (proxy.boolean("tls")) "tls" else "none"
            applyTLS(this, proxy, globalFingerprint)
            proxy.map("headers")?.takeIf(Map<*, *>::isNotEmpty)?.let {
                mergeCustom(this, JSONObject().put("headers", JSONObject(it)))
            }
        }

    private fun parseShadowsocks(proxy: Map<String, Any?>) =
        ShadowsocksBean().apply {
            serverAddress = proxy.requiredValue("server")
            serverPort = proxy.requiredPort()
            method = clashCipher(proxy.requiredValue("cipher"))
            password = proxy.requiredValue("password")
            val pluginName = proxy.value("plugin")
            val options = proxy.map("plugin-opts")
            plugin =
                when (pluginName) {
                    "obfs", "simple-obfs" -> {
                        listOf(
                            "obfs-local",
                            "obfs=${options?.value("mode").orEmpty()}",
                            "obfs-host=${options?.value("host").orEmpty()}",
                        ).joinToString(";")
                    }
                    "v2ray-plugin" -> {
                        buildList {
                            add("v2ray-plugin")
                            options?.value("mode")?.takeIf(String::isNotBlank)?.let { add("mode=$it") }
                            if (options?.boolean("tls") == true) add("tls")
                            options?.value("host")?.takeIf(String::isNotBlank)?.let { add("host=$it") }
                            options?.value("path")?.takeIf(String::isNotBlank)?.let { add("path=$it") }
                            if (options?.boolean("mux") == true) add("mux=8")
                        }.joinToString(";")
                    }
                    else -> pluginName.takeIf(String::isNotBlank).orEmpty()
                }
            if (proxy.boolean("udp-over-tcp") || proxy.boolean("uot")) sUoT = true
            applyMux(this, proxy.map("smux"))
        }

    private fun parseShadowsocksR(proxy: Map<String, Any?>) =
        ShadowsocksRBean().apply {
            serverAddress = proxy.requiredValue("server")
            serverPort = proxy.requiredPort()
            method = clashCipher(proxy.requiredValue("cipher"))
            password = proxy.requiredValue("password")
            obfs = proxy.requiredValue("obfs")
            protocol = proxy.requiredValue("protocol")
            obfsParam = proxy.value("obfs-param")
            protocolParam = proxy.value("protocol-param")
        }

    private fun parseV2Ray(
        type: String,
        proxy: Map<String, Any?>,
        globalFingerprint: String,
    ): StandardV2RayBean {
        val bean =
            when (type) {
                "vmess" -> VMessBean().apply {
                    uuid = proxy.requiredValue("uuid")
                    alterId = proxy.int("alterId", proxy.int("alter-id"))
                    encryption = proxy.value("cipher").ifBlank { "auto" }
                }
                "vless" -> VMessBean().apply {
                    alterId = -1
                    packetEncoding = StandardV2RayBean.PACKET_ENCODING_NOT_SPECIFIED
                    uuid = proxy.requiredValue("uuid")
                    encryption = proxy.value("flow").removeSuffix("-udp443")
                    vlessEncryption = proxy.value("encryption").ifBlank { "none" }
                }
                else -> TrojanBean().apply {
                    password = proxy.requiredValue("password")
                    security = "tls"
                }
            }
        bean.serverAddress = proxy.requiredValue("server")
        bean.serverPort = proxy.requiredPort()
        if (bean is VMessBean) {
            proxy.valueOrNull("packet-encoding")?.let {
                bean.packetEncoding =
                    when (it.lowercase(Locale.ROOT)) {
                        "packet", "packetaddr" -> StandardV2RayBean.PACKET_ENCODING_PACKETADDR
                        "xudp" -> StandardV2RayBean.PACKET_ENCODING_XUDP
                        "none", "false", "off", "disabled", "0" -> StandardV2RayBean.PACKET_ENCODING_NONE
                        else -> bean.packetEncoding
                    }
            }
            if (!proxy.containsNormalized("packet-encoding") && proxy.containsNormalized("xudp")) {
                bean.packetEncoding =
                    if (proxy.boolean("xudp")) {
                        StandardV2RayBean.PACKET_ENCODING_XUDP
                    } else {
                        StandardV2RayBean.PACKET_ENCODING_NONE
                    }
            }
        }
        applyV2RayTransport(bean, proxy)
        if (proxy.boolean("tls") || bean is TrojanBean || proxy.map("reality-opts") != null) {
            bean.security = "tls"
        }
        applyTLS(bean, proxy, globalFingerprint)
        applyMux(bean, proxy.map("smux"))
        return bean
    }

    private fun applyV2RayTransport(bean: StandardV2RayBean, proxy: Map<String, Any?>) {
        bean.type =
            when (proxy.value("network").lowercase(Locale.ROOT)) {
                "", "tcp" -> "tcp"
                "h2" -> "http"
                "ws", "grpc", "httpupgrade", "xhttp" -> proxy.value("network").lowercase(Locale.ROOT)
                else -> "tcp"
            }
        proxy.map("ws-opts")?.let { options ->
            bean.path = options.value("path")
            bean.host = options.map("headers")?.entries
                ?.firstOrNull { normalizeKey(it.key) == "host" }
                ?.value
                ?.toString()
                .orEmpty()
            bean.wsMaxEarlyData = options.int("max-early-data")
            bean.earlyDataHeaderName = options.value("early-data-header-name")
            if (options.boolean("v2ray-http-upgrade")) bean.type = "httpupgrade"
        }
        proxy.map("h2-opts")?.let { options ->
            bean.host = options.lines("host")
            bean.path = options.value("path")
        }
        proxy.map("http-opts")?.let { options ->
            bean.path = options.lines("path")
            bean.host = options.map("headers")?.entries
                ?.firstOrNull { normalizeKey(it.key) == "host" }
                ?.value
                ?.let(::lines)
                .orEmpty()
        }
        proxy.map("grpc-opts")?.let { bean.path = it.value("grpc-service-name") }
        if (bean.type == "xhttp" && bean is VMessBean && bean.isVLESS) {
            proxy.map("xhttp-opts")?.let { applyClashXhttpOptions(bean, it) }
        }
    }

    private fun applyTLS(
        bean: StandardV2RayBean,
        proxy: Map<String, Any?>,
        globalFingerprint: String,
    ) {
        bean.sni = proxy.value("servername").ifBlank { proxy.value("sni") }
        bean.alpn = proxy.lines("alpn")
        bean.allowInsecure = proxy.boolean("skip-cert-verify")
        bean.certificates = proxy.value("certificate")
        bean.tlsClientCertificate = proxy.value("certificate")
        bean.tlsClientKey = proxy.value("private-key")
        bean.utlsFingerprint =
            proxy.value("client-fingerprint").ifBlank {
                if (proxy.map("reality-opts") != null) globalFingerprint.ifBlank { "chrome" } else ""
            }
        proxy.map("reality-opts")?.let {
            bean.realityPubKey = it.value("public-key")
            bean.realityShortId = it.value("short-id")
            if (bean.realityPubKey.isNotBlank()) bean.security = "reality"
        }
        proxy.map("ech-opts")?.let {
            bean.enableECH = it.boolean("enable")
            bean.echConfig = it.lines("config")
        }
    }

    private fun parseHysteria(proxy: Map<String, Any?>, version: Int) =
        HysteriaBean().apply {
            protocolVersion = version
            serverAddress = proxy.requiredValue("server")
            serverPorts = proxy.value("ports").ifBlank { proxy.requiredValue("port") }
            serverPort = serverPorts.substringBefore(',').substringBefore('-').toIntOrNull() ?: 443
            authPayload =
                if (version == 1) {
                    proxy.value("auth-str").ifBlank { proxy.value("auth") }
                } else {
                    proxy.value("password")
                }
            if (version == 1 && proxy.value("auth-str").isNotBlank()) {
                authPayloadType = HysteriaBean.TYPE_STRING
            }
            obfuscation =
                if (version == 1) proxy.value("obfs") else proxy.value("obfs-password")
            sni = proxy.value("sni")
            alpn = proxy.lines("alpn")
            allowInsecure = proxy.boolean("skip-cert-verify")
            uploadMbps = speed(proxy["up"] ?: proxy["up-speed"])
            downloadMbps = speed(proxy["down"] ?: proxy["down-speed"])
            streamReceiveWindow = proxy.int("recv-window")
            connectionReceiveWindow = proxy.int("recv-window-conn")
            disableMtuDiscovery = proxy.boolean("disable-mtu-discovery")
            hopInterval = durationSeconds(proxy["hop-interval"], 10)
            proxy.value("certificate").takeIf(String::isNotBlank)?.let { caText = it }
        }

    private fun parseTUIC(proxy: Map<String, Any?>) =
        TuicBean().apply {
            serverAddress = proxy.value("ip").ifBlank { proxy.requiredValue("server") }
            serverPort = proxy.requiredPort()
            uuid = proxy.value("uuid")
            token = proxy.value("password").ifBlank { proxy.value("token") }
            protocolVersion = if (proxy.value("token").isNotBlank() && uuid.isBlank()) 4 else 5
            allowInsecure = proxy.boolean("skip-cert-verify")
            disableSNI = proxy.boolean("disable-sni")
            reduceRTT = proxy.boolean("reduce-rtt")
            sni = proxy.value("sni")
            if (sni.isBlank() && !serverAddress.isIpAddress()) sni = proxy.value("server")
            alpn = proxy.lines("alpn")
            congestionController = proxy.value("congestion-controller")
            udpRelayMode = proxy.value("udp-relay-mode")
            fastConnect = proxy.boolean("fast-open")
            proxy.value("certificate").takeIf(String::isNotBlank)?.let { caText = it }
            val custom = JSONObject()
            copy(proxy, custom, "heartbeat-interval", "heartbeat")
            copy(proxy, custom, "udp-over-stream", "udp_over_stream")
            if (custom.length() > 0) customJSON = custom.toString()
        }

    private fun parseSSH(proxy: Map<String, Any?>) =
        SSHBean().apply {
            serverAddress = proxy.requiredValue("server")
            serverPort = proxy.requiredPort()
            username = proxy.requiredValue("username")
            password = proxy.value("password")
            privateKey = proxy.value("private-key")
            privateKeyPassphrase = proxy.value("private-key-passphrase")
            authType =
                when {
                    privateKey.isNotBlank() -> SSHBean.AUTH_TYPE_PRIVATE_KEY
                    password.isNotBlank() -> SSHBean.AUTH_TYPE_PASSWORD
                    else -> SSHBean.AUTH_TYPE_NONE
                }
            publicKey = proxy.lines("host-key")
            hostKeyAlgorithms = proxy.lines("host-key-algorithms")
        }

    private fun parseMieru(proxy: Map<String, Any?>) =
        MieruBean().apply {
            serverAddress = proxy.requiredValue("server")
            serverPort = proxy.int("port")
            portRange = proxy.value("port-range").ifBlank { proxy.lines("server-ports") }
            require(serverPort > 0 || portRange.isNotBlank()) { "missing Mieru port" }
            username = proxy.requiredValue("username")
            password = proxy.requiredValue("password")
            protocol =
                if (proxy.value("transport").equals("UDP", true)) {
                    MieruBean.PROTOCOL_UDP
                } else {
                    MieruBean.PROTOCOL_TCP
                }
            multiplexingLevel =
                when (proxy.value("multiplexing")) {
                    "MULTIPLEXING_OFF" -> MieruBean.MULTIPLEXING_OFF
                    "MULTIPLEXING_LOW" -> MieruBean.MULTIPLEXING_LOW
                    "MULTIPLEXING_MIDDLE" -> MieruBean.MULTIPLEXING_MIDDLE
                    "MULTIPLEXING_HIGH" -> MieruBean.MULTIPLEXING_HIGH
                    else -> MieruBean.MULTIPLEXING_DEFAULT
                }
            handshakeMode =
                when (proxy.value("handshake-mode")) {
                    "HANDSHAKE_STANDARD" -> MieruBean.HANDSHAKE_STANDARD
                    "HANDSHAKE_NO_WAIT" -> MieruBean.HANDSHAKE_NO_WAIT
                    else -> MieruBean.HANDSHAKE_DEFAULT
                }
            trafficPattern = proxy.value("traffic-pattern")
            lowEntropyMode = proxy.value("low-entropy-mode")
            lowEntropyMaskRotation = proxy.value("low-entropy-mask-rotation")
        }

    private fun parseAnyTLS(proxy: Map<String, Any?>, globalFingerprint: String) =
        AnyTLSBean().apply {
            serverAddress = proxy.requiredValue("server")
            serverPort = proxy.requiredPort()
            password = proxy.requiredValue("password")
            sni = proxy.value("sni")
            alpn = proxy.lines("alpn")
            allowInsecure = proxy.boolean("skip-cert-verify")
            utlsFingerprint = proxy.value("client-fingerprint").ifBlank { globalFingerprint }
            realityPubKey = proxy.map("reality-opts")?.value("public-key").orEmpty()
            realityShortId = proxy.map("reality-opts")?.value("short-id").orEmpty()
            proxy.map("ech-opts")?.let {
                if (it.boolean("enable")) echConfig = it.lines("config")
            }
            val custom = JSONObject()
            copy(proxy, custom, "idle-session-check-interval", "idle_session_check_interval")
            copy(proxy, custom, "idle-session-timeout", "idle_session_timeout")
            copy(proxy, custom, "min-idle-session", "min_idle_session")
            if (custom.length() > 0) mergeCustom(this, custom)
        }

    private fun parseWireGuard(proxy: Map<String, Any?>): AbstractBean {
        val peer = (proxy["peers"] as? List<*>)?.firstOrNull().stringMap() ?: proxy
        val awgOptions = proxy.map("amnezia-wg-option") ?: peer.map("amnezia-wg-option")
        val isAwg =
            awgOptions != null ||
                peer.value("persistent-keepalive").contains('-') ||
                peer.value("persistent-keepalive-interval").contains('-')
        val bean: AbstractBean = if (isAwg) {
            AmneziaWGBean().apply {
                applyWireGuard(peer, proxy)
                peerPersistentKeepalive =
                    peer.value("persistent-keepalive").ifBlank { peer.value("persistent-keepalive-interval") }
                awgOptions?.let { applyAmnezia(it) }
            }
        } else {
            WireGuardBean().apply {
                applyWireGuard(peer, proxy)
                peerPersistentKeepalive =
                    peer.int("persistent-keepalive", peer.int("persistent-keepalive-interval"))
            }
        }
        applyWireGuardOverrides(bean, peer, proxy)
        return bean
    }

    private fun WireGuardBean.applyWireGuard(peer: Map<String, Any?>, root: Map<String, Any?>) {
        serverAddress = peer.requiredValue("server")
        serverPort = peer.requiredPort()
        localAddress = addresses(root)
        privateKey = root.requiredValue("private-key")
        peerPublicKey = peer.requiredValue("public-key")
        peerPreSharedKey = peer.value("pre-shared-key").ifBlank { peer.value("preshared-key") }
        reserved = reserved(peer["reserved"] ?: root["reserved"])
        mtu = root.int("mtu", 1280)
        val custom = JSONObject()
        root["workers"]?.let { custom.put("workers", it) }
        if (custom.length() > 0) mergeCustom(this, custom)
    }

    private fun AmneziaWGBean.applyWireGuard(peer: Map<String, Any?>, root: Map<String, Any?>) {
        serverAddress = peer.requiredValue("server")
        serverPort = peer.requiredPort()
        localAddress = addresses(root)
        privateKey = root.requiredValue("private-key")
        peerPublicKey = peer.requiredValue("public-key")
        peerPreSharedKey = peer.value("pre-shared-key").ifBlank { peer.value("preshared-key") }
        reserved = reserved(peer["reserved"] ?: root["reserved"])
        mtu = root.int("mtu", 1280)
    }

    private fun AmneziaWGBean.applyAmnezia(options: Map<String, Any?>) {
        jc = options.int("jc")
        jmin = options.int("jmin")
        jmax = options.int("jmax")
        s1 = options.int("s1")
        s2 = options.int("s2")
        s3 = options.int("s3")
        s4 = options.int("s4")
        h1 = options.value("h1")
        h2 = options.value("h2")
        h3 = options.value("h3")
        h4 = options.value("h4")
        i1 = options.value("i1")
        i2 = options.value("i2")
        i3 = options.value("i3")
        i4 = options.value("i4")
        i5 = options.value("i5")
        headerProtectionKey = options.value("header-protection-key")
        contentPaddingAddition = options.value("content-padding-addition")
        rekeyAfterTime = options.value("rekey-after-time")
        rekeyTimeout = options.value("rekey-timeout")
        rejectAfterTime = options.value("reject-after-time")
        keepaliveTimeout = options.value("keepalive-timeout")
        maxHandshakeAttempts = options.value("max-handshake-attempts")
        randomTrailers = options.boolean("random-trailers")
        disableCookies = options.boolean("disable-cookies")
    }

    private fun applyWireGuardOverrides(
        bean: AbstractBean,
        peer: Map<String, Any?>,
        root: Map<String, Any?>,
    ) {
        val allowedIPs = peer.entry("allowed-ips")?.value ?: root.entry("allowed-ips")?.value ?: return
        val peerJSON = JSONObject()
        when (bean) {
            is WireGuardBean -> {
                peerJSON.put("address", bean.serverAddress)
                peerJSON.put("port", bean.serverPort)
                peerJSON.put("public_key", bean.peerPublicKey)
                bean.peerPreSharedKey.takeIf(String::isNotBlank)?.let {
                    peerJSON.put("pre_shared_key", it)
                }
                bean.peerPersistentKeepalive.takeIf { it > 0 }?.let {
                    peerJSON.put("persistent_keepalive_interval", it)
                }
            }
            is AmneziaWGBean -> {
                peerJSON.put("address", bean.serverAddress)
                peerJSON.put("port", bean.serverPort)
                peerJSON.put("public_key", bean.peerPublicKey)
                bean.peerPreSharedKey.takeIf(String::isNotBlank)?.let {
                    peerJSON.put("preshared_key", it)
                }
                bean.peerPersistentKeepalive.takeIf(String::isNotBlank)?.let {
                    peerJSON.put("persistent_keepalive_interval", it)
                }
            }
            else -> return
        }
        peerJSON.put("allowed_ips", jsonArray(allowedIPs))
        val reservedValue = peer.entry("reserved")?.value ?: root.entry("reserved")?.value
        reservedValue?.let { peerJSON.put("reserved", jsonArray(it)) }
        mergeCustom(bean, JSONObject().put("peers", JSONArray().put(peerJSON)))
    }

    private fun parseMasque(proxy: Map<String, Any?>) =
        MasqueBean().apply {
            val server = proxy.requiredValue("server")
            serverAddress = server
            serverPort = proxy.requiredPort()
            configPrivateKey = proxy.requiredValue("private-key")
            configEndpointPubKey = pemPublicKey(proxy.requiredValue("public-key"))
            configIPv4 = proxy.value("ip")
            configIPv6 = proxy.value("ipv6")
            useIPv6 = server.isIpAddressV6()
            if (useIPv6) {
                configEndpointV6 = server
                configEndpointH2V6 = server
            } else {
                configEndpointV4 = server
                configEndpointH2V4 = server
            }
            useHTTP2 = proxy.value("network").equals("h2", true)
            allowedIPs = proxy.lines("allowed-ips")
            tlsSNI = proxy.value("sni")
            tlsInsecure = proxy.boolean("skip-cert-verify")
            val custom = JSONObject()
            copy(proxy, custom, "mtu", "mtu")
            if (custom.length() > 0) mergeCustom(this, custom)
        }

    private fun parseTrustTunnel(proxy: Map<String, Any?>, globalFingerprint: String) =
        TrustTunnelBean().apply {
            serverAddress = proxy.requiredValue("server")
            serverPort = proxy.requiredPort()
            username = proxy.value("username")
            password = proxy.value("password")
            serverName = proxy.value("sni")
            alpn = proxy.lines("alpn")
            allowInsecure = proxy.boolean("skip-cert-verify")
            certificates = proxy.value("certificate")
            clientCert = proxy.value("certificate")
            clientKey = proxy.value("private-key")
            utlsFingerprint = proxy.value("client-fingerprint").ifBlank { globalFingerprint }
            healthCheck = proxy.boolean("health-check")
            quic = proxy.boolean("quic")
            quicCongestionControl = proxy.value("congestion-controller").ifBlank { "bbr" }
            proxy.map("ech-opts")?.let {
                ech = it.boolean("enable")
                echConfig = it.lines("config")
            }
        }

    private fun parseTailscale(proxy: Map<String, Any?>) =
        TailscaleBean().apply {
            authKey = proxy.value("auth-key")
            controlURL = proxy.value("control-url")
            ephemeral = proxy.boolean("ephemeral")
            hostname = proxy.value("hostname")
            acceptRoutes = proxy.boolean("accept-routes")
            exitNode = proxy.value("exit-node")
            exitNodeAllowLANAccess = proxy.boolean("exit-node-allow-lan-access")
            advertiseRoutes = proxy.lines("advertise-routes")
            advertiseExitNode = proxy.boolean("advertise-exit-node")
            advertiseTags = proxy.lines("advertise-tags")
            relayServerPort = proxy.int("relay-server-port")
            relayServerStaticEndpoints = proxy.lines("relay-server-static-endpoints")
            udpTimeout = proxy.value("udp-timeout")
            magicDNS = proxy.boolean("magic-dns") || proxy.boolean("magicdns")
            disableTcpKeepAlive = proxy.boolean("disable-tcp-keep-alive")
            tcpKeepAlive = proxy.value("tcp-keep-alive")
            tcpKeepAliveInterval = proxy.value("tcp-keep-alive-interval")
        }

    private fun parseOpenVPN(proxy: Map<String, Any?>) =
        OpenVPNBean().applyDefaultValues().apply {
            serverAddress = proxy.requiredValue("server")
            serverPort = proxy.int("port", 1194)
            network = if (proxy.value("proto").startsWith("tcp")) "tcp" else "udp"
            username = proxy.value("username")
            password = proxy.value("password")
            caCertificates = proxy.value("ca")
            clientCertificate = proxy.value("cert")
            clientKey = proxy.value("key")
            proxy.value("tls-crypt").takeIf(String::isNotBlank)?.let {
                controlWrapType = "tls-crypt"
                controlWrapKey = it
            }
            proxy.valueOrNull("ping")?.let { pingInterval = "${it}s" }
            proxy.valueOrNull("ping-restart")?.let { pingRestart = "${it}s" }
            mtu = proxy.int("mtu")
            dataCiphersFallback = proxy.value("cipher")
            auth = proxy.value("auth")
            compressionLZO = proxy.value("comp-lzo")
        }

    private fun applyCommonOptions(bean: AbstractBean, proxy: Map<String, Any?>) {
        val custom = JSONObject()
        bean.tcpFastOpen = proxy.boolean("tfo")
        bean.tcpMultiPath = proxy.boolean("mptcp")
        copy(proxy, custom, "interface-name", "bind_interface")
        copy(proxy, custom, "routing-mark", "routing_mark")
        when (proxy.value("ip-version").lowercase(Locale.ROOT)) {
            "ipv4", "ipv4-only" -> custom.put("domain_strategy", "ipv4_only")
            "ipv6", "ipv6-only" -> custom.put("domain_strategy", "ipv6_only")
            "prefer-ipv4" -> custom.put("domain_strategy", "prefer_ipv4")
            "prefer-ipv6" -> custom.put("domain_strategy", "prefer_ipv6")
        }
        if (custom.length() > 0) mergeCustom(bean, custom)
    }

    private fun applyMux(bean: AbstractBean, options: Map<String, Any?>?) {
        if (options == null || !options.boolean("enabled")) return
        when (bean) {
            is StandardV2RayBean -> {
                bean.enableMux = true
                applyMuxValues(
                    options,
                    setConcurrency = { bean.muxConcurrency = it },
                    setConnections = {
                        bean.muxMode = 1
                        bean.muxMaxConnections = it
                    },
                    setMinStreams = { bean.muxMinStreams = it },
                    setPadding = { bean.muxPadding = it },
                    setBrutal = { enabled, up, down ->
                        bean.muxBrutal = enabled
                        bean.muxBrutalUpMbps = up
                        bean.muxBrutalDownMbps = down
                    },
                )
            }
            is ShadowsocksBean -> {
                bean.enableMux = true
                applyMuxValues(
                    options,
                    setConcurrency = { bean.muxConcurrency = it },
                    setConnections = {
                        bean.muxMode = 1
                        bean.muxMaxConnections = it
                    },
                    setMinStreams = { bean.muxMinStreams = it },
                    setPadding = { bean.muxPadding = it },
                    setBrutal = { enabled, up, down ->
                        bean.muxBrutal = enabled
                        bean.muxBrutalUpMbps = up
                        bean.muxBrutalDownMbps = down
                    },
                )
            }
        }
    }

    private fun applyMuxValues(
        options: Map<String, Any?>,
        setConcurrency: (Int) -> Unit,
        setConnections: (Int) -> Unit,
        setMinStreams: (Int) -> Unit,
        setPadding: (Boolean) -> Unit,
        setBrutal: (Boolean, Int, Int) -> Unit,
    ) {
        options.int("max-streams").takeIf { it > 0 }?.let(setConcurrency)
        options.int("max-connections").takeIf { it > 0 }?.let(setConnections)
        options.int("min-streams").takeIf { it > 0 }?.let(setMinStreams)
        setPadding(options.boolean("padding"))
        options.map("brutal-opts")?.let {
            setBrutal(it.boolean("enabled"), speed(it["up"]), speed(it["down"]))
        }
    }

    private fun loadYaml(text: String): Map<String, Any?>? {
        fun load(source: String): Map<String, Any?>? {
            val value =
                Yaml()
                    .apply { addTypeDescription(TypeDescription(String::class.java, "str")) }
                    .loadAs(source, Map::class.java)
            return value.stringMap()
        }
        return try {
            load(text)
        } catch (error: YAMLException) {
            val normalized = text.replace('\t', ' ')
            if (normalized == text) return null
            runCatching { load(normalized) }.getOrNull()
        }
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

    private fun copy(source: Map<String, Any?>, target: JSONObject, sourceKey: String, targetKey: String) {
        source.entry(sourceKey)?.value?.let { target.put(targetKey, it) }
    }

    private fun jsonArray(value: Any): JSONArray =
        when (value) {
            is Iterable<*> -> JSONArray(value.toList())
            is Array<*> -> JSONArray(value.toList())
            else ->
                JSONArray(
                    value
                        .toString()
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotEmpty),
                )
        }

    private fun Map<String, Any?>.entry(key: String) =
        entries.firstOrNull { normalizeKey(it.key) == normalizeKey(key) }

    private fun Map<String, Any?>.containsNormalized(key: String) = entry(key) != null

    private fun Map<String, Any?>.value(key: String): String = entry(key)?.value?.toString().orEmpty()

    private fun Map<String, Any?>.valueOrNull(key: String): String? =
        value(key).takeIf(String::isNotBlank)

    private fun Map<String, Any?>.requiredValue(key: String): String =
        value(key).takeIf(String::isNotBlank) ?: error("missing $key")

    private fun Map<String, Any?>.int(key: String, default: Int = 0): Int =
        entry(key)?.value?.toString()?.toIntOrNull() ?: default

    private fun Map<String, Any?>.boolean(key: String): Boolean =
        when (value(key).lowercase(Locale.ROOT)) {
            "1", "true", "yes", "on", "enabled" -> true
            else -> false
        }

    private fun Map<String, Any?>.requiredPort(): Int =
        int("port").takeIf { it in 1..65535 } ?: error("invalid port")

    private fun Map<String, Any?>.map(key: String): Map<String, Any?>? = entry(key)?.value.stringMap()

    private fun Map<String, Any?>.lines(key: String): String = lines(entry(key)?.value)

    private fun Any?.stringMap(): Map<String, Any?>? =
        (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }

    private fun lines(value: Any?): String =
        when (value) {
            is List<*> -> value.filterNotNull().joinToString("\n")
            null -> ""
            else -> value.toString()
        }

    private fun normalizeKey(key: Any?): String =
        key.toString().replace('_', '-').lowercase(Locale.ROOT)

    private fun speed(value: Any?): Int =
        value
            ?.toString()
            ?.trim()
            ?.substringBefore(' ')
            ?.removeSuffix("Mbps")
            ?.removeSuffix("mbps")
            ?.toIntOrNull()
            ?: 0

    private fun durationSeconds(value: Any?, default: Int): Int {
        val raw = value?.toString()?.trim().orEmpty()
        return raw.removeSuffix("s").toIntOrNull() ?: default
    }

    private fun addresses(proxy: Map<String, Any?>): String =
        buildList {
            proxy.value("ip").takeIf(String::isNotBlank)?.let {
                add(if ('/' in it) it else "$it/32")
            }
            proxy.value("ipv6").takeIf(String::isNotBlank)?.let {
                add(if ('/' in it) it else "$it/128")
            }
        }.joinToString("\n")

    private fun reserved(value: Any?): String =
        when (value) {
            is List<*> -> value.filterNotNull().joinToString("\n")
            null -> ""
            else -> value.toString().replace("[\\[\\] ]".toRegex(), "")
        }

    private fun pemPublicKey(value: String): String {
        val key = value.trim()
        if ("-----BEGIN PUBLIC KEY-----" in key) return key
        return key
            .lineSequence()
            .joinToString("") { it.trim() }
            .chunked(64)
            .joinToString("\n", "-----BEGIN PUBLIC KEY-----\n", "\n-----END PUBLIC KEY-----")
    }

    internal fun clashCipher(cipher: String): String = if (cipher == "dummy") "none" else cipher
}
