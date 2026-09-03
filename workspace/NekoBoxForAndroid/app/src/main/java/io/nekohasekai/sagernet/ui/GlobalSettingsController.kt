package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import io.nekohasekai.sagernet.AppLogLevel
import io.nekohasekai.sagernet.AppLogLevelController
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.LocalNetworkPermission
import io.nekohasekai.sagernet.LogcatRetentionSize
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TrafficFragmentation
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.UDP_NAT_MAX_VALUE
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.needRestart
import io.nekohasekai.sagernet.ui.compose.showComposeMessageDialog
import io.nekohasekai.sagernet.ui.compose.showThemePickerDialog
import io.nekohasekai.sagernet.ui.profile.ConfigEditActivity
import io.nekohasekai.sagernet.widget.RouteEditTextPreferenceDialogFragment
import io.nekohasekai.sagernet.utils.AppLocale
import io.nekohasekai.sagernet.utils.CustomTheme
import io.nekohasekai.sagernet.utils.Theme
import libcore.Libcore
import moe.matsuri.nb4a.utils.NGUtil.isPureIpAddress

internal class GlobalSettingsController(
    private val fragment: SettingsFragment,
    private val invalidate: () -> Unit,
) {
    private val context get() = fragment.requireContext()
    private val activity get() = fragment.requireActivity() as MainActivity
    private var pendingTunImplementation: Int? = null
    private val operations by lazy { GlobalSettingsOperations(fragment) }
    private val requestLocalNetworkPermission = fragment.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val requested = pendingTunImplementation ?: return@registerForActivityResult
        pendingTunImplementation = null
        DataStore.tunImplementation = if (granted) requested else TunImplementation.GVISOR
        fragment.needReload()
        invalidate()
    }

    fun isVisible(key: String): Boolean = when (key) {
        "configureCustomTheme" -> DataStore.appTheme == Theme.CUSTOM && CustomTheme.isSupported
        Key.CONFIGURE_TOOLBAR_LAYOUT -> DataStore.useToolbar
        Key.METERED_NETWORK -> Build.VERSION.SDK_INT >= 28
        Key.TUN_UNRECOGNIZED_TRAFFIC, Key.TUN_SYSTEM_DNS_TRAFFIC,
        Key.TUN_DNS_WHITELIST, Key.TUN_DOT_WHITELIST, Key.TUN_DOH_WHITELIST ->
            DataStore.serviceMode == Key.MODE_VPN
        Key.RULES_GEOSITE_URL, Key.RULES_GEOIP_URL ->
            DataStore.rulesProvider == DataStore.RULES_PROVIDER_CUSTOM
        Key.FRAGMENT_LENGTH, Key.FRAGMENT_INTERVAL ->
            DataStore.trafficFragmentation == TrafficFragmentation.STARIFLY
        Key.EXCLAVE_FRAGMENT_METHOD, Key.EXCLAVE_FRAGMENT_FOR_DIRECT ->
            DataStore.trafficFragmentation == TrafficFragmentation.EXCLAVE
        Key.BYEDPI_FRAGMENT_CLI -> DataStore.trafficFragmentation == TrafficFragmentation.BYEDPI
        Key.CORE_PROFILER_MODE, Key.SAVE_CORE_PROFILER_SNAPSHOT,
        Key.DELETE_CORE_PROFILER_SNAPSHOT -> DataStore.enableCoreProfiling
        else -> true
    }

    fun isEnabled(key: String): Boolean = when (key) {
        Key.CHANGE_ICON -> !SagerNet.isTv
        Key.NIGHT_THEME -> !Theme.isNightModeForced(DataStore.appTheme)
        Key.PROFILE_TRAFFIC_STATISTICS -> DataStore.profileTrafficUpdateInterval != 0
        Key.REMOTE_DNS_DEADLINE -> activeDnsLineCount(DataStore.remoteDns) > 1
        Key.DIRECT_DNS_DEADLINE -> activeDnsLineCount(DataStore.directDns) > 1
        Key.SAVE_CORE_PROFILER_SNAPSHOT, Key.DELETE_CORE_PROFILER_SNAPSHOT ->
            !DataStore.serviceState.started
        else -> true
    }

    fun validateChange(key: String, value: String): Boolean {
        when (key) {
            Key.MTU -> if (value.toIntOrNull() !in 200..9000) return false
            Key.UDP_NAT_MAX -> if (value.isNotEmpty() &&
                (value.toLongOrNull()?.let { it in 0..UDP_NAT_MAX_VALUE } != true)) {
                invalidValue()
                return false
            }
            Key.MEMORY_LIMIT -> {
                val parsed = value.toLongOrNull() ?: return false
                if (parsed < 0 || parsed > Long.MAX_VALUE / (1024L * 1024L)) return false
            }
            Key.DNS_CACHE_CAPACITY -> if (value.isNotEmpty() && (value.toIntOrNull() ?: 0) < 1024) {
                Toast.makeText(context, R.string.dns_cache_capacity_invalid, Toast.LENGTH_LONG).show()
                return false
            }
            Key.MIXED_LISTENER -> if (!isPureIpAddress(value.takeIf(String::isNotEmpty).orEmpty())) {
                invalidValue()
                return false
            }
            Key.LOG_BUF_SIZE -> {
                val parsed = LogcatRetentionSize.parse(value) ?: return false
                DataStore.configurationStore.putString(Key.LOG_BUF_SIZE, parsed.text)
                fragment.needRestart()
                invalidate()
                return false
            }
            Key.TUN_IMPLEMENTATION -> {
                val implementation = value.toIntOrNull() ?: return false
                if (LocalNetworkPermission.isRequired(context, implementation)) {
                    pendingTunImplementation = implementation
                    requestLocalNetworkPermission.launch(LocalNetworkPermission.NAME)
                    return false
                }
            }
            Key.SERVICE_MODE -> {
                if (DataStore.serviceState.started) SagerNet.stopService()
                if (value == Key.MODE_PROXY) proxyLeakWarning()
            }
            Key.REQUIRE_PROXY_IN_VPN, Key.APPEND_HTTP_PROXY -> if (value.toBoolean()) proxyLeakWarning()
            Key.PROXY_APPS -> activity.startActivity(Intent(activity, AppManagerActivity::class.java))
        }
        return true
    }

    fun onChanged(key: String) {
        when (key) {
            Key.NIGHT_THEME -> {
                Theme.currentNightMode = DataStore.nightTheme
                Theme.applyNightTheme()
            }
            Key.APP_LANGUAGE -> AppLocale.apply(DataStore.appLanguage)
            Key.LEGACY_MAIN_VIEW, Key.CERT_PROVIDER -> fragment.needRestart()
            Key.LOG_LEVEL -> applyLogLevel()
            Key.NOTIFICATION_COUNTRY_INDICATOR ->
                SagerNet.updateNotificationCountryIndicator(DataStore.notificationCountryIndicator)
            Key.HIDE_FROM_RECENT_APPS -> activity.applyHideFromRecentApps(DataStore.hideFromRecentApps)
            Key.ENABLE_CLASH_API -> activity.refreshNavMenu(DataStore.enableClashAPI)
            Key.ENABLE_CORE_PROFILING -> operations.profilingChanged(DataStore.enableCoreProfiling)
        }
        if (key in RELOAD_KEYS) fragment.needReload()
        invalidate()
    }

    fun onAction(key: String) {
        when (key) {
            Key.APP_THEME -> context.showThemePickerDialog(
                context.getText(R.string.theme), includeCustom = true, onThemeSelected = ::applyTheme,
            )
            Key.CHANGE_ICON -> AppIconDialog.show(
                context,
                context.getText(R.string.change_icon),
                onSelected = { invalidate() },
            )
            "configureCustomTheme" -> activity.displayFragment(CustomThemeFragment())
            Key.CONFIGURE_TOOLBAR_LAYOUT -> context.startActivity(Intent(context, ToolbarLayoutActivity::class.java))
            Key.GLOBAL_CUSTOM_CONFIG -> context.startActivity(Intent(context, ConfigEditActivity::class.java).apply {
                putExtra("useConfigStore", "1")
                putExtra("key", Key.GLOBAL_CUSTOM_CONFIG)
            })
            Key.PREVIEW_SING_BOX_CONFIG -> context.startActivity(Intent(context, SingBoxConfigPreviewActivity::class.java))
            Key.CUSTOM_DNS_SERVERS -> context.startActivity(Intent(context, CustomDnsServersActivity::class.java))
            Key.DNS_DOMAIN_OVERRIDES -> RouteEditTextPreferenceDialogFragment.newInstance(
                key = Key.DNS_DOMAIN_OVERRIDES,
                title = context.getString(R.string.dns_domain_overrides),
                value = DataStore.dnsDomainOverrides,
                mode = RouteEditTextPreferenceDialogFragment.EditorMode.DNS_DOMAIN_OVERRIDES,
                storageTarget = RouteEditTextPreferenceDialogFragment.StorageTarget.CONFIGURATION,
            ).show(fragment.childFragmentManager, Key.DNS_DOMAIN_OVERRIDES)
            "resetClashApiSecret" -> {
                DataStore.clashApiSecret = moe.matsuri.nb4a.utils.Util.generateCryptoSecurePassword(
                    16, moe.matsuri.nb4a.utils.Util.securePasswordCharsNoSymbols,
                )
                fragment.needRestart()
            }
            else -> operations.handle(key)
        }
    }

    private fun applyTheme(themeId: Int) {
        if (themeId == Theme.CUSTOM && !CustomTheme.isSupported) {
            Toast.makeText(context, R.string.custom_theme_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        if (themeId == Theme.CUSTOM) CustomTheme.ensureDefaults(context)
        DataStore.appTheme = themeId
        if (Theme.isNightModeForced(themeId)) {
            DataStore.nightTheme = 1
            Theme.currentNightMode = 1
            Theme.applyNightTheme()
        }
        val theme = Theme.getTheme(themeId)
        context.applicationContext.setTheme(theme)
        activity.setTheme(theme)
        SettingsFragment.restoreInterfaceOnNextCreate()
        ActivityCompat.recreate(activity)
    }

    private fun applyLogLevel() {
        val level = AppLogLevel.fromPreferenceValue(DataStore.logLevel)
        AppLogLevelController.set(level)
        runCatching { Libcore.setLogLevel(level.singBoxName, level.outputEnabled) }
    }

    private fun activeDnsLineCount(value: String) = value.lineSequence().count {
        it.trim().let { line -> line.isNotEmpty() && !line.startsWith("#") }
    }
    private fun invalidValue() = Toast.makeText(context, R.string.invalid_value, Toast.LENGTH_LONG).show()
    private fun proxyLeakWarning() = Toast.makeText(context, R.string.proxy_ip_leak_warning, Toast.LENGTH_LONG).show()

    private companion object {
        val RELOAD_KEYS = setOf(
            Key.SPEED_INTERVAL, Key.PROFILE_TRAFFIC_UPDATE_INTERVAL, Key.TUN_UNRECOGNIZED_TRAFFIC,
            Key.TUN_SYSTEM_DNS_TRAFFIC, Key.TUN_DNS_WHITELIST, Key.TUN_DOT_WHITELIST,
            Key.TUN_DOH_WHITELIST, Key.CONNECTION_GUARD, Key.OVERLOAD_WATCHDOG, Key.MEMORY_LIMIT,
            Key.ENABLE_CLASH_API, Key.HIDE_CLASH_API, Key.REQUIRE_PROXY_IN_VPN,
            Key.DISABLE_UDP_FOR_LOCAL_PROXY, Key.MIXED_LISTENER, Key.MIXED_PORT, Key.MIXED_USERNAME,
            Key.MIXED_PASSWORD, Key.APPEND_HTTP_PROXY, Key.STRICT_ROUTE, Key.SHOW_DIRECT_SPEED,
            Key.PERSISTENT_STATUS_NOTIFICATION, Key.TRAFFIC_SNIFFING, Key.BYPASS_LAN,
            Key.BYPASS_LAN_IN_CORE, Key.MTU, Key.UDP_NAT_MAPPING, Key.UDP_NAT_FILTERING,
            Key.UDP_NAT_MAX, Key.ENABLE_FAKEDNS, Key.DNS_DISABLE_CACHE, Key.DNS_DISABLE_EXPIRE,
            Key.DNS_CACHE_CAPACITY, Key.DNS_TIMEOUT, Key.DNS_OPTIMISTIC_CACHE,
            Key.DNS_OPTIMISTIC_TIMEOUT, Key.DNS_STORE_CACHE, Key.DNS_REVERSE_MAPPING,
            Key.REMOTE_DNS, Key.REMOTE_DNS_DEADLINE, Key.DIRECT_DNS, Key.DIRECT_DNS_DEADLINE,
            Key.DNS_DOMAIN_OVERRIDES, Key.ENABLE_DNS_ROUTING, Key.IPV6_MODE, Key.ALLOW_ACCESS,
            Key.RESOLVE_DESTINATION, Key.TUN_IMPLEMENTATION, Key.ACQUIRE_WAKE_LOCK,
            Key.TRAFFIC_FRAGMENTATION, Key.FRAGMENT_LENGTH, Key.FRAGMENT_INTERVAL,
            Key.EXCLAVE_FRAGMENT_METHOD, Key.EXCLAVE_FRAGMENT_FOR_DIRECT, Key.BYEDPI_FRAGMENT_CLI,
            Key.GLOBAL_TCP_FAST_OPEN, Key.GLOBAL_TCP_MULTI_PATH, Key.GLOBAL_UDP_FRAGMENT,
            Key.HYSTERIA2_DISABLE_CHROME_PARROT,
        )
    }
}
