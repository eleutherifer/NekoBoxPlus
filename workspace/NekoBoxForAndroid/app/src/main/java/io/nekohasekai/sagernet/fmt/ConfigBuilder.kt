package io.nekohasekai.sagernet.fmt

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.CustomDnsServerStore
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleType
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.internal.buildSingBoxOutboundProxySetBean
import io.nekohasekai.sagernet.fmt.internal.decodeEmbeddedProfiles
import io.nekohasekai.sagernet.fmt.internal.filterInsecureProfiles
import io.nekohasekai.sagernet.fmt.internal.hasEmbeddedProfiles
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.buildSingBoxOutboundJuicityBean
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.masque.buildSingBoxOutboundMasqueBean
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import io.nekohasekai.sagernet.fmt.masterdns.buildSingBoxOutboundMasterDnsVPNBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildSingBoxOutboundMieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildSingBoxOutboundNaiveBean
import io.nekohasekai.sagernet.fmt.openconnect.OpenConnectBean
import io.nekohasekai.sagernet.fmt.openconnect.buildSingBoxEndpointOpenConnectBean
import io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean
import io.nekohasekai.sagernet.fmt.openvpn.buildSingBoxEndpointOpenVPNBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.buildSingBoxOutboundShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.snell.buildSingBoxOutboundSnellBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.fmt.tailscale.buildSingBoxEndpointTailscaleBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildSingBoxOutboundTrojanGoBean
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.fmt.trusttunnel.buildSingBoxOutboundTrustTunnelBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireguardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointAwgBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.routing.RoutingSettingKind
import io.nekohasekai.sagernet.routing.SubscriptionRoutingPolicy
import io.nekohasekai.sagernet.routing.SubscriptionRoutingRepository
import libcore.Libcore
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.byedpi.ByeDPIBean
import moe.matsuri.nb4a.proxy.byedpi.buildSingBoxOutboundByeDPIBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.direct.DirectBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.NGUtil.isPureIpAddress
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val TAG_TUN = "tun-in"
const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"
const val TAG_FRAGMENT = "fragment"
const val TAG_FRAGMENT_EXCLAVE = "fragment-exclave"
const val TAG_BYEDPI_FRAGMENT = "byedpi-fragment"
const val TAG_DNS_HOSTS = "dns-hosts"

const val LOCALHOST = "127.0.0.1"

private fun showConfigToast(
    message: CharSequence,
    duration: Int,
) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(SagerNet.application, message, duration).show()
    }
}

internal fun Rule_DefaultOptions.applyRouteClashMode(rule: RuleEntity) {
    if (rule.clashMode.isNotBlank()) {
        clash_mode = rule.clashMode
    }
}

internal fun resolveActiveClashMode(cachedMode: String, rules: List<RuleEntity>): String {
    if (cachedMode.isBlank() || cachedMode.equals("Rule", ignoreCase = true)) return "Rule"
    return rules.firstOrNull { it.clashMode.equals(cachedMode, ignoreCase = true) }
        ?.clashMode
        ?.takeIf(String::isNotBlank)
        ?: "Rule"
}

internal fun RuleEntity.appliesToClashMode(activeMode: String): Boolean {
    return clashMode.isBlank() || clashMode.equals(activeMode, ignoreCase = true)
}

internal fun RuleEntity.contributesPackagesToTunFilter(activeMode: String): Boolean {
    return packages.isNotEmpty() && outbound != -1L && appliesToClashMode(activeMode)
}

internal fun Rule_DefaultOptions.replaceBlockOutboundWithRejectAction() {
    if (outbound == TAG_BLOCK) {
        outbound = null
        action = "reject"
    }
}

private fun sanitizeDnsEntry(value: String): String = value.filterNot { it.isISOControl() }.trim()

private fun serverHostOf(bean: AbstractBean): String? {
    val fallback = bean.serverAddress?.takeIf { it.isNotBlank() }
    if (bean is ConfigBean) {
        return try {
            val map = gson.fromJson(bean.config, mutableMapOf<String, Any>().javaClass)
            map["server"]?.toString()?.takeIf { it.isNotBlank() } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
    return fallback
}

fun buildConfig(
    proxy: ProxyEntity,
    forTest: Boolean = false,
    forExport: Boolean = false,
    showSubscriptionRoutingUnavailable: Boolean = true,
): ConfigBuildResult {
    RawCustomConfigRenderer.render(
        proxy = proxy,
        forTest = forTest,
        dnsDefaults = UrlTestDnsDefaults(
            addresses = DataStore.remoteDns.lineSequence()
                .map(String::trim)
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .toList(),
            deadline = DataStore.remoteDnsDeadline,
            strategy = SingBoxOptionsUtil.domainStrategy("dns-remote").takeIf(String::isNotBlank),
        ),
    )?.let { return it }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val tagPlanner =
        OutboundTagPlanner(
            setOf(
                TAG_DIRECT,
                TAG_BYPASS,
                TAG_BLOCK,
                TAG_FRAGMENT,
                TAG_FRAGMENT_EXCLAVE,
                TAG_BYEDPI_FRAGMENT,
                TAG_MIXED,
                TAG_PROXY,
            ),
        )
    val group = AppData.groups.getById(proxy.groupId)
    val subscriptionRouting =
        group?.subscription
            ?.takeIf { group.type == GroupType.SUBSCRIPTION && it.routingEnabled == true }
            ?.let(SubscriptionRoutingRepository::stored)
            ?.takeIf { SubscriptionRoutingRepository.assetsReady(proxy.groupId) }
            ?.let { SubscriptionRoutingPolicy(it.candidate()) }
    if (
        !forTest &&
        showSubscriptionRoutingUnavailable &&
        group?.type == GroupType.SUBSCRIPTION &&
        group.subscription?.routingEnabled == true &&
        subscriptionRouting == null
    ) {
        showConfigToast(
            SagerNet.application.getText(R.string.subscription_routing_unavailable),
            Toast.LENGTH_LONG,
        )
    }
    val frontProxy = group?.frontProxy?.let(AppData.profiles::getById)
    val landingProxy = group?.landingProxy?.let(AppData.profiles::getById)
    val groupForceUTLS = group?.forceUTLS?.takeIf { it.isNotBlank() }
    val trafficFragmentation = DataStore.trafficFragmentation
    val trafficFragmentationTag =
        when (trafficFragmentation) {
            TrafficFragmentation.STARIFLY -> TAG_FRAGMENT
            TrafficFragmentation.EXCLAVE -> TAG_FRAGMENT_EXCLAVE
            TrafficFragmentation.BYEDPI -> TAG_BYEDPI_FRAGMENT
            else -> null
        }

    fun shouldApplyTrafficFragmentation(
        outbound: SingBoxOption,
        bean: AbstractBean,
    ): Boolean =
        isTrafficFragmentationEligible(
            trafficFragmentation,
            DataStore.exclaveFragmentMethod,
            outbound,
            bean,
        )

    val profileResolver = ProfileChainResolver(
        profiles = AppData.profiles,
        allowInsecure = DataStore.globalAllowInsecure,
        frontProxy = frontProxy,
        landingProxy = landingProxy,
    )
    profileResolver.validateByeDpiPlacement(proxy, "chains")
    frontProxy?.let {
        if (!profileResolver.startsWithByeDpi(it) && profileResolver.containsByeDpi(it)) {
            error("ByeDPI must be the first profile in front proxy chains")
        }
    }
    if (landingProxy?.let(profileResolver::containsByeDpi) == true) {
        error("ByeDPI is not allowed as landing proxy")
    }

    val selectedGroupProfileIds = profileResolver.selectedGroupProfileIds(group)
    val groupForceUTLSProfileIds = if (groupForceUTLS == null) emptySet() else selectedGroupProfileIds

    fun AbstractBean.allowsUTLS(): Boolean =
        when (this) {
            is StandardV2RayBean -> security == "tls" || security == "reality"
            is AnyTLSBean -> true
            else -> false
        }

    fun SingBoxOption.applyGroupForceUTLS(
        bean: AbstractBean,
        proxyEntity: ProxyEntity,
        enabled: Boolean,
    ) {
        val fingerprint = groupForceUTLS ?: return
        if (!enabled || proxyEntity.id !in groupForceUTLSProfileIds || !bean.allowsUTLS()) return
        val tls =
            try {
                javaClass.getField("tls").get(this) as? OutboundTLSOptions
            } catch (_: Exception) {
                null
            } ?: return
        tls.utls =
            OutboundUTLSOptions().apply {
                this.enabled = true
                this.fingerprint = fingerprint
            }
    }

    val extraRules = when {
        forTest -> listOf()
        subscriptionRouting != null -> subscriptionRouting.rules()
        else -> AppData.rules.enabledRules()
    }
    val singBoxCachePath = subscriptionRouting?.let {
        SubscriptionRoutingRepository.singBoxCacheFile(proxy.groupId).absolutePath
    } ?: Param.LIBCORE_CACHE_FILE_PATH
    val activeClashMode =
        if (!forTest && !forExport) {
            val cachedMode =
                runCatching { Libcore.loadClashModeFromCache(singBoxCachePath) }
                    .onFailure(Logs::w)
                    .getOrDefault("")
            resolveActiveClashMode(cachedMode, extraRules)
        } else {
            "Rule"
        }
    val customDnsServers =
        if (forTest || subscriptionRouting != null) listOf() else CustomDnsServerStore.enabledServers()
    val customDnsServerTags = customDnsServers.map { it.tag }.toSet()
    val extraProxies =
        if (forTest) {
            mapOf()
        } else {
            AppData.profiles
                .getEntities(
                    extraRules
                        .mapNotNull { rule ->
                            rule.outbound.takeIf { it > 0 && it != proxy.id }
                        }.toHashSet()
                        .toList(),
                ).associateBy { it.id }
        }
    extraProxies.values.forEach { profileResolver.validateByeDpiPlacement(it, "route outbounds") }
    val masqueDetourBuildStack = LinkedHashSet<Long>()
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val endpointBootstrapDNS = LinkedHashMap<String, String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val perGroupResolver = HashMap<Long, String>()
    val perGroupServerHosts = HashMap<Long, MutableSet<String>>()
    val hostResolvers = HashMap<String, MutableSet<String>>()
    val nonCustomFinalHosts = hashSetOf<String>()
    var pushedDnsDefaultTag: String? = null
    val groupCache = HashMap<Long, ProxyGroup?>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val udpNatSettings =
        UdpNatSettings.fromPreferences(
            mapping = DataStore.udpNatMapping,
            filtering = DataStore.udpNatFiltering,
            maxSessions = DataStore.udpNatMax,
        )
    val bind =
        when {
            !forTest && DataStore.allowAccess -> "0.0.0.0"
            isPureIpAddress(DataStore.mixedListener) -> DataStore.mixedListener
            else -> LOCALHOST
        }
    val dnsPlan = ConfigDnsPlanner.plan(
        preferences = ConfigDnsPreferences(
            remoteDns = DataStore.remoteDns,
            directDns = DataStore.directDns,
            domainOverrides = DataStore.dnsDomainOverrides,
            fakeDns = DataStore.enableFakeDns,
            resolveDestination = DataStore.resolveDestination,
            ipv6Mode = DataStore.ipv6Mode,
        ),
        routingOverrides = RoutingSettingKind.entries.mapNotNull { kind ->
            subscriptionRouting?.setting(kind)?.value?.let { kind to it }
        }.toMap(),
        forTest = forTest,
    )
    val remoteDns = dnsPlan.remoteServers
    val directDNS = dnsPlan.directServers
    val dnsDomainOverrides = dnsPlan.domainOverrides
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = dnsPlan.useFakeDns
    val needSniff = DataStore.trafficSniffing > 0
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = dnsPlan.ipv6Mode

    return MyOptions()
        .apply {
            if (!forTest) {
                experimental =
                    ExperimentalOptions().apply {
                        cache_file =
                            CacheFile().apply {
                                enabled = true
                                path = singBoxCachePath
                                // if (DataStore.enableClashAPI) {
                                store_fakeip = true
                                store_dns = DataStore.dnsStoreCache.takeIf { it }
                                // }
                            }

                        clash_api =
                            ClashAPIOptions().apply {
                                if (DataStore.enableClashAPI) {
                                    external_controller =
                                        when (DataStore.hideClashApi) {
                                            true -> "unix://../cache/${DataStore.CLASH_API_SOCKET_NAME}"
                                            false -> "${DataStore.CLASH_API_HOST}:${DataStore.CLASH_API_PORT}"
                                        }
                                    external_ui = DataStore.CLASH_API_EXTERNAL_UI
                                    external_ui_download_url = DataStore.CLASH_API_EXTERNAL_UI_DOWNLOAD_URL
                                    secret = DataStore.clashApiSecret
                                }
                            }
                    }
            }

            log =
                LogOptions().apply {
                    level =
                        AppLogLevel.fromPreferenceValue(DataStore.logLevel).singBoxName
                }

            dns =
                DNSOptions().apply {
                    servers = mutableListOf()
                    rules = mutableListOf()
                    disable_cache = DataStore.dnsDisableCache.takeIf { it }
                    disable_expire = DataStore.dnsDisableExpire.takeIf { it }
                    cache_capacity = DataStore.dnsCacheCapacity.takeIf { it >= 1024 }
                    timeout = DataStore.dnsTimeout.takeIf { it.isNotBlank() }
                    if (DataStore.dnsOptimisticCache) {
                        optimistic = OptimisticDNSOptions().apply {
                            enabled = true
                            timeout = DataStore.dnsOptimisticTimeout.takeIf { it.isNotBlank() }
                        }
                    }
                    reverse_mapping = DataStore.dnsReverseMapping.takeIf { it }
                }

            val directDnsStrategy =
                dnsStrategyForIpv6Mode(SingBoxOptionsUtil.domainStrategy("dns-direct"), ipv6Mode)
            val remoteDnsStrategy =
                dnsStrategyForIpv6Mode(SingBoxOptionsUtil.domainStrategy("dns-remote"), ipv6Mode)
            val serverDnsStrategy =
                dnsStrategyForIpv6Mode(SingBoxOptionsUtil.domainStrategy("server"), ipv6Mode)

            inbounds = mutableListOf()

            if (!forTest) {
                if (isVPN) {
                    inbounds.add(
                        Inbound_TunOptions().apply {
                            type = "tun"
                            tag = TAG_TUN
                            interface_name = "tun0"
                            stack =
                                when (DataStore.tunImplementation) {
                                    TunImplementation.GVISOR -> "gvisor"
                                    TunImplementation.SYSTEM -> "system"
                                    else -> "mixed"
                                }
                            applyUdpNatSettings(udpNatSettings)
                            mtu = DataStore.mtu
                            auto_route = true
                            strict_route = DataStore.strictRoute
                            // The control subnet is a single /30 for IPv4 and /126 for IPv6 so
                            // the sing-box TUN address and the Android VpnService address agree
                            // on exactly one prefix (previously /28 here vs /30 in VpnService).
                            address =
                                when (ipv6Mode) {
                                    IPv6Mode.DISABLE -> {
                                        listOf(TunAddresses.INET4_CLIENT + "/30")
                                    }
                                    IPv6Mode.ONLY -> {
                                        listOf(TunAddresses.INET6_CLIENT + "/126")
                                    }

                                    else -> {
                                        listOf(
                                            TunAddresses.INET4_CLIENT + "/30",
                                            TunAddresses.INET6_CLIENT + "/126",
                                        )
                                    }
                                }
                            dns_mode = "hijack"
                            dns_address =
                                tunDnsAddressesForIpv6Mode(
                                    ipv6Mode,
                                    TunAddresses.INET4_ROUTER,
                                    TunAddresses.INET6_ROUTER,
                                )
                            // Move the bypass-LAN route policy into the generated TUN configuration
                            // so sing-box's effective TUN options (BuildAutoRouteRanges) are
                            // authoritative: the Android side applies exactly what the core
                            // computed instead of re-deriving it from DataStore.
                            if (subscriptionRouting == null && DataStore.bypassLan) {
                                val publicRoutes =
                                    SagerNet.application
                                        .resources
                                        .getStringArray(R.array.bypass_private_route)
                                        .toList()
                                route_address = buildBypassLanRouteAddress(ipv6Mode, publicRoutes)
                            }
                        },
                    )
                }

                if (!isVPN || DataStore.requireProxyInVPN) {
                    inbounds.add(
                        Inbound_MixedOptions().apply {
                            type = "mixed"
                            tag = TAG_MIXED
                            listen = bind
                            listen_port = DataStore.mixedPort
                            if (DataStore.mixedUsername.isNotBlank() || DataStore.mixedPassword.isNotBlank()) {
                                users =
                                    listOf(
                                        User().apply {
                                            username = DataStore.mixedUsername
                                            password = DataStore.mixedPassword
                                        },
                                    )
                            }
                        },
                    )
                }
            }

            outbounds = mutableListOf()
            endpoints = mutableListOf()

            // init routing object
            route =
                RouteOptions().apply {
                    auto_detect_interface = true
                    // Android VPN chaining is intentionally unsupported: the default-network
                    // tracker only ever selects a non-VPN physical underlay, so emitting
                    // override_android_vpn here would be misleading.
                    rules = mutableListOf()
                    rule_set = mutableListOf()
                }

            // returns outbound tag
            @Suppress("UNCHECKED_CAST")
            fun buildChain(
                chainId: Long,
                entity: ProxyEntity,
                applyGroupForceUTLS: Boolean,
                includeGroupProxyChain: Boolean,
            ): String {
                val profileList =
                    if (includeGroupProxyChain) {
                        profileResolver.resolve(entity)
                    } else {
                        profileResolver.run { entity.resolveInternal() }
                    }
                val chainTrafficSet =
                    HashSet<ProxyEntity>().apply {
                        if (entity.type == ProxyEntity.TYPE_CHAIN || entity.type == ProxyEntity.TYPE_PROXY_SET) {
                            add(entity)
                        } else {
                            plusAssign(profileList)
                            add(entity)
                        }
                    }

                var currentOutbound: SingBoxOption
                lateinit var pastOutbound: SingBoxOption
                lateinit var pastInboundTag: String
                var pastEntity: ProxyEntity? = null
                val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
                externalIndexMap.add(IndexEntity(externalChainMap))
                val chainOutbounds = ArrayList<SingBoxOption>()
                val outboundsByTag = HashMap<String, SingBoxOption>()
                val mappingInboundTags = HashMap<Long, String>()

                // chainTagOut: v2ray outbound tag for this chain
                var chainTagOut = ""
                val chainTag = "c-$chainId"
                var muxApplied = false
                var pastChainEntity: ProxyEntity? = null

                val defaultServerDomainStrategy = serverDnsStrategy.orEmpty()
                val isProxySet = entity.type == ProxyEntity.TYPE_PROXY_SET

                fun ProxyEntity.resolveProxySetMembers(): List<ProxyEntity> {
                    if (type != ProxyEntity.TYPE_PROXY_SET) return emptyList()
                    val chain = profileResolver.run { this@resolveProxySetMembers.resolveInternal() }
                    return if (chain.isEmpty()) emptyList() else chain.dropLast(1)
                }

                val reservedTags = HashMap<Long, String>()

                fun reserveTag(proxyEntity: ProxyEntity): String {
                    reservedTags[proxyEntity.id]?.let { return it }
                    val tag = tagPlanner.readable(proxyEntity.displayName())
                    reservedTags[proxyEntity.id] = tag
                    return tag
                }

                fun SingBoxOption.setDetour(tag: String) {
                    // A profile-local ByeDPI outbound is already the fragmentation layer.
                    if (optionType() != "byedpi") {
                        setGeneratedOptionField("detour", tag)
                    }
                }

                fun connectDetouredEndpointDomain(
                    proxyEntity: ProxyEntity,
                    outbound: SingBoxOption,
                    detourTag: String,
                ) {
                    outbound.setDetour(detourTag)
                    val bean = proxyEntity.requireBean()
                    if ((bean is WireGuardBean || bean is AmneziaWGBean) && !bean.serverAddress.isIpAddress()) {
                        val resolverTag = "dns-bootstrap-${endpointBootstrapDNS.size + 1}"
                        endpointBootstrapDNS[resolverTag] = detourTag
                        outbound.setGeneratedOptionField(
                            "domain_resolver",
                            buildDomainResolverConfig(
                                resolverTag,
                                defaultServerDomainStrategy,
                            ),
                        )
                    }
                }

                fun buildMasqueProfileDetour(
                    proxyEntity: ProxyEntity,
                    bean: MasqueBean,
                ): String? {
                    val detourId = bean.profileDetour ?: 0L
                    if (detourId <= 0L) return TAG_DIRECT
                    if (detourId == proxyEntity.id) {
                        error("MASQUE profile detour cannot reference itself")
                    }
                    tagMap[detourId]?.let { return it }
                    if (!masqueDetourBuildStack.add(detourId)) {
                        error("MASQUE profile detour cycle detected")
                    }
                    val detourProfile =
                        AppData.profiles.getById(detourId)
                            ?: error("MASQUE profile detour not found: $detourId")
                    profileResolver.validateByeDpiPlacement(detourProfile, "MASQUE profile detour")
                    val detourTag = buildChain(detourId, detourProfile, false, false)
                    tagMap[detourId] = detourTag
                    masqueDetourBuildStack.remove(detourId)
                    return detourTag
                }

                val proxySetMemberIds =
                    LinkedHashSet<Long>().apply {
                        for (proxyEntity in profileList) {
                            if (proxyEntity.requireBean() is ProxySetBean) {
                                for (member in proxyEntity.resolveProxySetMembers()) {
                                    add(member.id)
                                }
                            }
                        }
                    }
                val hasProxySet = proxySetMemberIds.isNotEmpty()

                fun connectChainNode(
                    previousEntity: ProxyEntity,
                    currentTag: String,
                ) {
                    if (previousEntity.requireBean() is ProxySetBean) {
                        for (member in previousEntity.resolveProxySetMembers()) {
                            val memberTag = checkNotNull(reservedTags[member.id])
                            outboundsByTag[memberTag]?.let {
                                connectDetouredEndpointDomain(member, it, currentTag)
                            }
                        }
                        return
                    }
                    if (previousEntity.needExternal()) {
                        route.rules.add(
                            Rule_DefaultOptions().apply {
                                inbound = listOf(checkNotNull(mappingInboundTags[previousEntity.id]))
                                outbound = currentTag
                            },
                        )
                    } else {
                        val previousTag = checkNotNull(reservedTags[previousEntity.id])
                        outboundsByTag[previousTag]?.let {
                            connectDetouredEndpointDomain(previousEntity, it, currentTag)
                        }
                    }
                }

                profileList.forEachIndexed { index, proxyEntity ->
                    val bean = proxyEntity.requireBean()
                    var currentIsEndpoint = false
                    val isProxySetMember = proxySetMemberIds.contains(proxyEntity.id) && bean !is ProxySetBean
                    val isChainNode = !isProxySetMember

                    // tagOut: v2ray outbound tag for a profile
                    // profile2 (in) (global)   tag g-(id)
                    // profile1                 tag (chainTag)-(id)
                    // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                    var tagOut = if (hasProxySet) reserveTag(proxyEntity) else "$chainTag-${proxyEntity.id}"

                    // needGlobal: can only contain one?
                    var needGlobal = false

                    // first profile set as global
                    if (!hasProxySet && index == profileList.lastIndex) {
                        needGlobal = true
                        tagOut = "g-" + proxyEntity.id
                        bypassDNSBeans += proxyEntity.requireBean()

                        if (!forTest) {
                            val ownerGid = entity.groupId
                            val ownerGroup =
                                groupCache.getOrPut(ownerGid) {
                                    AppData.groups.getById(ownerGid)
                                }
                            val resolver =
                                ownerGroup
                                    ?.takeIf { it.type == GroupType.SUBSCRIPTION }
                                    ?.subscription
                                    ?.serverDnsResolver
                                    ?.let { sanitizeDnsEntry(it) }
                                    ?.takeIf { it.isNotBlank() }

                            if (resolver != null) {
                                profileList.forEach { hop ->
                                    val host = serverHostOf(hop.requireBean())
                                    if (host != null && !host.isIpAddress()) {
                                        if (hop.groupId == ownerGid) {
                                            perGroupResolver[ownerGid] = resolver
                                            perGroupServerHosts
                                                .getOrPut(ownerGid) { mutableSetOf() }
                                                .add(host)
                                            hostResolvers.getOrPut(host) { mutableSetOf() }.add(resolver)
                                        } else {
                                            nonCustomFinalHosts.add(host)
                                        }
                                    }
                                }
                            } else {
                                profileList.forEach { hop ->
                                    val host = serverHostOf(hop.requireBean())
                                    if (host != null && !host.isIpAddress()) {
                                        nonCustomFinalHosts.add(host)
                                    }
                                }
                            }
                        }
                    }

                    if (!hasProxySet && index == 0) {
                        tagOut = tagPlanner.readable(bean.displayName())
                    }

                    // Resolve a reused tag before linking the chain. The provisional g-* tag
                    // is not emitted when this profile already exists as a global outbound.
                    val globalOutboundTag =
                        if (needGlobal) {
                            tagPlanner.resolveGlobal(proxyEntity.id, tagOut)
                                .also { tagOut = it.tag }
                        } else {
                            null
                        }

                    // chain rules
                    if (!isProxySet) {
                        if (hasProxySet) {
                            if (isChainNode) {
                                if (pastChainEntity != null) {
                                    connectChainNode(pastChainEntity, tagOut)
                                } else {
                                    chainTagOut = tagOut
                                }
                            }
                        } else {
                            if (index > 0) {
                                // chain route/proxy rules
                                if (pastEntity!!.needExternal()) {
                                    route.rules.add(
                                        Rule_DefaultOptions().apply {
                                            inbound = listOf(pastInboundTag)
                                            outbound = tagOut
                                        },
                                    )
                                } else {
                                    connectDetouredEndpointDomain(pastEntity, pastOutbound, tagOut)
                                }
                            } else {
                                // index == 0 means last profile in chain / not chain
                                chainTagOut = tagOut
                            }
                        }
                    }

                    // now tagOut is determined
                    if (globalOutboundTag?.reused == true) {
                        if (index == 0) chainTagOut = tagOut // single, duplicate chain
                        return@forEachIndexed
                    }

                    if (proxyEntity.needExternal()) { // externel outbound
                        val localPort = mkPort()
                        externalChainMap[localPort] = proxyEntity
                        currentOutbound =
                            Outbound_SocksOptions().apply {
                                type = "socks"
                                server = LOCALHOST
                                server_port = localPort
                            }
                    } else {
                        // internal outbound

                        currentOutbound =
                            when (bean) {
                                is ConfigBean -> {
                                    CustomSingBoxOption(bean.config) as SingBoxOption
                                }

                                is DirectBean -> {
                                    Outbound().apply { type = "direct" }
                                }

                                is ShadowTLSBean -> {
                                    // before StandardV2RayBean
                                    buildSingBoxOutboundShadowTLSBean(bean)
                                }

                                is StandardV2RayBean -> {
                                    // http/trojan/vmess/vless
                                    buildSingBoxOutboundStandardV2RayBean(bean)
                                }

                                is HysteriaBean -> {
                                    buildSingBoxOutboundHysteriaBean(bean)
                                }

                                is TrojanGoBean -> {
                                    buildSingBoxOutboundTrojanGoBean(bean)
                                }

                                is MieruBean -> {
                                    buildSingBoxOutboundMieruBean(bean)
                                }

                                is TuicBean -> {
                                    buildSingBoxOutboundTuicBean(bean)
                                }

                                is JuicityBean -> {
                                    buildSingBoxOutboundJuicityBean(bean)
                                }

                                is TrustTunnelBean -> {
                                    buildSingBoxOutboundTrustTunnelBean(bean)
                                }

                                is MasqueBean -> {
                                    buildSingBoxOutboundMasqueBean(bean, buildMasqueProfileDetour(proxyEntity, bean))
                                }

                                is MasterDnsVPNBean -> {
                                    buildSingBoxOutboundMasterDnsVPNBean(bean, proxyEntity.id)
                                }

                                is ByeDPIBean -> {
                                    buildSingBoxOutboundByeDPIBean(bean)
                                }

                                is SOCKSBean -> {
                                    buildSingBoxOutboundSocksBean(bean)
                                }

                                is NaiveBean -> {
                                    buildSingBoxOutboundNaiveBean(bean)
                                }

                                is ShadowsocksBean -> {
                                    buildSingBoxOutboundShadowsocksBean(bean)
                                }

                                is ShadowsocksRBean -> {
                                    buildSingBoxOutboundShadowsocksRBean(bean)
                                }

                                is WireGuardBean -> {
                                    // WireGuard is now an endpoint in sing-box 1.13+
                                    val wgEndpoint =
                                        buildSingBoxEndpointWireguardBean(bean).apply {
                                            tag = tagOut
                                            type = "wireguard"
                                            applyUdpNatSettings(udpNatSettings)
                                        }
                                    currentIsEndpoint = true
                                    endpoints!!.add(wgEndpoint)
                                    wgEndpoint
                                }

                                is TailscaleBean -> {
                                    val tailscaleEndpoint =
                                        buildSingBoxEndpointTailscaleBean(bean, proxyEntity.id).apply {
                                            tag = tagOut
                                        }
                                    currentIsEndpoint = true
                                    endpoints!!.add(tailscaleEndpoint)
                                    if (bean.magicDNS == true) {
                                        val dnsTag = "tailscale-dns-${proxyEntity.id}"
                                        dns.servers.add(
                                            TailscaleDNSServerOptions().apply {
                                                type = "tailscale"
                                                tag = dnsTag
                                                endpoint = tagOut
                                                accept_default_resolvers = true
                                            },
                                        )
                                        dns.rules.add(
                                            0,
                                            DNSRule_DefaultOptions().apply {
                                                server = dnsTag
                                            },
                                        )
                                    }
                                    tailscaleEndpoint
                                }

                                is OpenVPNBean -> {
                                    val endpoint = buildSingBoxEndpointOpenVPNBean(bean).apply {
                                        tag = tagOut
                                        applyUdpNatSettings(udpNatSettings)
                                    }
                                    currentIsEndpoint = true
                                    endpoints!!.add(endpoint)
                                    if (!forTest && bean.usePushedDNS) {
                                        val dnsTag = "openvpn-dns-${proxyEntity.id}"
                                        dns.servers.add(
                                            OpenVPNDNSServerOptions().apply {
                                                type = "openvpn"
                                                tag = dnsTag
                                                this.endpoint = tagOut
                                                accept_default_resolvers = bean.acceptPushedDefaultResolvers.takeIf { it }
                                                accept_search_domain = bean.expandPushedSearchDomains.takeIf { it }
                                            },
                                        )
                                        dns.rules.add(
                                            DNSRule_DefaultOptions().apply {
                                                preferred_by = listOf(dnsTag)
                                                server = dnsTag
                                            },
                                        )
                                        if (entity.id == proxy.id && bean.acceptPushedDefaultResolvers && pushedDnsDefaultTag == null) {
                                            pushedDnsDefaultTag = dnsTag
                                        }
                                    }
                                    endpoint
                                }

                                is OpenConnectBean -> {
                                    val endpoint = buildSingBoxEndpointOpenConnectBean(bean).apply {
                                        tag = tagOut
                                        applyUdpNatSettings(udpNatSettings)
                                    }
                                    currentIsEndpoint = true
                                    endpoints!!.add(endpoint)
                                    if (!forTest && bean.usePushedDNS) {
                                        val dnsTag = "openconnect-dns-${proxyEntity.id}"
                                        dns.servers.add(
                                            OpenConnectDNSServerOptions().apply {
                                                type = "openconnect"
                                                tag = dnsTag
                                                this.endpoint = tagOut
                                                accept_default_resolvers = bean.acceptPushedDefaultResolvers.takeIf { it }
                                                accept_search_domain = bean.expandPushedSearchDomains.takeIf { it }
                                            },
                                        )
                                        dns.rules.add(
                                            DNSRule_DefaultOptions().apply {
                                                preferred_by = listOf(dnsTag)
                                                server = dnsTag
                                            },
                                        )
                                        if (entity.id == proxy.id && bean.acceptPushedDefaultResolvers && pushedDnsDefaultTag == null) {
                                            pushedDnsDefaultTag = dnsTag
                                        }
                                    }
                                    endpoint
                                }

                                is AmneziaWGBean -> {
                                    val awgEndpoint = buildSingBoxEndpointAwgBean(bean)
                                    awgEndpoint.tag = tagOut
                                    currentIsEndpoint = true
                                    endpoints!!.add(awgEndpoint)
                                    awgEndpoint
                                }

                                is SSHBean -> {
                                    buildSingBoxOutboundSSHBean(bean)
                                }

                                is AnyTLSBean -> {
                                    buildSingBoxOutboundAnyTLSBean(bean)
                                }

                                is SnellBean -> {
                                    buildSingBoxOutboundSnellBean(bean)
                                }

                                is ProxySetBean -> {
                                    val memberTags = LinkedHashMap<Long, String>()
                                    for (member in proxyEntity.resolveProxySetMembers()) {
                                        val memberTag = reserveTag(member)
                                        if (memberTag != tagOut) {
                                            memberTags[member.id] = memberTag
                                        }
                                    }
                                    buildSingBoxOutboundProxySetBean(bean, memberTags)
                                }

                                else -> {
                                    throw IllegalStateException("can't reach")
                                }
                            }

                        currentOutbound.applyGroupForceUTLS(bean, proxyEntity, applyGroupForceUTLS)
                        currentOutbound.applyConfiguredDialOptions(
                            bean,
                            DataStore.globalTcpFastOpen,
                            DataStore.globalTcpMultiPath,
                            DataStore.globalUdpFragment,
                        )

                        // internal mux
                        if (bean !is ProxySetBean) {
                            val muxApplication =
                                resolveMuxApplication(proxyEntity, muxApplied) { groupId ->
                                    groupCache.getOrPut(groupId) {
                                        AppData.groups.getById(groupId)
                                    }
                                }
                            if (muxApplication != null) {
                                if (muxApplication.consumesProfileMuxSlot) {
                                    muxApplied = true
                                }
                                currentOutbound.setGeneratedOptionField(
                                    "multiplex",
                                    muxApplication.options,
                                )
                            }
                        }

                        if (needGlobal && shouldApplyTrafficFragmentation(currentOutbound, bean)) {
                            currentOutbound.setDetour(checkNotNull(trafficFragmentationTag))
                        }
                    }

                    // internal & external
                    currentOutbound.apply {
                        // udp over tcp
                        try {
                            val sUoT = bean.javaClass.getField("sUoT").get(bean)
                            if (sUoT is Boolean && sUoT) {
                                setGeneratedOptionField(
                                    "udp_over_tcp",
                                    UDPOverTCPOptions().apply { enabled = true },
                                )
                            }
                        } catch (_: Exception) {
                        }

                        // domain_strategy
                        pastEntity?.requireBean()?.apply {
                            // don't loopback
                            if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                                domainListDNSDirectForce.add("full:$serverAddress")
                            }
                        }
                        if (bean !is ProxySetBean && bean !is MasterDnsVPNBean) {
                            setGeneratedOptionField(
                                "domain_resolver",
                                buildProfileServerDomainResolverConfig(
                                    forTest,
                                    remoteDnsStrategy.orEmpty(),
                                    defaultServerDomainStrategy,
                                ),
                            )
                        }

                        setGeneratedOptionField("tag", tagOut)

                        _hack_custom_config = bean.customOutboundJson
                    }

                    // External proxy need a dokodemo-door inbound to forward the traffic
                    // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                    bean.finalAddress = bean.serverAddress
                    bean.finalPort = bean.serverPort
                    if (bean.canMapping() && proxyEntity.needExternal()) {
                        // With ss protect, don't use mapping
                        var needExternal = true
                        if (index == profileList.lastIndex) {
                            val pluginId =
                                when (bean) {
                                    is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
                                    else -> ""
                                }
                            if (Plugins.isUsingMatsuriExe(pluginId)) {
                                needExternal = false
                            } else if (Plugins.getPluginExternal(pluginId) != null) {
                                throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                            }
                        }
                        if (needExternal) {
                            val mappingPort = mkPort()
                            bean.finalAddress = LOCALHOST
                            bean.finalPort = mappingPort

                            inbounds.add(
                                Inbound_DirectOptions().apply {
                                    type = "direct"
                                    listen = LOCALHOST
                                    listen_port = mappingPort
                                    tag = "$chainTag-mapping-${proxyEntity.id}"

                                    override_address = bean.serverAddress
                                    override_port = bean.serverPort

                                    pastInboundTag = tag
                                    mappingInboundTags[proxyEntity.id] = tag

                                    // no chain rule and not outbound, so need to set to direct
                                    if (index == profileList.lastIndex) {
                                        if (shouldApplyTrafficFragmentation(currentOutbound, bean)) {
                                            route.rules.add(
                                                Rule_DefaultOptions().apply {
                                                    network = listOf("tcp")
                                                    inbound = listOf(tag)
                                                    outbound = checkNotNull(trafficFragmentationTag)
                                                },
                                            )
                                        }

                                        route.rules.add(
                                            Rule_DefaultOptions().apply {
                                                inbound = listOf(tag)
                                                outbound = TAG_DIRECT
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    }

                    // Endpoint entries participate in chaining, but are emitted separately.
                    if (!currentIsEndpoint) {
                        outbounds!!.add(currentOutbound)
                    }
                    chainOutbounds.add(currentOutbound)
                    outboundsByTag[tagOut] = currentOutbound
                    pastOutbound = currentOutbound
                    pastEntity = proxyEntity
                    if (!isProxySet && isChainNode) {
                        pastChainEntity = proxyEntity
                    }
                }

                if (isProxySet) {
                    val chainNodes =
                        profileList.filter { proxyEntity ->
                            val bean = proxyEntity.requireBean()
                            !proxySetMemberIds.contains(proxyEntity.id) || bean is ProxySetBean
                        }
                    if (chainNodes.isNotEmpty()) {
                        chainTagOut = checkNotNull(reservedTags[chainNodes.first().id])
                        for (nodeIndex in 1 until chainNodes.size) {
                            val currentTag = checkNotNull(reservedTags[chainNodes[nodeIndex].id])
                            connectChainNode(chainNodes[nodeIndex - 1], currentTag)
                        }
                        val lastChainNode = chainNodes.last()
                        if (lastChainNode.requireBean() is ProxySetBean) {
                            for (member in lastChainNode.resolveProxySetMembers()) {
                                val memberTag = checkNotNull(reservedTags[member.id])
                                val memberOutbound = outboundsByTag[memberTag] ?: continue
                                if (shouldApplyTrafficFragmentation(memberOutbound, member.requireBean())) {
                                    memberOutbound.setDetour(checkNotNull(trafficFragmentationTag))
                                }
                            }
                        } else {
                            val lastChainNodeTag = checkNotNull(reservedTags[lastChainNode.id])
                            val lastOutbound = outboundsByTag[lastChainNodeTag]
                            if (lastOutbound != null && shouldApplyTrafficFragmentation(lastOutbound, lastChainNode.requireBean())) {
                                connectChainNode(lastChainNode, checkNotNull(trafficFragmentationTag))
                            }
                        }

                        val proxySetTag = checkNotNull(reservedTags[entity.id])
                        val chunkStart = (outbounds!!.size - profileList.size).coerceAtLeast(0)
                        val proxySetIndex =
                            outbounds!!.indexOfLast { it.optionTag() == proxySetTag }
                        if (proxySetIndex in chunkStart..outbounds!!.lastIndex) {
                            outbounds!!.add(chunkStart, outbounds!!.removeAt(proxySetIndex))
                        }
                    }
                }

                trafficMap[chainTagOut] = chainTrafficSet.toList()
                return chainTagOut
            }

            // build outbounds
            if (buildSelector) {
                val list = group.id.let(AppData.profiles::getByGroup)
                if (list.any(profileResolver::containsByeDpi)) {
                    error("ByeDPI is not allowed in selector groups")
                }
                list.forEach {
                    tagMap[it.id] = buildChain(it.id, it, true, true)
                }
                outbounds.add(
                    0,
                    Outbound_SelectorOptions().apply {
                        type = "selector"
                        tag = TAG_PROXY
                        default_ = tagMap[proxy.id]
                        outbounds = tagMap.values.toList()
                    },
                )
            } else {
                val mainTag = buildChain(0, proxy, true, true)
                tagMap[proxy.id] = mainTag
            }
            // build outbounds from route item
            extraProxies.forEach { (key, p) ->
                val includeGroupProxyChain = p.id in selectedGroupProfileIds && !p.containsByeDPI()
                tagMap[key] = buildChain(key, p, false, includeGroupProxyChain)
            }

            val mainProxyTag = (if (buildSelector) TAG_PROXY else tagMap[proxy.id]) ?: TAG_PROXY
            val mainProxyIsEndpoint =
                endpoints?.any {
                    it.optionTag() == mainProxyTag
                } == true

            // 在应用用户规则之前检查全局模式
            if (forTest) {
                route.final_ = mainProxyTag
            } else if (subscriptionRouting == null && DataStore.globalMode) {
                // 全局模式下的规则处理

                // 绕过内部网络（如果启用）
                if (DataStore.bypassLan) {
                    route.rules.add(
                        Rule_DefaultOptions().apply {
                            ip_cidr =
                                listOf(
                                    "224.0.0.0/3",
                                    "172.16.0.0/12",
                                    "127.0.0.0/8",
                                    "10.0.0.0/8",
                                    "192.168.0.0/16",
                                    "169.254.0.0/16",
                                    "::1/128",
                                    "fc00::/7",
                                    "fe80::/10",
                                )
                            outbound = TAG_DIRECT
                        },
                    )
                }

                route.rules.add(
                    Rule_DefaultOptions().apply {
                        inbound = listOf(TAG_TUN)
                        outbound = mainProxyTag
                    },
                )

                route.rules.add(
                    Rule_DefaultOptions().apply {
                        inbound = listOf(TAG_MIXED)
                        outbound = mainProxyTag
                    },
                )

                route.final_ = mainProxyTag
            } else {
                if (mainProxyIsEndpoint) {
                    // Preserve the historical "selected profile is the implicit default" behavior
                    // when the selected main hop is emitted as an endpoint instead of an outbound.
                    route.final_ = mainProxyTag
                }

                val nonBypassPackages = mutableListOf<String>()

                // 应用用户规则
                for (rule in extraRules) {
                    if (RuleType.fromValue(rule.type) == RuleType.DNS) {
                        val dnsRuleSets = mutableListOf<RuleSet>()
                        val dnsRuleSetTags =
                            if (rule.ruleset.isBlank()) {
                                ""
                            } else {
                                rule.ruleset
                                    .listByLineOrComma()
                                    .map { rawRuleSet ->
                                        when {
                                            rawRuleSet.startsWith("geoip:") || rawRuleSet.startsWith("geosite:") -> {
                                                generateRuleSet(listOf(rawRuleSet), dnsRuleSets)
                                                rawRuleSet
                                            }

                                            else -> {
                                                val (url, _) = processRulesetUrl(rawRuleSet)
                                                generateRemoteRuleSet(url, dnsRuleSets, DataStore.rulesUpdateInterval, mainProxyTag)
                                            }
                                        }
                                    }.joinToString("\n")
                            }
                        buildStandaloneDnsRule(rule.copy(ruleset = dnsRuleSetTags), customDnsServerTags)?.let {
                            userDNSRuleList += it
                            route.rule_set.addAll(dnsRuleSets)
                        }
                        continue
                    }
                    if (rule.packages.isNotEmpty()) {
                        PackageCache.awaitLoadSync()
                    }
                    val uidList =
                        rule.packages
                            .map {
                                if (!isVPN) {
                                    showConfigToast(
                                        SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                                        Toast.LENGTH_SHORT,
                                    )
                                }
                                PackageCache[it]?.takeIf { uid -> uid >= 1000 }
                            }.toHashSet()
                            .filterNotNull()
                    val ruleSets = mutableListOf<RuleSet>()

                    val ruleObj =
                        Rule_DefaultOptions().apply {
                            if (uidList.isNotEmpty()) {
                                PackageCache.awaitLoadSync()
                                user_id = uidList
                            }
                            var domainList: List<String>? = null
                            if (rule.domains.isNotBlank()) {
                                domainList = rule.domains.listByLineOrComma()
                                makeSingBoxRule(domainList, false)
                            }
                            if (rule.ip.isNotBlank()) {
                                makeSingBoxRule(rule.ip.listByLineOrComma(), true)
                            }

                            if (rule_set != null) generateRuleSet(rule_set, ruleSets)

                            // 存储ruleset标签和类型信息
                            val rulesetTags = mutableListOf<Pair<String, Boolean>>()

                            // 处理远程ruleset
                            if (rule.ruleset.isNotBlank()) {
                                val rulesetUrls = rule.ruleset.listByLineOrComma()
                                rulesetUrls.forEach { origUrl ->
                                    val (url, isIPRuleset) = processRulesetUrl(origUrl)

                                    val tag = generateRemoteRuleSet(url, ruleSets, DataStore.rulesUpdateInterval, mainProxyTag)

                                    rulesetTags.add(Pair(tag, isIPRuleset))

                                    rule_set =
                                        (rule_set ?: mutableListOf()).apply {
                                            add(tag)
                                        }
                                }
                            }

                            if (rule.port.isNotBlank()) {
                                port = mutableListOf<Int>()
                                port_range = mutableListOf<String>()
                                rule.port.listByLineOrComma().map {
                                    if (it.contains(":")) {
                                        port_range.add(it)
                                    } else {
                                        it.toIntOrNull()?.apply { port.add(this) }
                                    }
                                }
                            }
                            if (rule.sourcePort.isNotBlank()) {
                                source_port = mutableListOf<Int>()
                                source_port_range = mutableListOf<String>()
                                rule.sourcePort.listByLineOrComma().map {
                                    if (it.contains(":")) {
                                        source_port_range.add(it)
                                    } else {
                                        it.toIntOrNull()?.apply { source_port.add(this) }
                                    }
                                }
                            }
                            if (rule.networkType.isNotEmpty()) {
                                network_type = rule.networkType.toList()
                            }
                            if (RuleEntity.isWifiIdentityVisible(rule.networkType)) {
                                val wifiSsidList = RuleEntity.normalizeWifiSsidList(rule.wifiSsid)
                                if (wifiSsidList.isNotEmpty()) {
                                    wifi_ssid = wifiSsidList
                                }
                                val wifiBssidList = RuleEntity.normalizeWifiBssidList(rule.wifiBssid)
                                if (wifiBssidList.isNotEmpty()) {
                                    wifi_bssid = wifiBssidList
                                }
                            }
                            if (rule.network.isNotBlank()) {
                                network = listOf(rule.network)
                            }
                            if (rule.source.isNotBlank()) {
                                source_ip_cidr = rule.source.listByLineOrComma()
                            }
                            if (rule.protocol.isNotBlank()) {
                                protocol = rule.protocol.listByLineOrComma()
                            }
                            applyRouteClashMode(rule)

                            val createBaseDnsRule =
                                shouldCreateBaseRouteDnsRule(
                                    uidList = uidList,
                                    domainList = domainList,
                                    hasIpCriteria = rule.ip.isNotBlank() || rulesetTags.any { it.second },
                                    hasDomainRuleset = rulesetTags.any { !it.second },
                                    hasOtherRouteCriteria =
                                        rule.port.isNotBlank() ||
                                            rule.sourcePort.isNotBlank() ||
                                            rule.network.isNotBlank() ||
                                            rule.source.isNotBlank() ||
                                            rule.protocol.isNotBlank(),
                                    clashMode = rule.clashMode,
                                )

                            userDNSRuleList +=
                                buildRouteDnsRules(
                                    createDnsRule = rule.createDnsRule,
                                    createBaseDnsRule = createBaseDnsRule,
                                    outbound = rule.outbound,
                                    uidList = uidList,
                                    domainList = domainList,
                                    ruleSet = rule_set,
                                    rulesetTags = rulesetTags,
                                    useFakeDns = useFakeDns,
                                    clashMode = rule.clashMode,
                                )

                            outbound =
                                when (val outId = rule.outbound) {
                                    0L -> mainProxyTag
                                    -1L -> TAG_BYPASS
                                    -2L -> TAG_BLOCK
                                    else -> if (outId == proxy.id) mainProxyTag else tagMap[outId] ?: ""
                                }

                            _hack_custom_config = rule.config
                        }

                    if (!ruleObj.checkEmpty()) {
                        if (ruleObj.outbound.isNullOrBlank()) {
                            showConfigToast(
                                "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                                Toast.LENGTH_LONG,
                            )
                        } else {
                            // block 改用新的写法
                            ruleObj.replaceBlockOutboundWithRejectAction()
                            route.rules.addAll(splitRouteRuleSetSemantics114(ruleObj))
                            route.rule_set.addAll(ruleSets)
                        }
                    }

                    // List of packages from active per-rule routes where outbound is not Bypass.
                    // This excludes those packages from TUN filter rules and enables user-defined rules for them.
                    if (rule.contributesPackagesToTunFilter(activeClashMode)) {
                        nonBypassPackages.addAll(rule.packages)
                    }
                }

                // System per-app enforcement rules: prevent force-bound apps from
                // leaking through the wrong outbound. Only apply them to the TUN
                // inbound so the optional local mixed/SOCKS listener stays usable.
                if (isVPN && DataStore.proxyApps) {
                    // The background process loads PackageCache asynchronously. A service restart
                    // can reach config building before that initial load has completed.
                    PackageCache.awaitLoadSync()
                    val bypassMode = DataStore.bypass
                    val configuredIndividualPackages =
                        DataStore.individual
                            .lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .toSet()
                    val nonBypassPackageSet = nonBypassPackages.toSet()
                    val individualPackages =
                        if (bypassMode) {
                            configuredIndividualPackages
                        } else {
                            configuredIndividualPackages + nonBypassPackageSet
                        }
                    val adblockProxyPackages =
                        if (bypassMode) {
                            (PackageCache.installedPackages.keys.toSet() - configuredIndividualPackages).toSet() + nonBypassPackageSet
                        } else {
                            nonBypassPackageSet + configuredIndividualPackages
                        }
                    val adblockDirectPackages =
                        (
                            DataStore.adblockIncludedPackages
                                .lineSequence()
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .toSet() - adblockProxyPackages
                        ).toSet()

                    val tunDnsMode =
                        when (DataStore.tunSystemDnsTraffic) {
                            "proxy" -> mainProxyTag
                            "direct" -> TAG_BYPASS
                            else -> if (bypassMode) TAG_BYPASS else mainProxyTag
                        }

                    // Explicitly route direct DNS to direct.
                    val directDnsRule = buildDirectDnsRouteRule(dnsPlan.directDns)

                    // Only Android's DNS daemon may bypass the system-traffic policy
                    // for known resolver endpoints. Other apps follow their normal rules.
                    val systemDnsRules =
                        buildTunSystemDnsRouteRules(
                            dnsWhitelist = DataStore.tunDnsWhitelist,
                            dotWhitelist = DataStore.tunDotWhitelist,
                            dohWhitelist = DataStore.tunDohWhitelist,
                            outboundTag = tunDnsMode,
                        )

                    val catchMyself =
                        Rule_DefaultOptions().apply {
                            inbound = listOf(TAG_TUN)
                            package_name = listOf<String>(BuildConfig.APPLICATION_ID)
                            outbound = mainProxyTag
                        }

                    val tunMode = DataStore.tunUnrecognizedTraffic
                    val catchUnmatched =
                        buildTunUnrecognizedTrafficRule(
                            tunMode = tunMode,
                            bypassMode = bypassMode,
                            mainProxyTag = mainProxyTag,
                        )

                    val adblockPackageRule =
                        if (adblockDirectPackages.isNotEmpty()) {
                            Rule_DefaultOptions().apply {
                                inbound = listOf(TAG_TUN)
                                package_name = adblockDirectPackages.toMutableList()
                                outbound = TAG_DIRECT
                            }
                        } else {
                            null
                        }

                    val packageRule =
                        if (individualPackages.isNotEmpty()) {
                            Rule_DefaultOptions().apply {
                                inbound = listOf(TAG_TUN)
                                package_name =
                                    when (bypassMode) {
                                        true -> individualPackages.toMutableList()
                                        false -> null
                                    }
                                package_name_exclude =
                                    when (bypassMode) {
                                        false -> individualPackages.toMutableList()
                                        true -> null
                                    }
                                outbound = TAG_BYPASS
                            }
                        } else {
                            null
                        }

                    val catchAllRule =
                        if (bypassMode) {
                            Rule_DefaultOptions().apply {
                                inbound = listOf(TAG_TUN)
                                package_name_exclude = mutableListOf("none")
                                outbound = mainProxyTag
                            }
                        } else {
                            null
                        }

                    var index = 0

                    route.rules.add(index, catchMyself)
                    index++

                    directDnsRule?.let {
                        route.rules.add(index, it)
                        index++
                    }

                    systemDnsRules.forEach {
                        route.rules.add(index, it)
                        index++
                    }

                    catchUnmatched?.let {
                        route.rules.add(index, it)
                        index++
                    }

                    adblockPackageRule?.let {
                        route.rules.add(index, it)
                        index++
                    }

                    packageRule?.let { route.rules.add(index, it) }
                    catchAllRule?.let { route.rules.add(catchAllRule) }
                }
            }

            // 对 rule_set tag 去重，后出现的定义覆盖前面的同名 tag
            if (route.rule_set != null) {
                route.rule_set =
                    route.rule_set
                        .asReversed()
                        .distinctBy { it.tag }
                        .asReversed()
            }

            fun buildExclaveFragmentOutbound(tagValue: String): Outbound =
                Outbound().apply {
                    tag = tagValue
                    type = "fragment-exclave"
                    when (DataStore.exclaveFragmentMethod) {
                        ExclaveFragmentationMethod.TCP_SEGMENTATION -> {
                            tcp_segmentation = true
                        }

                        ExclaveFragmentationMethod.TLS_RECORD_FRAGMENTATION_AND_TCP_SEGMENTATION -> {
                            tls_record_fragmentation = true
                            tcp_segmentation = true
                        }

                        else -> {
                            tls_record_fragmentation = true
                        }
                    }
                }

            for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) {
                outbounds.add(
                    if (trafficFragmentation == TrafficFragmentation.EXCLAVE &&
                        DataStore.exclaveFragmentForDirect &&
                        freedom == TAG_DIRECT
                    ) {
                        buildExclaveFragmentOutbound(freedom)
                    } else {
                        Outbound().apply {
                            tag = freedom
                            type = "direct"
                        }
                    },
                )
            }

            when (trafficFragmentation) {
                TrafficFragmentation.STARIFLY -> {
                    val fragmentOutbound =
                        Outbound_DirectOptions().apply {
                            tag = TAG_FRAGMENT
                            type = "direct"
                            fragment =
                                Fragment()
                                    .apply {
                                        length = DataStore.fragmentLength
                                        interval = DataStore.fragmentInterval
                                    }
                        }
                    outbounds.add(fragmentOutbound)
                }

                TrafficFragmentation.EXCLAVE -> {
                    outbounds.add(buildExclaveFragmentOutbound(TAG_FRAGMENT_EXCLAVE))
                }

                TrafficFragmentation.BYEDPI -> {
                    outbounds.add(
                        Outbound_ByeDPIOptions().apply {
                            tag = TAG_BYEDPI_FRAGMENT
                            type = "byedpi"
                            cli = DataStore.byedpiFragmentCli
                        },
                    )
                }

                else -> {
                    Unit
                }
            }

            fun isExclusiveCustomHost(host: String): Boolean = hostResolvers[host]?.size == 1 && !nonCustomFinalHosts.contains(host)

            // Bypass Lookup for the first profile
            bypassDNSBeans.forEach {
                var serverAddr = it.serverAddress

                if (it is ConfigBean) {
                    var config = mutableMapOf<String, Any>()
                    config = gson.fromJson(it.config, config.javaClass)
                    config["server"]?.apply {
                        serverAddr = toString()
                    }
                }

                if (!serverAddr.isIpAddress()) {
                    if (!isExclusiveCustomHost(serverAddr)) {
                        domainListDNSDirectForce.add("full:$serverAddr")
                    }
                }
            }

            remoteDns.forEach {
                var address = it
                if (address.contains("://")) {
                    address = address.substringAfter("://")
                }
                "https://$address".toHttpUrlOrNull()?.apply {
                    if (!host.isIpAddress()) {
                        domainListDNSDirectForce.add("full:$host")
                    }
                }
            }

            if (forTest) {
                dns.servers.addAll(
                    buildUrlTestDnsServers(
                        remoteDns,
                        DataStore.remoteDnsDeadline,
                        remoteDnsDetourTag(mainProxyTag, outbounds),
                    ),
                )
            } else {
                dns.servers.add(
                    buildDnsServerOptions(
                        rawAddress = "local",
                        tagValue = "dns-local",
                        detourValue = TAG_DIRECT,
                    ),
                )
                dns.servers.add(
                    buildDnsServerOptions(
                        rawAddresses = directDNS,
                        tagValue = "dns-direct",
                        detourValue = TAG_DIRECT,
                        domainResolver = buildDomainResolverConfig("dns-local"),
                        queryDeadline = DataStore.directDnsDeadline,
                    ),
                )
                dns.servers.add(
                    buildDnsServerOptions(
                        rawAddresses = remoteDns,
                        tagValue = "dns-remote",
                        detourValue = remoteDnsDetourTag(mainProxyTag, outbounds),
                        domainResolver = buildDomainResolverConfig("dns-direct", directDnsStrategy.orEmpty()),
                        queryDeadline = DataStore.remoteDnsDeadline,
                    ),
                )
            }
            endpointBootstrapDNS.forEach { (resolverTag, detourTag) ->
                dns.servers.add(
                    buildEndpointBootstrapDnsServer(
                        rawAddresses = remoteDns,
                        tag = resolverTag,
                        detour = detourTag,
                        forTest = forTest,
                        queryDeadline = DataStore.remoteDnsDeadline,
                        remoteStrategy = remoteDnsStrategy.orEmpty(),
                        directStrategy = directDnsStrategy.orEmpty(),
                    ),
                )
            }

            customDnsServers.forEach {
                dns.servers.add(
                    buildCustomDnsServerOptions(
                        it,
                        customDnsDetourTag(it.detour, mainProxyTag, outbounds),
                    ),
                )
            }

            if (dnsDomainOverrides.isNotEmpty()) {
                dns.servers.add(
                    DNSServerOptions().apply {
                        type = "hosts"
                        tag = TAG_DNS_HOSTS
                        predefined = dnsDomainOverrides
                    },
                )
            }

            dns.final_ = pushedDnsDefaultTag ?: "dns-remote"
            dns.strategy = remoteDnsStrategy

            // dns object user rules
            if (enableDnsRouting) {
                userDNSRuleList.forEach {
                    if (!it.checkEmpty()) dns.rules.add(it)
                }
            }

            if (forTest) {
                dns.rules = listOf()
            } else {
                // built-in DNS rules
                route.rules.add(
                    0,
                    Rule_DefaultOptions().apply {
                        protocol = listOf("dns")
                        action = "hijack-dns"
                    },
                )
                route.rules.add(
                    0,
                    Rule_DefaultOptions().apply {
                        port = listOf(53)
                        action = "hijack-dns"
                    },
                )
                // Migrate legacy inbound sniff/domain_strategy to route rule actions (sing-box 1.13)
                val routeActionInbounds =
                    buildList {
                        if (isVPN) add(TAG_TUN)
                        if (!isVPN || DataStore.requireProxyInVPN) add(TAG_MIXED)
                    }
                val domainStrategyStr =
                    destinationStrategyForIpv6Mode(dnsPlan.resolveDestination, ipv6Mode)
                routeActionInbounds.asReversed().forEach { inboundTag ->
                    if (domainStrategyStr.isNotEmpty()) {
                        route.rules.add(
                            0,
                            Rule_DefaultOptions().apply {
                                inbound = listOf(inboundTag)
                                action = "resolve"
                                strategy = domainStrategyStr
                            },
                        )
                    }
                    if (needSniff) {
                        route.rules.add(
                            0,
                            Rule_DefaultOptions().apply {
                                inbound = listOf(inboundTag)
                                action = "sniff"
                            },
                        )
                    }
                }
                if (isVPN && DataStore.requireProxyInVPN && DataStore.disableUdpForLocalProxy) {
                    route.rules.add(
                        0,
                        Rule_DefaultOptions().apply {
                            inbound = listOf(TAG_MIXED)
                            protocol = listOf("udp")
                            action = "reject"
                        },
                    )
                }
                if (subscriptionRouting == null && DataStore.bypassLanInCore) {
                    route.rules.add(
                        Rule_DefaultOptions().apply {
                            outbound = TAG_BYPASS
                            ip_is_private = true
                        },
                    )
                }
                // block mcast
                route.rules.add(
                    Rule_DefaultOptions().apply {
                        ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                        source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                        action = "reject"
                    },
                )
                // FakeDNS obj
                if (useFakeDns) {
                    dns.servers.add(
                        DNSServerOptions().apply {
                            type = "fakeip"
                            tag = "dns-fake"
                            inet4_range = "198.18.0.0/15"
                            inet6_range = "fc00::/18"
                        },
                    )
                    dns.rules.add(
                        DNSRule_DefaultOptions().apply {
                            inbound = listOf(TAG_TUN)
                            server = "dns-fake"
                            disable_cache = true
                            query_type = listOf("A", "AAAA")
                        },
                    )
                    connectionIpResolveHost(DataStore.connectionIPResolveURL)?.let { host ->
                        dns.rules.add(
                            0,
                            DNSRule_DefaultOptions().apply {
                                inbound = listOf(TAG_TUN)
                                makeSingBoxRule(listOf("full:$host"))
                                server = "dns-remote"
                            },
                        )
                    }
                }
                // force bypass (always top DNS rule)
                if (domainListDNSDirectForce.isNotEmpty()) {
                    dns.rules.add(
                        0,
                        DNSRule_DefaultOptions().apply {
                            makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                            server = "dns-direct"
                        },
                    )
                }
                perGroupResolver.forEach { (gid, resolver) ->
                    val hosts =
                        perGroupServerHosts[gid]
                            ?.filter { it.isNotBlank() && isExclusiveCustomHost(it) }
                            ?.map { "full:$it" }
                    if (hosts.isNullOrEmpty()) return@forEach

                    val serverTag = "dns-sub-$gid"
                    dns.servers.add(
                        buildDnsServerOptions(
                            rawAddress = resolver,
                            tagValue = serverTag,
                            detourValue = TAG_DIRECT,
                            domainResolver = buildDomainResolverConfig("dns-direct", directDnsStrategy.orEmpty()),
                        ),
                    )
                    dns.rules.add(
                        0,
                        DNSRule_DefaultOptions().apply {
                            makeSingBoxRule(hosts)
                            server = serverTag
                        },
                    )
                }

                if (dnsDomainOverrides.isNotEmpty()) {
                    dns.rules.add(
                        0,
                        DNSRule_DefaultOptions().apply {
                            makeSingBoxRule(dnsDomainOverrides.keys.map { "full:$it" })
                            server = TAG_DNS_HOSTS
                            disable_cache = true
                        },
                    )
                }
            }

            applySingBox114Config(mainProxyTag)
            if (!forTest) _hack_custom_config = DataStore.globalCustomConfig
        }.let {
            val configMap = it.asMap()
            Util.mergeJSON(configMap, proxy.requireBean().customConfigJson)
            configMap.sanitizeDNSRules114(
                forcedStrategy = strictDnsStrategyForIpv6Mode(ipv6Mode),
                firstRule = buildAddressFamilyFilterDnsRule(ipv6Mode)?.asMap(),
            )

            if (!forTest && !forExport) {
                buildAdblockOptions()?.let { adblock ->
                    @Suppress("UNCHECKED_CAST")
                    val experimental =
                        configMap.getOrPut("experimental") {
                            linkedMapOf<String, Any>()
                        } as MutableMap<String, Any>
                    experimental["adblock"] = adblock
                }
            }

            if (!forTest && isVPN && (!DataStore.appendHttpProxy && !DataStore.requireProxyInVPN)) {
                @Suppress("UNCHECKED_CAST")
                val inboundsList = configMap["inbounds"] as? MutableList<MutableMap<String, Any?>>
                inboundsList?.removeAll { inbound ->
                    val type = inbound["type"] as? String
                    type != null && type != "tun" && type != "direct"
                }
            }

            ConfigBuildResult(
                gson.toJson(configMap),
                externalIndexMap,
                proxy.id,
                trafficMap,
                tagMap,
                if (buildSelector) group.id else -1L,
                subscriptionRouting?.let {
                    SubscriptionRoutingRepository.assetsDirectory(proxy.groupId).absolutePath
                },
                subscriptionRouting?.let {
                    SubscriptionRoutingRepository.routingRulesCacheFile(proxy.groupId).absolutePath
                },
                singBoxCachePath,
            )
        }
}
