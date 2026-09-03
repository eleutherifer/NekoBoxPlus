package io.nekohasekai.sagernet.database

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Build
import androidx.core.content.edit
import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.CONNECTION_GROUP_TEST_URL
import io.nekohasekai.sagernet.CONNECTION_IP_RESOLVE_URL
import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.CertProvider
import io.nekohasekai.sagernet.CoreProfilerMode
import io.nekohasekai.sagernet.ExclaveFragmentationMethod
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.LogcatRetentionSize
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TrafficFragmentation
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.database.preference.RoomSettingsRepository
import io.nekohasekai.sagernet.database.preference.SettingsRepository
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.boolean
import io.nekohasekai.sagernet.ktx.int
import io.nekohasekai.sagernet.ktx.long
import io.nekohasekai.sagernet.ktx.parsePort
import io.nekohasekai.sagernet.ktx.positiveStringToIntCompat
import io.nekohasekai.sagernet.ktx.string
import io.nekohasekai.sagernet.ktx.stringSet
import io.nekohasekai.sagernet.ktx.stringToInt
import io.nekohasekai.sagernet.utils.SubscriptionTrafficUnit
import io.nekohasekai.sagernet.ktx.stringToIntIfExists
import io.nekohasekai.sagernet.ktx.stringToLong
import io.nekohasekai.sagernet.ui.LegacyMainViewPolicy
import moe.matsuri.nb4a.TempDatabase
import moe.matsuri.nb4a.utils.NGUtil.isPureIpAddress
import moe.matsuri.nb4a.utils.Util
import java.io.File

object DataStore : OnPreferenceDataStoreChangeListener {
    private const val DEVICE_LOCAL_PREFERENCES = "device_local"
    private const val BATTERY_OPTIMIZATION_PROMPT_ASKED = "batteryOptimizationPromptAsked"
    private const val LAST_STARTED_VERSION_CODE = "lastStartedVersionCode"
    const val CLASH_API_HOST = "127.0.0.1"
    const val CLASH_API_DASHBOARD_HOST = "core"
    const val CLASH_API_PORT = 9090
    const val CLASH_API_SOCKET_NAME = "clash-api.socket"
    const val CLASH_API_EXTERNAL_UI = "../files/metacubexd"
    const val CLASH_API_EXTERNAL_UI_DOWNLOAD_URL =
        "https://github.com/MetaCubeX/metacubexd/archive/refs/heads/gh-pages.zip"

    private const val RULES_PROVIDER_MIGRATION_VERSION_KEY = "rulesProviderMigrationVersion"
    private const val RULES_PROVIDER_MIGRATION_VERSION = 1
    const val RULES_PROVIDER_OFFICIAL = 0
    const val RULES_PROVIDER_LOYALSOLDIER = 1
    const val RULES_PROVIDER_IRAN = 2
    const val RULES_PROVIDER_ANTIZAPRET = 3
    const val RULES_PROVIDER_ITDOG = 4
    const val RULES_PROVIDER_CUSTOM = 5
    const val RULES_PROVIDER_V2RAY_DAT = 6
    const val RULES_PROVIDER_RUNETFREEDOM_DAT = 7

    // share service state in main & bg process
    @Volatile
    var serviceState = BaseService.State.Idle

    val configurationStore = RoomPreferenceDataStore(PublicDatabase.kvPairDao)
    val profileCacheStore = RoomPreferenceDataStore(TempDatabase.profileCacheDao)
    val settings: SettingsRepository = RoomSettingsRepository(configurationStore)
    private val deviceLocalPreferences by lazy {
        SagerNet.application.getSharedPreferences(
            DEVICE_LOCAL_PREFERENCES,
            Context.MODE_PRIVATE,
        )
    }

    // Device-local because the battery exemption itself is not restored on another device.
    var batteryOptimizationPromptAsked: Boolean
        get() = deviceLocalPreferences.getBoolean(BATTERY_OPTIMIZATION_PROMPT_ASKED, false)
        set(value) = deviceLocalPreferences.edit {
            putBoolean(BATTERY_OPTIMIZATION_PROMPT_ASKED, value)
        }

    var lastStartedVersionCode: Int?
        get() = if (deviceLocalPreferences.contains(LAST_STARTED_VERSION_CODE)) {
            deviceLocalPreferences.getInt(LAST_STARTED_VERSION_CODE, 0)
        } else {
            null
        }
        set(value) = deviceLocalPreferences.edit(commit = true) {
            if (value == null) {
                remove(LAST_STARTED_VERSION_CODE)
            } else {
                putInt(LAST_STARTED_VERSION_CODE, value)
            }
        }

    // last used, but may not be running
    var currentProfile by configurationStore.long(Key.PROFILE_CURRENT)

    var selectedProxy by configurationStore.long(Key.PROFILE_ID)
    var selectedGroup by configurationStore.long(Key.PROFILE_GROUP) { currentGroupId() } // "ungrouped" group id = 1

    // only in bg process
    var vpnService: VpnService? = null
    var baseService: BaseService.Interface? = null

    // main

    var runningTest = false

    fun currentGroupId(): Long {
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) return currentSelected
        val groups = AppData.groups.allGroups()
        if (groups.isNotEmpty()) {
            val groupId = groups[0].id
            selectedGroup = groupId
            return groupId
        }
        val groupId = AppData.groups.createGroup(ProxyGroup(ungrouped = true))
        selectedGroup = groupId
        return groupId
    }

    fun currentGroup(): ProxyGroup {
        var group: ProxyGroup? = null
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) {
            group = AppData.groups.getById(currentSelected)
        }
        if (group != null) return group
        val groups = AppData.groups.allGroups()
        if (groups.isEmpty()) {
            group =
                ProxyGroup(ungrouped = true).apply {
                    id = AppData.groups.createGroup(this)
                }
        } else {
            group = groups[0]
        }
        selectedGroup = group.id
        return group
    }

    fun selectedGroupForImport(): Long {
        val current = currentGroup()
        val groups = AppData.groups.allGroups()
        val existingTargetId =
            ImportGroupSelectionPolicy.existingTargetGroupId(
                current.toImportGroup(),
                groups.map { it.toImportGroup() },
            )
        if (existingTargetId != null) {
            selectedGroup = existingTargetId
            return existingTargetId
        }
        val group =
            ProxyGroup(ungrouped = true).apply {
                id = AppData.groups.createGroup(this)
            }
        selectedGroup = group.id
        return group.id
    }

    private fun ProxyGroup.toImportGroup(): ImportGroupSelectionPolicy.Group =
        ImportGroupSelectionPolicy.Group(
            id = id,
            type = type,
            ungrouped = ungrouped,
        )

    var appTLSVersion by configurationStore.string(Key.APP_TLS_VERSION)
    var appUTLSFingerprint by configurationStore.string(Key.APP_UTLS_FINGERPRINT)
    var enableClashAPI by configurationStore.boolean(Key.ENABLE_CLASH_API) { true }
    var hideClashApi by configurationStore.boolean(Key.HIDE_CLASH_API) { true }
    var clashApiSecret: String
        get() {
            val current = configurationStore.getString(Key.CLASH_API_SECRET)
            if (!current.isNullOrBlank()) return current
            return Util.generateCryptoSecurePassword(16, Util.securePasswordCharsNoSymbols).also {
                configurationStore.putString(Key.CLASH_API_SECRET, it)
            }
        }
        set(value) = configurationStore.putString(Key.CLASH_API_SECRET, value)
    var confirmProfileDelete by configurationStore.boolean(Key.CONFIRM_PROFILE_DELETE) { true }
    var groupLayoutMode by configurationStore.stringToInt(Key.GROUP_LAYOUT_MODE) { 0 }
    var profileCardBorders by configurationStore.boolean(Key.PROFILE_CARD_BORDERS)
    var groupOrderModeAlways by configurationStore.boolean(Key.GROUP_ORDER_MODE_ALWAYS) { true }
    var groupOrderModeUrlTest by configurationStore.boolean(Key.GROUP_ORDER_MODE_URL_TEST) { true }
    var groupOrderModeUpdate by configurationStore.boolean(Key.GROUP_ORDER_MODE_UPDATE) { true }
    var allowInsecureOnRequest by configurationStore.boolean(Key.ALLOW_INSECURE_ON_REQUEST)
    var networkChangeReconnect by configurationStore.boolean(Key.NETWORK_CHANGE_RECONNECT)
    var networkChangeResetConnections by configurationStore.boolean(Key.NETWORK_CHANGE_RESET_CONNECTIONS) { true }
    var wakeReconnect by configurationStore.boolean(Key.WAKE_RECONNECT)
    var wakeResetConnections by configurationStore.boolean(Key.WAKE_RESET_CONNECTIONS)
    var globalTcpFastOpen by configurationStore.boolean(Key.GLOBAL_TCP_FAST_OPEN)
    var globalTcpMultiPath by configurationStore.boolean(Key.GLOBAL_TCP_MULTI_PATH)
    var globalUdpFragment by configurationStore.string(Key.GLOBAL_UDP_FRAGMENT) { "" }

    @Volatile
    var pendingResetConnectionsAfterReconnect = false

    //

    var isExpert by configurationStore.boolean(Key.APP_EXPERT)
    var appTheme by configurationStore.int(Key.APP_THEME)
    var customThemeLight by configurationStore.string(Key.CUSTOM_THEME_LIGHT) { "" }
    var customThemeDark by configurationStore.string(Key.CUSTOM_THEME_DARK) { "" }
    var customThemeDynamicColors by configurationStore.boolean(Key.CUSTOM_THEME_DYNAMIC_COLORS)
    var customThemeHeaderPrimary by configurationStore.boolean(Key.CUSTOM_THEME_HEADER_PRIMARY)
    var customThemeStatsBarPrimary by configurationStore.boolean(Key.CUSTOM_THEME_STATS_BAR_PRIMARY)
    var customThemePendingPreview by configurationStore.string(Key.CUSTOM_THEME_PENDING_PREVIEW) { "" }
    var nightTheme by configurationStore.stringToInt(Key.NIGHT_THEME)
    var appLanguage by configurationStore.string(Key.APP_LANGUAGE) { "" }
    var useToolbar by configurationStore.boolean(Key.USE_TOOLBAR)
    var toolbarLayout by configurationStore.string(Key.TOOLBAR_LAYOUT) { "" }
    var showProfileCountOnTabs by configurationStore.boolean(Key.SHOW_PROFILE_COUNT_ON_TABS)
    var profileCountryIndicator by configurationStore.boolean(Key.PROFILE_COUNTRY_INDICATOR) { true }
    var notificationCountryIndicator by configurationStore.boolean(
        Key.NOTIFICATION_COUNTRY_INDICATOR,
    ) { true }
    var tabDoubleTapToNavigate by configurationStore.boolean(Key.TAB_DOUBLE_TAP_TO_NAVIGATE) { true }
    var shortProfileProtocolInfo by configurationStore.boolean(Key.SHORT_PROFILE_PROTOCOL_INFO)
    var dontHighlightInsecureProfiles by configurationStore.boolean(Key.DONT_HIGHLIGHT_INSECURE_PROFILES)
    var showBottomBarInSettings by configurationStore.boolean(Key.SHOW_BOTTOM_BAR_IN_SETTINGS)
    var compactStatsBar by configurationStore.boolean(Key.COMPACT_STATS_BAR)
    var legacyMainView by configurationStore.boolean(Key.LEGACY_MAIN_VIEW) {
        LegacyMainViewPolicy.defaultEnabled()
    }
    var automaticConnectionCheck by configurationStore.boolean(Key.AUTOMATIC_CONNECTION_CHECK) { true }
    var enableGroupUpdateDialog by configurationStore.boolean(Key.ENABLE_GROUP_UPDATE_DIALOG) { true }
    var openGroupSettingsOnLongPress by configurationStore.boolean(Key.OPEN_GROUP_SETTINGS_ON_LONG_PRESS) { true }
    var serviceMode by configurationStore.string(Key.SERVICE_MODE) { Key.MODE_VPN }
    var udpNatMapping by configurationStore.string(Key.UDP_NAT_MAPPING) { "" }
    var udpNatFiltering by configurationStore.string(Key.UDP_NAT_FILTERING) { "" }
    var udpNatMax by configurationStore.string(Key.UDP_NAT_MAX) { "" }

    var trafficSniffing by configurationStore.stringToInt(Key.TRAFFIC_SNIFFING) { 1 }
    var resolveDestination by configurationStore.boolean(Key.RESOLVE_DESTINATION)

    var mtu by configurationStore.stringToInt(Key.MTU) { 9000 }

    var bypassLan by configurationStore.boolean(Key.BYPASS_LAN)
    var bypassLanInCore by configurationStore.boolean(Key.BYPASS_LAN_IN_CORE)

    var allowAccess by configurationStore.boolean(Key.ALLOW_ACCESS)
    var speedInterval by configurationStore.stringToInt(Key.SPEED_INTERVAL)
    var profileTrafficUpdateInterval by configurationStore.stringToInt(Key.PROFILE_TRAFFIC_UPDATE_INTERVAL)
    var subscriptionTrafficUnit by configurationStore.stringToInt(Key.SUBSCRIPTION_TRAFFIC_UNIT) {
        SubscriptionTrafficUnit.DECIMAL
    }
    var showGroupInNotification by configurationStore.boolean("showGroupInNotification")
    var persistentStatusNotification by configurationStore.boolean(Key.PERSISTENT_STATUS_NOTIFICATION)

    var certProvider by configurationStore.stringToInt(Key.CERT_PROVIDER) { CertProvider.SYSTEM_AND_USER }
    var globalCustomConfig by configurationStore.string(Key.GLOBAL_CUSTOM_CONFIG) { "" }

    var remoteDns by configurationStore.string(Key.REMOTE_DNS) { "https://1.1.1.1/dns-query" }
    var remoteDnsDeadline by configurationStore.string(Key.REMOTE_DNS_DEADLINE) { "5000ms" }
    var directDns by configurationStore.string(Key.DIRECT_DNS) { "local" }
    var directDnsDeadline by configurationStore.string(Key.DIRECT_DNS_DEADLINE) { "5000ms" }
    var enableDnsRouting by configurationStore.boolean(Key.ENABLE_DNS_ROUTING) { true }
    var enableFakeDns by configurationStore.boolean(Key.ENABLE_FAKEDNS) { true }
    var dnsDisableCache by configurationStore.boolean(Key.DNS_DISABLE_CACHE) { false }
    var dnsDisableExpire by configurationStore.boolean(Key.DNS_DISABLE_EXPIRE) { false }
    var dnsCacheCapacity by configurationStore.stringToIntIfExists(Key.DNS_CACHE_CAPACITY)
    var dnsTimeout by configurationStore.string(Key.DNS_TIMEOUT) { "10s" }
    var dnsOptimisticCache by configurationStore.boolean(Key.DNS_OPTIMISTIC_CACHE) { false }
    var dnsOptimisticTimeout by configurationStore.string(Key.DNS_OPTIMISTIC_TIMEOUT) { "5s" }
    var dnsStoreCache by configurationStore.boolean(Key.DNS_STORE_CACHE) { true }
    var dnsReverseMapping by configurationStore.boolean(Key.DNS_REVERSE_MAPPING) { false }
    var dnsDomainOverrides by configurationStore.string(Key.DNS_DOMAIN_OVERRIDES) { "" }
    var customDnsServers by configurationStore.string(Key.CUSTOM_DNS_SERVERS) { "" }

    private var rulesProviderRaw by configurationStore.stringToInt(Key.RULES_PROVIDER) {
        RULES_PROVIDER_LOYALSOLDIER
    }
    var rulesProvider: Int
        get() {
            migrateRulesProviderIfNeeded()
            return rulesProviderRaw
        }
        set(value) {
            configurationStore.putInt(RULES_PROVIDER_MIGRATION_VERSION_KEY, RULES_PROVIDER_MIGRATION_VERSION)
            rulesProviderRaw = value
        }
    var logLevel by configurationStore.stringToInt(Key.LOG_LEVEL)
    val logBufSize: Int
        get() = logBufSizeValue.kilobytes
    val logBufSizeValue: LogcatRetentionSize.Value
        get() = LogcatRetentionSize.resolve(
            configurationStore.getString(Key.LOG_BUF_SIZE),
            configurationStore.getInt(Key.LOG_BUF_SIZE),
        )
    var enableCoreProfiling by configurationStore.boolean(Key.ENABLE_CORE_PROFILING)
    var coreProfilerMode by configurationStore.stringToInt(Key.CORE_PROFILER_MODE) { CoreProfilerMode.CPU }
    var connectionGuard by configurationStore.boolean(Key.CONNECTION_GUARD) { false }
    var coreRecoveryExpectedStop by configurationStore.boolean(Key.CORE_RECOVERY_EXPECTED_STOP) { true }
    var overloadWatchdog by configurationStore.boolean(Key.OVERLOAD_WATCHDOG) { false }
    var memoryLimit by configurationStore.stringToLong(Key.MEMORY_LIMIT) { 0L }
    var acquireWakeLock by configurationStore.boolean(Key.ACQUIRE_WAKE_LOCK)
    var hideFromRecentApps by configurationStore.boolean(Key.HIDE_FROM_RECENT_APPS)

    var rulesGeositeUrl by configurationStore.string(Key.RULES_GEOSITE_URL) {
        "https://github.com/SagerNet/sing-geosite/releases/latest/download/geosite.db"
    }
    var rulesGeoipUrl by configurationStore.string(Key.RULES_GEOIP_URL) {
        "https://github.com/SagerNet/sing-geoip/releases/latest/download/geoip.db"
    }
    var rulesUpdateInterval by configurationStore.string(Key.RULES_UPDATE_INTERVAL) { "0" } // 默认为0，不自动更新

    // hopefully hashCode = mHandle doesn't change, currently this is true from KitKat to Nougat
    private val userIndex by lazy { Binder.getCallingUserHandle().hashCode() }

    var mixedListener: String
        get() = configurationStore.getString(Key.MIXED_LISTENER) ?: LOCALHOST
        set(value) = configurationStore.putString(Key.MIXED_LISTENER, if (isPureIpAddress(value)) value else LOCALHOST)

    var mixedPort: Int
        get() = getLocalPort(Key.MIXED_PORT, 2080)
        set(value) = saveLocalPort(Key.MIXED_PORT, value)

    var mixedUsername by configurationStore.string(Key.MIXED_USERNAME) { "User" }
    var mixedPassword by configurationStore.string(Key.MIXED_PASSWORD) { Util.generateCryptoSecurePassword() }

    fun initGlobal() {
        migrateRulesProviderIfNeeded()

        if (!isPureIpAddress(configurationStore.getString(Key.MIXED_LISTENER).orEmpty())) {
            mixedListener = mixedListener
        }

        if (configurationStore.getString(Key.MIXED_PORT) == null) {
            mixedPort = mixedPort
        }

        if (configurationStore.getString(Key.MIXED_PASSWORD) == null) {
            mixedPassword = Util.generateCryptoSecurePassword()
        }

        if (configurationStore.getString(Key.CLASH_API_SECRET) == null) {
            clashApiSecret = clashApiSecret
        }

        if (configurationStore.getBoolean(Key.ENABLE_CLASH_API) == null) {
            enableClashAPI = true
        }

        if (configurationStore.getBoolean(Key.HIDE_CLASH_API) == null) {
            hideClashApi = true
        }

        if (configurationStore.getString(Key.PROFILE_TRAFFIC_UPDATE_INTERVAL) == null && speedInterval > 0) {
            profileTrafficUpdateInterval = speedInterval
        }
    }

    private fun migrateRulesProviderIfNeeded() {
        if (configurationStore.getInt(RULES_PROVIDER_MIGRATION_VERSION_KEY, 0) >= RULES_PROVIDER_MIGRATION_VERSION) {
            return
        }
        if (rulesProviderRaw == RULES_PROVIDER_ITDOG) {
            rulesProviderRaw = RULES_PROVIDER_CUSTOM
        }
        configurationStore.putInt(RULES_PROVIDER_MIGRATION_VERSION_KEY, RULES_PROVIDER_MIGRATION_VERSION)
    }

    private fun getLocalPort(
        key: String,
        default: Int,
    ): Int = parsePort(configurationStore.getString(key), default + userIndex)

    private fun saveLocalPort(
        key: String,
        value: Int,
    ) {
        configurationStore.putString(key, "$value")
    }

    var ipv6Mode by configurationStore.stringToInt(Key.IPV6_MODE) { IPv6Mode.DISABLE }

    var meteredNetwork by configurationStore.boolean(Key.METERED_NETWORK)
    var proxyApps by configurationStore.boolean(Key.PROXY_APPS)
    var bypass by configurationStore.boolean(Key.BYPASS_MODE) { true }
    var individual by configurationStore.string(Key.INDIVIDUAL)
    var adblockEnabled by configurationStore.boolean(Key.ADBLOCK_ENABLED)
    var adblockDnsFiltering by configurationStore.boolean(Key.ADBLOCK_DNS_FILTERING) { true }
    var adblockCnameUncloaking by configurationStore.boolean(Key.ADBLOCK_CNAME_UNCLOAKING) { false }
    var adblockHttpFiltering by configurationStore.boolean(Key.ADBLOCK_HTTP_FILTERING) { true }
    var adblockHttpsFiltering by configurationStore.boolean(Key.ADBLOCK_HTTPS_FILTERING)
    var adblockHttpsFingerprint by configurationStore.string(Key.ADBLOCK_HTTPS_FINGERPRINT)
    var adblockHttpsCronet by configurationStore.boolean(Key.ADBLOCK_HTTPS_CRONET)
    var adblockMixedLanFiltering by configurationStore.boolean(Key.ADBLOCK_MIXED_LAN_FILTERING)
    var adblockSkipEvCerts by configurationStore.boolean(Key.ADBLOCK_SKIP_EV_CERTS) { true }
    var adblockBundledFilters by configurationStore.stringSet(Key.ADBLOCK_BUNDLED_FILTERS)
    var adblockBundledFiltersInitialized by configurationStore.boolean(Key.ADBLOCK_BUNDLED_FILTERS_INITIALIZED)
    var adblockCustomFilters by configurationStore.string(Key.ADBLOCK_CUSTOM_FILTERS)
    var adblockCustomRules by configurationStore.string(Key.ADBLOCK_CUSTOM_RULES)
    var adblockCaCertificate by configurationStore.string(Key.ADBLOCK_CA_CERTIFICATE)
    var adblockCaKey by configurationStore.string(Key.ADBLOCK_CA_KEY)
    var adblockSystemWideFilter by configurationStore.boolean(Key.ADBLOCK_SYSTEM_WIDE_FILTER)
    var adblockIncludedPackages by configurationStore.string(Key.ADBLOCK_INCLUDED_PACKAGES)
    var tunUnrecognizedTraffic by configurationStore.string(Key.TUN_UNRECOGNIZED_TRAFFIC) { "block" }
    var tunSystemDnsTraffic by configurationStore.string(Key.TUN_SYSTEM_DNS_TRAFFIC) { "normal-proxy-bypass-direct" }
    var tunDnsWhitelist by configurationStore.string(Key.TUN_DNS_WHITELIST) {
        "1.1.1.1\n1.0.0.1\n2606:4700:4700::1111\n2606:4700:4700::1001\n104.16.132.229\n104.16.133.229\n2606:4700::6810:84e5\n2606:4700::6810:85e5\n104.16.248.249\n104.16.249.249\n2606:4700::6810:f8f9\n2606:4700::6810:f9f9\n1.12.12.12\n120.53.53.53\n8.8.8.8\n8.8.4.4\n2001:4860:4860::8888\n2001:4860:4860::8844\n9.9.9.9\n149.112.112.112\n2620:fe::fe\n2620:fe::9\n94.140.14.14\n94.140.15.15\n2a10:50c0::ad1:ff\n2a10:50c0::ad2:ff"
    }
    var tunDotWhitelist by configurationStore.string(Key.TUN_DOT_WHITELIST) {
        "8.8.8.8\n8.8.4.4\ndns.google\n9.9.9.9\n149.112.112.112\ndns.quad9.net\ndns9.quad9.net\n1.1.1.1\ncloudflare-dns.com\nsecurity.cloudflare-dns.com\nfamily.cloudflare-dns.com\ndoh.pub\n1.12.12.12\ndns.adguard-dns.com"
    }
    var tunDohWhitelist by configurationStore.string(Key.TUN_DOH_WHITELIST) {
        "8.8.8.8\n8.8.4.4\ndns.google\n9.9.9.9\n149.112.112.112\ndns.quad9.net\ndns9.quad9.net\n1.1.1.1\ncloudflare-dns.com\nsecurity.cloudflare-dns.com\nfamily.cloudflare-dns.com\ndoh.pub\n1.12.12.12\ndns.adguard-dns.com"
    }
    var showDirectSpeed by configurationStore.boolean(Key.SHOW_DIRECT_SPEED) { true }

    val persistAcrossReboot by configurationStore.boolean(Key.PERSIST_ACROSS_REBOOT) { false }

    var appendHttpProxy by configurationStore.boolean(Key.APPEND_HTTP_PROXY)
    var requireProxyInVPN by configurationStore.boolean(Key.REQUIRE_PROXY_IN_VPN)
    var disableUdpForLocalProxy by configurationStore.boolean(Key.DISABLE_UDP_FOR_LOCAL_PROXY) { false }
    var httpProxyBypass by configurationStore.string(Key.HTTP_PROXY_BYPASS) { "" }
    var strictRoute by configurationStore.boolean(Key.STRICT_ROUTE) { true }
    var connectionTestURL by configurationStore.string(Key.CONNECTION_TEST_URL) { CONNECTION_TEST_URL }
    var connectionGroupTestURL by configurationStore.string(Key.CONNECTION_GROUP_TEST_URL) { CONNECTION_GROUP_TEST_URL }
    var connectionIPResolveURL by configurationStore.string(Key.CONNECTION_IP_RESOLVE_URL) { CONNECTION_IP_RESOLVE_URL }
    var connectionTestConcurrent by configurationStore.positiveStringToIntCompat(Key.CONNECTION_TEST_CONCURRENT) { 5 }
    var connectionTestTimeout by configurationStore.positiveStringToIntCompat(Key.CONNECTION_TEST_TIMEOUT) { 10000 }
    var connectionGroupTestTimeout by configurationStore.positiveStringToIntCompat(Key.CONNECTION_GROUP_TEST_TIMEOUT) { 4500 }
    var connectionTestAttempts by configurationStore.stringToInt(Key.CONNECTION_TEST_ATTEMPTS) { 1 }
    var connectionTestPause by configurationStore.positiveStringToIntCompat(Key.CONNECTION_TEST_PAUSE) { 50 }
    var connectionTestHardened by configurationStore.boolean(Key.CONNECTION_TEST_HARDENED)
    var profileTestType by configurationStore.stringToInt(Key.PROFILE_TEST_TYPE)
    var speedTestDuration by configurationStore.stringToInt(Key.SPEED_TEST_DURATION) { 5000 }
    var speedTestConnections by configurationStore.stringToInt(Key.SPEED_TEST_CONNECTIONS) { 8 }
    var speedTestServerMode by configurationStore.stringToInt(Key.SPEED_TEST_SERVER_MODE)
    var speedTestServerValue by configurationStore.string(Key.SPEED_TEST_SERVER_VALUE) { "" }
    var speedTestFinalResult by configurationStore.stringToInt(Key.SPEED_TEST_FINAL_RESULT)
    var stunTestPreset by configurationStore.string(Key.STUN_TEST_PRESET) { "balanced" }
    var stunTestCustomServers by configurationStore.string(Key.STUN_TEST_CUSTOM_SERVERS) { "" }
    var alwaysShowAddress by configurationStore.boolean(Key.ALWAYS_SHOW_ADDRESS)

    val clashApiSocketPath: String
        get() = File(SagerNet.application.cacheDir, CLASH_API_SOCKET_NAME).absolutePath

    var tunImplementation by configurationStore.stringToInt(Key.TUN_IMPLEMENTATION) { TunImplementation.GVISOR }
    var profileTrafficStatistics by configurationStore.boolean(Key.PROFILE_TRAFFIC_STATISTICS) { true }

    private fun buildLoopbackYacdURL(
        host: String = if (hideClashApi) CLASH_API_DASHBOARD_HOST else CLASH_API_HOST,
        port: Int = CLASH_API_PORT,
    ): String {
        val backendHost = if (hideClashApi && host == CLASH_API_DASHBOARD_HOST) CLASH_API_HOST else host
        return Uri
            .Builder()
            .scheme("http")
            .encodedAuthority("$host:$port")
            .path("/ui/")
            .appendQueryParameter("hostname", backendHost)
            .appendQueryParameter("port", port.toString())
            .appendQueryParameter("secret", clashApiSecret)
            .build()
            .toString()
    }

    val defaultYacdURL: String
        get() = buildLoopbackYacdURL()

    var yacdURL by configurationStore.string("yacdURL") {
        defaultYacdURL
    }

    fun resolvedYacdURL(): String {
        if (hideClashApi) return defaultYacdURL
        val current = yacdURL.takeIf { it.isNotBlank() } ?: return defaultYacdURL
        val uri = Uri.parse(current)
        val host = uri.host ?: return current
        val isLoopbackHost =
            host == CLASH_API_HOST ||
                host == CLASH_API_DASHBOARD_HOST ||
                host == "localhost"
        val isDashboardPath = uri.path?.startsWith("/ui") == true
        if (!isLoopbackHost || uri.port != CLASH_API_PORT || !isDashboardPath) return current
        return buildLoopbackYacdURL(CLASH_API_HOST, CLASH_API_PORT)
    }

    // protocol

    var globalAllowInsecure by configurationStore.boolean(Key.GLOBAL_ALLOW_INSECURE) { false }
    var hysteria2DisableChromeParrot by
        configurationStore.boolean(Key.HYSTERIA2_DISABLE_CHROME_PARROT) { false }

    var enableTLSFragment by configurationStore.boolean(Key.ENABLE_TLS_FRAGMENT) { false }
    var trafficFragmentation: String
        get() =
            configurationStore.getString(Key.TRAFFIC_FRAGMENTATION)
                ?: if (enableTLSFragment) TrafficFragmentation.STARIFLY else TrafficFragmentation.NONE
        set(value) = configurationStore.putString(Key.TRAFFIC_FRAGMENTATION, value)
    var fragmentLength by configurationStore.string(Key.FRAGMENT_LENGTH) { "100-200" }
    var fragmentInterval by configurationStore.string(Key.FRAGMENT_INTERVAL) { "10-20" }
    var exclaveFragmentMethod by configurationStore.stringToInt(Key.EXCLAVE_FRAGMENT_METHOD) {
        ExclaveFragmentationMethod.TLS_RECORD_FRAGMENTATION
    }
    var exclaveFragmentForDirect by configurationStore.boolean(Key.EXCLAVE_FRAGMENT_FOR_DIRECT) { false }
    var byedpiFragmentCli by configurationStore.string(Key.BYEDPI_FRAGMENT_CLI) { "" }

    // old cache, DO NOT ADD

    var dirty by profileCacheStore.boolean(Key.PROFILE_DIRTY)
    var editingId by profileCacheStore.long(Key.PROFILE_ID)
    var editingGroup by profileCacheStore.long(Key.PROFILE_GROUP)
    var profileName by profileCacheStore.string(Key.PROFILE_NAME)
    var serverAddress by profileCacheStore.string(Key.SERVER_ADDRESS)
    var serverPort by profileCacheStore.stringToInt(Key.SERVER_PORT)
    var serverPorts by profileCacheStore.string(Key.SERVER_PORTS)
    var serverUsername by profileCacheStore.string(Key.SERVER_USERNAME)
    var serverPassword by profileCacheStore.string(Key.SERVER_PASSWORD)
    var serverPassword1 by profileCacheStore.string(Key.SERVER_PASSWORD1)
    var serverMethod by profileCacheStore.string(Key.SERVER_METHOD)

    var sharedStorage by profileCacheStore.string("sharedStorage")

    var serverProtocol by profileCacheStore.string(Key.SERVER_PROTOCOL)
    var serverObfs by profileCacheStore.string(Key.SERVER_OBFS)
    var serverProtocolParam by profileCacheStore.string(Key.SERVER_PROTOCOL_PARAM)
    var serverObfsParam by profileCacheStore.string(Key.SERVER_OBFS_PARAM)

    var serverNetwork by profileCacheStore.string(Key.SERVER_NETWORK)
    var serverHost by profileCacheStore.string(Key.SERVER_HOST)
    var serverPath by profileCacheStore.string(Key.SERVER_PATH)
    var serverSNI by profileCacheStore.string(Key.SERVER_SNI)
    var serverEncryption by profileCacheStore.string(Key.SERVER_ENCRYPTION)
    var serverALPN by profileCacheStore.string(Key.SERVER_ALPN)
    var serverCertificates by profileCacheStore.string(Key.SERVER_CERTIFICATES)
    var serverMTU by profileCacheStore.stringToInt(Key.SERVER_MTU)
    var serverHeaders by profileCacheStore.string(Key.SERVER_HEADERS)
    var serverAllowInsecure by profileCacheStore.boolean(Key.SERVER_ALLOW_INSECURE)

    var serverAuthType by profileCacheStore.stringToInt(Key.SERVER_AUTH_TYPE)
    var serverUploadSpeed by profileCacheStore.stringToInt(Key.SERVER_UPLOAD_SPEED)
    var serverDownloadSpeed by profileCacheStore.stringToInt(Key.SERVER_DOWNLOAD_SPEED)
    var serverStreamReceiveWindow by profileCacheStore.stringToIntIfExists(Key.SERVER_STREAM_RECEIVE_WINDOW)
    var serverConnectionReceiveWindow by profileCacheStore.stringToIntIfExists(Key.SERVER_CONNECTION_RECEIVE_WINDOW)
    var serverDisableMtuDiscovery by profileCacheStore.boolean(Key.SERVER_DISABLE_MTU_DISCOVERY)
    var serverHopInterval by profileCacheStore.stringToInt(Key.SERVER_HOP_INTERVAL) { 10 }

    var protocolVersion by profileCacheStore.stringToInt(Key.PROTOCOL_VERSION) { 2 } // default is SOCKS5

    var serverProtocolInt by profileCacheStore.stringToInt(Key.SERVER_PROTOCOL)
    var serverPrivateKey by profileCacheStore.string(Key.SERVER_PRIVATE_KEY)
    var serverInsecureConcurrency by profileCacheStore.stringToInt(Key.SERVER_INSECURE_CONCURRENCY)

    var serverUDPRelayMode by profileCacheStore.string(Key.SERVER_UDP_RELAY_MODE)
    var serverCongestionController by profileCacheStore.string(Key.SERVER_CONGESTION_CONTROLLER)
    var serverDisableSNI by profileCacheStore.boolean(Key.SERVER_DISABLE_SNI)
    var serverReduceRTT by profileCacheStore.boolean(Key.SERVER_REDUCE_RTT)
    var serverMieruMuxLevel by profileCacheStore.stringToInt(Key.SERVER_MIERU_MUX_LEVEL)
    var serverMieruHandshakeMode by profileCacheStore.stringToInt(Key.SERVER_MIERU_HANDSHAKE_MODE)
    var serverMieruTrafficPattern by profileCacheStore.string(Key.SERVER_MIERU_TRAFFIC_PATTERN)
    var serverMieruLowEntropyMode by profileCacheStore.string(Key.SERVER_MIERU_LOW_ENTROPY_MODE)
    var serverMieruLowEntropyMaskRotation by profileCacheStore.string(Key.SERVER_MIERU_LOW_ENTROPY_MASK_ROTATION)

    var serverUserId by profileCacheStore.string(Key.SERVER_USER_ID)
    var serverPinnedCertChainSha256 by profileCacheStore.string(Key.SERVER_PINNED_CERT_CHAIN_SHA256)

    var routeName by profileCacheStore.string(Key.ROUTE_NAME)
    var routeDomain by profileCacheStore.string(Key.ROUTE_DOMAIN)
    var routeIP by profileCacheStore.string(Key.ROUTE_IP)
    var routePort by profileCacheStore.string(Key.ROUTE_PORT)
    var routeSourcePort by profileCacheStore.string(Key.ROUTE_SOURCE_PORT)
    var routeNetworkType by profileCacheStore.stringSet(Key.ROUTE_NETWORK_TYPE)
    var routeWifiSsid by profileCacheStore.string(Key.ROUTE_WIFI_SSID)
    var routeWifiBssid by profileCacheStore.string(Key.ROUTE_WIFI_BSSID)
    var routeNetwork by profileCacheStore.string(Key.ROUTE_NETWORK)
    var routeSource by profileCacheStore.string(Key.ROUTE_SOURCE)
    var routeProtocol by profileCacheStore.string(Key.ROUTE_PROTOCOL)
    var routeRuleset by profileCacheStore.string(Key.ROUTE_RULESET)
    var routeClashMode by profileCacheStore.string(Key.ROUTE_CLASH_MODE)
    var routeCreateDnsRule by profileCacheStore.stringToInt(Key.ROUTE_CREATE_DNS_RULE) { 1 }
    var routeDnsAction by profileCacheStore.string(Key.ROUTE_DNS_ACTION) { "route" }
    var routeDnsServer by profileCacheStore.string(Key.ROUTE_DNS_SERVER)
    var routeDnsDisableCache by profileCacheStore.boolean(Key.ROUTE_DNS_DISABLE_CACHE)
    var routeDnsRewriteTtl by profileCacheStore.stringToInt(Key.ROUTE_DNS_REWRITE_TTL)
    var routeDnsClientSubnet by profileCacheStore.string(Key.ROUTE_DNS_CLIENT_SUBNET)
    var routeDnsRcode by profileCacheStore.string(Key.ROUTE_DNS_RCODE) { "NOERROR" }
    var routeDnsRejectMethod by profileCacheStore.string(Key.ROUTE_DNS_REJECT_METHOD)
    var routeDnsPredefinedAnswer by profileCacheStore.string(Key.ROUTE_DNS_PREDEFINED_ANSWER)
    var routeDnsPredefinedNs by profileCacheStore.string(Key.ROUTE_DNS_PREDEFINED_NS)
    var routeDnsPredefinedExtra by profileCacheStore.string(Key.ROUTE_DNS_PREDEFINED_EXTRA)
    var routeOutbound by profileCacheStore.stringToInt(Key.ROUTE_OUTBOUND)
    var routeOutboundRule by profileCacheStore.long(Key.ROUTE_OUTBOUND + "Long")
    var routePackages by profileCacheStore.string(Key.ROUTE_PACKAGES)

    var frontProxy by profileCacheStore.long(Key.GROUP_FRONT_PROXY + "Long")
    var landingProxy by profileCacheStore.long(Key.GROUP_LANDING_PROXY + "Long")
    var frontProxyTmp by profileCacheStore.stringToInt(Key.GROUP_FRONT_PROXY)
    var landingProxyTmp by profileCacheStore.stringToInt(Key.GROUP_LANDING_PROXY)

    var serverConfig by profileCacheStore.string(Key.SERVER_CONFIG)
    var serverCustom by profileCacheStore.string(Key.SERVER_CUSTOM)
    var serverCustomOutbound by profileCacheStore.string(Key.SERVER_CUSTOM_OUTBOUND)

    var groupName by profileCacheStore.string(Key.GROUP_NAME)
    var groupType by profileCacheStore.stringToInt(Key.GROUP_TYPE)
    var groupOrder by profileCacheStore.stringToInt(Key.GROUP_ORDER)
    var groupIsSelector by profileCacheStore.boolean(Key.GROUP_IS_SELECTOR)
    var groupForceUTLS by profileCacheStore.string(Key.GROUP_FORCE_UTLS)
    var groupEnableMux by profileCacheStore.boolean(Key.GROUP_ENABLE_MUX)
    var groupMuxType by profileCacheStore.stringToInt(Key.GROUP_MUX_TYPE)
    var groupMuxMode by profileCacheStore.stringToInt(Key.GROUP_MUX_MODE)
    var groupMuxConcurrency by profileCacheStore.stringToInt(Key.GROUP_MUX_CONCURRENCY) { 8 }
    var groupMuxMaxConnections by profileCacheStore.stringToInt(Key.GROUP_MUX_MAX_CONNECTIONS) { 4 }
    var groupMuxMinStreams by profileCacheStore.stringToInt(Key.GROUP_MUX_MIN_STREAMS) { 4 }
    var groupMuxPadding by profileCacheStore.boolean(Key.GROUP_MUX_PADDING)
    var groupMuxBrutal by profileCacheStore.boolean(Key.GROUP_MUX_BRUTAL)
    var groupMuxBrutalUpMbps by profileCacheStore.stringToInt(Key.GROUP_MUX_BRUTAL_UP_MBPS) { 100 }
    var groupMuxBrutalDownMbps by profileCacheStore.stringToInt(Key.GROUP_MUX_BRUTAL_DOWN_MBPS) { 100 }

    var subscriptionLink by profileCacheStore.string(Key.SUBSCRIPTION_LINK)
    var subscriptionForceResolve by profileCacheStore.boolean(Key.SUBSCRIPTION_FORCE_RESOLVE)
    var subscriptionDeduplication by profileCacheStore.boolean(Key.SUBSCRIPTION_DEDUPLICATION)
    var subscriptionUpdateWhenConnectedOnly by profileCacheStore.boolean(Key.SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY)
    var subscriptionUserAgent by profileCacheStore.string(Key.SUBSCRIPTION_USER_AGENT)
    var subscriptionAutoUpdate by profileCacheStore.boolean(Key.SUBSCRIPTION_AUTO_UPDATE)
    var subscriptionAutoUpdateDelay by profileCacheStore.stringToInt(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY) { 360 }
    var subscriptionFilterMode by profileCacheStore.stringToInt(Key.SUBSCRIPTION_FILTER_MODE) { 0 }
    var subscriptionFilterRegex by profileCacheStore.string(Key.SUBSCRIPTION_FILTER_REGEX)
    var subscriptionHwidEnabled by profileCacheStore.boolean(Key.SUBSCRIPTION_HWID_ENABLED)
    var subscriptionSpoofApp by profileCacheStore.stringToInt(Key.SUBSCRIPTION_SPOOF_APP) { 0 }
    var subscriptionServerDns by profileCacheStore.string(Key.SUBSCRIPTION_SERVER_DNS)
    var subscriptionBannerLayout by profileCacheStore.stringSet(Key.SUBSCRIPTION_BANNER_LAYOUT)
    var subscriptionRoutingEnabled by profileCacheStore.boolean(Key.SUBSCRIPTION_ROUTING_ENABLED)
    var subscriptionRoutingInterval by profileCacheStore.stringToInt(Key.SUBSCRIPTION_ROUTING_INTERVAL) { 86400 }

    var rulesFirstCreate by profileCacheStore.boolean("rulesFirstCreate")
    var proxyAppsFirstSetup by configurationStore.boolean("proxyAppsFirstSetup")
    var firstRunRoutingRegion by configurationStore.string("firstRunRoutingRegion")

    // var enableTLSFragment by configurationStore.boolean(Key.ENABLE_TLS_FRAGMENT)

    var webdavServer: String?
        get() = configurationStore.getString("webdavServer")
        set(value) = configurationStore.putString("webdavServer", value)

    var webdavUsername: String?
        get() = configurationStore.getString("webdavUsername")
        set(value) = configurationStore.putString("webdavUsername", value)

    var webdavPassword: String?
        get() = configurationStore.getString("webdavPassword")
        set(value) = configurationStore.putString("webdavPassword", value)

    var webdavPath: String?
        get() = configurationStore.getString("webdavPath") ?: "NekoBox" // 设置默认值
        set(value) = configurationStore.putString("webdavPath", value)

    var globalMode by configurationStore.boolean(Key.GLOBAL_MODE)

    override fun onPreferenceDataStoreChanged(
        store: PreferenceDataStore,
        key: String,
    ) {
    }
}
