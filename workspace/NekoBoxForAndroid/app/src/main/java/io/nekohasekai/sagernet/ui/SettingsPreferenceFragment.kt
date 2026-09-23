package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.StatFs
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.AppLogLevel
import io.nekohasekai.sagernet.AppLogLevelController
import io.nekohasekai.sagernet.AppIcon
import io.nekohasekai.sagernet.AppIconManager
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.LocalNetworkPermission
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TrafficFragmentation
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RuleType
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.AppLocale
import io.nekohasekai.sagernet.utils.AdblockRepository
import io.nekohasekai.sagernet.utils.CrashHandler
import io.nekohasekai.sagernet.utils.CustomTheme
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.RouteEditTextPreferenceDialogFragment
import moe.matsuri.nb4a.ui.*
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.os.bundleOf
import moe.matsuri.nb4a.utils.NGUtil.isPureIpAddress
import moe.matsuri.nb4a.utils.SendLog
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SettingsPreferenceFragment : PreferenceFragmentCompat(), OnPreferenceDataStoreChangeListener,
    RouteEditTextPreferenceDialogFragment.PreferenceSaveListener {

    private enum class Mode { TOP_LEVEL, GROUP, SEARCH }

    private enum class LibcoreCrashOption(val type: String, val titleRes: Int) {
        GO_PANIC(BaseService.LibcoreCrashType.GO_PANIC, R.string.libcore_crash_go_panic),
        GO_NIL_POINTER(BaseService.LibcoreCrashType.GO_NIL_POINTER, R.string.libcore_crash_go_nil_pointer),
        GO_INDEX_OUT_OF_RANGE(BaseService.LibcoreCrashType.GO_INDEX_OUT_OF_RANGE, R.string.libcore_crash_go_index_out_of_range),
        GO_CONCURRENT_MAP_WRITE(BaseService.LibcoreCrashType.GO_CONCURRENT_MAP_WRITE, R.string.libcore_crash_go_concurrent_map_write),
        GO_STACK_OVERFLOW(BaseService.LibcoreCrashType.GO_STACK_OVERFLOW, R.string.libcore_crash_go_stack_overflow),
        GO_UNSAFE_MEMORY_WRITE(BaseService.LibcoreCrashType.GO_UNSAFE_MEMORY_WRITE, R.string.libcore_crash_go_unsafe_memory_write),
        NATIVE_ABORT(BaseService.LibcoreCrashType.NATIVE_ABORT, R.string.libcore_crash_native_abort),
        NATIVE_SIGSEGV(BaseService.LibcoreCrashType.NATIVE_SIGSEGV, R.string.libcore_crash_native_sigsegv),
        NATIVE_TRAP(BaseService.LibcoreCrashType.NATIVE_TRAP, R.string.libcore_crash_native_trap),
        NATIVE_DOUBLE_FREE(BaseService.LibcoreCrashType.NATIVE_DOUBLE_FREE, R.string.libcore_crash_native_double_free),
        NATIVE_HEAP_CORRUPTION(BaseService.LibcoreCrashType.NATIVE_HEAP_CORRUPTION, R.string.libcore_crash_native_heap_corruption),
    }

    private data class SettingsGroup(
        val id: String,
        val titleRes: Int,
        val descriptionRes: Int,
        val iconRes: Int,
        val keys: List<String>,
    )

    private data class SearchItem(
        val key: String,
        val groupId: String,
        val title: CharSequence,
        val summary: CharSequence?,
        val icon: Drawable?,
        val englishTitle: String,
        val englishSummary: String,
        val visibleInGroup: Boolean,
    )

    private data class SearchMatch(
        val weight: Int,
        val englishOnly: Boolean,
    )

    private sealed class CoreProfilerSaveResult {
        class Saved(val zipFile: File) : CoreProfilerSaveResult()
        class Failed(val error: Throwable) : CoreProfilerSaveResult()
        object NoSnapshot : CoreProfilerSaveResult()
    }

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_GROUP_ID = "groupId"
        private const val ARG_HIGHLIGHT_KEY = "highlightKey"
        private const val MODE_TOP_LEVEL = "topLevel"
        private const val MODE_GROUP = "group"
        private const val MODE_SEARCH = "search"

        private const val KEY_CONFIGURE_CUSTOM_THEME = "configureCustomTheme"
        private const val KEY_SHOW_GROUP_IN_NOTIFICATION = "showGroupInNotification"
        private const val KEY_RESET_SETTINGS = "resetSettings"
        private const val KEY_RESET_CLASH_API_SECRET = "resetClashApiSecret"
        private const val STATE_PENDING_TUN_IMPLEMENTATION = "pendingTunImplementation"

        private val GROUPS = listOf(
            SettingsGroup(
                id = "interface",
                titleRes = R.string.settings_group_interface,
                descriptionRes = R.string.settings_group_interface_description,
                iconRes = R.drawable.ic_baseline_view_list_24,
                keys = listOf(
                    Key.APP_THEME,
                    KEY_CONFIGURE_CUSTOM_THEME,
                    Key.NIGHT_THEME,
                    Key.APP_LANGUAGE,
                    Key.CHANGE_ICON,
                    Key.USE_TOOLBAR,
                    Key.SHOW_PROFILE_COUNT_ON_TABS,
                    Key.DONT_HIGHLIGHT_INSECURE_PROFILES,
                    Key.SHOW_BOTTOM_BAR_IN_SETTINGS,
                    Key.COMPACT_STATS_BAR,
                    Key.AUTOMATIC_CONNECTION_CHECK,
                    Key.ENABLE_GROUP_UPDATE_DIALOG,
                    Key.OPEN_GROUP_SETTINGS_ON_LONG_PRESS,
                    Key.SPEED_INTERVAL,
                    Key.PROFILE_TRAFFIC_UPDATE_INTERVAL,
                    Key.PROFILE_TRAFFIC_STATISTICS,
                    Key.SHOW_DIRECT_SPEED,
                    KEY_SHOW_GROUP_IN_NOTIFICATION,
                    Key.PERSISTENT_STATUS_NOTIFICATION,
                    Key.CONFIRM_PROFILE_DELETE,
                    Key.ALWAYS_SHOW_ADDRESS,
                    Key.HIDE_FROM_RECENT_APPS,
                ),
            ),
            SettingsGroup(
                id = "connection",
                titleRes = R.string.settings_group_connection,
                descriptionRes = R.string.settings_group_connection_description,
                iconRes = R.drawable.ic_baseline_compare_arrows_24,
                keys = listOf(
                    Key.PERSIST_ACROSS_REBOOT,
                    Key.SERVICE_MODE,
                    Key.TUN_IMPLEMENTATION,
                    Key.MTU,
                    Key.METERED_NETWORK,
                    Key.ACQUIRE_WAKE_LOCK,
                    Key.TRAFFIC_FRAGMENTATION,
                    Key.FRAGMENT_LENGTH,
                    Key.FRAGMENT_INTERVAL,
                    Key.EXCLAVE_FRAGMENT_METHOD,
                    Key.EXCLAVE_FRAGMENT_FOR_DIRECT,
                    Key.BYEDPI_FRAGMENT_CLI,
                    Key.NETWORK_CHANGE_RECONNECT,
                    Key.NETWORK_CHANGE_RESET_CONNECTIONS,
                    Key.WAKE_RECONNECT,
                    Key.WAKE_RESET_CONNECTIONS,
                ),
            ),
            SettingsGroup(
                id = "core",
                titleRes = R.string.settings_group_core,
                descriptionRes = R.string.settings_group_core_description,
                iconRes = R.drawable.baseline_developer_board_24,
                keys = listOf(
                    Key.CONNECTION_GUARD,
                    Key.OVERLOAD_WATCHDOG,
                    Key.MEMORY_LIMIT,
                    Key.LOG_LEVEL,
                    Key.CERT_PROVIDER,
                    Key.GLOBAL_CUSTOM_CONFIG,
                    Key.PREVIEW_SING_BOX_CONFIG,
                ),
            ),
            SettingsGroup(
                id = "inbound",
                titleRes = R.string.settings_group_inbound,
                descriptionRes = R.string.settings_group_inbound_description,
                iconRes = R.drawable.ic_baseline_vpn_key_24,
                keys = listOf(
                    Key.REQUIRE_PROXY_IN_VPN,
                    Key.DISABLE_UDP_FOR_LOCAL_PROXY,
                    Key.MIXED_LISTENER,
                    Key.MIXED_PORT,
                    Key.MIXED_USERNAME,
                    Key.MIXED_PASSWORD,
                    Key.APPEND_HTTP_PROXY,
                    Key.HTTP_PROXY_BYPASS,
                    Key.STRICT_ROUTE,
                    Key.ALLOW_ACCESS,
                ),
            ),
            SettingsGroup(
                id = "routing",
                titleRes = R.string.settings_group_routing,
                descriptionRes = R.string.settings_group_routing_description,
                iconRes = R.drawable.ic_baseline_add_road_24,
                keys = listOf(
                    Key.PROXY_APPS,
                    Key.TUN_UNRECOGNIZED_TRAFFIC,
                    Key.TUN_SYSTEM_DNS_TRAFFIC,
                    Key.TUN_DNS_WHITELIST,
                    Key.TUN_DOT_WHITELIST,
                    Key.TUN_DOH_WHITELIST,
                    Key.BYPASS_LAN,
                    Key.BYPASS_LAN_IN_CORE,
                    Key.TRAFFIC_SNIFFING,
                    Key.RESOLVE_DESTINATION,
                    Key.IPV6_MODE,
                    Key.RULES_PROVIDER,
                    Key.RULES_GEOSITE_URL,
                    Key.RULES_GEOIP_URL,
                    Key.RULES_UPDATE_INTERVAL,
                ),
            ),
            SettingsGroup(
                id = "dns",
                titleRes = R.string.settings_group_dns,
                descriptionRes = R.string.settings_group_dns_description,
                iconRes = R.drawable.ic_action_dns,
                keys = listOf(
                    Key.ENABLE_DNS_ROUTING,
                    Key.ENABLE_FAKEDNS,
                    Key.DNS_DISABLE_CACHE,
                    Key.DNS_DISABLE_EXPIRE,
                    Key.DNS_CACHE_CAPACITY,
                    Key.DNS_REVERSE_MAPPING,
                    Key.REMOTE_DNS,
                    Key.REMOTE_DNS_DEADLINE,
                    "domain_strategy_for_remote",
                    Key.DIRECT_DNS,
                    Key.DIRECT_DNS_DEADLINE,
                    Key.CUSTOM_DNS_SERVERS,
                    "domain_strategy_for_direct",
                    "domain_strategy_for_server",
                    Key.DNS_DOMAIN_OVERRIDES,
                ),
            ),
            SettingsGroup(
                id = "connectionTesting",
                titleRes = R.string.settings_group_connection_testing,
                descriptionRes = R.string.settings_group_connection_testing_description,
                iconRes = R.drawable.ic_baseline_speed_24,
                keys = listOf(
                    Key.CONNECTION_TEST_URL,
                    Key.CONNECTION_GROUP_TEST_URL,
                    Key.PROFILE_TEST_TYPE,
                    Key.CONNECTION_TEST_CONCURRENT,
                    Key.CONNECTION_TEST_TIMEOUT,
                    Key.CONNECTION_TEST_ATTEMPTS,
                    Key.CONNECTION_TEST_PAUSE,
                    Key.CONNECTION_TEST_HARDENED,
                    Key.CONNECTION_GROUP_TEST_TIMEOUT,
                    Key.CONNECTION_IP_RESOLVE_URL,
                ),
            ),
            SettingsGroup(
                id = "developers",
                titleRes = R.string.settings_group_developers,
                descriptionRes = R.string.settings_group_developers_description,
                iconRes = R.drawable.ic_baseline_bug_report_24,
                keys = listOf(
                    Key.ENABLE_CORE_PROFILING,
                    Key.SAVE_CORE_PROFILER_SNAPSHOT,
                    Key.DELETE_CORE_PROFILER_SNAPSHOT,
                    Key.PERFORM_LIBCORE_GC_SWEEP,
                    Key.PERFORM_LIBCORE_MANUAL_CRASH,
                    Key.ENABLE_CLASH_API,
                    Key.HIDE_CLASH_API,
                    KEY_RESET_CLASH_API_SECRET,
                ),
            ),
            SettingsGroup(
                id = "others",
                titleRes = R.string.settings_group_others,
                descriptionRes = R.string.settings_group_others_description,
                iconRes = R.drawable.ic_baseline_tune_24,
                keys = listOf(
                    Key.GLOBAL_ALLOW_INSECURE,
                    Key.ALLOW_INSECURE_ON_REQUEST,
                    Key.APP_TLS_VERSION,
                    KEY_RESET_SETTINGS,
                    Key.CLEAR_CACHE,
                    Key.RUN_STORAGE_MAINTENANCE,
                ),
            ),
        )

        private val GROUP_BY_ID = GROUPS.associateBy { it.id }
        private val GROUP_BY_KEY = LinkedHashMap<String, SettingsGroup>().apply {
            GROUPS.forEach { group ->
                group.keys.forEach { key -> putIfAbsent(key, group) }
            }
        }

        fun topLevel() = SettingsPreferenceFragment().apply {
            arguments = bundleOf(ARG_MODE to MODE_TOP_LEVEL)
        }

        fun forGroup(groupId: String, highlightKey: String? = null) = SettingsPreferenceFragment().apply {
            arguments = bundleOf(
                ARG_MODE to MODE_GROUP,
                ARG_GROUP_ID to groupId,
                ARG_HIGHLIGHT_KEY to highlightKey,
            )
        }

        fun searchResults() = SettingsPreferenceFragment().apply {
            arguments = bundleOf(ARG_MODE to MODE_SEARCH)
        }

        fun groupTitle(groupId: String): Int {
            return GROUP_BY_ID[groupId]?.titleRes ?: R.string.settings
        }
    }

    private lateinit var isProxyApps: MaterialSwitchPreference
    private var syncingProxyAppsPreference = false
    private lateinit var globalCustomConfig: EditConfigPreference
    private lateinit var enableCoreProfiling: MaterialSwitchPreference
    private lateinit var performLibcoreGcSweep: Preference
    private lateinit var performLibcoreManualCrash: Preference
    private lateinit var saveCoreProfilerSnapshot: Preference
    private lateinit var deleteCoreProfilerSnapshot: Preference
    private var coreProfilerProgressDialog: AlertDialog? = null
    private var searchItems = emptyList<SearchItem>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var pendingCacheReload: Runnable? = null
    private var pendingTunImplementation: Int? = null

    private val requestLocalNetworkPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val requestedImplementation = pendingTunImplementation ?: return@registerForActivityResult
        pendingTunImplementation = null
        val implementation = if (granted) requestedImplementation else TunImplementation.GVISOR
        val changed = DataStore.tunImplementation != implementation
        DataStore.tunImplementation = implementation
        findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)?.value = implementation.toString()
        if (changed) needReload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingTunImplementation?.let {
            outState.putInt(STATE_PENDING_TUN_IMPLEMENTATION, it)
        }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
        if (mode() == Mode.GROUP) {
            arguments?.getString(ARG_HIGHLIGHT_KEY)?.let { key ->
                listView.post { highlightPreference(key) }
            }
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (showDnsDomainOverridesDialog(preference)) return
        if (showMaterialEditTextPreferenceDialog(preference)) return
        super.onDisplayPreferenceDialog(preference)
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    private fun showDnsDomainOverridesDialog(preference: Preference): Boolean {
        if (preference.key != Key.DNS_DOMAIN_OVERRIDES || preference !is EditTextPreference) {
            return false
        }
        RouteEditTextPreferenceDialogFragment.newInstance(
            key = preference.key,
            title = preference.title?.toString().orEmpty(),
            value = preference.text.orEmpty(),
            mode = RouteEditTextPreferenceDialogFragment.EditorMode.DNS_DOMAIN_OVERRIDES,
            storageTarget = RouteEditTextPreferenceDialogFragment.StorageTarget.CONFIGURATION,
        ).show(childFragmentManager, preference.key)
        return true
    }

    override fun onRouteEditorPreferenceSaved(key: String, value: String) {
        if (key != Key.DNS_DOMAIN_OVERRIDES) return
        findPreference<EditTextPreference>(key)?.text = value
        needReload()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        if (savedInstanceState?.containsKey(STATE_PENDING_TUN_IMPLEMENTATION) == true) {
            pendingTunImplementation = savedInstanceState.getInt(STATE_PENDING_TUN_IMPLEMENTATION)
        }
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        when (mode()) {
            Mode.TOP_LEVEL -> {
                buildTopLevelPreferences()
                return
            }
            Mode.SEARCH -> {
                buildSearchPreferences("")
                return
            }
            Mode.GROUP -> Unit
        }
        addPreferencesFromResource(R.xml.global_preferences)
        buildGroupPreferences(groupId())

        val appTheme = findPreference<ColorPickerPreference>(Key.APP_THEME)!!
        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)!!
        val configureCustomTheme = findPreference<Preference>("configureCustomTheme")!!.apply {
            setOnPreferenceClickListener {
                (activity as? MainActivity)?.displayFragment(CustomThemeFragment())
                true
            }
        }

        fun syncCustomThemePreference(themeValue: Int = DataStore.appTheme) {
            configureCustomTheme.isVisible = themeValue == Theme.CUSTOM && CustomTheme.isSupported
        }

        fun syncNightThemePreference(themeValue: Int) {
            val forceNightMode = Theme.isNightModeForced(themeValue)
            if (forceNightMode) {
                DataStore.nightTheme = 1
                Theme.currentNightMode = 1
                if (nightTheme.value != "1") {
                    nightTheme.value = "1"
                }
                Theme.applyNightTheme()
            }
            nightTheme.isEnabled = !forceNightMode
        }

        syncNightThemePreference(DataStore.appTheme)
        syncCustomThemePreference(DataStore.appTheme)

        appTheme.setOnPreferenceChangeListener { _, newTheme ->
            val selectedTheme = newTheme as Int
            if (selectedTheme == Theme.CUSTOM) {
                CustomTheme.ensureDefaults(requireContext())
            }
            syncNightThemePreference(selectedTheme)
            syncCustomThemePreference(selectedTheme)
            val theme = Theme.getTheme(selectedTheme)
            app.setTheme(theme)
            requireActivity().apply {
                setTheme(theme)
                SettingsFragment.restoreInterfaceOnNextCreate()
                ActivityCompat.recreate(this)
            }
            true
        }

        nightTheme.setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            SettingsFragment.restoreInterfaceOnNextCreate()
            Theme.applyNightTheme()
            true
        }
        val appLanguage = findPreference<SimpleMenuPreference>(Key.APP_LANGUAGE)!!
        appLanguage.setOnPreferenceChangeListener { _, newValue ->
            AppLocale.apply(newValue as String)
            true
        }

        val requireProxyInVPN = findPreference<MaterialSwitchPreference>(Key.REQUIRE_PROXY_IN_VPN)!!
        val disableUdpForLocalProxy = findPreference<MaterialSwitchPreference>(Key.DISABLE_UDP_FOR_LOCAL_PROXY)!!
        val mixedListener = findPreference<EditTextPreference>(Key.MIXED_LISTENER)!!
        val mixedPort = findPreference<EditTextPreference>(Key.MIXED_PORT)!!
        val mixedUsername = findPreference<EditTextPreference>(Key.MIXED_USERNAME)!!
        val mixedPassword = findPreference<EditTextPreference>(Key.MIXED_PASSWORD)!!
        val serviceMode = findPreference<Preference>(Key.SERVICE_MODE)!!
        val connectionGuard = findPreference<MaterialSwitchPreference>(Key.CONNECTION_GUARD)!!
        val overloadWatchdog = findPreference<MaterialSwitchPreference>(Key.OVERLOAD_WATCHDOG)!!
        val memoryLimit = findPreference<EditTextPreference>(Key.MEMORY_LIMIT)!!
        val allowAccess = findPreference<Preference>(Key.ALLOW_ACCESS)!!
        val appendHttpProxy = findPreference<MaterialSwitchPreference>(Key.APPEND_HTTP_PROXY)!!
        val httpProxyBypass = findPreference<EditTextPreference>(Key.HTTP_PROXY_BYPASS)!!
        val strictRoute = findPreference<MaterialSwitchPreference>(Key.STRICT_ROUTE)!!

        val showDirectSpeed = findPreference<MaterialSwitchPreference>(Key.SHOW_DIRECT_SPEED)!!
        val persistentStatusNotification =
            findPreference<MaterialSwitchPreference>(Key.PERSISTENT_STATUS_NOTIFICATION)!!
        val ipv6Mode = findPreference<Preference>(Key.IPV6_MODE)!!
        val trafficSniffing = findPreference<SimpleMenuPreference>(Key.TRAFFIC_SNIFFING)!!

        val bypassLan = findPreference<MaterialSwitchPreference>(Key.BYPASS_LAN)!!
        val bypassLanInCore = findPreference<MaterialSwitchPreference>(Key.BYPASS_LAN_IN_CORE)!!

        val remoteDns = findPreference<EditTextPreference>(Key.REMOTE_DNS)!!
        val remoteDnsDeadline = findPreference<EditTextPreference>(Key.REMOTE_DNS_DEADLINE)!!
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)!!
        val directDnsDeadline = findPreference<EditTextPreference>(Key.DIRECT_DNS_DEADLINE)!!
        val customDnsServers = findPreference<Preference>(Key.CUSTOM_DNS_SERVERS)!!
        val dnsDomainOverrides = findPreference<EditTextPreference>(Key.DNS_DOMAIN_OVERRIDES)!!
        val enableDnsRouting = findPreference<MaterialSwitchPreference>(Key.ENABLE_DNS_ROUTING)!!
        val enableFakeDns = findPreference<MaterialSwitchPreference>(Key.ENABLE_FAKEDNS)!!
        val dnsDisableCache = findPreference<MaterialSwitchPreference>(Key.DNS_DISABLE_CACHE)!!
        val dnsDisableExpire = findPreference<MaterialSwitchPreference>(Key.DNS_DISABLE_EXPIRE)!!
        val dnsCacheCapacity = findPreference<EditTextPreference>(Key.DNS_CACHE_CAPACITY)!!
        val dnsReverseMapping = findPreference<MaterialSwitchPreference>(Key.DNS_REVERSE_MAPPING)!!

        val trafficFragmentation = findPreference<SimpleMenuPreference>(Key.TRAFFIC_FRAGMENTATION)!!
        val fragmentLength = findPreference<EditTextPreference>(Key.FRAGMENT_LENGTH)!!
        val fragmentInterval = findPreference<EditTextPreference>(Key.FRAGMENT_INTERVAL)!!
        val exclaveFragmentMethod = findPreference<SimpleMenuPreference>(Key.EXCLAVE_FRAGMENT_METHOD)!!
        val exclaveFragmentForDirect = findPreference<MaterialSwitchPreference>(Key.EXCLAVE_FRAGMENT_FOR_DIRECT)!!
        val byedpiFragmentCli = findPreference<EditTextPreference>(Key.BYEDPI_FRAGMENT_CLI)!!

        val logLevel = findPreference<LongClickListPreference>(Key.LOG_LEVEL)!!
        val mtu = findPreference<EditTextPreference>(Key.MTU)!!
        mtu.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        memoryLimit.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        val certProvider = findPreference<SimpleMenuPreference>(Key.CERT_PROVIDER)!!
        globalCustomConfig = findPreference(Key.GLOBAL_CUSTOM_CONFIG)!!
        globalCustomConfig.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)
        findPreference<Preference>(Key.PREVIEW_SING_BOX_CONFIG)!!.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), SingBoxConfigPreviewActivity::class.java))
            true
        }
        enableCoreProfiling = findPreference(Key.ENABLE_CORE_PROFILING)!!
        performLibcoreGcSweep = findPreference(Key.PERFORM_LIBCORE_GC_SWEEP)!!
        performLibcoreManualCrash = findPreference(Key.PERFORM_LIBCORE_MANUAL_CRASH)!!
        saveCoreProfilerSnapshot = findPreference(Key.SAVE_CORE_PROFILER_SNAPSHOT)!!
        deleteCoreProfilerSnapshot = findPreference(Key.DELETE_CORE_PROFILER_SNAPSHOT)!!
        syncCoreProfilerPreferences()
        enableCoreProfiling.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            handleCoreProfilingChanged(enabled)
            syncCoreProfilerPreferences(enabled)
            true
        }
        performLibcoreGcSweep.setOnPreferenceClickListener {
            handlePerformLibcoreGcSweep()
            true
        }
        performLibcoreManualCrash.setOnPreferenceClickListener {
            handlePerformLibcoreManualCrash()
            true
        }
        saveCoreProfilerSnapshot.setOnPreferenceClickListener {
            handleSaveCoreProfilerSnapshot()
            true
        }
        deleteCoreProfilerSnapshot.setOnPreferenceClickListener {
            handleDeleteCoreProfilerSnapshot()
            true
        }

        logLevel.dialogLayoutResource = R.layout.layout_loglevel_help
        logLevel.setOnPreferenceChangeListener { _, newValue ->
            val selectedLevel = AppLogLevel.fromPreferenceValue(
                newValue.toString().toIntOrNull() ?: Int.MIN_VALUE,
            )
            applyLogLevel(selectedLevel)
            true
        }
        logLevel.setOnLongClickListener {
            if (context == null) return@setOnLongClickListener true

            val view = EditText(context).apply {
                inputType = EditorInfo.TYPE_CLASS_NUMBER
                var size = DataStore.logBufSize
                if (size == 0) size = 50
                setText(size.toString())
            }

            MaterialAlertDialogBuilder(requireContext()).setTitle("Log buffer size (kb)")
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DataStore.logBufSize = view.text.toString().toInt()
                    if (DataStore.logBufSize <= 0) DataStore.logBufSize = 250
                    needRestart()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        mixedListener.setOnBindEditTextListener(EditTextPreferenceModifiers.Listener)
        mixedPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        mixedPassword.summaryProvider = ProxyPasswordSummaryProvider
        httpProxyBypass.summaryProvider = ListSummaryProvider(maxLines = 1)
        remoteDns.setOnBindEditTextListener(EditTextPreferenceModifiers.Multiline)
        remoteDns.summaryProvider = ListSummaryProvider(maxLines = 1)
        remoteDnsDeadline.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        directDns.setOnBindEditTextListener(EditTextPreferenceModifiers.Multiline)
        directDns.summaryProvider = ListSummaryProvider(maxLines = 1)
        directDnsDeadline.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        dnsCacheCapacity.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        dnsDomainOverrides.summaryProvider = ListSummaryProvider(maxLines = 1)

        val metedNetwork = findPreference<Preference>(Key.METERED_NETWORK)!!
        if (Build.VERSION.SDK_INT < 28) {
            metedNetwork.remove()
        }
        isProxyApps = findPreference(Key.PROXY_APPS)!!
        isProxyApps.setOnPreferenceChangeListener { _, newValue ->
            if (syncingProxyAppsPreference) return@setOnPreferenceChangeListener true
            startActivity(Intent(activity, AppManagerActivity::class.java))
            if (newValue as Boolean) DataStore.dirty = true
            newValue
        }
        syncProxyAppsPreference()

        val profileTrafficStatistics =
            findPreference<MaterialSwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)!!
        val profileTrafficUpdateInterval =
            findPreference<SimpleMenuPreference>(Key.PROFILE_TRAFFIC_UPDATE_INTERVAL)!!
        val speedInterval = findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)!!
        profileTrafficStatistics.isEnabled = profileTrafficUpdateInterval.value.toString() != "0"
        profileTrafficUpdateInterval.setOnPreferenceChangeListener { _, newValue ->
            profileTrafficStatistics.isEnabled = newValue.toString() != "0"
            needReload()
            true
        }
        speedInterval.setOnPreferenceChangeListener { _, _ ->
            needReload()
            true
        }

        val tunUnrecognizedTraffic = findPreference<ListPreference>(Key.TUN_UNRECOGNIZED_TRAFFIC)!!
        tunUnrecognizedTraffic.onPreferenceChangeListener = reloadListener
        val tunSystemDnsTraffic = findPreference<ListPreference>(Key.TUN_SYSTEM_DNS_TRAFFIC)!!
        tunSystemDnsTraffic.onPreferenceChangeListener = reloadListener
        val tunDnsWhitelist = findPreference<EditTextPreference>(Key.TUN_DNS_WHITELIST)!!
        val tunDotWhitelist = findPreference<EditTextPreference>(Key.TUN_DOT_WHITELIST)!!
        val tunDohWhitelist = findPreference<EditTextPreference>(Key.TUN_DOH_WHITELIST)!!
        tunDnsWhitelist.setOnBindEditTextListener(EditTextPreferenceModifiers.Multiline)
        tunDotWhitelist.setOnBindEditTextListener(EditTextPreferenceModifiers.Multiline)
        tunDohWhitelist.setOnBindEditTextListener(EditTextPreferenceModifiers.Multiline)
        tunDnsWhitelist.onPreferenceChangeListener = reloadListener
        tunDotWhitelist.onPreferenceChangeListener = reloadListener
        tunDohWhitelist.onPreferenceChangeListener = reloadListener
        val updateTunPrefVisibility = {
            val isVpn = DataStore.serviceMode == Key.MODE_VPN
            tunUnrecognizedTraffic.isVisible = isVpn
            tunSystemDnsTraffic.isVisible = isVpn
            tunDnsWhitelist.isVisible = isVpn
            tunDotWhitelist.isVisible = isVpn
            tunDohWhitelist.isVisible = isVpn
        }
        updateTunPrefVisibility()

        serviceMode.setOnPreferenceChangeListener { _, newValue ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            updateTunPrefVisibility()
            if (newValue == Key.MODE_PROXY) {
                Toast.makeText(requireContext(), R.string.proxy_ip_leak_warning, Toast.LENGTH_LONG).show()
            }
            true
        }
        connectionGuard.onPreferenceChangeListener = reloadListener
        overloadWatchdog.onPreferenceChangeListener = reloadListener
        memoryLimit.setOnPreferenceChangeListener { _, newValue ->
            val bytesPerMegabyte = 1024L * 1024L
            val value = newValue.toString().toLongOrNull() ?: return@setOnPreferenceChangeListener false
            if (value < 0 || value > Long.MAX_VALUE / bytesPerMegabyte) {
                return@setOnPreferenceChangeListener false
            }
            needReload()
            true
        }

        val tunImplementation = findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)!!
        val resolveDestination = findPreference<MaterialSwitchPreference>(Key.RESOLVE_DESTINATION)!!
        if (DataStore.trafficSniffing > 1) {
            DataStore.trafficSniffing = 1
            trafficSniffing.value = "1"
        }
        val acquireWakeLock = findPreference<MaterialSwitchPreference>(Key.ACQUIRE_WAKE_LOCK)!!
        val hideFromRecentApps = findPreference<MaterialSwitchPreference>(Key.HIDE_FROM_RECENT_APPS)!!
        val enableClashAPI = findPreference<MaterialSwitchPreference>(Key.ENABLE_CLASH_API)!!
        val hideClashAPI = findPreference<MaterialSwitchPreference>(Key.HIDE_CLASH_API)!!
        val resetClashApiSecret = findPreference<Preference>("resetClashApiSecret")!!
        enableClashAPI.setOnPreferenceChangeListener { _, newValue ->
            (activity as MainActivity?)?.refreshNavMenu(newValue as Boolean)
            needReload()
            true
        }
        hideClashAPI.setOnPreferenceChangeListener { _, _ ->
            needReload()
            true
        }
        resetClashApiSecret.setOnPreferenceClickListener {
            DataStore.clashApiSecret = Util.generateCryptoSecurePassword(16, Util.securePasswordCharsNoSymbols)
            needRestart()
            true
        }

        val rulesProvider = findPreference<SimpleMenuPreference>(Key.RULES_PROVIDER)!!
        val rulesGeositeUrl = findPreference<EditTextPreference>(Key.RULES_GEOSITE_URL)!!
        val rulesGeoipUrl = findPreference<EditTextPreference>(Key.RULES_GEOIP_URL)!!
        rulesGeositeUrl.isVisible = DataStore.rulesProvider == DataStore.RULES_PROVIDER_CUSTOM
        rulesGeoipUrl.isVisible = DataStore.rulesProvider == DataStore.RULES_PROVIDER_CUSTOM
        rulesProvider.setOnPreferenceChangeListener { _, newValue ->
            val provider = (newValue as String).toInt()
            rulesGeositeUrl.isVisible = provider == DataStore.RULES_PROVIDER_CUSTOM
            rulesGeoipUrl.isVisible = provider == DataStore.RULES_PROVIDER_CUSTOM
            true
        }

        requireProxyInVPN.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                Toast.makeText(requireContext(), R.string.proxy_ip_leak_warning, Toast.LENGTH_LONG).show()
            }
            needReload()
            true
        }
        disableUdpForLocalProxy.onPreferenceChangeListener = reloadListener

        mixedListener.setOnPreferenceChangeListener { _, newValue ->
            if (!isPureIpAddress((newValue as? String)?.takeIf { it.isNotEmpty() } ?: "")) {
                Toast.makeText(requireContext(), R.string.invalid_value, Toast.LENGTH_LONG).show()
                return@setOnPreferenceChangeListener false
            } else {
                needReload()
                true
            }
        }

        mixedPort.onPreferenceChangeListener = reloadListener
        mixedUsername.onPreferenceChangeListener = reloadListener
        mixedPassword.onPreferenceChangeListener = reloadListener
        appendHttpProxy.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                Toast.makeText(requireContext(), R.string.proxy_ip_leak_warning, Toast.LENGTH_LONG).show()
            }
            needReload()
            true
        }
        strictRoute.onPreferenceChangeListener = reloadListener
        showDirectSpeed.onPreferenceChangeListener = reloadListener
        persistentStatusNotification.onPreferenceChangeListener = reloadListener
        trafficSniffing.onPreferenceChangeListener = reloadListener
        bypassLan.onPreferenceChangeListener = reloadListener
        bypassLanInCore.onPreferenceChangeListener = reloadListener
        mtu.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue.toString().toIntOrNull() ?: return@setOnPreferenceChangeListener false
            if (value !in 200..9000) return@setOnPreferenceChangeListener false
            needReload()
            true
        }

        fun activeDnsLineCount(value: String?): Int =
            value.orEmpty()
                .lineSequence()
                .count { line ->
                    val trimmed = line.trim()
                    trimmed.isNotEmpty() && !trimmed.startsWith("#")
                }

        fun updateDnsDeadlineEnablement(
            remoteValue: String? = remoteDns.text,
            directValue: String? = directDns.text,
        ) {
            remoteDnsDeadline.isEnabled = activeDnsLineCount(remoteValue) > 1
            directDnsDeadline.isEnabled = activeDnsLineCount(directValue) > 1
        }

        updateDnsDeadlineEnablement()
        enableFakeDns.onPreferenceChangeListener = reloadListener
        dnsDisableCache.onPreferenceChangeListener = reloadListener
        dnsDisableExpire.onPreferenceChangeListener = reloadListener
        dnsCacheCapacity.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue.toString()
            if (value.isNotEmpty() && (value.toIntOrNull() ?: 0) < 1024) {
                Toast.makeText(requireContext(), R.string.dns_cache_capacity_invalid, Toast.LENGTH_LONG).show()
                return@setOnPreferenceChangeListener false
            }
            needReload()
            true
        }
        dnsReverseMapping.onPreferenceChangeListener = reloadListener
        remoteDns.setOnPreferenceChangeListener { _, newValue ->
            updateDnsDeadlineEnablement(remoteValue = newValue as? String)
            needReload()
            true
        }
        remoteDnsDeadline.onPreferenceChangeListener = reloadListener
        directDns.setOnPreferenceChangeListener { _, newValue ->
            updateDnsDeadlineEnablement(directValue = newValue as? String)
            needReload()
            true
        }
        directDnsDeadline.onPreferenceChangeListener = reloadListener
        customDnsServers.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), CustomDnsServersActivity::class.java))
            true
        }
        dnsDomainOverrides.onPreferenceChangeListener = reloadListener
        enableDnsRouting.onPreferenceChangeListener = reloadListener

        ipv6Mode.onPreferenceChangeListener = reloadListener
        allowAccess.onPreferenceChangeListener = reloadListener

        resolveDestination.onPreferenceChangeListener = reloadListener
        tunImplementation.setOnPreferenceChangeListener { _, newValue ->
            val implementation = newValue.toString().toIntOrNull()
                ?: return@setOnPreferenceChangeListener false
            if (LocalNetworkPermission.isRequired(requireContext(), implementation)) {
                pendingTunImplementation = implementation
                requestLocalNetworkPermission.launch(LocalNetworkPermission.NAME)
                false
            } else {
                needReload()
                true
            }
        }
        acquireWakeLock.onPreferenceChangeListener = reloadListener
        certProvider.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }
        hideFromRecentApps.setOnPreferenceChangeListener { _, newValue ->
            (activity as? MainActivity)?.applyHideFromRecentApps(newValue as Boolean)
            // needReload()
            true
        }

        fun updateTrafficFragmentationVisibility(value: String = DataStore.trafficFragmentation) {
            fragmentLength.isVisible = value == TrafficFragmentation.STARIFLY
            fragmentInterval.isVisible = value == TrafficFragmentation.STARIFLY
            exclaveFragmentMethod.isVisible = value == TrafficFragmentation.EXCLAVE
            exclaveFragmentForDirect.isVisible = value == TrafficFragmentation.EXCLAVE
            byedpiFragmentCli.isVisible = value == TrafficFragmentation.BYEDPI
        }

        if (trafficFragmentation.value != DataStore.trafficFragmentation) {
            trafficFragmentation.value = DataStore.trafficFragmentation
        }
        updateTrafficFragmentationVisibility()
        trafficFragmentation.setOnPreferenceChangeListener { _, newValue ->
            updateTrafficFragmentationVisibility(newValue as String)
            needReload()
            true
        }
        fragmentLength.onPreferenceChangeListener = reloadListener
        fragmentInterval.onPreferenceChangeListener = reloadListener
        exclaveFragmentMethod.onPreferenceChangeListener = reloadListener
        exclaveFragmentForDirect.onPreferenceChangeListener = reloadListener
        byedpiFragmentCli.onPreferenceChangeListener = reloadListener

        // 恢复默认设置功能
        val resetSettings = findPreference<Preference>("resetSettings")!!
        resetSettings.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.confirm)
                setMessage(R.string.reset_settings_message)
                setNegativeButton(R.string.no, null)
                setPositiveButton(R.string.yes) { _, _ ->
                    AppIconManager.set(requireContext(), AppIcon.NEKOBOX_PLUS)
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
            }.show()
            true
        }

        // 清理缓存功能
        val clearCache = findPreference<Preference>(Key.CLEAR_CACHE)!!
        clearCache.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.clear_cache)
                setMessage(R.string.clear_cache_confirm)
                setPositiveButton(android.R.string.ok) { _, _ ->
                    clearAppCache()
                }
                setNegativeButton(android.R.string.cancel, null)
            }.show()
            true
        }

        findPreference<Preference>(Key.RUN_STORAGE_MAINTENANCE)!!.setOnPreferenceClickListener {
            startStorageMaintenance()
            true
        }
    }

    private fun applyLogLevel(logLevel: AppLogLevel) {
        AppLogLevelController.set(logLevel)
        runCatching { Libcore.setLogLevel(logLevel.singBoxName, logLevel.outputEnabled) }
            .onFailure { Logs.w("Unable to update app log level: ${it.message}") }

        val service = (activity as? MainActivity)?.connection?.service ?: return
        if (!hasLiveView()) return
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                runCatching { service.setLogLevel(logLevel.singBoxName, logLevel.outputEnabled) }
                    .onFailure { Logs.w("Unable to update background log level: ${it.message}") }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        syncProxyAppsPreference()
        if (::globalCustomConfig.isInitialized) {
            globalCustomConfig.notifyChanged()
        }
        syncCoreProfilerPreferences()
    }

    override fun onStart() {
        super.onStart()
        DataStore.configurationStore.registerChangeListener(this)
    }

    override fun onStop() {
        DataStore.configurationStore.unregisterChangeListener(this)
        super.onStop()
    }

    override fun onDestroyView() {
        pendingSearch?.let { mainHandler.removeCallbacks(it) }
        pendingSearch = null
        pendingCacheReload?.let { mainHandler.removeCallbacks(it) }
        pendingCacheReload = null
        dismissCoreProfilerProgress()
        super.onDestroyView()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == Key.PROXY_APPS && ::isProxyApps.isInitialized) {
            syncProxyAppsPreference()
        }
    }

    private fun syncProxyAppsPreference() {
        if (!::isProxyApps.isInitialized) return
        syncingProxyAppsPreference = true
        isProxyApps.isChecked = DataStore.proxyApps
        syncingProxyAppsPreference = false
    }

    private fun syncCoreProfilerPreferences(enabled: Boolean = DataStore.enableCoreProfiling) {
        if (!::saveCoreProfilerSnapshot.isInitialized) return
        val coreActive = DataStore.serviceState.started
        saveCoreProfilerSnapshot.isVisible = enabled
        deleteCoreProfilerSnapshot.isVisible = enabled
        if (!enabled) return

        saveCoreProfilerSnapshot.isEnabled = !coreActive
        deleteCoreProfilerSnapshot.isEnabled = !coreActive

        if (coreActive) {
            val collecting = getString(R.string.core_profiler_collecting)
            saveCoreProfilerSnapshot.summary = collecting
            deleteCoreProfilerSnapshot.summary = collecting
            return
        }

        val profilerDataSize = coreProfilerDataSize()
        saveCoreProfilerSnapshot.summary = if (profilerDataSize > 0L) {
            getString(R.string.core_profiler_data_size, formatProfilerMegabytes(profilerDataSize))
        } else {
            getString(R.string.core_profiler_no_data)
        }
        deleteCoreProfilerSnapshot.summary = null
    }

    private fun coreProfilerService(): ISagerNetService? {
        return (activity as? MainActivity)?.connection?.service
    }

    private fun handlePerformLibcoreGcSweep() {
        val service = coreProfilerService()
        if (service == null || !DataStore.serviceState.started) {
            Toast.makeText(requireContext(), R.string.service_is_not_running, Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasLiveView()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.Default) {
                try {
                    service.performLibcoreGcSweep()
                    true
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    Logs.w(e)
                    false
                }
            }
            if (!hasLiveView()) return@launch
            showCoreProfilerToast(if (success) R.string.done else R.string.service_is_not_running)
        }
    }

    private fun handlePerformLibcoreManualCrash() {
        val service = coreProfilerService()
        if (service == null || !DataStore.serviceState.started) {
            Toast.makeText(requireContext(), R.string.service_is_not_running, Toast.LENGTH_SHORT).show()
            return
        }
        val crashOptions = LibcoreCrashOption.entries.toTypedArray()
        val labels = crashOptions.map { getString(it.titleRes) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.perform_libcore_fake_crash)
            .setItems(labels) { _, which ->
                triggerLibcoreCrash(service, crashOptions[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun triggerLibcoreCrash(service: ISagerNetService, crashOption: LibcoreCrashOption) {
        if (!hasLiveView()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val crashed = withContext(Dispatchers.Default) {
                try {
                    service.triggerLibcoreCrash(crashOption.type)
                    false
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    Logs.w(e)
                    true
                }
            }
            if (!hasLiveView()) return@launch
            showCoreProfilerToast(if (crashed) R.string.done else R.string.fail_no_crash)
        }
    }

    private fun handleCoreProfilingChanged(enabled: Boolean) {
        if (!DataStore.serviceState.connected) return
        val service = coreProfilerService() ?: return
        if (!hasLiveView()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.Default) {
                try {
                    if (enabled) {
                        service.startCoreProfiling()
                    } else {
                        service.stopCoreProfiling()
                    }
                    null
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    Logs.w(e)
                    e
                }
            }
            if (!hasLiveView()) return@launch
            if (error != null) showCoreProfilerError(error)
            syncCoreProfilerPreferences(enabled)
        }
    }

    private fun handleSaveCoreProfilerSnapshot() {
        val service = coreProfilerService()
        if (!hasLiveView()) return
        showCoreProfilerProgress()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val exportRoot = File(app.cacheDir, "core-profiler-export").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    val profilerDir = File(exportRoot, "profiler").apply { mkdirs() }
                    if (service != null && DataStore.serviceState.started) {
                        service.writeCoreProfilerSnapshot(profilerDir.absolutePath)
                    } else if (!copyLocalProfilerSnapshot(profilerDir)) {
                        return@withContext CoreProfilerSaveResult.NoSnapshot
                    }

                    val zipFile = File(
                        File(app.cacheDir, "log").also { it.mkdirs() },
                        "NB4A-profiler-${profilerTimestamp()}.zip"
                    )
                    writeProfilerZip(zipFile, profilerDir)
                    CoreProfilerSaveResult.Saved(zipFile)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    CoreProfilerSaveResult.Failed(e)
                }
            }
            if (!hasLiveView()) return@launch
            dismissCoreProfilerProgress()
            when (result) {
                is CoreProfilerSaveResult.Saved -> {
                    shareProfilerZip(result.zipFile)
                    syncCoreProfilerPreferences()
                    showCoreProfilerToast(R.string.core_profiler_snapshot_saved)
                }
                is CoreProfilerSaveResult.Failed -> showCoreProfilerError(result.error)
                CoreProfilerSaveResult.NoSnapshot -> showCoreProfilerToast(R.string.core_profiler_no_snapshot)
            }
        }
    }

    private fun handleDeleteCoreProfilerSnapshot() {
        val service = coreProfilerService()
        if (!hasLiveView()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.Default) {
                try {
                    service?.deleteCoreProfilerSnapshot()
                    deleteLocalCoreProfilerData()
                    null
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    e
                }
            }
            if (!hasLiveView()) return@launch
            if (error != null) {
                showCoreProfilerError(error)
                return@launch
            }
            syncCoreProfilerPreferences()
            showCoreProfilerToast(R.string.core_profiler_snapshot_deleted)
        }
    }

    fun syncServiceState() {
        syncCoreProfilerPreferences()
    }

    private fun deleteLocalCoreProfilerData() {
        File(app.cacheDir, "core-profiler").deleteRecursively()
        File(app.cacheDir, "core-profiler-export").deleteRecursively()
    }

    private fun coreProfilerDataSize(): Long {
        val sourceDir = File(app.cacheDir, "core-profiler")
        if (!sourceDir.exists()) return 0L
        return sourceDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length().coerceAtLeast(0L) }
    }

    private fun formatProfilerMegabytes(bytes: Long): String {
        val megabytes = bytes.toDouble() / (1024.0 * 1024.0)
        val visibleMegabytes = if (megabytes > 0.0) maxOf(0.1, megabytes) else 0.0
        val pattern = if (visibleMegabytes < 10.0) "%.1f" else "%.0f"
        return String.format(Locale.US, pattern, visibleMegabytes)
    }

    private fun showCoreProfilerError(error: Throwable) {
        if (!hasLiveView()) return
        val toastContext = context ?: return
        val message = error.readableMessage
        val resId = when {
            message.contains("Core is not started yet", ignoreCase = true) -> R.string.core_not_started_yet
            message.contains("no profiler snapshot", ignoreCase = true) -> R.string.core_profiler_no_snapshot
            else -> null
        }
        if (resId != null) {
            showCoreProfilerToast(resId)
        } else {
            Toast.makeText(
                toastContext,
                toastContext.getString(R.string.core_profiler_failed, message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showCoreProfilerToast(resId: Int) {
        if (!hasLiveView()) return
        Toast.makeText(context ?: return, resId, Toast.LENGTH_SHORT).show()
    }

    private fun hasLiveView(): Boolean {
        return isAdded && view != null
    }

    private fun showCoreProfilerProgress() {
        if (coreProfilerProgressDialog?.isShowing == true) return
        val dialogContext = context ?: return
        val progress = ProgressBar(dialogContext).apply {
            isIndeterminate = true
        }
        val container = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * dialogContext.resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(progress)
        }
        coreProfilerProgressDialog = MaterialAlertDialogBuilder(dialogContext)
            .setTitle(R.string.core_profiler_saving)
            .setView(container)
            .setCancelable(false)
            .show()
    }

    private fun dismissCoreProfilerProgress() {
        coreProfilerProgressDialog?.dismiss()
        coreProfilerProgressDialog = null
    }

    private fun writeProfilerZip(zipFile: File, profilerDir: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            addTextEntry(zip, "report-header.txt", CrashHandler.buildReportHeader())
            addLogcatEntry(zip)
            addByteEntry(zip, "neko.log", SendLog.getNekoLog(0))
            profilerDir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(profilerDir).invariantSeparatorsPath }
                .forEach { file ->
                    addFileEntry(zip, "profiler/${file.relativeTo(profilerDir).invariantSeparatorsPath}", file)
                }
        }
    }

    private fun copyLocalProfilerSnapshot(outputDir: File): Boolean {
        val sourceDir = File(app.cacheDir, "core-profiler")
        if (!sourceDir.exists()) return false
        val profilerFiles = sourceDir.listFiles()?.filter { it.isFile && it.length() > 0L } ?: return false
        if (profilerFiles.isEmpty()) return false
        profilerFiles.forEach { source ->
            source.copyTo(File(outputDir, source.name), overwrite = true)
        }
        return true
    }

    private fun addLogcatEntry(zip: ZipOutputStream) {
        try {
            Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream.use { input ->
                zip.putNextEntry(ZipEntry("logcat.txt"))
                input.copyTo(zip)
                zip.closeEntry()
            }
        } catch (e: IOException) {
            addTextEntry(zip, "logcat.txt", "Export logcat error: " + CrashHandler.formatThrowable(e))
        }
    }

    private fun addTextEntry(zip: ZipOutputStream, name: String, text: String) {
        addByteEntry(zip, name, text.toByteArray(Charsets.UTF_8))
    }

    private fun addByteEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun addFileEntry(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun shareProfilerZip(zipFile: File) {
        if (!hasLiveView()) return
        val shareContext = context ?: return
        shareContext.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("application/zip")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                            shareContext, BuildConfig.APPLICATION_ID + ".cache", zipFile
                        )
                    ), shareContext.getString(R.string.abc_shareactionprovider_share_with)
            )
        )
    }

    private fun profilerTimestamp(): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }

    private fun clearAppCache() {
        val appContext = SagerNet.application
        try {
            val cacheDir = appContext.cacheDir
            clearDirFiles(cacheDir, skipFiles = setOf("neko.log"))
            
            val parentDir = cacheDir.parentFile
            val relativeCache = File(parentDir, "cache")
            if (relativeCache.exists() && relativeCache.isDirectory) {
                clearDirFiles(relativeCache)
            }
            
            Toast.makeText(appContext, R.string.clear_cache_success, Toast.LENGTH_SHORT).show()
            
            pendingCacheReload?.let { mainHandler.removeCallbacks(it) }
            pendingCacheReload = Runnable {
                pendingCacheReload = null
                needReload()
            }.also {
                mainHandler.postDelayed(it, 500)
            }
        } catch (e: Exception) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.clear_cache_failed, e.message),
                Toast.LENGTH_SHORT
            ).show()
            e.printStackTrace()
        }
    }

    private fun startStorageMaintenance() {
        if (!hasLiveView()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val stopped = withContext(Dispatchers.Default) { stopServiceForStorageMaintenance() }
            if (!hasLiveView()) return@launch
            if (!stopped) {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.storage_maintenance_stop_failed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            if (!hasEnoughStorageForMaintenance()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.storage_maintenance_insufficient_space)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            val confirmationDialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.run_storage_maintenance)
                .setMessage(R.string.storage_maintenance_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create()
            confirmationDialog.setOnShowListener {
                confirmationDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    confirmationDialog.dismiss()
                    runStorageMaintenance()
                }
            }
            confirmationDialog.show()
        }
    }

    private suspend fun stopServiceForStorageMaintenance(): Boolean {
        if (DataStore.serviceState == BaseService.State.Stopped || DataStore.serviceState == BaseService.State.Idle) return true
        SagerNet.stopService()
        repeat(300) {
            if (DataStore.serviceState == BaseService.State.Stopped || DataStore.serviceState == BaseService.State.Idle) return true
            delay(100)
        }
        return false
    }

    private fun hasEnoughStorageForMaintenance(): Boolean {
        val appData = File(SagerNet.application.applicationInfo.dataDir)
        val usedBytes = appData.walkTopDown().filter { it.isFile }
            .sumOf { it.length().coerceAtLeast(0L) }
        return StatFs(appData.absolutePath).availableBytes >= usedBytes.coerceAtLeast(1L) * 2
    }

    private fun runStorageMaintenance() {
        if (!hasLiveView()) return
        Log.i("StorageMaintenance", "maintenance confirmed")
        val progress = ProgressBar(requireContext()).apply {
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.storage_maintenance_working)
            .setView(progress)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        val enabledUrls = enabledAdblockFilterUrls().joinToString("\n")
        val routingRuleCacheKeys = enabledRoutingRuleCacheKeys().joinToString("\n")
        viewLifecycleOwner.lifecycleScope.launch {
            val failure = withContext(Dispatchers.Default) {
                runCatching {
                    Libcore.performStorageMaintenance(DataStore.adblockEnabled, enabledUrls, routingRuleCacheKeys)
                    vacuumDatabases()
                    clearDisposableCache()
                }.exceptionOrNull()
            }
            if (!hasLiveView()) return@launch
            dialog.dismiss()
            if (failure == null) {
                Log.i("StorageMaintenance", "maintenance completed; restarting app")
                ProcessPhoenix.triggerRebirth(requireContext(), Intent(requireContext(), MainActivity::class.java))
            } else {
                Log.e("StorageMaintenance", "maintenance failed", failure)
                Logs.e(failure)
                showStorageMaintenanceFailure()
            }
        }
    }

    private fun enabledAdblockFilterUrls(): Set<String> {
        if (!DataStore.adblockEnabled) return emptySet()
        val selectedBundledFilters = AdblockRepository.ensureBundledDefaults()
        return buildSet {
            AdblockRepository.catalog.filter { it.id in selectedBundledFilters }
                .flatMap { it.sources }.map { it.url.trim() }
                .filterTo(this) { it.isNotBlank() }
            AdblockRepository.customFilters().filter(AdblockRepository::customFilterEnabled)
                .map { it.url.trim() }.filterTo(this) { it.isNotBlank() }
        }
    }

    private fun enabledRoutingRuleCacheKeys(): Set<String> {
        return SagerDatabase.rulesDao.enabledRules().asSequence()
            .flatMap { rule ->
                when (RuleType.fromValue(rule.type)) {
                    RuleType.DNS -> rule.ruleset.listByLineOrComma().asSequence()
                    else -> sequenceOf(
                        rule.domains.listByLineOrComma(),
                        rule.ip.listByLineOrComma(),
                    ).flatten()
                }
            }
            .map(String::trim)
            .mapNotNull { rawKey ->
                when {
                    rawKey.startsWith("geoip:", ignoreCase = true) -> "geoip:${rawKey.substringAfter(':')}"
                    rawKey.startsWith("geosite:", ignoreCase = true) -> "geosite:${rawKey.substringAfter(':')}"
                    else -> null
                }
            }
            .filterNot { it == "geoip:private" }
            .toSet()
    }

    private fun vacuumDatabases() {
        SagerDatabase.proxyDao.clearTestResults()

        listOf(SagerDatabase.instance, PublicDatabase.instance).forEach { database ->
            database.openHelper.writableDatabase.execSQL("VACUUM")
        }
    }

    private fun clearDisposableCache() {
        val preserved = setOf("cache.db", "adblock.db", "routing-rules-cache.db")
        SagerNet.application.cacheDir.listFiles()?.forEach { file ->
            when {
                file.name == "neko.log" -> file.writeText("")
                file.name in preserved -> Unit
                !file.deleteRecursively() && file.exists() -> error("Unable to remove ${file.name}")
            }
        }
        removeStorageMaintenancePath(SagerNet.application.codeCacheDir)
        removeStorageMaintenancePath(File(SagerNet.application.applicationInfo.dataDir, "app_textures"))
        if (!DataStore.enableClashAPI) {
            removeStorageMaintenancePath(File(SagerNet.application.filesDir, "metacubexd"))
            removeStorageMaintenancePath(File(SagerNet.application.filesDir, "metacubexd.version.txt"))
        }
    }

    private fun removeStorageMaintenancePath(path: File) {
        if (path.exists() && !path.deleteRecursively() && path.exists()) {
            error("Unable to remove ${path.name}")
        }
    }

    private fun showStorageMaintenanceFailure() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.storage_maintenance_failed)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                activity?.finishAffinity()
                Process.killProcess(Process.myPid())
            }
            .show()
    }
    
    private fun clearDirFiles(dir: File, skipFiles: Set<String> = emptySet()): Boolean {
        if (dir.isDirectory) {
            val children = dir.list() ?: return true
            
            for (child in children) {
                val childFile = File(dir, child)
                
                if (child == "neko.log") {
                    try {
                        childFile.writeText("")
                        continue
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                if (child in skipFiles) {
                    continue
                }
                
                if (childFile.isDirectory) {
                    clearDirFiles(childFile, skipFiles)
                } else {
                    childFile.delete()
                }
            }
            
            return true
        }
        return false
    }

    private fun mode(): Mode {
        return when (arguments?.getString(ARG_MODE, MODE_TOP_LEVEL)) {
            MODE_GROUP -> Mode.GROUP
            MODE_SEARCH -> Mode.SEARCH
            else -> Mode.TOP_LEVEL
        }
    }

    private fun groupId(): String {
        return arguments?.getString(ARG_GROUP_ID) ?: GROUPS.first().id
    }

    private fun buildTopLevelPreferences() {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        GROUPS.forEach { group ->
            screen.addPreference(Preference(requireContext()).apply {
                key = group.id
                setTitle(group.titleRes)
                setSummary(group.descriptionRes)
                setIcon(group.iconRes)
                setOnPreferenceClickListener {
                    (parentFragment as? SettingsFragment)?.openGroup(group.id)
                    true
                }
            })
        }
        preferenceScreen = screen
    }

    private fun buildGroupPreferences(groupId: String) {
        val group = GROUP_BY_ID[groupId] ?: GROUPS.first()
        val originalGroups = preferenceScreen.visibleChildren().filterIsInstance<PreferenceCategory>()

        group.keys.forEachIndexed { index, key ->
            val preference = findPreference<Preference>(key) ?: return@forEachIndexed
            preference.parentPreferenceGroup()?.removePreference(preference)
            preference.order = index
            preferenceScreen.addPreference(preference)
        }

        originalGroups.forEach { it.isVisible = false }
    }

    fun updateSearch(query: String) {
        pendingSearch?.let { mainHandler.removeCallbacks(it) }
        pendingSearch = Runnable { buildSearchPreferences(query) }.also {
            mainHandler.postDelayed(it, 180L)
        }
    }

    private fun buildSearchPreferences(query: String) {
        if (searchItems.isEmpty()) {
            addPreferencesFromResource(R.xml.global_preferences)
            searchItems = collectSearchItems()
        }

        val trimmed = query.trim()
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        if (trimmed.isNotEmpty()) {
            searchItems.asSequence()
                .mapNotNull { item -> item.match(trimmed)?.let { match -> match to item } }
                .sortedWith(
                    compareBy<Pair<SearchMatch, SearchItem>> { it.searchResultLayer() }
                        .thenByDescending { it.first.weight }
                        .thenBy { it.second.title.toString() }
                )
                .take(50)
                .forEach { (_, item) ->
                    screen.addPreference(SearchResultPreference(requireContext(), item.visibleInGroup).apply {
                        key = "search:${item.key}"
                        title = item.title.highlight(trimmed)
                        summary = item.summary?.highlight(trimmed)
                        icon = item.icon?.constantState?.newDrawable(resources)?.mutate() ?: item.icon
                        isIconSpaceReserved = item.icon != null
                        setOnPreferenceClickListener {
                            (parentFragment as? SettingsFragment)?.openGroup(item.groupId, item.key)
                            true
                        }
                    })
                }
        }
        preferenceScreen = screen
    }

    private fun collectSearchItems(): List<SearchItem> {
        val englishResources = englishResources()
        val englishStrings = readEnglishPreferenceStrings(englishResources)
        return GROUPS.flatMap { group ->
            group.keys.mapNotNull { key ->
                val preference = findPreference<Preference>(key) ?: return@mapNotNull null
                val english = englishStrings[key]
                SearchItem(
                    key = key,
                    groupId = group.id,
                    title = preference.title ?: return@mapNotNull null,
                    summary = preference.summary,
                    icon = preference.icon,
                    englishTitle = english?.first.orEmpty(),
                    englishSummary = english?.second.orEmpty(),
                    visibleInGroup = isSearchItemVisible(key),
                )
            }
        }
    }

    private fun isSearchItemVisible(key: String): Boolean {
        return when (key) {
            KEY_CONFIGURE_CUSTOM_THEME -> DataStore.appTheme == Theme.CUSTOM && CustomTheme.isSupported
            Key.METERED_NETWORK -> Build.VERSION.SDK_INT >= 28
            Key.TUN_UNRECOGNIZED_TRAFFIC,
            Key.TUN_SYSTEM_DNS_TRAFFIC,
            Key.TUN_DNS_WHITELIST,
            Key.TUN_DOT_WHITELIST,
            Key.TUN_DOH_WHITELIST -> DataStore.serviceMode == Key.MODE_VPN
            Key.RULES_GEOSITE_URL,
            Key.RULES_GEOIP_URL -> DataStore.rulesProvider == DataStore.RULES_PROVIDER_CUSTOM
            Key.FRAGMENT_LENGTH,
            Key.FRAGMENT_INTERVAL -> DataStore.trafficFragmentation == TrafficFragmentation.STARIFLY
            Key.EXCLAVE_FRAGMENT_METHOD,
            Key.EXCLAVE_FRAGMENT_FOR_DIRECT -> DataStore.trafficFragmentation == TrafficFragmentation.EXCLAVE
            Key.BYEDPI_FRAGMENT_CLI -> DataStore.trafficFragmentation == TrafficFragmentation.BYEDPI
            Key.SAVE_CORE_PROFILER_SNAPSHOT,
            Key.DELETE_CORE_PROFILER_SNAPSHOT -> DataStore.enableCoreProfiling
            else -> true
        }
    }

    private fun englishResources(): android.content.res.Resources {
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(Locale.ENGLISH)
        return requireContext().createConfigurationContext(configuration).resources
    }

    private fun readEnglishPreferenceStrings(
        englishResources: android.content.res.Resources,
    ): Map<String, Pair<String, String>> {
        val result = LinkedHashMap<String, Pair<String, String>>()
        val parser = resources.getXml(R.xml.global_preferences)
        val appNamespace = "http://schemas.android.com/apk/res-auto"
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        parser.use {
            while (it.next() != XmlPullParser.END_DOCUMENT) {
                if (it.eventType != XmlPullParser.START_TAG) continue
                val key = it.getAttributeValue(appNamespace, "key") ?: continue
                val titleRes = it.getAttributeResourceValue(appNamespace, "title", 0)
                    .takeIf { res -> res != 0 }
                    ?: it.getAttributeResourceValue(androidNamespace, "title", 0)
                val summaryRes = it.getAttributeResourceValue(appNamespace, "summary", 0)
                    .takeIf { res -> res != 0 }
                    ?: it.getAttributeResourceValue(androidNamespace, "summary", 0)
                val title = if (titleRes != 0) englishResources.getString(titleRes) else ""
                val summary = if (summaryRes != 0) englishResources.getString(summaryRes) else ""
                result[key] = title to summary
            }
        }
        return result
    }

    private fun SearchItem.match(query: String): SearchMatch? {
        val normalizedQuery = query.normalizedForSearch()
        if (normalizedQuery.isBlank()) return null

        val currentWeight = matchWeight(
            title.toString(),
            summary?.toString().orEmpty(),
            normalizedQuery,
        )
        val englishWeight = matchWeight(englishTitle, englishSummary, normalizedQuery)
        return when {
            currentWeight != null -> SearchMatch(currentWeight, englishOnly = false)
            englishWeight != null -> SearchMatch(englishWeight, englishOnly = true)
            else -> null
        }
    }

    private fun matchWeight(
        title: String,
        summary: String,
        normalizedQuery: String,
    ): Int? {
        val titleStartMatches = title.matchesSearchStart(normalizedQuery)
        val summaryStartMatches = summary.matchesSearchStart(normalizedQuery)
        val titleMatches = title.matchesSearch(normalizedQuery)
        val summaryMatches = summary.matchesSearch(normalizedQuery)
        return when {
            titleStartMatches && summaryStartMatches -> 7
            titleStartMatches && summaryMatches -> 6
            titleStartMatches -> 5
            titleMatches && summaryStartMatches -> 4
            titleMatches && summaryMatches -> 3
            titleMatches -> 2
            summaryMatches -> 1
            else -> null
        }
    }

    private fun Pair<SearchMatch, SearchItem>.searchResultLayer(): Int {
        val (match, item) = this
        return when {
            !item.visibleInGroup -> 2
            match.englishOnly -> 1
            else -> 0
        }
    }

    private fun String.matchesSearch(normalizedQuery: String): Boolean {
        return normalizedForSearch().contains(normalizedQuery)
    }

    private fun String.matchesSearchStart(normalizedQuery: String): Boolean {
        return normalizedForSearch().startsWith(normalizedQuery)
    }

    private fun CharSequence.highlight(query: String): CharSequence {
        val text = toString()
        val index = text.normalizedForSearch().indexOf(query.normalizedForSearch())
        if (index < 0) return text
        val spannable = SpannableString(text)
        spannable.setSpan(
            BackgroundColorSpan(requireContext().getColorAttr(R.attr.colorSecondaryContainer)),
            index,
            (index + query.length).coerceAtMost(text.length),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return spannable
    }

    private fun String.normalizedForSearch(): String {
        return Normalizer.normalize(lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
    }

    private fun highlightPreference(key: String) {
        scrollToPreference(key)
        listView.postDelayed({
            val position = preferenceScreen.flattenVisiblePreferences()
                .indexOfFirst { it.key == key }
            if (position < 0) return@postDelayed
            val holder = listView.findViewHolderForAdapterPosition(position) ?: return@postDelayed
            val view = holder.itemView
            val originalBackground = view.background
            val stroke = (2 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
            view.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(stroke, requireContext().getColorAttr(R.attr.colorPrimary))
                cornerRadius = 8 * resources.displayMetrics.density
            }
            view.postDelayed({
                view.background = originalBackground
            }, 1600L)
        }, 350L)
    }

    private fun Preference.parentPreferenceGroup(): PreferenceGroup? {
        return preferenceScreen.findParentOf(this)
    }

    private fun PreferenceGroup.findParentOf(target: Preference): PreferenceGroup? {
        visibleChildren(includeInvisible = true).forEach { child ->
            if (child === target) return this
            if (child is PreferenceGroup) {
                child.findParentOf(target)?.let { return it }
            }
        }
        return null
    }

    private fun PreferenceGroup.visibleChildren(includeInvisible: Boolean = false): List<Preference> {
        return (0 until preferenceCount)
            .map { getPreference(it) }
            .filter { includeInvisible || it.isVisible }
    }

    private fun PreferenceGroup.flattenVisiblePreferences(): List<Preference> {
        val result = mutableListOf<Preference>()
        visibleChildren().forEach { preference ->
            result.add(preference)
            if (preference is PreferenceGroup) {
                result.addAll(preference.flattenVisiblePreferences())
            }
        }
        return result
    }

    private class SearchResultPreference(
        context: android.content.Context,
        private val visibleResult: Boolean,
    ) : Preference(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            if (visibleResult) return
            (holder.findViewById(android.R.id.title) as? TextView)?.isEnabled = false
            (holder.findViewById(android.R.id.summary) as? TextView)?.isEnabled = false
        }
    }

    private object ProxyPasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {
        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "*".repeat(text.length)
            }
        }
    }

    private class ListSummaryProvider(
        private val maxLines: Int,
    ) : Preference.SummaryProvider<EditTextPreference> {
        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val lines = preference.text.orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
            if (lines.isEmpty()) {
                return preference.context.getString(androidx.preference.R.string.not_set)
            }
            return if (lines.size > maxLines) {
                lines.take(maxLines).joinToString("\n", postfix = "\n...")
            } else {
                lines.joinToString("\n")
            }
        }
    }

}
