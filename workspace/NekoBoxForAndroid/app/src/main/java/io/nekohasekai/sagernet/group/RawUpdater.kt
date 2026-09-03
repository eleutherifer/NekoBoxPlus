package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1Json
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.shadowsocksr.parseShadowsocksR
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.applyAmneziaWG3Options
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardConfDocument
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardConfParser
import io.nekohasekai.sagernet.fmt.wireguard.parseAmneziaWGJsonContainer
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.routing.SubscriptionRoutingExtractor
import io.nekohasekai.sagernet.routing.SubscriptionRoutingRepository
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal fun mergeCurrentSubscriptionSettings(
    current: SubscriptionBean,
    updated: SubscriptionBean,
) {
    updated.link = current.link
    updated.forceResolve = current.forceResolve
    updated.deduplication = current.deduplication
    updated.updateWhenConnectedOnly = current.updateWhenConnectedOnly
    updated.customUserAgent = current.customUserAgent
    updated.filterMode = current.filterMode
    updated.filterRegex = current.filterRegex
    updated.hwidEnabled = current.hwidEnabled
    updated.spoofApp = current.spoofApp
    updated.serverDnsResolver = current.serverDnsResolver
    updated.bannerLayout = current.bannerLayout
    updated.routingEnabled = current.routingEnabled
    updated.routingUpdateInterval = current.routingUpdateInterval

    if (current.providerAutoUpdateDefaultsApplied == true) {
        updated.autoUpdate = current.autoUpdate
        updated.autoUpdateDelay = current.autoUpdateDelay
    }
    updated.providerAutoUpdateDefaultsApplied = true
}

internal fun preserveMuxSettings(existing: AbstractBean, updated: AbstractBean) {
    when {
        existing is StandardV2RayBean && updated is StandardV2RayBean -> {
            updated.enableMux = existing.enableMux
            updated.muxPadding = existing.muxPadding
            updated.muxType = existing.muxType
            updated.muxConcurrency = existing.muxConcurrency
            updated.muxMode = existing.muxMode
            updated.muxMaxConnections = existing.muxMaxConnections
            updated.muxMinStreams = existing.muxMinStreams
            updated.muxBrutal = existing.muxBrutal
            updated.muxBrutalUpMbps = existing.muxBrutalUpMbps
            updated.muxBrutalDownMbps = existing.muxBrutalDownMbps
        }

        existing is ShadowsocksBean && updated is ShadowsocksBean -> {
            updated.enableMux = existing.enableMux
            updated.muxPadding = existing.muxPadding
            updated.muxType = existing.muxType
            updated.muxConcurrency = existing.muxConcurrency
            updated.muxMode = existing.muxMode
            updated.muxMaxConnections = existing.muxMaxConnections
            updated.muxMinStreams = existing.muxMinStreams
            updated.muxBrutal = existing.muxBrutal
            updated.muxBrutalUpMbps = existing.muxBrutalUpMbps
            updated.muxBrutalDownMbps = existing.muxBrutalDownMbps
        }
    }
}

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {
    internal var subscriptionReader: SubscriptionReader = DefaultSubscriptionReader
    internal var contentParser: SubscriptionContentParser = DefaultSubscriptionContentParser

    private fun TailscaleBean.applyTailscaleOptions(options: Map<*, *>) {
        for ((rawKey, value) in options) {
            if (value == null) continue
            when (normalizeClashKey(rawKey)) {
                "name", "tag" -> name = value.toString()
                "auth-key" -> authKey = value.toString()
                "control-url" -> controlURL = value.toString()
                "ephemeral" -> ephemeral = value.toString().toBoolean()
                "hostname" -> hostname = value.toString()
                "accept-routes" -> acceptRoutes = value.toString().toBoolean()
                "exit-node" -> exitNode = value.toString()
                "exit-node-allow-lan-access" -> exitNodeAllowLANAccess = value.toString().toBoolean()
                "advertise-routes" -> advertiseRoutes = listToLines(value)
                "advertise-exit-node" -> advertiseExitNode = value.toString().toBoolean()
                "advertise-tags" -> advertiseTags = listToLines(value)
                "relay-server-port" -> relayServerPort = value.toString().toIntOrNull() ?: 0
                "relay-server-static-endpoints" -> relayServerStaticEndpoints = listToLines(value)
                "udp-timeout" -> udpTimeout = value.toString()
                "magic-dns", "magicdns" -> magicDNS = value.toString().toBoolean()
                "disable-tcp-keep-alive" -> disableTcpKeepAlive = value.toString().toBoolean()
                "tcp-keep-alive" -> tcpKeepAlive = value.toString()
                "tcp-keep-alive-interval" -> tcpKeepAliveInterval = value.toString()
            }
        }
    }

    private fun normalizeWireGuardAddress(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.contains("/")) return trimmed
        return if (trimmed.isIpAddressV6()) "$trimmed/128" else "$trimmed/32"
    }

    private fun parseWireGuardAddresses(values: List<String>): String =
        values
            .flatMap { it.split(",") }
            .map { normalizeWireGuardAddress(it) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun normalizeClashKey(key: Any?): String = key.toString().replace("_", "-").lowercase(Locale.ROOT)

    private fun listToLines(value: Any?): String =
        when (value) {
            is List<*> -> value.filterNotNull().joinToString("\n") { it.toString() }
            else -> value.toString()
        }

    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean,
    ) {
        val link = subscription.link
        val originalName = proxyGroup.name
        val document = subscriptionReader.read(subscription)
        val responseText = document.body
        var proxies = parseRaw(responseText) ?: error(
            app.getString(
                if (document.source == SubscriptionDocument.Source.CONTENT) {
                    R.string.no_proxies_found_in_subscription
                } else {
                    R.string.no_proxies_found
                },
            ),
        )
        var autoUpdateEnabledFromHeader = false
        if (document.source == SubscriptionDocument.Source.CONTENT) {
            runCatching {
                SubscriptionRoutingRepository.updateStored(
                    subscription,
                    SubscriptionRoutingExtractor.extract("", "", responseText),
                    proxyGroup.id,
                )
            }.onFailure(Logs::w)
        } else {
            if (document.headers["x-hwid-not-supported"].equals("true", ignoreCase = true)) {
                error(app.getString(R.string.hwid_not_supported))
            } else if (
                document.headers["x-hwid-max-devices-reached"].equals("true", ignoreCase = true) ||
                document.headers["x-hwid-limit"].equals("true", ignoreCase = true)
            ) {
                error(app.getString(R.string.hwid_max_devices_reached))
            }

            runCatching {
                val routingSource = SubscriptionRoutingExtractor.extract(
                    document.headers["autorouting"],
                    document.headers["routing"],
                    responseText,
                )
                SubscriptionRoutingRepository.updateStored(subscription, routingSource, proxyGroup.id)
            }.onFailure(Logs::w)
            val metadata = SubscriptionMetadataParser.parse(
                document,
                isFirstUpdate = subscription.providerAutoUpdateDefaultsApplied != true,
            )
            subscription.subscriptionUserinfo = metadata.userinfo
            subscription.expireAt = metadata.expireAt
            subscription.announcement = metadata.announcement
            subscription.announcementUrl = metadata.announcementUrl
            subscription.supportUrl = metadata.supportUrl
            subscription.supportEmail = metadata.supportEmail
            subscription.profileWebPageUrl = metadata.profileWebPageUrl
            subscription.homepage = metadata.homepage
            metadata.autoUpdateIntervalMinutes?.let { intervalMinutes ->
                subscription.autoUpdate = true
                subscription.autoUpdateDelay = intervalMinutes
                autoUpdateEnabledFromHeader = true
            }
            subscription.providerAutoUpdateDefaultsApplied = true

            // 修改默认名字
            if (proxyGroup.name?.startsWith("Subscription #") == true) {
                metadata.suggestedName?.takeIf(String::isNotBlank)?.let { proxyGroup.name = it }
            }
        }
        subscription.providerAutoUpdateDefaultsApplied = true

        coroutineContext.ensureActive()
        val currentGroup = AppData.groups.getById(proxyGroup.id)
            ?: throw CancellationException("Subscription group was deleted")
        val currentSubscription = currentGroup.subscription
            ?: throw CancellationException("Subscription group no longer exists")
        if (currentGroup.type != GroupType.SUBSCRIPTION || currentSubscription.link != link) {
            throw CancellationException("Subscription changed during update")
        }
        if (!byUser && currentSubscription.autoUpdate != true) {
            throw CancellationException("Automatic subscription update was disabled")
        }
        mergeCurrentSubscriptionSettings(currentSubscription, subscription)
        proxyGroup.apply {
            userOrder = currentGroup.userOrder
            ungrouped = currentGroup.ungrouped
            name = if (currentGroup.name == originalName) name else currentGroup.name
            type = currentGroup.type
            order = currentGroup.order
            isSelector = currentGroup.isSelector
            frontProxy = currentGroup.frontProxy
            landingProxy = currentGroup.landingProxy
            forceUTLS = currentGroup.forceUTLS
            enableMux = currentGroup.enableMux
            muxType = currentGroup.muxType
            muxMode = currentGroup.muxMode
            muxConcurrency = currentGroup.muxConcurrency
            muxMaxConnections = currentGroup.muxMaxConnections
            muxMinStreams = currentGroup.muxMinStreams
            muxPadding = currentGroup.muxPadding
            muxBrutal = currentGroup.muxBrutal
            muxBrutalUpMbps = currentGroup.muxBrutalUpMbps
            muxBrutalDownMbps = currentGroup.muxBrutalDownMbps
        }

        proxies = SubscriptionProfilePolicy.assignUniqueNames(proxies)

        if (subscription.forceResolve) forceResolve(proxies, proxyGroup.id)

        val filterMode = subscription.filterMode ?: SubscriptionFilterMode.DISABLED
        val filterRegex = subscription.filterRegex ?: ""
        if (filterMode != SubscriptionFilterMode.DISABLED && filterRegex.isNotBlank()) {
            proxies = SubscriptionProfilePolicy.filter(proxies, filterMode, filterRegex)
            Logs.d("After filter (mode=$filterMode): ${proxies.size}")
        }

        val duplicate = ArrayList<String>()
        if (subscription.deduplication) {
            Logs.d("Before deduplication: ${proxies.size}")
            val result = SubscriptionProfilePolicy.deduplicate(proxies)
            proxies = result.profiles
            duplicate += result.duplicateNames
        }

        Logs.d("New profiles: ${proxies.size}")

        val shouldApplyUpdateOrder =
            proxyGroup.order != GroupOrder.MANUAL &&
                (DataStore.groupOrderModeAlways || DataStore.groupOrderModeUpdate)
        val syncResult = SubscriptionProfileSynchronizer.synchronize(
            proxyGroup,
            proxies,
            shouldApplyUpdateOrder,
        )
        if (
            autoUpdateEnabledFromHeader ||
            (subscription.routingEnabled == true && subscription.autoRoutingUrl.isNotBlank())
        ) {
            SubscriptionUpdater.reconfigureUpdater()
        }

        userInterface?.onUpdateSuccess(
            proxyGroup,
            syncResult.changed,
            syncResult.added,
            syncResult.updated,
            syncResult.deleted,
            duplicate,
            byUser,
        )
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun parseRaw(
        text: String,
        fileName: String = "",
    ): List<AbstractBean>? = contentParser.parse(text, fileName)

    fun parseWireGuard(conf: String): List<WireGuardBean> =
        parseWireGuard(WireGuardConfParser.parse(conf))

    private fun parseWireGuard(document: WireGuardConfDocument): List<WireGuardBean> {
        val iface = document.interfaceOptions
        val bean = WireGuardBean().applyDefaultValues()
        val localAddresses = iface.getAll("Address")
        if (localAddresses.isNullOrEmpty()) error("Empty address in 'Interface' selection")
        bean.localAddress = parseWireGuardAddresses(localAddresses)
        bean.privateKey = iface["PrivateKey"]
        bean.mtu = iface["MTU"]?.toIntOrNull() ?: 1280
        val peers = document.peers
        if (peers.isNullOrEmpty()) error("Missing 'Peer' selections")
        val beans = mutableListOf<WireGuardBean>()
        for (peer in peers) {
            val endpoint = peer["Endpoint"]?.parseHostAndPort() ?: continue
            val peerBean = bean.clone()
            peerBean.serverAddress = endpoint.host
            peerBean.serverPort = endpoint.port
            peerBean.peerPublicKey = peer["PublicKey"] ?: continue
            peerBean.peerPreSharedKey = peer["PreSharedKey"] ?: peer["PresharedKey"] ?: ""
            peerBean.peerPersistentKeepalive = (peer["PersistentKeepalive"] ?: peer["PersistentKeepAlive"])?.toIntOrNull() ?: 0
            peerBean.reserved = peer["Reserved"] ?: ""
            beans.add(peerBean.applyDefaultValues())
        }
        if (beans.isEmpty()) error("Empty available peer list")
        return beans
    }

    fun parseAmneziaWG(conf: String): List<AmneziaWGBean> =
        parseAmneziaWG(WireGuardConfParser.parse(conf))

    private fun parseAmneziaWG(document: WireGuardConfDocument): List<AmneziaWGBean> {
        val iface = document.interfaceOptions
        val bean = AmneziaWGBean().applyDefaultValues()
        val localAddresses = iface.getAll("Address")
        if (localAddresses.isNullOrEmpty()) error("Empty address in 'Interface' selection")
        bean.localAddress = parseWireGuardAddresses(localAddresses)
        bean.privateKey = iface["PrivateKey"]
        bean.mtu = iface["MTU"]?.toIntOrNull() ?: 1280
        // AWG 1.0 parameters
        iface["Jc"]?.toIntOrNull()?.let { bean.jc = it }
        iface["Jmin"]?.toIntOrNull()?.let { bean.jmin = it }
        iface["Jmax"]?.toIntOrNull()?.let { bean.jmax = it }
        iface["S1"]?.toIntOrNull()?.let { bean.s1 = it }
        iface["S2"]?.toIntOrNull()?.let { bean.s2 = it }
        iface["H1"]?.let { bean.h1 = it }
        iface["H2"]?.let { bean.h2 = it }
        iface["H3"]?.let { bean.h3 = it }
        iface["H4"]?.let { bean.h4 = it }
        // AWG 1.5 parameters
        iface["I1"]?.let { bean.i1 = it }
        iface["I2"]?.let { bean.i2 = it }
        iface["I3"]?.let { bean.i3 = it }
        iface["I4"]?.let { bean.i4 = it }
        iface["I5"]?.let { bean.i5 = it }
        // AWG 2.0 parameters
        iface["S3"]?.toIntOrNull()?.let { bean.s3 = it }
        iface["S4"]?.toIntOrNull()?.let { bean.s4 = it }
        bean.applyAmneziaWG3Options { iface[it] }
        val peers = document.peers
        if (peers.isNullOrEmpty()) error("Missing 'Peer' selections")
        val beans = mutableListOf<AmneziaWGBean>()
        for (peer in peers) {
            val endpoint = peer["Endpoint"]?.parseHostAndPort() ?: continue
            val peerBean = bean.clone()
            peerBean.serverAddress = endpoint.host
            peerBean.serverPort = endpoint.port
            peerBean.peerPublicKey = peer["PublicKey"] ?: continue
            peerBean.peerPreSharedKey = peer["PreSharedKey"] ?: peer["PresharedKey"] ?: ""
            peerBean.peerPersistentKeepalive =
                peer["PersistentKeepalive"] ?: peer["PersistentKeepAlive"] ?: "0"
            peerBean.reserved = peer["Reserved"] ?: ""
            beans.add(peerBean.applyDefaultValues())
        }
        if (beans.isEmpty()) error("Empty available peer list")
        return beans
    }

    fun parseJSON(json: Any): List<AbstractBean> {
        val proxies = ArrayList<AbstractBean>()

        fun JSONObject.parseSingBoxMieru(): MieruBean? {
            if (getStr("type") != "mieru") return null
            return MieruBean().applyDefaultValues().apply {
                name = getStr("tag") ?: ""
                serverAddress = getStr("server") ?: return null
                serverPort = optInt("server_port", 0)
                when (val serverPorts = opt("server_ports")) {
                    is JSONArray -> {
                        portRange = buildList {
                            for (i in 0 until serverPorts.length()) {
                                serverPorts.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                            }
                        }.joinToString("\n")
                    }
                    is String -> portRange = serverPorts
                }
                if (serverPort <= 0 && portRange.isBlank()) return null
                username = getStr("username") ?: ""
                password = getStr("password") ?: ""
                protocol = when (getStr("transport")?.uppercase(Locale.ROOT)) {
                    "UDP" -> MieruBean.PROTOCOL_UDP
                    else -> MieruBean.PROTOCOL_TCP
                }
                multiplexingLevel = when (getStr("multiplexing")) {
                    "MULTIPLEXING_OFF" -> MieruBean.MULTIPLEXING_OFF
                    "MULTIPLEXING_LOW" -> MieruBean.MULTIPLEXING_LOW
                    "MULTIPLEXING_MIDDLE" -> MieruBean.MULTIPLEXING_MIDDLE
                    "MULTIPLEXING_HIGH" -> MieruBean.MULTIPLEXING_HIGH
                    else -> MieruBean.MULTIPLEXING_DEFAULT
                }
                handshakeMode = when (getStr("handshake_mode")) {
                    "HANDSHAKE_STANDARD" -> MieruBean.HANDSHAKE_STANDARD
                    "HANDSHAKE_NO_WAIT" -> MieruBean.HANDSHAKE_NO_WAIT
                    else -> MieruBean.HANDSHAKE_DEFAULT
                }
                trafficPattern = getStr("traffic_pattern") ?: ""
                lowEntropyMode = getStr("low_entropy_mode") ?: ""
                lowEntropyMaskRotation = getStr("low_entropy_mask_rotation") ?: ""
            }
        }

        fun JSONObject.parseSingBoxTailscale(): TailscaleBean? {
            if (getStr("type") != "tailscale") return null
            val options = keys().asSequence().associateWith { key -> opt(key) }
            return TailscaleBean().applyDefaultValues().apply {
                applyTailscaleOptions(options)
            }
        }

        if (json is JSONObject) {
            when {
                json.getStr("type") == "amneziawg" -> {
                    return parseAmneziaWGJsonContainer(json)
                }

                json.getStr("type") == "mieru" -> {
                    return listOfNotNull(json.parseSingBoxMieru())
                }

                json.getStr("type") == "tailscale" -> {
                    return listOfNotNull(json.parseSingBoxTailscale())
                }

                json.getStr("type") in setOf("openvpn", "openvpn-client") -> {
                    return listOfNotNull(SingBoxEndpointParser.parseOpenVPN(json))
                }

                json.getStr("type") == "openconnect" -> {
                    return listOfNotNull(SingBoxEndpointParser.parseOpenConnect(json))
                }

                json.has("server") && (json.has("up") || json.has("up_mbps")) -> {
                    return listOf(json.parseHysteria1Json())
                }

                json.has("method") && json.has("obfs") && json.has("protocol") -> {
                    return listOf(json.parseShadowsocksR())
                }

                json.has("method") -> {
                    return listOf(json.parseShadowsocks())
                }

                json.has("remote_addr") -> {
                    return listOf(json.parseTrojanGo())
                }

                json.has("outbounds") || json.has("endpoints") -> {
                    val imported = mutableListOf<AbstractBean>()
                    val magicDnsEndpoints = json.optJSONObject("dns")
                        ?.optJSONArray("servers")
                        ?.filterIsInstance<JSONObject>()
                        ?.filter { it.getStr("type") == "tailscale" }
                        ?.mapNotNull { it.getStr("endpoint") }
                        ?.toSet()
                        .orEmpty()
                    json.optJSONArray("outbounds")
                        ?.filterIsInstance<JSONObject>()
                        ?.mapNotNull {
                            val ty = it.getStr("type")
                            if (ty == null || ty == "" ||
                                ty == "dns" || ty == "block" || ty == "direct" || ty == "selector" || ty == "urltest"
                            ) {
                                null
                            } else {
                                it
                            }
                        }?.mapNotNull {
                            it.parseSingBoxMieru() ?: ConfigBean().apply {
                                    applyDefaultValues()
                                    type = 1
                                    config = it.toStringPretty()
                                    name = it.getStr("tag")
                            }
                        }?.let(imported::addAll)
                    json.optJSONArray("endpoints")
                        ?.filterIsInstance<JSONObject>()
                        ?.mapNotNull { endpoint ->
                            endpoint.parseSingBoxTailscale()?.apply {
                                magicDNS = endpoint.getStr("tag") in magicDnsEndpoints
                            } ?: SingBoxEndpointParser.parseOpenVPN(endpoint)
                                ?: SingBoxEndpointParser.parseOpenConnect(endpoint)
                                ?: ConfigBean().apply {
                                applyDefaultValues()
                                type = 1
                                config = endpoint.toStringPretty()
                                name = endpoint.getStr("tag")
                            }
                        }?.let(imported::addAll)
                    return imported
                }

                json.has("server") && json.has("server_port") -> {
                    return listOf(
                        ConfigBean().applyDefaultValues().apply {
                            type = 1
                            config = json.toStringPretty()
                        },
                    )
                }
            }
        } else {
            json as JSONArray
            json.forEach { _, it ->
                if (isJsonObjectValid(it)) {
                    proxies.addAll(parseJSON(it))
                }
            }
        }

        proxies.forEach { it.initializeDefaultValues() }
        return proxies
    }
}
