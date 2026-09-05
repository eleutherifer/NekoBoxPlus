package io.nekohasekai.sagernet

const val CONNECTION_TEST_URL = "https://www.gstatic.com/generate_204"
const val CONNECTION_GROUP_TEST_URL = "http://64.233.161.94/generate_204"
const val CONNECTION_IP_RESOLVE_URL = "https://ipv4.ipleak.net/json/"

object Key {
    const val DB_PUBLIC = "configuration.db"
    const val DB_PROFILE = "sager_net.db"

    const val PERSIST_ACROSS_REBOOT = "isAutoConnect"

    const val CLEAR_CACHE = "clearCache"
    const val RUN_STORAGE_MAINTENANCE = "runStorageMaintenance"

    const val APP_EXPERT = "isExpert"
    const val APP_THEME = "appTheme"
    const val CUSTOM_THEME_LIGHT = "customThemeLight"
    const val CUSTOM_THEME_DARK = "customThemeDark"
    const val CUSTOM_THEME_DYNAMIC_COLORS = "customThemeDynamicColors"
    const val CUSTOM_THEME_HEADER_PRIMARY = "customThemeHeaderPrimary"
    const val CUSTOM_THEME_STATS_BAR_PRIMARY = "customThemeStatsBarPrimary"
    const val CUSTOM_THEME_PENDING_PREVIEW = "customThemePendingPreview"
    const val NIGHT_THEME = "nightTheme"
    const val APP_LANGUAGE = "appLanguage"
    const val CHANGE_ICON = "changeIcon"
    const val USE_TOOLBAR = "useToolbar"
    const val TOOLBAR_LAYOUT = "toolbarLayout"
    const val CONFIGURE_TOOLBAR_LAYOUT = "configureToolbarLayout"
    const val SHOW_PROFILE_COUNT_ON_TABS = "showProfileCountOnTabs"
    const val PROFILE_COUNTRY_INDICATOR = "profileCountryIndicator"
    const val NOTIFICATION_COUNTRY_INDICATOR = "notificationCountryIndicator"
    const val TAB_DOUBLE_TAP_TO_NAVIGATE = "tabDoubleTapToNavigate"
    const val SHORT_PROFILE_PROTOCOL_INFO = "shortProfileProtocolInfo"
    const val DONT_HIGHLIGHT_INSECURE_PROFILES = "dontHighlightInsecureProfiles"
    const val SHOW_BOTTOM_BAR_IN_SETTINGS = "showBottomBarInSettings"
    const val COMPACT_STATS_BAR = "compactStatsBar"
    const val LEGACY_MAIN_VIEW = "legacyMainView"
    const val AUTOMATIC_CONNECTION_CHECK = "automaticConnectionCheck"
    const val ENABLE_GROUP_UPDATE_DIALOG = "enableGroupUpdateDialog"
    const val OPEN_GROUP_SETTINGS_ON_LONG_PRESS = "openGroupSettingsOnLongPress"
    const val SERVICE_MODE = "serviceMode"
    const val MODE_VPN = "vpn"
    const val MODE_PROXY = "proxy"

    const val CERT_PROVIDER = "certProvider"
    const val GLOBAL_CUSTOM_CONFIG = "globalCustomConfig"
    const val PREVIEW_SING_BOX_CONFIG = "previewSingBoxConfig"
    const val KILL_BACKGROUND_PROCESS = "killBackgroundProcess"

    const val REMOTE_DNS = "remoteDns"
    const val REMOTE_DNS_DEADLINE = "remoteDnsDeadline"
    const val DIRECT_DNS = "directDns"
    const val DIRECT_DNS_DEADLINE = "directDnsDeadline"
    const val ENABLE_DNS_ROUTING = "enableDnsRouting"
    const val ENABLE_FAKEDNS = "enableFakeDns"
    const val DNS_DISABLE_CACHE = "dnsDisableCache"
    const val DNS_DISABLE_EXPIRE = "dnsDisableExpire"
    const val DNS_CACHE_CAPACITY = "dnsCacheCapacity"
    const val DNS_TIMEOUT = "dnsTimeout"
    const val DNS_OPTIMISTIC_CACHE = "dnsOptimisticCache"
    const val DNS_OPTIMISTIC_TIMEOUT = "dnsOptimisticTimeout"
    const val DNS_STORE_CACHE = "dnsStoreCache"
    const val DNS_REVERSE_MAPPING = "dnsReverseMapping"
    const val DNS_DOMAIN_OVERRIDES = "dnsDomainOverrides"
    const val CUSTOM_DNS_SERVERS = "customDnsServers"

    const val IPV6_MODE = "ipv6Mode"

    const val PROXY_APPS = "proxyApps"
    const val BYPASS_MODE = "bypassMode"
    const val INDIVIDUAL = "individual"
    const val ADBLOCK_ENABLED = "adblockEnabled"
    const val ADBLOCK_FILTERING_SECTION = "adblockFilteringSection"
    const val ADBLOCK_PER_APP_SECTION = "adblockPerAppSection"
    const val ADBLOCK_FILTER_LISTS_SECTION = "adblockFilterListsSection"
    const val ADBLOCK_DNS_FILTERING = "adblockDnsFiltering"
    const val ADBLOCK_CNAME_UNCLOAKING = "adblockCnameUncloaking"
    const val ADBLOCK_HTTP_FILTERING = "adblockHttpFiltering"
    const val ADBLOCK_HTTPS_FILTERING = "adblockHttpsFiltering"
    const val ADBLOCK_HTTPS_FINGERPRINT = "adblockHttpsFingerprint"
    const val ADBLOCK_HTTPS_CRONET = "adblockHttpsCronet"
    const val ADBLOCK_MIXED_LAN_FILTERING = "adblockMixedLanFiltering"
    const val ADBLOCK_SKIP_EV_CERTS = "adblockSkipEvCerts"
    const val ADBLOCK_BUNDLED_FILTERS = "adblockBundledFilters"
    const val ADBLOCK_BUNDLED_FILTERS_INITIALIZED = "adblockBundledFiltersInitialized"
    const val ADBLOCK_CUSTOM_FILTERS = "adblockCustomFilters"
    const val ADBLOCK_CUSTOM_RULES = "adblockCustomRules"
    const val ADBLOCK_CA_CERTIFICATE = "adblockCaCertificate"
    const val ADBLOCK_CA_KEY = "adblockCaKey"
    const val ADBLOCK_SYSTEM_WIDE_FILTER = "adblockSystemWideFilter"
    const val ADBLOCK_INCLUDED_PACKAGES = "adblockIncludedPackages"
    const val TUN_UNRECOGNIZED_TRAFFIC = "tunUnrecognizedTraffic"
    const val TUN_SYSTEM_DNS_TRAFFIC = "tunSystemDnsTraffic"
    const val TUN_DNS_WHITELIST = "tunDnsWhitelist"
    const val TUN_DOT_WHITELIST = "tunDotWhitelist"
    const val TUN_DOH_WHITELIST = "tunDohWhitelist"
    const val METERED_NETWORK = "meteredNetwork"

    const val TRAFFIC_SNIFFING = "trafficSniffing"
    const val RESOLVE_DESTINATION = "resolveDestination"

    const val BYPASS_LAN = "bypassLan"
    const val BYPASS_LAN_IN_CORE = "bypassLanInCore"

    const val MIXED_LISTENER = "mixedListener"
    const val MIXED_PORT = "mixedPort"
    const val MIXED_USERNAME = "mixedUsername"
    const val MIXED_PASSWORD = "mixedPassword"
    const val ALLOW_ACCESS = "allowAccess"
    const val SPEED_INTERVAL = "speedInterval"
    const val PROFILE_TRAFFIC_UPDATE_INTERVAL = "profileTrafficUpdateInterval"
    const val SUBSCRIPTION_TRAFFIC_UNIT = "subscriptionTrafficUnit"
    const val SHOW_DIRECT_SPEED = "showDirectSpeed"
    const val PERSISTENT_STATUS_NOTIFICATION = "persistentStatusNotification"

    const val APPEND_HTTP_PROXY = "appendHttpProxy"

    const val REQUIRE_PROXY_IN_VPN = "requireProxyInVPN"
    const val DISABLE_UDP_FOR_LOCAL_PROXY = "disableUdpForLocalProxy"
    const val HTTP_PROXY_BYPASS = "httpProxyBypass"

    const val STRICT_ROUTE = "strictRoute"
    const val CONNECTION_TEST_URL = "connectionTestURL"
    const val CONNECTION_GROUP_TEST_URL = "connectionGroupTestURL"
    const val CONNECTION_IP_RESOLVE_URL = "connectionIPResolveURL"
    const val CONNECTION_TEST_CONCURRENT = "connectionTestConcurrent"
    const val CONNECTION_TEST_TIMEOUT = "connectionTestTimeout"
    const val CONNECTION_GROUP_TEST_TIMEOUT = "connectionGroupTestTimeout"
    const val CONNECTION_TEST_ATTEMPTS = "connectionTestAttempts"
    const val CONNECTION_TEST_PAUSE = "connectionTestPause"
    const val CONNECTION_TEST_HARDENED = "connectionTestHardened"
    const val PROFILE_TEST_TYPE = "profileTestType"
    const val SPEED_TEST_DURATION = "speedTestDuration"
    const val SPEED_TEST_CONNECTIONS = "speedTestConnections"
    const val SPEED_TEST_SERVER_MODE = "speedTestServerMode"
    const val SPEED_TEST_SERVER_VALUE = "speedTestServerValue"
    const val SPEED_TEST_FINAL_RESULT = "speedTestFinalResult"
    const val STUN_TEST_PRESET = "stunTestPreset"
    const val STUN_TEST_CUSTOM_SERVERS = "stunTestCustomServers"

    const val NETWORK_CHANGE_RECONNECT = "networkChangeReconnect"
    const val NETWORK_CHANGE_RESET_CONNECTIONS = "networkChangeResetConnections"
    const val WAKE_RECONNECT = "wakeReconnect"
    const val WAKE_RESET_CONNECTIONS = "wakeResetConnections"
    const val GLOBAL_TCP_FAST_OPEN = "globalTcpFastOpen"
    const val GLOBAL_TCP_MULTI_PATH = "globalTcpMultiPath"
    const val GLOBAL_UDP_FRAGMENT = "globalUdpFragment"
    const val RULES_PROVIDER = "rulesProvider"
    const val LOG_LEVEL = "logLevel"
    const val LOG_BUF_SIZE = "logBufSize"
    const val ENABLE_CORE_PROFILING = "enableCoreProfiling"
    const val CORE_PROFILER_MODE = "coreProfilerMode"
    const val CONNECTION_GUARD = "connectionGuard"
    const val CORE_RECOVERY_EXPECTED_STOP = "coreRecoveryExpectedStop"
    const val OVERLOAD_WATCHDOG = "overloadWatchdog"
    const val MEMORY_LIMIT = "memoryLimit"
    const val PERFORM_LIBCORE_GC_SWEEP = "performLibcoreGcSweep"
    const val PERFORM_LIBCORE_MANUAL_CRASH = "performLibcoreManualCrash"
    const val SAVE_CORE_PROFILER_SNAPSHOT = "saveCoreProfilerSnapshot"
    const val DELETE_CORE_PROFILER_SNAPSHOT = "deleteCoreProfilerSnapshot"
    const val MTU = "mtu"
    const val ALWAYS_SHOW_ADDRESS = "alwaysShowAddress"

    const val RULES_GEOSITE_URL = "rulesGeositeUrl"
    const val RULES_GEOIP_URL = "rulesGeoipUrl"
    const val RULES_UPDATE_INTERVAL = "rulesUpdateInterval"

    // Protocol Settings
    const val GLOBAL_ALLOW_INSECURE = "globalAllowInsecure"
    const val HYSTERIA2_DISABLE_CHROME_PARROT = "hysteria2DisableChromeParrot"

    const val ACQUIRE_WAKE_LOCK = "acquireWakeLock"
    const val HIDE_FROM_RECENT_APPS = "hideFromRecentApps"
    const val CONFIRM_PROFILE_DELETE = "confirmProfileDelete"
    const val GROUP_LAYOUT_MODE = "groupLayoutMode"
    const val PROFILE_CARD_BORDERS = "profileCardBorders"
    const val GROUP_ORDER_MODE_ALWAYS = "groupOrderModeAlways"
    const val GROUP_ORDER_MODE_URL_TEST = "groupOrderModeUrlTest"
    const val GROUP_ORDER_MODE_UPDATE = "groupOrderModeUpdate"

    const val ALLOW_INSECURE_ON_REQUEST = "allowInsecureOnRequest"

    const val TUN_IMPLEMENTATION = "tunImplementation"
    const val UDP_NAT_MAPPING = "udpNatMapping"
    const val UDP_NAT_FILTERING = "udpNatFiltering"
    const val UDP_NAT_MAX = "udpNatMax"
    const val PROFILE_TRAFFIC_STATISTICS = "profileTrafficStatistics"

    const val PROFILE_DIRTY = "profileDirty"
    const val PROFILE_ID = "profileId"
    const val PROFILE_NAME = "profileName"
    const val PROFILE_GROUP = "profileGroup"
    const val PROFILE_CURRENT = "profileCurrent"

    const val SERVER_ADDRESS = "serverAddress"
    const val SERVER_PORT = "serverPort"
    const val SERVER_PORTS = "serverPorts"
    const val SERVER_USERNAME = "serverUsername"
    const val SERVER_PASSWORD = "serverPassword"
    const val SERVER_METHOD = "serverMethod"
    const val SERVER_PASSWORD1 = "serverPassword1"

    const val PROTOCOL_VERSION = "protocolVersion"

    const val SERVER_PROTOCOL = "serverProtocol"
    const val SERVER_OBFS = "serverObfs"

    const val SERVER_PROTOCOL_PARAM = "serverProtocolParam"
    const val SERVER_OBFS_PARAM = "serverObfsParam"

    const val SERVER_NETWORK = "serverNetwork"
    const val SERVER_HOST = "serverHost"
    const val SERVER_PATH = "serverPath"
    const val SERVER_SNI = "serverSNI"
    const val SERVER_ENCRYPTION = "serverEncryption"
    const val SERVER_ALPN = "serverALPN"
    const val SERVER_CERTIFICATES = "serverCertificates"
    const val FETCH_SSH_HOST_KEY = "fetchSSHHostKey"
    const val SERVER_MTU = "serverMTU"

    const val SERVER_CONFIG = "serverConfig"
    const val SERVER_CUSTOM = "serverCustom"
    const val SERVER_CUSTOM_OUTBOUND = "serverCustomOutbound"

    const val SERVER_SECURITY_CATEGORY = "serverSecurityCategory"
    const val SERVER_TLS_CAMOUFLAGE_CATEGORY = "serverTlsCamouflageCategory"
    const val SERVER_ECH_CATEORY = "serverECHCategory"
    const val SERVER_WS_CATEGORY = "serverWsCategory"
    const val SERVER_SS_CATEGORY = "serverSsCategory"
    const val SERVER_HEADERS = "serverHeaders"
    const val SERVER_ALLOW_INSECURE = "serverAllowInsecure"

    const val SERVER_AUTH_TYPE = "serverAuthType"
    const val SERVER_UPLOAD_SPEED = "serverUploadSpeed"
    const val SERVER_DOWNLOAD_SPEED = "serverDownloadSpeed"
    const val SERVER_STREAM_RECEIVE_WINDOW = "serverStreamReceiveWindow"
    const val SERVER_CONNECTION_RECEIVE_WINDOW = "serverConnectionReceiveWindow"
    const val SERVER_DISABLE_MTU_DISCOVERY = "serverDisableMtuDiscovery"
    const val SERVER_HOP_INTERVAL = "hopInterval"

    const val SERVER_PRIVATE_KEY = "serverPrivateKey"
    const val SERVER_INSECURE_CONCURRENCY = "serverInsecureConcurrency"

    const val SERVER_UDP_RELAY_MODE = "serverUDPRelayMode"
    const val SERVER_CONGESTION_CONTROLLER = "serverCongestionController"
    const val SERVER_DISABLE_SNI = "serverDisableSNI"
    const val SERVER_REDUCE_RTT = "serverReduceRTT"
    const val SERVER_MIERU_MUX_LEVEL = "serverMieruMuxLevel"
    const val SERVER_MIERU_HANDSHAKE_MODE = "serverMieruHandshakeMode"
    const val SERVER_MIERU_TRAFFIC_PATTERN = "serverMieruTrafficPattern"
    const val SERVER_MIERU_LOW_ENTROPY_MODE = "serverMieruLowEntropyMode"
    const val SERVER_MIERU_LOW_ENTROPY_MASK_ROTATION = "serverMieruLowEntropyMaskRotation"

    const val SERVER_USER_ID = "serverUserId"
    const val SERVER_PINNED_CERT_CHAIN_SHA256 = "serverPinnedCertChainSha256"

    const val ROUTE_NAME = "routeName"
    const val ROUTE_DOMAIN = "routeDomain"
    const val ROUTE_IP = "routeIP"
    const val ROUTE_PORT = "routePort"
    const val ROUTE_SOURCE_PORT = "routeSourcePort"
    const val ROUTE_NETWORK_TYPE = "routeNetworkType"
    const val ROUTE_WIFI_SSID = "routeWifiSsid"
    const val ROUTE_WIFI_BSSID = "routeWifiBssid"
    const val ROUTE_NETWORK = "routeNetwork"
    const val ROUTE_SOURCE = "routeSource"
    const val ROUTE_PROTOCOL = "routeProtocol"
    const val ROUTE_RULESET = "routeRuleset"
    const val ROUTE_CLASH_MODE = "routeClashMode"
    const val ROUTE_CREATE_DNS_RULE = "routeCreateDnsRule"
    const val ROUTE_DNS_ACTION = "routeDnsAction"
    const val ROUTE_DNS_SERVER = "routeDnsServer"
    const val ROUTE_DNS_DISABLE_CACHE = "routeDnsDisableCache"
    const val ROUTE_DNS_REWRITE_TTL = "routeDnsRewriteTtl"
    const val ROUTE_DNS_CLIENT_SUBNET = "routeDnsClientSubnet"
    const val ROUTE_DNS_RCODE = "routeDnsRcode"
    const val ROUTE_DNS_REJECT_METHOD = "routeDnsRejectMethod"
    const val ROUTE_DNS_PREDEFINED_ANSWER = "routeDnsPredefinedAnswer"
    const val ROUTE_DNS_PREDEFINED_NS = "routeDnsPredefinedNs"
    const val ROUTE_DNS_PREDEFINED_EXTRA = "routeDnsPredefinedExtra"
    const val ROUTE_OUTBOUND = "routeOutbound"
    const val ROUTE_PACKAGES = "routePackages"

    const val GROUP_NAME = "groupName"
    const val GROUP_TYPE = "groupType"
    const val GROUP_ORDER = "groupOrder"
    const val GROUP_IS_SELECTOR = "groupIsSelector"
    const val GROUP_FRONT_PROXY = "groupFrontProxy"
    const val GROUP_LANDING_PROXY = "groupLandingProxy"
    const val GROUP_FORCE_UTLS = "groupForceUTLS"
    const val GROUP_ENABLE_MUX = "groupEnableMux"
    const val GROUP_MUX_TYPE = "groupMuxType"
    const val GROUP_MUX_MODE = "groupMuxMode"
    const val GROUP_MUX_CONCURRENCY = "groupMuxConcurrency"
    const val GROUP_MUX_MAX_CONNECTIONS = "groupMuxMaxConnections"
    const val GROUP_MUX_MIN_STREAMS = "groupMuxMinStreams"
    const val GROUP_MUX_PADDING = "groupMuxPadding"
    const val GROUP_MUX_BRUTAL = "groupMuxBrutal"
    const val GROUP_MUX_BRUTAL_UP_MBPS = "groupMuxBrutalUpMbps"
    const val GROUP_MUX_BRUTAL_DOWN_MBPS = "groupMuxBrutalDownMbps"

    const val GROUP_SUBSCRIPTION = "groupSubscription"
    const val SUBSCRIPTION_LINK = "subscriptionLink"
    const val SUBSCRIPTION_FORCE_RESOLVE = "subscriptionForceResolve"
    const val SUBSCRIPTION_DEDUPLICATION = "subscriptionDeduplication"
    const val SUBSCRIPTION_UPDATE = "subscriptionUpdate"
    const val SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY = "subscriptionUpdateWhenConnectedOnly"
    const val SUBSCRIPTION_USER_AGENT = "subscriptionUserAgent"
    const val SUBSCRIPTION_AUTO_UPDATE = "subscriptionAutoUpdate"
    const val SUBSCRIPTION_AUTO_UPDATE_DELAY = "subscriptionAutoUpdateDelay"
    const val SUBSCRIPTION_FILTER_MODE = "subscriptionFilterMode"
    const val SUBSCRIPTION_FILTER_REGEX = "subscriptionFilterRegex"
    const val SUBSCRIPTION_HWID_ENABLED = "subscriptionHwidEnabled"
    const val SUBSCRIPTION_SPOOF_APP = "subscriptionSpoofApp"
    const val SUBSCRIPTION_SERVER_DNS = "subscriptionServerDns"
    const val SUBSCRIPTION_BANNER_LAYOUT = "subscriptionBannerLayout"
    const val SUBSCRIPTION_ROUTING_ENABLED = "subscriptionRoutingEnabled"
    const val SUBSCRIPTION_ROUTING_INTERVAL = "subscriptionRoutingInterval"
    const val SUBSCRIPTION_IMPORT_ROUTING = "subscriptionImportRouting"

    //

    const val APP_TLS_VERSION = "appTLSVersion"
    const val APP_UTLS_FINGERPRINT = "appUTLSFingerprint"
    const val ENABLE_CLASH_API = "enableClashAPI"
    const val HIDE_CLASH_API = "hideClashAPI"
    const val CLASH_API_SECRET = "clashApiSecret"

    const val ENABLE_TLS_FRAGMENT = "enableTLSFragment"
    const val TRAFFIC_FRAGMENTATION = "trafficFragmentation"

    const val FRAGMENT_LENGTH = "fragmentLength"
    const val FRAGMENT_INTERVAL = "fragmentInterval"
    const val EXCLAVE_FRAGMENT_METHOD = "exclaveFragmentMethod"
    const val EXCLAVE_FRAGMENT_FOR_DIRECT = "exclaveFragmentForDirect"
    const val BYEDPI_FRAGMENT_CLI = "byedpiFragmentCli"

    const val WEBDAV_SERVER = "webdavServer"
    const val WEBDAV_USERNAME = "webdavUsername"
    const val WEBDAV_PASSWORD = "webdavPassword"
    const val WEBDAV_PATH = "webdavPath"

    const val GLOBAL_MODE = "globalMode"
}

object CoreProfilerMode {
    const val CPU = 0
    const val TRACE = 1
}

object TunImplementation {
    const val GVISOR = 0
    const val SYSTEM = 1
    const val MIXED = 2
}

object CertProvider {
    const val SYSTEM = 0
    const val MOZILLA = 1
    const val SYSTEM_AND_USER = 2
    const val CHROME = 3
}

object IPv6Mode {
    const val DISABLE = 0
    const val ENABLE = 1
    const val PREFER = 2
    const val ONLY = 3
}

object GroupType {
    const val BASIC = 0
    const val SUBSCRIPTION = 1
}

object GroupOrder {
    const val ORIGIN = 0
    const val BY_NAME = 1
    const val BY_DELAY = 2
    const val MANUAL = 3
}

object SubscriptionFilterMode {
    const val DISABLED = 0
    const val INCLUDE = 1
    const val EXCLUDE = 2
}

object TrafficFragmentation {
    const val NONE = "none"
    const val STARIFLY = "starifly"
    const val EXCLAVE = "exclave"
    const val BYEDPI = "byedpi"
}

object ExclaveFragmentationMethod {
    const val TLS_RECORD_FRAGMENTATION = 0
    const val TCP_SEGMENTATION = 1
    const val TLS_RECORD_FRAGMENTATION_AND_TCP_SEGMENTATION = 2
}

object SpoofApp {
    const val NONE = 0
    const val HAPP = 1
    const val V2RAY_TUN = 2
    const val INCY = 3
}

object Action {
    const val SERVICE = "io.nekohasekai.sagernet.SERVICE"
    const val CLOSE = "io.nekohasekai.sagernet.CLOSE"
    const val RELOAD = "io.nekohasekai.sagernet.RELOAD"
    const val EXTRA_PROFILE_ID = "io.nekohasekai.sagernet.extra.PROFILE_ID"
    const val EXTRA_REQUEST_ID = "io.nekohasekai.sagernet.extra.REQUEST_ID"
    const val EXTRA_NOTIFICATION_COUNTRY_INDICATOR_ENABLED =
        "io.nekohasekai.sagernet.extra.NOTIFICATION_COUNTRY_INDICATOR_ENABLED"
    const val RECOVER_CORE = "${BuildConfig.APPLICATION_ID}.RECOVER_CORE"

    // const val SWITCH_WAKE_LOCK = "io.nekohasekai.sagernet.SWITCH_WAKELOCK"
    const val RESET_UPSTREAM_CONNECTIONS = "${BuildConfig.APPLICATION_ID}.RESET_UPSTREAM_CONNECTIONS"
    const val UPDATE_NOTIFICATION_COUNTRY_INDICATOR =
        "${BuildConfig.APPLICATION_ID}.UPDATE_NOTIFICATION_COUNTRY_INDICATOR"
}

object Param {
    const val LIBCORE_CACHE_FILE_PATH = "../cache/cache.db"
    const val LIBCORE_ADBLOCK_DB_FILE_PATH = "../cache/adblock.db"
}
