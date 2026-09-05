package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.compose.AppRoutingMode
import io.nekohasekai.sagernet.ui.compose.AppSelectionAction
import io.nekohasekai.sagernet.ui.compose.AppSelectionNotice
import io.nekohasekai.sagernet.ui.compose.AppSelectionScreen
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.RoutingRulesService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AppManagerActivity : ThemedActivity() {
    private lateinit var model: AppSelectionModel
    private var routingMode by mutableStateOf(AppRoutingMode.Proxy)
    private var actionsExpanded by mutableStateOf(false)
    private var notice by mutableStateOf<AppSelectionNotice?>(null)
    private var showRegionPicker by mutableStateOf(false)
    private var confirmRoutingDir by mutableStateOf<String?>(null)
    private var nextNoticeId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DataStore.proxyApps) DataStore.proxyApps = true
        routingMode = if (DataStore.bypass) AppRoutingMode.Bypass else AppRoutingMode.Proxy
        model = AppSelectionModel(DataStore.individual) { DataStore.individual = it }
        setContent {
            NekoComposeTheme {
                AppSelectionScreen(
                    title = stringResource(R.string.proxied_apps),
                    apps = model.visibleApps,
                    allAppsEmpty = model.apps.isEmpty(),
                    loading = model.loading,
                    query = model.query,
                    showSystemApps = model.showSystemApps,
                    routingMode = routingMode,
                    actionsExpanded = actionsExpanded,
                    clipboardActionsInToolbar = true,
                    notice = notice,
                    isSelected = model::isSelected,
                    onClose = ::finish,
                    onQueryChange = { model.query = it },
                    onShowSystemAppsChange = { model.showSystemApps = it },
                    onRoutingModeChange = ::changeRoutingMode,
                    onAutoSelect = ::selectProxyApp,
                    onToggle = model::toggle,
                    onAction = ::handleAction,
                    onActionsExpandedChange = { actionsExpanded = it },
                    onNoticeShown = { notice = null },
                    onOpenSettings = ::openAppSettings,
                    showRegionPicker = showRegionPicker,
                    confirmRoutingSelection = confirmRoutingDir != null,
                    onRegionSelected = {
                        showRegionPicker = false
                        confirmRoutingDir = it
                    },
                    onDismissRegionPicker = { showRegionPicker = false },
                    onConfirmRoutingSelection = {
                        confirmRoutingDir?.let(::applyRoutingSelection)
                        confirmRoutingDir = null
                    },
                    onDismissRoutingSelection = { confirmRoutingDir = null },
                )
            }
        }
        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) { loadSelectableApps(packageManager) }
                model.load(apps)
            } catch (error: Exception) {
                Logs.e(error)
                model.failLoading()
            }
        }
    }

    private fun changeRoutingMode(mode: AppRoutingMode) {
        when (mode) {
            AppRoutingMode.Off -> {
                DataStore.proxyApps = false
                finish()
            }
            AppRoutingMode.Proxy -> {
                routingMode = mode
                DataStore.bypass = false
            }
            AppRoutingMode.Bypass -> {
                routingMode = mode
                DataStore.bypass = true
            }
        }
    }

    private fun handleAction(action: AppSelectionAction) {
        when (action) {
            AppSelectionAction.Invert -> model.invert()
            AppSelectionAction.Clear -> model.clear()
            AppSelectionAction.Export -> {
                val success = SagerNet.trySetPrimaryClip(
                    "${DataStore.bypass}\n${model.exportPackages()}",
                )
                showNotice(if (success) R.string.action_export_msg else R.string.action_export_err)
            }
            AppSelectionAction.Import -> importFromClipboard()
        }
    }

    private fun importFromClipboard() {
        val clipboard = SagerNet.getClipboardText()
        if (clipboard.isEmpty()) {
            showNotice(R.string.action_import_err)
            return
        }
        val separator = clipboard.indexOf('\n')
        val (bypass, packages) = if (separator < 0) {
            clipboard to ""
        } else {
            clipboard.substring(0, separator) to clipboard.substring(separator + 1)
        }
        changeRoutingMode(if (bypass.toBoolean()) AppRoutingMode.Bypass else AppRoutingMode.Proxy)
        model.importPackages(packages)
        showNotice(R.string.action_import_msg)
    }

    private fun showNotice(messageRes: Int) {
        notice = AppSelectionNotice(++nextNoticeId, messageRes)
    }

    private fun openAppSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        })
    }

    private fun selectProxyApp() {
        val dir = detectRoutingDir()
        if (dir != null) confirmRoutingDir = dir else showRegionPicker = true
    }

    private fun detectRoutingDir(): String? = when (Locale.getDefault().country.uppercase()) {
        "RU" -> "ru"
        "CN" -> "cn"
        "IR" -> "ir"
        else -> null
    }

    private fun applyRoutingSelection(routingDir: String) {
        try {
            val selected = RoutingRulesService(app, routingDir).computeProxiedPackages(
                PackageCache.installedApps,
                DataStore.bypass,
            )
            model.replaceSelection(selected)
        } catch (error: Exception) {
            Logs.e(error)
        }
    }

    override fun supportNavigateUpTo(upIntent: Intent) =
        super.supportNavigateUpTo(upIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_MENU) {
            actionsExpanded = !actionsExpanded
            true
        } else {
            super.onKeyUp(keyCode, event)
        }
    }
}
