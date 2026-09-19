package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.RemoteException
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.AppLogLevel
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.AmneziaApiKeyUnsupportedException
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.happCryptUnsupportedDialog
import io.nekohasekai.sagernet.ktx.isPlay
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.routing.RoutingLinkProcessors
import io.nekohasekai.sagernet.routing.RoutingImportManager
import io.nekohasekai.sagernet.routing.RoutingPreviewPayloadStore
import io.nekohasekai.sagernet.routing.RoutingProfileFormat
import io.nekohasekai.sagernet.ui.MessageStore
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.RoutingRulesService
import io.nekohasekai.sagernet.utils.CustomTheme
import io.nekohasekai.sagernet.utils.CustomThemeLink
import io.nekohasekai.sagernet.utils.CustomThemePreview
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.Util
import java.util.Locale

class MainActivity :
    ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {
    companion object {
        const val ACTION_SHOW_CONNECTION_TEST = "io.nekohasekai.sagernet.action.SHOW_CONNECTION_TEST"
        private var openSettingsOnCreate = false

        fun openSettingsOnNextCreate() {
            openSettingsOnCreate = true
        }

        private fun consumeOpenSettingsOnCreate(): Boolean {
            val openSettings = openSettingsOnCreate
            openSettingsOnCreate = false
            return openSettings
        }
    }

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView
    private var proxyAppsDrawerSwitch: MaterialSwitch? = null
    private var syncingProxyAppsDrawerSwitch = false
    private var bottomControlsVisibleForCurrentFragment = false
    private var activityStarted = false
    private var renderedServiceState = BaseService.State.Idle
    private var currentServiceProfileName: String? = null
    private var masterDnsVPNConnectedToastShown = false
    private var restoreConnectionTestLifecycleCallback: FragmentManager.FragmentLifecycleCallbacks? =
        null
    private var customThemePreviewDialog: AlertDialog? = null
    private var customThemePreviewTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MessageStore.setCurrentActivity(this)
        val openSettingsOnCreate = consumeOpenSettingsOnCreate()

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        navigation = binding.navView
        navigation.setNavigationItemSelectedListener(this)
        setupProxyAppsDrawerItem()

        binding.fab.setOnClickListener {
            if (DataStore.serviceState.canStop) {
                SagerNet.stopService()
            } else {
                // ПРОВЕРКА БАЗ ПЕРЕД ЗАПУСКОМ VPN
                val filesDir = getExternalFilesDir(null) ?: filesDir
                val geoip = java.io.File(filesDir, "geoip.db")
                val geosite = java.io.File(filesDir, "geosite.db")

                if (!geoip.exists() || !geosite.exists()) {
                    // Баз нет! Показываем красивое окно и отправляем качать
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.geodb_update_needed)
                        .setMessage(R.string.geodb_update_needed_message)
                        .setPositiveButton(R.string.download) { _, _ ->
                            startActivity(Intent(this, AssetsActivity::class.java))
                        }.setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    // Базы есть, запускаем VPN
                    connect.launch(null)
                }
            }
        }
        binding.stats.setOnClickListener {
            if (binding.stats.isEnabled && DataStore.serviceState.connected) binding.stats.testConnection()
        }

        if (savedInstanceState == null && !openSettingsOnCreate) {
            displayFragmentWithId(R.id.nav_configuration)
        }
        onBackPressedDispatcher.addCallback {
            val fragment = supportFragmentManager.findFragmentById(R.id.fragment_holder)
            if ((fragment as? ToolbarFragment)?.onBackPressed() == true) {
                return@addCallback
            }
            if (fragment is ConfigurationFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_configuration)
            }
        }

        setContentView(binding.root)
        if (DataStore.legacyMainView) {
            setupLegacyNavigationBarInsets()
        }
        if (openSettingsOnCreate) {
            displayFragmentWithId(R.id.nav_settings)
            supportFragmentManager.executePendingTransactions()
        }
        binding.fab.bringToFront()
        binding.fabProgress.bringToFront()
        changeState(BaseService.State.Idle)
        if (savedInstanceState != null) {
            updateBottomControlsVisibility(animate = false)
        }
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        showPendingCustomThemePreview()

        if (intent?.action == Intent.ACTION_VIEW || intent?.action == ACTION_SHOW_CONNECTION_TEST) {
            onNewIntent(intent)
        }

        refreshNavMenu(DataStore.enableClashAPI)

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                // 动态申请
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(POST_NOTIFICATIONS),
                    0,
                )
            }
        }

        if (isPreview) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        if (!DataStore.proxyAppsFirstSetup) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { PackageCache.awaitLoadSync() }
                performProxyAppsFirstSetup()
            }
        }
    }

    private fun setupLegacyNavigationBarInsets() {
        val coordinatorInitialLeft = binding.coordinator.paddingLeft
        val coordinatorInitialRight = binding.coordinator.paddingRight
        val coordinatorInitialBottom = binding.coordinator.paddingBottom
        val navigationInitialLeft = binding.navView.paddingLeft
        val navigationInitialRight = binding.navView.paddingRight
        val navigationInitialBottom = binding.navView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout) { _, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.coordinator.updatePadding(
                left = coordinatorInitialLeft + navigationBars.left,
                right = coordinatorInitialRight + navigationBars.right,
                bottom = coordinatorInitialBottom + navigationBars.bottom,
            )
            binding.navView.updatePadding(
                left = navigationInitialLeft + navigationBars.left,
                right = navigationInitialRight + navigationBars.right,
                bottom = navigationInitialBottom + navigationBars.bottom,
            )
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
                .build()
        }
        ViewCompat.requestApplyInsets(binding.drawerLayout)
    }

    private suspend fun performProxyAppsFirstSetup() {
        DataStore.proxyApps = true
        DataStore.bypass = false
        DataStore.proxyAppsFirstSetup = true

        val dir = detectRoutingDir()
        if (dir != null) {
            applyFirstRunSelection(dir)
        } else {
            showFirstRunRegionPicker()
        }
    }

    private fun detectRoutingDir(): String? =
        when (Locale.getDefault().country.uppercase()) {
            "RU" -> "ru"
            "CN" -> "cn"
            "IR" -> "ir"
            else -> null
        }

    private fun applyFirstRunSelection(routingDir: String) {
        DataStore.firstRunRoutingRegion = routingDir
        val service = RoutingRulesService(this, routingDir)
        val selected = service.computeProxiedPackages(PackageCache.installedApps, false)
        DataStore.individual = selected.joinToString("\n")
    }

    private fun showFirstRunRegionPicker() {
        val labels =
            arrayOf(
                getString(R.string.routing_region_russia),
                getString(R.string.routing_region_china),
                getString(R.string.routing_region_iran),
                getString(R.string.routing_region_other),
            )
        val dirs = arrayOf("ru", "cn", "ir", "other")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.routing_select_region)
            .setItems(labels) { _, i -> applyFirstRunSelection(dirs[i]) }
            .setCancelable(false)
            .show()
    }

    private fun setupProxyAppsDrawerItem() {
        val item = navigation.menu.findItem(R.id.nav_route_apps) ?: return
        item.isCheckable = false
        item.setActionView(R.layout.layout_main_drawer_switch)

        val switchView = item.actionView?.findViewById<MaterialSwitch>(R.id.drawer_switch) ?: return
        proxyAppsDrawerSwitch = switchView
        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (syncingProxyAppsDrawerSwitch || DataStore.proxyApps == isChecked) return@setOnCheckedChangeListener
            if (isChecked) {
                DataStore.proxyApps = true
                DataStore.dirty = true
                return@setOnCheckedChangeListener
            }

            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.disable_per_app_routing_warning)
                .setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.proxyApps = false
                    syncProxyAppsDrawerItem()
                }.setNegativeButton(R.string.no) { _, _ ->
                    syncProxyAppsDrawerItem()
                }.setOnCancelListener {
                    syncProxyAppsDrawerItem()
                }.show()
        }
        syncProxyAppsDrawerItem()
    }

    private fun syncProxyAppsDrawerItem() {
        val switchView = proxyAppsDrawerSwitch ?: return
        syncingProxyAppsDrawerSwitch = true
        switchView.isChecked = DataStore.proxyApps
        syncingProxyAppsDrawerSwitch = false
    }

    private fun openAppManager() {
        startActivity(Intent(this, AppManagerActivity::class.java))
        binding.drawerLayout.closeDrawers()
    }

    override fun onResume() {
        super.onResume()
        MessageStore.setCurrentActivity(this)
        syncProxyAppsDrawerItem()

        if (DataStore.hideFromRecentApps) {
            applyHideFromRecentApps(DataStore.hideFromRecentApps)
        }
    }

    fun applyHideFromRecentApps(hide: Boolean) {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = activityManager.appTasks
            if (tasks.isNotEmpty()) {
                val task = tasks[0]
                task.setExcludeFromRecents(hide)
            }
        } catch (e: Exception) {
            Logs.w("Failed to set excludeFromRecents: ${e.message}")
        }
    }

    fun refreshNavMenu(clashApi: Boolean) {
        if (::navigation.isInitialized) {
            navigation.menu.findItem(R.id.nav_traffic)?.isVisible = clashApi
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent.action == ACTION_SHOW_CONNECTION_TEST) {
            restoreConnectionTestDialog()
            return
        }
        val uri = intent.data ?: return

        RoutingLinkProcessors.forLink(uri.toString())?.let { processor ->
            setIntent(Intent(intent).setAction(null).setData(null))
            requestRoutingImport(uri.toString(), processor)
            return
        }

        if (SubscriptionLinkImportPolicy.isHappCryptLink(uri.toString())) {
            setIntent(Intent(intent).setAction(null).setData(null))
            happCryptUnsupportedDialog().show()
            return
        }

        if (uri.scheme.equals("sn", ignoreCase = true) &&
            uri.host.equals("customtheme", ignoreCase = true)
        ) {
            setIntent(Intent(intent).setAction(null).setData(null))
            requestCustomThemeImport(uri.toString(), returnToInterface = false)
            return
        }

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun requestRoutingImport(link: String) {
        val processor = RoutingLinkProcessors.forLink(link)
        if (processor == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.error_title)
                .setMessage(R.string.routing_import_unsupported)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        requestRoutingImport(link, processor)
    }

    private fun requestRoutingImport(
        link: String,
        processor: io.nekohasekai.sagernet.routing.RoutingLinkProcessor,
    ) {
        if (processor.format == RoutingProfileFormat.NEKOBOX_PLUS) {
            val progress = LayoutProgressBinding.inflate(layoutInflater)
            progress.content.setText(R.string.routing_import_preparing)
            val dialog = MaterialAlertDialogBuilder(this)
                .setView(progress.root)
                .setCancelable(false)
                .show()
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.Default) {
                        RoutingImportManager.prepareNekoBoxPlus(processor.process(link))
                    }
                }
                dialog.dismiss()
                result.onSuccess(::openRoutingImportPreview)
                    .onFailure(::showRoutingImportError)
            }
            return
        }
        val candidate = runCatching { processor.process(link) }.getOrElse {
            showRoutingImportError(it)
            return
        }
        openRoutingImportPreview(candidate)
    }

    private fun openRoutingImportPreview(candidate: io.nekohasekai.sagernet.routing.RoutingImportCandidate) {
        val token = RoutingPreviewPayloadStore.put(this, candidate)
        startActivity(Intent(this, RoutingImportPreviewActivity::class.java).apply {
            putExtra(RoutingImportPreviewActivity.EXTRA_PAYLOAD_TOKEN, token)
        })
    }

    private fun showRoutingImportError(error: Throwable) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(getString(R.string.routing_import_invalid, error.readableMessage))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun requestCustomThemeImport(link: String, returnToInterface: Boolean) {
        if (!CustomTheme.isSupported) {
            Toast.makeText(this, R.string.custom_theme_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        if (CustomThemePreview.pending() != null) {
            Toast.makeText(this, R.string.custom_theme_preview_active, Toast.LENGTH_SHORT).show()
            return
        }
        val candidate = runCatching { CustomThemeLink.decode(link) }.getOrElse {
            Toast.makeText(this, R.string.invalid_custom_theme_link, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_custom_theme)
            .setMessage(R.string.import_custom_theme_warning)
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { _, _ ->
                if (CustomThemePreview.pending() != null) {
                    Toast.makeText(this, R.string.custom_theme_preview_active, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                CustomThemePreview.begin(this, candidate)
                if (returnToInterface) {
                    openSettingsOnNextCreate()
                    SettingsFragment.restoreInterfaceOnNextCreate()
                }
                ActivityCompat.recreate(this)
            }
            .show()
    }

    private fun showPendingCustomThemePreview() {
        val pending = CustomThemePreview.pending() ?: return
        val remaining = CustomThemePreview.remainingMillis(pending)
        if (remaining <= 0L) {
            if (CustomThemePreview.rollback(pending.id)) ActivityCompat.recreate(this)
            return
        }

        var resolved = false
        fun rollback() {
            if (resolved) return
            resolved = true
            customThemePreviewTimer?.cancel()
            if (CustomThemePreview.rollback(pending.id)) ActivityCompat.recreate(this)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(
                getString(
                    R.string.custom_theme_applied_save_changes,
                    CustomThemePreview.remainingSeconds(pending),
                ),
            )
            .setNegativeButton(R.string.no) { _, _ -> rollback() }
            .setPositiveButton(R.string.yes) { _, _ ->
                if (resolved) return@setPositiveButton
                resolved = true
                customThemePreviewTimer?.cancel()
                CustomThemePreview.confirm(pending.id)
            }
            .setOnCancelListener { rollback() }
            .show()
        customThemePreviewDialog = dialog

        var displayedSeconds = CustomThemePreview.remainingSeconds(pending)
        customThemePreviewTimer = object : CountDownTimer(remaining, 200L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = CustomThemePreview.remainingSeconds(pending)
                if (seconds != displayedSeconds) {
                    displayedSeconds = seconds
                    dialog.setMessage(
                        getString(R.string.custom_theme_applied_save_changes, seconds),
                    )
                }
            }

            override fun onFinish() {
                rollback()
            }
        }.start()
        dialog.setOnDismissListener {
            customThemePreviewTimer?.cancel()
            if (customThemePreviewDialog === dialog) customThemePreviewDialog = null
        }
    }

    private fun restoreConnectionTestDialog() {
        if (!GroupConnectionTestController.requestRestore()) {
            displayFragmentWithId(R.id.nav_configuration)
            return
        }
        val activeGroupId = GroupConnectionTestController.activeGroupId
        if (activeGroupId > 0L) {
            DataStore.selectedGroup = activeGroupId
        }
        displayFragmentWithId(R.id.nav_configuration)
        supportFragmentManager.executePendingTransactions()
        restoreConnectionTestDialogWhenReady()
    }

    private fun restoreConnectionTestDialogWhenReady(attempt: Int = 0) {
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)
            ?.takeIf { it.isAdded && it.view != null }
            ?.let {
                GroupConnectionTestController.restore(it)
                if (!GroupConnectionTestController.isRestorePending) return
            }

        restoreConnectionTestLifecycleCallback?.let {
            supportFragmentManager.unregisterFragmentLifecycleCallbacks(it)
        }
        restoreConnectionTestLifecycleCallback =
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    fragment: Fragment,
                    view: View,
                    savedInstanceState: Bundle?,
                ) {
                    if (fragment !is ConfigurationFragment || !GroupConnectionTestController.isActive) return
                    restoreConnectionTestLifecycleCallback?.let {
                        supportFragmentManager.unregisterFragmentLifecycleCallbacks(it)
                    }
                    restoreConnectionTestLifecycleCallback = null
                    GroupConnectionTestController.restore(fragment)
                }
            }
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            restoreConnectionTestLifecycleCallback!!,
            false,
        )
        if (attempt < 20 && GroupConnectionTestController.isActive) {
            binding.root.postDelayed({
                if (GroupConnectionTestController.isRestorePending) {
                    restoreConnectionTestDialogWhenReady(attempt + 1)
                }
            }, 100)
        }
    }

    fun urlTest(automatic: Boolean = false): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest(automatic)
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group =
                    KryoConverters
                        .deserialize(
                            ProxyGroup().apply { export = true },
                            Util.zlibDecompress(Util.b64Decode(data)),
                        ).apply {
                            export = false
                        }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name =
            group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
                ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }.setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profiles =
            try {
                parseProxies(uri.toString()).takeIf { it.isNotEmpty() }
                    ?: error(getString(R.string.no_proxies_found))
            } catch (e: AmneziaApiKeyUnsupportedException) {
                onMainDispatcher {
                    alert(getString(R.string.amnezia_api_key_unsupported)).show()
                }
                return
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }

        onMainDispatcher {
            val confirmation = ProfileImportPolicy.confirmation(profiles.map { it.displayName() })
            val message =
                when (confirmation) {
                    is ProfileImportPolicy.Confirmation.Single -> {
                        getString(R.string.profile_import_message, confirmation.profileName)
                    }

                    is ProfileImportPolicy.Confirmation.Multiple -> {
                        getString(R.string.profile_import_many_message, confirmation.count)
                    }
                }

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.profile_import)
                .setMessage(message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfiles(profiles)
                    }
                }.setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private suspend fun finishImportProfiles(profiles: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfiles(targetId, profiles)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, profiles.size, profiles.size)).show()
        }
    }

    override fun missingPlugin(
        profileName: String,
        pluginName: String,
    ) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin,
                    profileName,
                    pluginEntity.displayName,
                ),
            ).setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }.setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab("https://matsuridayo.github.io/nb4a-plugin/")
            }.show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this)
            .setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }.show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) {
            binding.drawerLayout.closeDrawers()
        } else {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        updateBottomControlsVisibility(fragment)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
    }

    fun displaySettingsGroup(groupId: String) {
        val fragment = SettingsFragment()
        displayFragment(fragment)
        supportFragmentManager.executePendingTransactions()
        fragment.openGroup(groupId, animate = false)
    }

    fun displayToolsFragment(fragment: ToolsFragment = ToolsFragment()) {
        displayFragment(fragment)
        navigation.menu.findItem(R.id.nav_tools).isChecked = true
    }

    private fun updateBottomControlsVisibility(
        fragment: ToolbarFragment? = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment,
        animate: Boolean = true,
        syncState: Boolean = true,
    ) {
        if (fragment == null) return
        bottomControlsVisibleForCurrentFragment =
            fragment is ConfigurationFragment ||
            (fragment is SettingsFragment && DataStore.showBottomBarInSettings)
        binding.stats.useExternalScrollDriver = fragment is ConfigurationFragment
        if (bottomControlsVisibleForCurrentFragment) {
            binding.stats.allowShow = true
            binding.stats.visibility = View.VISIBLE
            binding.fabCluster.visibility = View.VISIBLE
            binding.fabProgress.visibility = View.INVISIBLE
            if (syncState) {
                binding.fab.changeState(DataStore.serviceState, DataStore.serviceState, false)
                binding.stats.changeState(DataStore.serviceState, DataStore.serviceState)
            }
            syncGlobalModeFabStyle()
        } else {
            hideBottomControls(animate)
        }
    }

    private fun hideBottomControls(animate: Boolean) {
        binding.stats.allowShow = false
        binding.stats.hideOnScroll = false
        binding.stats.cancelPendingTransitions()
        syncGlobalModeFabStyle()
        if (animate) {
            binding.stats.performHide()
        }
        // Keep the FAB's own visibility state untouched. The parent cluster is the only anchored
        // unit, so hiding a fragment cannot leave Material's FAB animation state half-finished.
        binding.stats.visibility = View.GONE
        binding.fabCluster.visibility = View.GONE
    }

    fun driveBottomBar(scrollDy: Int) {
        if (!bottomControlsVisibleForCurrentFragment) return
        binding.stats.onListScrolled(scrollDy)
    }

    fun displayFragmentWithId(
        @IdRes id: Int,
    ): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }

            R.id.nav_group -> {
                displayFragment(GroupFragment())
            }

            R.id.nav_route -> {
                displayFragment(RouteFragment())
            }

            R.id.nav_route_apps -> {
                openAppManager()
                return false
            }

            R.id.nav_adblock -> {
                startActivity(Intent(this, AdblockSettingsActivity::class.java))
                binding.drawerLayout.closeDrawers()
                return false
            }

            R.id.nav_settings -> {
                displayFragment(SettingsFragment())
            }

            R.id.nav_traffic -> {
                displayFragment(WebviewFragment())
            }

            R.id.nav_tools -> {
                displayToolsFragment()
                return true
            }

            R.id.nav_logcat -> {
                displayFragment(LogcatFragment())
            }

            R.id.nav_about -> {
                displayFragment(AboutFragment())
            }

            else -> {
                return false
            }
        }
        navigation.menu.findItem(id).isChecked = true
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        updateBottomControlsVisibility(animate = false, syncState = false)
        val previousState = renderedServiceState
        renderedServiceState = state
        DataStore.serviceState = state

        if (bottomControlsVisibleForCurrentFragment) {
            binding.fab.changeState(state, previousState, animate)
            binding.stats.changeState(state, previousState)
            syncGlobalModeFabStyle()
        } else {
            hideBottomControls(animate = false)
        }
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)
            ?.let {
                it.refreshVisibleTraffic()
                it.refreshVisibleProfileActions()
                it.refreshToolbarMenuState()
            }
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? WebviewFragment)
            ?.applyServiceState(state)
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? SettingsFragment)
            ?.syncServiceState()
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    private fun syncGlobalModeFabStyle() {
        val showGlobalModeStyle =
            bottomControlsVisibleForCurrentFragment &&
                DataStore.globalMode
        val showGlobalModeOutline =
            bottomControlsVisibleForCurrentFragment &&
                DataStore.globalMode &&
                DataStore.serviceState != BaseService.State.Connecting
        val usePrimary = showGlobalModeStyle &&
            Theme.isCustom() &&
            DataStore.customThemeStatsBarPrimary
        val fabBackgroundAttr =
            if (showGlobalModeStyle) {
                if (usePrimary) R.attr.colorPrimary else R.attr.colorSurfaceContainerHigh
            } else {
                R.attr.colorPrimary
            }
        val fabIconAttr =
            if (showGlobalModeStyle) {
                if (usePrimary) R.attr.colorOnPrimary else R.attr.colorOnSurface
            } else {
                R.attr.colorOnPrimary
            }

        binding.fabProgress.setIndicatorColor(getColorAttr(R.attr.colorPrimary))
        binding.fab.setGlobalModeStyle(showGlobalModeStyle, showGlobalModeOutline)
        binding.fab.backgroundTintList = ColorStateList.valueOf(getColorAttr(fabBackgroundAttr))
        binding.fab.imageTintList = ColorStateList.valueOf(getColorAttr(fabIconAttr))
    }

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.isShown) {
                anchorView = binding.fab
            }
            // TODO
        }

    override fun stateChanged(
        state: BaseService.State,
        profileName: String?,
        msg: String?,
    ) {
        currentServiceProfileName = profileName
        if (state != BaseService.State.Connected) masterDnsVPNConnectedToastShown = false
        changeState(state, msg, true)
    }

    override fun cbMasterDnsVPNResolverProgress(
        found: Int,
        total: Int,
        ready: Boolean,
    ) {
        if (bottomControlsVisibleForCurrentFragment) {
            binding.stats.showMasterDnsVPNResolverProgress(found, total, ready)
        }
        if (!ready) {
            masterDnsVPNConnectedToastShown = false
            return
        }
        if (!activityStarted && !masterDnsVPNConnectedToastShown) {
            masterDnsVPNConnectedToastShown = true
            Toast
                .makeText(
                    applicationContext,
                    getString(R.string.masterdnsvpn_profile_connected, currentServiceProfileName.orEmpty()),
                    Toast.LENGTH_SHORT,
                ).show()
        }
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)

    override fun onServiceConnected(service: ISagerNetService) {
        binding.stats.bindConnectionCheckService(service)
        val logLevel = AppLogLevel.fromPreferenceValue(DataStore.logLevel)
        runOnDefaultDispatcher {
            runCatching { service.setLogLevel(logLevel.singBoxName, logLevel.outputEnabled) }
                .onFailure { Logs.w("Unable to synchronize log level: ${it.message}") }
        }
        currentServiceProfileName =
            try {
                service.profileName
            } catch (_: RemoteException) {
                null
            }
        changeState(
            try {
                BaseService.State.values()[service.state]
            } catch (_: RemoteException) {
                BaseService.State.Idle
            },
        )
    }

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)

    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect =
        registerForActivityResult(VpnRequestActivity.StartService()) {
            if (it) snackbar(R.string.vpn_permission_denied).show()
        }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
    }

    override suspend fun cbTrafficUpdate(data: TrafficDataBatch) {
        ProfileManager.postUpdate(data.items)
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(
        store: PreferenceDataStore,
        key: String,
    ) {
        when (key) {
            Key.SERVICE_MODE -> {
                onBinderDied()
            }

            Key.SPEED_INTERVAL, Key.PROFILE_TRAFFIC_UPDATE_INTERVAL, Key.PROFILE_TRAFFIC_STATISTICS -> {
                (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)
                    ?.refreshVisibleTraffic()
            }

            Key.SUBSCRIPTION_TRAFFIC_UNIT -> {
                when (val fragment =
                    supportFragmentManager.findFragmentById(R.id.fragment_holder)) {
                    is ConfigurationFragment -> fragment.refreshSubscriptionTrafficUnits()
                    is GroupFragment -> fragment.refreshSubscriptionTrafficUnits()
                }
            }

            Key.SHOW_BOTTOM_BAR_IN_SETTINGS -> {
                updateBottomControlsVisibility(animate = true)
            }

            Key.COMPACT_STATS_BAR -> {
                binding.stats.setCompactMode(DataStore.compactStatsBar)
            }

            Key.GLOBAL_MODE -> {
                syncGlobalModeFabStyle()
            }

            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (key == Key.PROXY_APPS) {
                    syncProxyAppsDrawerItem()
                }
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload))
                        .setAction(R.string.apply) {
                            SagerNet.reloadService()
                        }.show()
                }
            }
        }
    }

    override fun onStart() {
        activityStarted = true
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        activityStarted = false
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        customThemePreviewTimer?.cancel()
        customThemePreviewTimer = null
        customThemePreviewDialog?.setOnCancelListener(null)
        customThemePreviewDialog?.dismiss()
        customThemePreviewDialog = null
        if (isFinishing) {
            GroupConnectionTestController.cancelFromNotification()
        }
        restoreConnectionTestLifecycleCallback?.let {
            supportFragmentManager.unregisterFragmentLifecycleCallbacks(it)
        }
        restoreConnectionTestLifecycleCallback = null
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                navigation.requestFocus()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }
}
