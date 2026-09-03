package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ui.compose.AppSelectionAction
import io.nekohasekai.sagernet.ui.compose.AppSelectionNotice
import io.nekohasekai.sagernet.ui.compose.AppSelectionScreen
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListActivity : ThemedActivity() {
    companion object {
        const val EXTRA_PACKAGE_LIST_KEY = "package_list_key"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var model: AppSelectionModel
    private var actionsExpanded by mutableStateOf(false)
    private var notice by mutableStateOf<AppSelectionNotice?>(null)
    private var nextNoticeId = 0L

    private val packageListKey by lazy {
        intent.getStringExtra(EXTRA_PACKAGE_LIST_KEY) ?: Key.ROUTE_PACKAGES
    }
    private var packageList: String
        get() = when (packageListKey) {
            Key.ADBLOCK_INCLUDED_PACKAGES -> DataStore.adblockIncludedPackages
            else -> DataStore.routePackages
        }
        set(value) {
            when (packageListKey) {
                Key.ADBLOCK_INCLUDED_PACKAGES -> DataStore.adblockIncludedPackages = value
                else -> DataStore.routePackages = value
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = AppSelectionModel(packageList) { packageList = it }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.select_apps)
        setContent {
            NekoComposeTheme {
                AppSelectionScreen(
                    title = title,
                    apps = model.visibleApps,
                    allAppsEmpty = model.apps.isEmpty(),
                    loading = model.loading,
                    query = model.query,
                    showSystemApps = model.showSystemApps,
                    routingMode = null,
                    actionsExpanded = actionsExpanded,
                    clipboardActionsInToolbar = false,
                    notice = notice,
                    isSelected = model::isSelected,
                    onClose = ::finish,
                    onQueryChange = { model.query = it },
                    onShowSystemAppsChange = { model.showSystemApps = it },
                    onRoutingModeChange = {},
                    onAutoSelect = {},
                    onToggle = model::toggle,
                    onAction = ::handleAction,
                    onActionsExpandedChange = { actionsExpanded = it },
                    onNoticeShown = { notice = null },
                    onOpenSettings = ::openAppSettings,
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

    private fun handleAction(action: AppSelectionAction) {
        when (action) {
            AppSelectionAction.Invert -> model.invert()
            AppSelectionAction.Clear -> model.clear()
            AppSelectionAction.Export -> {
                val success = SagerNet.trySetPrimaryClip("false\n${model.exportPackages()}")
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
        val packages = if (separator < 0) "" else clipboard.substring(separator + 1)
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
