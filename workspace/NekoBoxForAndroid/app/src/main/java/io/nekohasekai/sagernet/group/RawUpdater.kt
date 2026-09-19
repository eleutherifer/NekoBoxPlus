package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import androidx.core.net.toUri
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SpoofApp
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
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardConfDocument
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardConfParser
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.routing.SubscriptionRoutingExtractor
import io.nekohasekai.sagernet.routing.SubscriptionRoutingRepository
import libcore.Libcore
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeProfileTitle(headerValue: String): String? {
    val value = headerValue.trim()
    if (value.isEmpty() || value.equals("null", ignoreCase = true)) return null
    if (!value.startsWith("base64:", ignoreCase = true)) return value

    val encoded = value.substringAfter(':').trim()
    if (encoded.isEmpty()) return null
    val padded = encoded.padEnd(encoded.length + (4 - encoded.length % 4) % 4, '=')

    val decoded =
        runCatching { Base64.Default.decode(padded) }
            .recoverCatching { Base64.UrlSafe.decode(padded) }
            .getOrNull()
            ?: return null

    return decoded
        .toString(Charsets.UTF_8)
        .trim()
        .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
}

internal fun profileUpdateIntervalMinutes(headerValue: String, isFirstUpdate: Boolean): Int? {
    if (!isFirstUpdate) return null

    val hours = headerValue.trim().toLongOrNull() ?: return null
    if (hours <= 0L || hours > Int.MAX_VALUE / 60L) return null
    return (hours * 60L).toInt()
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

internal data class XraySubscriptionBodyHeaders(
    val profileTitle: String? = null,
    val profileUpdateInterval: String? = null,
    val subscriptionUserinfo: String? = null,
    val announcement: String? = null,
    val announcementUrl: String? = null,
    val supportUrl: String? = null,
    val supportEmail: String? = null,
    val profileWebPageUrl: String? = null,
    val homepage: String? = null,
)

internal fun parseXraySubscriptionBodyHeaders(text: String): XraySubscriptionBodyHeaders {
    var profileTitle: String? = null
    var profileUpdateInterval: String? = null
    var subscriptionUserinfo: String? = null
    var announcement: String? = null
    var announcementUrl: String? = null
    var supportUrl: String? = null
    var supportEmail: String? = null
    var profileWebPageUrl: String? = null
    var homepage: String? = null

    for ((index, rawLine) in text.lineSequence().withIndex()) {
        val line = rawLine
            .let { if (index == 0) it.removePrefix("\uFEFF") else it }
            .trim()
        if (line.isEmpty()) continue
        if (!line.startsWith('#')) break

        val header = line.substring(1).trimStart()
        val separator = header.indexOf(':')
        if (separator <= 0) continue

        val name = header.substring(0, separator).trim().lowercase(Locale.ROOT)
        val value = header.substring(separator + 1).trim().takeIf { it.isNotEmpty() } ?: continue
        when (name) {
            "profile-title" -> if (profileTitle == null) profileTitle = value
            "profile-update-interval" -> if (profileUpdateInterval == null) {
                profileUpdateInterval = value
            }
            "subscription-userinfo" -> if (subscriptionUserinfo == null) {
                subscriptionUserinfo = value
            }
            "announce" -> if (announcement == null) announcement = value
            "announce-url" -> if (announcementUrl == null) announcementUrl = value
            "support-url" -> if (supportUrl == null) supportUrl = value
            "support-email" -> if (supportEmail == null) supportEmail = value
            "profile-web-page-url" -> if (profileWebPageUrl == null) profileWebPageUrl = value
            "homepage" -> if (homepage == null) homepage = value
        }
    }

    return XraySubscriptionBodyHeaders(
        profileTitle,
        profileUpdateInterval,
        subscriptionUserinfo,
        announcement,
        announcementUrl,
        supportUrl,
        supportEmail,
        profileWebPageUrl,
        homepage,
    )
}

internal fun responseOrBodyHeader(responseHeader: String, bodyHeader: String?): String =
    responseHeader.ifBlank { bodyHeader.orEmpty() }

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {
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
    @SuppressLint("Recycle")
    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean,
    ) {
        val link = subscription.link
        var proxies: List<AbstractBean>
        var autoUpdateEnabledFromHeader = false
        if (link.startsWith("content://")) {
            val contentText =
                app.contentResolver
                    .openInputStream(link.toUri())
                    ?.bufferedReader()
                    ?.readText()

            proxies = contentText?.let { parseRaw(contentText) }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
            runCatching {
                SubscriptionRoutingRepository.updateStored(
                    subscription,
                    SubscriptionRoutingExtractor.extract("", "", contentText),
                    proxyGroup.id,
                )
            }.onFailure(Logs::w)
        } else {
            val client =
                Libcore.newHttpClient().apply {
                    setTimeoutMillis(GroupUpdater.SUBSCRIPTION_UPDATE_TIMEOUT_MILLIS)
                    tryH3Direct()
                    when (DataStore.appTLSVersion) {
                        "1.3" -> restrictedTLS()
                    }
                }
            try {
                val response =
                    client
                        .newRequest()
                        .apply {
                            if (DataStore.allowInsecureOnRequest) {
                                allowInsecure()
                            }
                            setURL(subscription.link)
                            val fingerprint =
                                buildSubscriptionRequestFingerprint(
                                    spoofApp = subscription.spoofApp ?: SpoofApp.NONE,
                                    hwidEnabled = subscription.hwidEnabled == true,
                                    customUserAgent = subscription.customUserAgent,
                                    fallbackUserAgent = USER_AGENT,
                                )
                            setUserAgent(fingerprint.userAgent)
                            for ((name, value) in fingerprint.headers) {
                                setHeader(name, value)
                            }
                        }.execute()

                if (Util.getStringBox(response.getHeader("x-hwid-not-supported")).lowercase() == "true") {
                    error(app.getString(R.string.hwid_not_supported))
                } else if (Util.getStringBox(response.getHeader("x-hwid-max-devices-reached")).lowercase() == "true" ||
                    Util.getStringBox(response.getHeader("x-hwid-limit")).lowercase() == "true"
                ) {
                    error(app.getString(R.string.hwid_max_devices_reached))
                }

                val responseText = Util.getStringBox(response.contentString)
                proxies = parseRaw(responseText)
                    ?: error(app.getString(R.string.no_proxies_found))
                runCatching {
                    val routingSource = SubscriptionRoutingExtractor.extract(
                        Util.getStringBox(response.getHeader("autorouting")),
                        Util.getStringBox(response.getHeader("routing")),
                        responseText,
                    )
                    SubscriptionRoutingRepository.updateStored(
                        subscription,
                        routingSource,
                        proxyGroup.id,
                    )
                }.onFailure(Logs::w)
                val bodyHeaders = parseXraySubscriptionBodyHeaders(responseText)

                subscription.subscriptionUserinfo =
                    responseOrBodyHeader(
                        Util.getStringBox(response.getHeader("Subscription-Userinfo")),
                        bodyHeaders.subscriptionUserinfo,
                    )
                subscription.announcement =
                    decodeProfileTitle(
                        responseOrBodyHeader(
                            Util.getStringBox(response.getHeader("announce")),
                            bodyHeaders.announcement,
                        ),
                    ).orEmpty()
                subscription.announcementUrl =
                    responseOrBodyHeader(
                        Util.getStringBox(response.getHeader("announce-url")),
                        bodyHeaders.announcementUrl,
                    )
                subscription.supportUrl =
                    responseOrBodyHeader(
                        Util.getStringBox(response.getHeader("support-url")),
                        bodyHeaders.supportUrl,
                    )
                subscription.supportEmail =
                    responseOrBodyHeader(
                        Util.getStringBox(response.getHeader("support-email")),
                        bodyHeaders.supportEmail,
                    )
                subscription.profileWebPageUrl =
                    responseOrBodyHeader(
                        Util.getStringBox(response.getHeader("profile-web-page-url")),
                        bodyHeaders.profileWebPageUrl,
                    )
                subscription.homepage =
                    responseOrBodyHeader(
                        Util.getStringBox(response.getHeader("homepage")),
                        bodyHeaders.homepage,
                    )

                val updateIntervalHeader =
                    responseOrBodyHeader(
                        Util.getStringBox(response.getHeader("profile-update-interval")),
                        bodyHeaders.profileUpdateInterval,
                    )
                profileUpdateIntervalMinutes(
                    updateIntervalHeader,
                    isFirstUpdate = subscription.lastUpdated == 0,
                )?.let { intervalMinutes ->
                    subscription.autoUpdate = true
                    subscription.autoUpdateDelay = intervalMinutes
                    autoUpdateEnabledFromHeader = true
                }

                // 修改默认名字
                if (proxyGroup.name?.startsWith("Subscription #") == true) {
                    val profileTitleHeader =
                        responseOrBodyHeader(
                            Util.getStringBox(response.getHeader("profile-title")),
                            bodyHeaders.profileTitle,
                        )
                    var remoteName =
                        decodeProfileTitle(profileTitleHeader)
                    if (remoteName.isNullOrBlank()) {
                        remoteName = Util.getStringBox(response.getHeader("content-disposition"))
                            .takeIf { it.isNotBlank() }
                            ?.let { Util.decodeFilename(it) }
                    }
                    if (!remoteName.isNullOrBlank()) {
                        proxyGroup.name = remoteName
                    }
                }
            } finally {
                client.close()
            }
        }

        val proxiesMap = LinkedHashMap<String, AbstractBean>()
        for (proxy in proxies) {
            var index = 0
            var name = proxy.displayName()
            while (proxiesMap.containsKey(name)) {
                println("Exists name: $name")
                index++
                name = name.replace(" (${index - 1})", "")
                name = "$name ($index)"
                proxy.name = name
            }
            proxiesMap[proxy.displayName()] = proxy
        }
        proxies = proxiesMap.values.toList()

        if (subscription.forceResolve) forceResolve(proxies, proxyGroup.id)

        val filterMode = subscription.filterMode ?: SubscriptionFilterMode.DISABLED
        val filterRegex = subscription.filterRegex ?: ""
        if (filterMode != SubscriptionFilterMode.DISABLED && filterRegex.isNotBlank()) {
            val regex = filterRegex.toRegex()
            proxies =
                when (filterMode) {
                    SubscriptionFilterMode.INCLUDE -> proxies.filter { regex.containsMatchIn(it.displayName()) }
                    SubscriptionFilterMode.EXCLUDE -> proxies.filterNot { regex.containsMatchIn(it.displayName()) }
                    else -> proxies
                }
            Logs.d("After filter (mode=$filterMode): ${proxies.size}")
        }

        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        val duplicate = ArrayList<String>()
        if (subscription.deduplication) {
            Logs.d("Before deduplication: ${proxies.size}")
            val uniqueProxyHashes = ArrayList<String>()
            val uniqueProxies = LinkedHashMap<String, AbstractBean>()
            val uniqueNames = HashMap<String, String>()
            for (_proxy in proxies) {
                val proxyHash = _proxy.hash
                val existingIndex = uniqueProxyHashes.indexOf(proxyHash)
                if (existingIndex >= 0) {
                    if (uniqueNames.containsKey(proxyHash)) {
                        val name = uniqueNames[proxyHash]!!.replace(" ($existingIndex)", "")
                        if (name.isNotBlank()) {
                            duplicate.add("$name ($existingIndex)")
                            uniqueNames[proxyHash] = ""
                        }
                    }
                    duplicate.add(_proxy.displayName() + " ($existingIndex)")
                } else {
                    uniqueProxyHashes.add(proxyHash)
                    uniqueProxies[proxyHash] = _proxy
                    uniqueNames[proxyHash] = _proxy.displayName()
                }
            }
            uniqueProxies.keys.retainAll(uniqueNames.keys)
            proxies = uniqueProxies.values.toList()
        }

        Logs.d("New profiles: ${proxies.size}")

        val nameMap =
            proxies.associateBy { bean ->
                bean.displayName()
            }

        Logs.d("Unique profiles: ${nameMap.size}")

        val toDelete = ArrayList<ProxyEntity>()
        val toReplace =
            exists
                .mapNotNull { entity ->
                    val name = entity.displayName()
                    if (nameMap.contains(name)) {
                        name to entity
                    } else {
                        let {
                            toDelete.add(entity)
                            null
                        }
                    }
                }.toMap()

        Logs.d("toDelete profiles: ${toDelete.size}")
        Logs.d("toReplace profiles: ${toReplace.size}")

        val toUpdate = ArrayList<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()
        val deleted = toDelete.map { it.displayName() }

        val shouldApplyUpdateOrder =
            proxyGroup.order != GroupOrder.MANUAL &&
                (DataStore.groupOrderModeAlways || DataStore.groupOrderModeUpdate)
        var userOrder = 1L
        var appendedUserOrder = SagerDatabase.proxyDao.nextOrder(proxyGroup.id) ?: 1L
        var changed = toDelete.size
        val originOrderIds = mutableListOf<Long>()
        for ((name, bean) in nameMap.entries) {
            if (toReplace.contains(name)) {
                val entity = toReplace[name]!!
                originOrderIds.add(entity.id)
                val existsBean = entity.requireBean()
                // 更新订阅，保留自定义覆写设置
                bean.customOutboundJson = existsBean.customOutboundJson
                bean.customConfigJson = existsBean.customConfigJson
                preserveMuxSettings(existsBean, bean)
                when {
                    existsBean != bean -> {
                        changed++
                        entity.putBean(bean)
                        toUpdate.add(entity)
                        updated[entity.displayName()] = name

                        Logs.d("Updated profile: $name")
                    }

                    shouldApplyUpdateOrder && entity.userOrder != userOrder -> {
                        entity.putBean(bean)
                        toUpdate.add(entity)
                        entity.userOrder = userOrder

                        Logs.d("Reordered profile: $name")
                    }

                    else -> {
                        Logs.d("Ignored profile: $name")
                    }
                }
            } else {
                changed++
                val profileId =
                    SagerDatabase.proxyDao.addProxy(
                        ProxyEntity(
                            groupId = proxyGroup.id,
                            userOrder = if (shouldApplyUpdateOrder) userOrder else appendedUserOrder++,
                        ).apply {
                            putBean(bean)
                        },
                    )
                originOrderIds.add(profileId)
                added.add(name)
                Logs.d("Inserted profile: $name")
            }
            userOrder++
        }

        SagerDatabase.proxyDao.updateProxy(toUpdate).also {
            Logs.d("Updated profiles: $it")
        }

        SagerDatabase.proxyDao.deleteProxy(toDelete).also {
            Logs.d("Deleted profiles: $it")
        }

        val existCount = SagerDatabase.proxyDao.countByGroup(proxyGroup.id).toInt()

        if (existCount != proxies.size) {
            Logs.e("Exist profiles: $existCount, new profiles: ${proxies.size}")
        }

        subscription.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
        proxyGroup.setOriginOrderIds(originOrderIds)
        SagerDatabase.groupDao.updateGroup(proxyGroup)
        if (
            autoUpdateEnabledFromHeader ||
            (subscription.routingEnabled == true && subscription.autoRoutingUrl.isNotBlank())
        ) {
            SubscriptionUpdater.reconfigureUpdater()
        }

        userInterface?.onUpdateSuccess(
            proxyGroup,
            changed,
            added,
            updated,
            deleted,
            duplicate,
            byUser,
        )
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun parseRaw(
        text: String,
        fileName: String = "",
    ): List<AbstractBean>? {
        val proxies = mutableListOf<AbstractBean>()

        XrayParser.parse(text)?.let { parsed ->
            return parsed.takeIf { it.isNotEmpty() }
        }

        ClashParser.parse(text)?.let { parsed ->
            return parsed.takeIf { it.isNotEmpty() }
        }

        if (WireGuardConfParser.looksLikeWireGuardConf(text)) {
            // AmneziaWG or WireGuard .conf
            try {
                val document = WireGuardConfParser.parse(text)
                proxies.addAll(
                    (if (document.isAmneziaWG) {
                        parseAmneziaWG(document)
                    } else {
                        parseWireGuard(document)
                    }).map {
                        if (fileName.isNotBlank()) it.name = fileName.removeSuffix(".conf")
                        it
                    },
                )
                return proxies
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        try {
            val json = JSONTokener(text).nextValue()
            return parseJSON(json)
        } catch (ignored: Exception) {
        }

        try {
            return parseProxies(text.decodeBase64UrlSafe()).takeIf { it.isNotEmpty() }
                ?: error("Not found")
        } catch (e: Exception) {
            if (e is AmneziaApiKeyUnsupportedException) throw e
            Logs.w(e)
        }

        try {
            return parseProxies(text).takeIf { it.isNotEmpty() } ?: error("Not found")
        } catch (e: Exception) {
            if (e is AmneziaApiKeyUnsupportedException) throw e
        }

        return null
    }

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
        // AWG 3.0 parameters
        iface["HeaderProtectionKey"]?.let { bean.headerProtectionKey = it }
        iface["ContentPaddingAddition"]?.let { bean.contentPaddingAddition = it }
        iface["RekeyAfterTime"]?.let { bean.rekeyAfterTime = it }
        iface["RekeyTimeout"]?.let { bean.rekeyTimeout = it }
        iface["RejectAfterTime"]?.let { bean.rejectAfterTime = it }
        iface["KeepaliveTimeout"]?.let { bean.keepaliveTimeout = it }
        iface["MaxHandshakeAttempts"]?.let { bean.maxHandshakeAttempts = it }
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
                json.getStr("type") == "mieru" -> {
                    return listOfNotNull(json.parseSingBoxMieru())
                }

                json.getStr("type") == "tailscale" -> {
                    return listOfNotNull(json.parseSingBoxTailscale())
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
                            } ?: ConfigBean().apply {
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
