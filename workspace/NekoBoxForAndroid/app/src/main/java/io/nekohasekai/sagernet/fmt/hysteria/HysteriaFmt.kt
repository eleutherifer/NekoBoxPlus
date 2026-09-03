package io.nekohasekai.sagernet.fmt.hysteria

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.addTLSQUICOptions
import io.nekohasekai.sagernet.fmt.applyClashTLSQUICOptions
import io.nekohasekai.sagernet.fmt.applySharedTLSOptions
import io.nekohasekai.sagernet.fmt.applySharedQUICOptions
import io.nekohasekai.sagernet.fmt.applyUriTLSQUICOptions
import io.nekohasekai.sagernet.fmt.queryParameterAny
import io.nekohasekai.sagernet.fmt.subscriptionBoolean
import io.nekohasekai.sagernet.fmt.subscriptionLines
import io.nekohasekai.sagernet.fmt.subscriptionValue
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File


// hysteria://host:port?auth=123456&peer=sni.domain&insecure=1|0&upmbps=100&downmbps=100&alpn=hysteria&obfs=xplus&obfsParam=123456#remarks
fun parseHysteria1(url: String): HysteriaBean {
    val link = url.replace("hysteria://", "https://").toHttpUrlOrNull() ?: error(
        "invalid hysteria link $url"
    )
    return HysteriaBean().apply {
        protocolVersion = 1
        serverAddress = link.host
        serverPorts = link.port.toString()
        name = link.fragment

        link.queryParameter("mport")?.also {
            serverPorts = it
        }
        link.queryParameter("peer")?.also {
            sni = it
        }
        link.queryParameter("auth")?.takeIf { it.isNotBlank() }?.also {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
        link.queryParameter("upmbps")?.also {
            uploadMbps = it.toIntOrNull() ?: uploadMbps
        }
        link.queryParameter("downmbps")?.also {
            downloadMbps = it.toIntOrNull() ?: downloadMbps
        }
        link.queryParameter("alpn")?.also {
            if (it != "none") alpn = it
        }
        link.queryParameter("obfsParam")?.also {
            obfuscation = it
        }
        link.queryParameter("protocol")?.also {
            when (it) {
                "faketcp" -> {
                    protocol = HysteriaBean.PROTOCOL_FAKETCP
                }

                "wechat-video" -> {
                    protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
                }
            }
        }
        link.queryParameterAny("hop_interval", "hop-interval")?.removeSuffix("s")?.toIntOrNull()?.let { hopInterval = it }
        applyUriTLSQUICOptions(link)
    }
}

// hysteria2://[auth@]hostname[:port]/?[key=value]&[key=value]...
fun parseHysteria2(url: String): HysteriaBean {
    val link = url
        .replace("hysteria2://", "https://")
        .replace("hy2://", "https://")
        .toHttpUrlOrNull() ?: error("invalid hysteria link $url")
    return HysteriaBean().apply {
        protocolVersion = 2
        serverAddress = link.host
        serverPorts = link.port.toString()
        authPayload = if (link.password.isNotBlank()) {
            link.username + ":" + link.password
        } else {
            link.username
        }
        name = link.fragment

        link.queryParameter("mport")?.also {
            serverPorts = it
        }
        link.queryParameter("sni")?.also {
            sni = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
//        link.queryParameter("upmbps")?.also {
//            uploadMbps = it.toIntOrNull() ?: uploadMbps
//        }
//        link.queryParameter("downmbps")?.also {
//            downloadMbps = it.toIntOrNull() ?: downloadMbps
//        }
        link.queryParameterAny("obfs-password", "obfs_password")?.also {
            obfuscation = it
        }
        link.queryParameter("obfs")?.takeIf { it.isNotBlank() }?.let { obfsType = it }
        link.queryParameterAny("hop_interval", "hop-interval")?.removeSuffix("s")?.toIntOrNull()?.let { hopInterval = it }
        link.queryParameterAny("hop_interval_max", "hop-interval-max")?.let { hopIntervalMax = it }
        link.queryParameter("upmbps")?.toIntOrNull()?.let { uploadMbps = it }
        link.queryParameter("downmbps")?.toIntOrNull()?.let { downloadMbps = it }
        link.queryParameterAny("bbr_profile", "bbr-profile")?.let { bbrProfile = it }
        link.queryParameterAny("brutal_debug", "brutal-debug")?.let { brutalDebug = it.subscriptionBoolean() }
        link.queryParameterAny("gecko_min_packet_size", "gecko-min-packet-size")?.toIntOrNull()?.let { geckoMinPacketSize = it }
        link.queryParameterAny("gecko_max_packet_size", "gecko-max-packet-size")?.toIntOrNull()?.let { geckoMaxPacketSize = it }
        link.queryParameterAny("realm_server_url", "realm-server-url")?.let { realmServerUrl = it }
        link.queryParameterAny("realm_token", "realm-token")?.let { realmToken = it }
        link.queryParameterAny("realm_id", "realm-id")?.let { realmId = it }
        link.queryParameterAny("realm_stun_servers", "realm-stun-servers")?.let { realmStunServers = it.replace(',', '\n') }
        link.queryParameterAny("realm_ip_version", "realm-ip-version")?.toIntOrNull()?.let { realmIpVersion = it }
        link.queryParameterAny("realm_port_mapping", "realm-port-mapping")?.let { realmPortMapping = it.subscriptionBoolean() }
        link.queryParameterAny("realm_port_mapping_timeout", "realm-port-mapping-timeout")?.let { realmPortMappingTimeout = it }
        link.queryParameterAny("realm_port_mapping_lifetime", "realm-port-mapping-lifetime")?.let { realmPortMappingLifetime = it }
        applyUriTLSQUICOptions(link)
//        link.queryParameter("pinSHA256")?.also {
//            // TODO your box do not support it
//        }
    }
}

fun HysteriaBean.toUri(): String {
    var un = ""
    var pw = ""
    if (protocolVersion == 2) {
        if (authPayload.contains(":")) {
            un = authPayload.substringBefore(":")
            pw = authPayload.substringAfter(":")
        } else {
            un = authPayload
        }
    }
    //
    val builder = linkBuilder()
        .host(serverAddress)
        .port(getFirstPort(serverPorts))
        .username(un)
        .password(pw)
    if (isMultiPort(displayAddress())) {
        builder.addQueryParameter("mport", serverPorts)
    }
    if (name.isNotBlank()) {
        builder.fragment(name)
    }
    if (allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    builder.addTLSQUICOptions(this)
    if (hopInterval > 0) builder.addQueryParameter("hop_interval", hopInterval.toString())
    if (protocolVersion == 1) {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("peer", sni)
        }
        if (authPayload.isNotBlank()) {
            builder.addQueryParameter("auth", authPayload)
        }
        builder.addQueryParameter("upmbps", "$uploadMbps")
        builder.addQueryParameter("downmbps", "$downloadMbps")
        if (alpn.isNotBlank()) {
            builder.addQueryParameter("alpn", alpn)
        }
        if (obfuscation.isNotBlank()) {
            builder.addQueryParameter("obfs", "xplus")
            builder.addQueryParameter("obfsParam", obfuscation)
        }
        when (protocol) {
            HysteriaBean.PROTOCOL_FAKETCP -> {
                builder.addQueryParameter("protocol", "faketcp")
            }

            HysteriaBean.PROTOCOL_WECHAT_VIDEO -> {
                builder.addQueryParameter("protocol", "wechat-video")
            }
        }
    } else {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("sni", sni)
        }
        if (obfuscation.isNotBlank()) {
            builder.addQueryParameter("obfs", obfsType.ifBlank { "salamander" })
            builder.addQueryParameter("obfs-password", obfuscation)
        }
        if (hopIntervalMax.isNotBlank()) builder.addQueryParameter("hop_interval_max", hopIntervalMax)
        if (uploadMbps > 0) builder.addQueryParameter("upmbps", uploadMbps.toString())
        if (downloadMbps > 0) builder.addQueryParameter("downmbps", downloadMbps.toString())
        if (bbrProfile.isNotBlank()) builder.addQueryParameter("bbr_profile", bbrProfile)
        if (brutalDebug) builder.addQueryParameter("brutal_debug", "1")
        if (geckoMinPacketSize > 0) builder.addQueryParameter("gecko_min_packet_size", geckoMinPacketSize.toString())
        if (geckoMaxPacketSize > 0) builder.addQueryParameter("gecko_max_packet_size", geckoMaxPacketSize.toString())
        if (realmServerUrl.isNotBlank()) builder.addQueryParameter("realm_server_url", realmServerUrl)
        if (realmToken.isNotBlank()) builder.addQueryParameter("realm_token", realmToken)
        if (realmId.isNotBlank()) builder.addQueryParameter("realm_id", realmId)
        if (realmStunServers.isNotBlank()) builder.addQueryParameter("realm_stun_servers", realmStunServers.replace('\n', ','))
        if (realmIpVersion > 0) builder.addQueryParameter("realm_ip_version", realmIpVersion.toString())
        if (realmPortMapping) builder.addQueryParameter("realm_port_mapping", "1")
        if (realmPortMappingTimeout.isNotBlank()) builder.addQueryParameter("realm_port_mapping_timeout", realmPortMappingTimeout)
        if (realmPortMappingLifetime.isNotBlank()) builder.addQueryParameter("realm_port_mapping_lifetime", realmPortMappingLifetime)
    }
    return builder.toLink(if (protocolVersion == 2) "hy2" else "hysteria")
}

fun parseClashHysteria(proxy: Map<String, Any?>, version: Int): HysteriaBean =
    HysteriaBean().applyDefaultValues().apply {
        protocolVersion = version
        name = proxy.subscriptionValue("name")?.toString() ?: ""
        serverAddress = proxy.subscriptionValue("server")?.toString() ?: serverAddress
        serverPorts = proxy.subscriptionValue("ports", "server-ports", "port")?.subscriptionLines() ?: serverPorts
        sni = proxy.subscriptionValue("sni", "server-name")?.toString() ?: ""
        allowInsecure = proxy.subscriptionValue("skip-cert-verify", "insecure").subscriptionBoolean()
        uploadMbps = proxy.subscriptionValue("up", "up-mbps", "upmbps")?.toString()?.substringBefore(' ')?.toIntOrNull() ?: uploadMbps
        downloadMbps = proxy.subscriptionValue("down", "down-mbps", "downmbps")?.toString()?.substringBefore(' ')?.toIntOrNull() ?: downloadMbps
        hopInterval = proxy.subscriptionValue("hop-interval")?.toString()?.removeSuffix("s")?.toIntOrNull() ?: hopInterval
        applyClashTLSQUICOptions(proxy)

        if (version == 1) {
            obfuscation = proxy.subscriptionValue("obfs")?.toString() ?: ""
            proxy.subscriptionValue("auth-str")?.let {
                authPayloadType = HysteriaBean.TYPE_STRING
                authPayload = it.toString()
            }
            proxy.subscriptionValue("auth")?.let {
                authPayloadType = HysteriaBean.TYPE_BASE64
                authPayload = it.toString()
            }
            streamReceiveWindow = proxy.subscriptionValue("recv-window")?.toString()?.toIntOrNull() ?: 0
            connectionReceiveWindow = proxy.subscriptionValue("recv-window-conn")?.toString()?.toIntOrNull() ?: 0
            disableMtuDiscovery = proxy.subscriptionValue("disable-mtu-discovery").subscriptionBoolean()
            alpn = proxy.subscriptionValue("alpn").subscriptionLines()
        } else {
            authPayload = proxy.subscriptionValue("password", "auth")?.toString() ?: ""
            val obfsOptions = proxy.subscriptionValue("obfs")
            if (obfsOptions is Map<*, *>) {
                obfsType = obfsOptions.subscriptionValue("type")?.toString() ?: obfsType
                obfuscation = obfsOptions.subscriptionValue("password")?.toString() ?: ""
                geckoMinPacketSize = obfsOptions.subscriptionValue("min-packet-size")?.toString()?.toIntOrNull() ?: 0
                geckoMaxPacketSize = obfsOptions.subscriptionValue("max-packet-size")?.toString()?.toIntOrNull() ?: 0
            } else {
                obfuscation = proxy.subscriptionValue("obfs-password")?.toString() ?: ""
                obfsType = obfsOptions?.toString() ?: obfsType
            }
            hopIntervalMax = proxy.subscriptionValue("hop-interval-max")?.toString() ?: ""
            bbrProfile = proxy.subscriptionValue("bbr-profile")?.toString() ?: ""
            brutalDebug = proxy.subscriptionValue("brutal-debug").subscriptionBoolean()
            proxy.subscriptionValue("gecko-min-packet-size")?.toString()?.toIntOrNull()?.let { geckoMinPacketSize = it }
            proxy.subscriptionValue("gecko-max-packet-size")?.toString()?.toIntOrNull()?.let { geckoMaxPacketSize = it }

            (proxy.subscriptionValue("realm") as? Map<*, *>)?.let { realm ->
                realmServerUrl = realm.subscriptionValue("server-url")?.toString() ?: ""
                realmToken = realm.subscriptionValue("token")?.toString() ?: ""
                realmId = realm.subscriptionValue("realm-id")?.toString() ?: ""
                realmStunServers = realm.subscriptionValue("stun-servers").subscriptionLines()
                realmIpVersion = realm.subscriptionValue("ip-version")?.toString()?.toIntOrNull() ?: 0
                (realm.subscriptionValue("port-mapping") as? Map<*, *>)?.let { mapping ->
                    realmPortMapping = mapping.subscriptionValue("enabled").subscriptionBoolean()
                    realmPortMappingTimeout = mapping.subscriptionValue("timeout")?.toString() ?: ""
                    realmPortMappingLifetime = mapping.subscriptionValue("lifetime")?.toString() ?: ""
                }
            }
        }
    }

fun JSONObject.parseHysteria1Json(): HysteriaBean {
    // TODO parse HY2 JSON+YAML
    return HysteriaBean().apply {
        protocolVersion = 1
        serverAddress = optString("server").substringBeforeLast(":")
        serverPorts = optString("server").substringAfterLast(":")
        uploadMbps = getIntNya("up_mbps")
        downloadMbps = getIntNya("down_mbps")
        obfuscation = getStr("obfs")
        getStr("auth")?.also {
            authPayloadType = HysteriaBean.TYPE_BASE64
            authPayload = it
        }
        getStr("auth_str")?.also {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        getStr("protocol")?.also {
            when (it) {
                "faketcp" -> {
                    protocol = HysteriaBean.PROTOCOL_FAKETCP
                }

                "wechat-video" -> {
                    protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
                }
            }
        }
        sni = getStr("server_name")
        getStr("alpn")?.also { if (it != "none") alpn = it }
        allowInsecure = getBool("insecure")

        streamReceiveWindow = getIntNya("recv_window")
        connectionReceiveWindow = getIntNya("recv_window_conn")
        disableMtuDiscovery = getBool("disable_mtu_discovery")
    }
}

fun HysteriaBean.buildHysteria1Config(port: Int, cacheFile: (() -> File)?): String {
    if (protocolVersion != 1) {
        throw Exception("error version: $protocolVersion")
    }
    return JSONObject().apply {
        put("server", displayAddress())
        when (protocol) {
            HysteriaBean.PROTOCOL_FAKETCP -> {
                put("protocol", "faketcp")
            }

            HysteriaBean.PROTOCOL_WECHAT_VIDEO -> {
                put("protocol", "wechat-video")
            }
        }
        put("up_mbps", uploadMbps)
        put("down_mbps", downloadMbps)
        put(
            "socks5", JSONObject(
                mapOf(
                    "listen" to "$LOCALHOST:$port",
                )
            )
        )
        put("retry", 5)
        put("fast_open", true)
        put("lazy_start", true)
        put("obfs", obfuscation)
        when (authPayloadType) {
            HysteriaBean.TYPE_BASE64 -> put("auth", authPayload)
            HysteriaBean.TYPE_STRING -> put("auth_str", authPayload)
        }
        if (sni.isBlank() && finalAddress == LOCALHOST && !serverAddress.isIpAddress()) {
            sni = serverAddress
        }
        if (sni.isNotBlank()) {
            put("server_name", sni)
        }
        if (alpn.isNotBlank()) put("alpn", alpn)
        if (caText.isNotBlank() && cacheFile != null) {
            val caFile = cacheFile()
            caFile.writeText(caText)
            put("ca", caFile.absolutePath)
        }

        if (allowInsecure) put("insecure", true)
        if (streamReceiveWindow > 0) put("recv_window", streamReceiveWindow)
        if (connectionReceiveWindow > 0) put("recv_window_conn", connectionReceiveWindow)
        if (disableMtuDiscovery) put("disable_mtu_discovery", true)

        put("hop_interval", hopInterval)
    }.toStringPretty()
}

fun isMultiPort(hyAddr: String): Boolean {
    if (!hyAddr.contains(":")) return false
    val p = hyAddr.substringAfterLast(":")
    if (p.contains("-") || p.contains(",")) return true
    return false
}

fun getFirstPort(portStr: String): Int {
    return portStr.substringBefore(":").substringBefore(",").toIntOrNull() ?: 443
}

fun HysteriaBean.canUseSingBox(): Boolean {
    if (protocol != HysteriaBean.PROTOCOL_UDP) return false
    return true
}

fun buildSingBoxOutboundHysteriaBean(bean: HysteriaBean): SingBoxOptions.SingBoxOption {
    return buildSingBoxOutboundHysteriaBean(
        bean,
        DataStore.globalAllowInsecure,
        DataStore.hysteria2DisableChromeParrot,
    )
}

internal fun buildSingBoxOutboundHysteriaBean(
    bean: HysteriaBean,
    globalAllowInsecure: Boolean,
    disableChromeParrot: Boolean = false,
): SingBoxOptions.SingBoxOption {
    return when (bean.protocolVersion) {
        1 -> SingBoxOptions.Outbound_HysteriaOptions().apply {
            type = "hysteria"
            server = bean.serverAddress
            val port = bean.serverPorts.toIntOrNull()
            if (port != null) {
                server_port = port
            } else {
                server_ports = hopPortsToSingboxList(bean.serverPorts)
            }
            hop_interval = "${bean.hopInterval}s"
            up_mbps = bean.uploadMbps
            down_mbps = bean.downloadMbps
            obfs = bean.obfuscation
            when (bean.authPayloadType) {
                HysteriaBean.TYPE_BASE64 -> auth = bean.authPayload
                HysteriaBean.TYPE_STRING -> auth_str = bean.authPayload
            }
            if (bean.streamReceiveWindow > 0) {
                stream_receive_window = bean.streamReceiveWindow.toLong()
            }
            if (bean.connectionReceiveWindow > 0) {
                connection_receive_window = bean.connectionReceiveWindow.toLong()
            }
            disable_path_mtu_discovery = bean.disableMtuDiscovery.takeIf { it }
            applySharedQUICOptions(bean)
            tls = SingBoxOptions.OutboundTLSOptions().apply {
                if (bean.sni.isNotBlank()) {
                    server_name = bean.sni
                }
                if (bean.alpn.isNotBlank()) {
                    alpn = bean.alpn.listByLineOrComma()
                }
                if (bean.caText.isNotBlank()) {
                    certificate = bean.caText
                }
                insecure = bean.allowInsecure || globalAllowInsecure
                enabled = true
                applySharedTLSOptions(bean)
            }
        }

        2 -> SingBoxOptions.Outbound_Hysteria2Options().apply {
            type = "hysteria2"
            val useRealm = bean.realmServerUrl.isNotBlank()
            if (!useRealm) {
                server = bean.serverAddress
                val port = bean.serverPorts.toIntOrNull()
                if (port != null) {
                    server_port = port
                } else {
                    server_ports = hopPortsToSingboxList(bean.serverPorts)
                }
            }
            hop_interval = "${bean.hopInterval}s"
            hop_interval_max = bean.hopIntervalMax.takeIf { it.isNotBlank() }
            up_mbps = bean.uploadMbps
            down_mbps = bean.downloadMbps
            if (bean.obfuscation.isNotBlank()) {
                obfs = SingBoxOptions.Hysteria2Obfs().apply {
                    type = bean.obfsType
                    password = bean.obfuscation
                    min_packet_size = bean.geckoMinPacketSize.takeIf { bean.obfsType == "gecko" && it > 0 }
                    max_packet_size = bean.geckoMaxPacketSize.takeIf { bean.obfsType == "gecko" && it > 0 }
                }
            }
            password = bean.authPayload
            if (bean.streamReceiveWindow > 0) {
                stream_receive_window = bean.streamReceiveWindow.toLong()
            }
            if (bean.connectionReceiveWindow > 0) {
                connection_receive_window = bean.connectionReceiveWindow.toLong()
            }
            disable_path_mtu_discovery = bean.disableMtuDiscovery.takeIf { it }
            bbr_profile = bean.bbrProfile.takeIf { it.isNotBlank() }
            brutal_debug = bean.brutalDebug.takeIf { it }
            disable_chrome_parrot = disableChromeParrot.takeIf { it }
            if (useRealm) {
                realm = SingBoxOptions.Hysteria2Realm().apply {
                    server_url = bean.realmServerUrl
                    token = bean.realmToken.takeIf { it.isNotBlank() }
                    realm_id = bean.realmId
                    stun_servers = bean.realmStunServers.listByLineOrComma()
                    ip_version = bean.realmIpVersion.takeIf { it == 4 || it == 6 }
                    if (bean.realmPortMapping) {
                        port_mapping = SingBoxOptions.Hysteria2RealmPortMapping().apply {
                            enabled = true
                            timeout = bean.realmPortMappingTimeout.takeIf { it.isNotBlank() }
                            lifetime = bean.realmPortMappingLifetime.takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
            applySharedQUICOptions(bean)
            tls = SingBoxOptions.OutboundTLSOptions().apply {
                if (bean.sni.isNotBlank()) {
                    server_name = bean.sni
                }
                alpn = listOf("h3")
                if (bean.caText.isNotBlank()) {
                    certificate = bean.caText
                }
                insecure = bean.allowInsecure || globalAllowInsecure
                enabled = true
                applySharedTLSOptions(bean)
            }
        }

        else -> error("error_version $bean.protocolVersion")
    }
}

fun hopPortsToSingboxList(s: String): List<String> {
    return s.split(",").mapNotNull {
        val pRange = it.replace("-", ":")
        if (pRange.split(":").size == 2) {
            pRange
        } else {
            null
        }
    }
}
