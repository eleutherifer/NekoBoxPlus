package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.BatteryOptimization
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.ui.compose.AboutPluginItem
import io.nekohasekai.sagernet.ui.compose.AboutScreen
import io.nekohasekai.sagernet.ui.compose.AboutScreenData
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.plugin.Plugins

class AboutFragment : ToolbarFragment() {

    private var screenData by mutableStateOf<AboutScreenData?>(null)
    private var showBatteryOptimization by mutableStateOf(false)

    private val requestIgnoreBatteryOptimizations = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        showBatteryOptimization = !BatteryOptimization.isIgnoringOptimizations(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NekoComposeTheme {
                AboutScreen(
                    data = screenData,
                    showBatteryOptimization = showBatteryOptimization,
                    onOpenDrawer = { (activity as? MainActivity)?.openDrawer() },
                    onPluginClick = ::openPluginDetails,
                    onBatteryOptimizationClick = ::requestBatteryOptimization,
                    onProjectClick = {
                        requireContext().launchCustomTab(
                            "https://4pda.to/forum/index.php?showtopic=1121122",
                        )
                    },
                    onDocumentationClick = {
                        requireContext().launchCustomTab("https://matsuridayo.github.io/")
                    },
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        showBatteryOptimization = shouldShowBatteryOptimization()
        loadAboutData()
    }

    private fun loadAboutData() {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            screenData = withContext(Dispatchers.Default) {
                PackageCache.awaitLoadSync()
                val plugins = buildList {
                    for ((_, pkg) in PackageCache.installedPluginPackages) {
                        try {
                            val pluginId = pkg.providers?.firstOrNull()
                                ?.loadString(Plugins.METADATA_KEY_ID)
                            if (pluginId.isNullOrBlank()) continue
                            add(
                                AboutPluginItem(
                                    packageName = pkg.packageName,
                                    title = context.getString(
                                        R.string.version_x,
                                        pluginId,
                                    ) + " (${Plugins.displayExeProvider(pkg.packageName)})",
                                    version = "v${pkg.versionName}",
                                ),
                            )
                        } catch (error: Exception) {
                            Logs.w(error)
                        }
                    }
                }
                AboutScreenData(
                    appVersion = SagerNet.appVersionNameForDisplay,
                    singBoxVersion = Libcore.versionBox(),
                    moduleVersions = Libcore.versionModules(),
                    plugins = plugins,
                    license = context.assets.open("LICENSE").bufferedReader().use {
                        it.readText()
                    },
                )
            }
        }
    }

    private fun shouldShowBatteryOptimization(): Boolean {
        val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        return !powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    private fun requestBatteryOptimization() {
        DataStore.batteryOptimizationPromptAsked = true
        requestIgnoreBatteryOptimizations.launch(
            BatteryOptimization.requestIntent(requireContext()),
        )
    }

    private fun openPluginDetails(packageName: String) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        })
    }
}
