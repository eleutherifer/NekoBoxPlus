package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteria1Config
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.delay
import java.io.File

internal suspend fun cleanupUrlTestRuntime(
    closeProcesses: suspend () -> Unit,
    deleteTrackedFiles: () -> Unit,
    deleteOrphanedFiles: () -> Unit,
    clearPluginConfigs: () -> Unit,
) {
    val closeError = runCatching { closeProcesses() }.exceptionOrNull()
    try {
        deleteTrackedFiles()
    } finally {
        try {
            deleteOrphanedFiles()
        } finally {
            clearPluginConfigs()
        }
    }
    closeError?.let { throw it }
}

internal class UrlTestPluginRuntime(private val runKey: String) {
    private val pluginPaths = hashMapOf<String, PluginManager.InitResult>()
    private val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    private val cacheFiles = ArrayList<File>()
    private var processes: GuardedProcessPool? = null

    suspend fun prepare(profileId: Long, config: ConfigBuildResult) {
        initPlugins(profileId, config)
        if (config.externalIndex.none { it.chain.isNotEmpty() }) return
        processes = GuardedProcessPool { throw it }
        launchPlugins(profileId, config)
        delay(500L)
    }

    suspend fun close(profileId: Long) {
        cleanupUrlTestRuntime(
            closeProcesses = { processes?.closeAndJoin() },
            deleteTrackedFiles = {
                processes = null
                cacheFiles.forEach { runCatching { it.delete() } }
                cacheFiles.clear()
            },
            deleteOrphanedFiles = { cleanupOrphanedFiles(profileId) },
            clearPluginConfigs = pluginConfigs::clear,
        )
    }

    private fun initPlugin(name: String): PluginManager.InitResult =
        pluginPaths.getOrPut(name) { checkNotNull(PluginManager.init(name)) }

    private fun newCacheFile(profileId: Long, purpose: String, suffix: String): File =
        File.createTempFile("urltest_${profileId}_${runKey}_${purpose}_", suffix, SagerNet.application.cacheDir)
            .also(cacheFiles::add)

    private fun initPlugins(profileId: Long, config: ConfigBuildResult) {
        for ((chain) in config.externalIndex) {
            chain.forEach { (port, profile) ->
                when (val bean = profile.requireBean()) {
                    is TrojanGoBean -> {
                        initPlugin("trojan-go-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(port)
                    }
                    is HysteriaBean -> {
                        initPlugin("hysteria-plugin")
                        pluginConfigs[port] = profile.type to bean.buildHysteria1Config(port) {
                            newCacheFile(profileId, "hysteria_ca", ".ca")
                        }
                    }
                }
            }
        }
    }

    private fun launchPlugins(profileId: Long, config: ConfigBuildResult) {
        val processPool = checkNotNull(processes)
        for ((chain) in config.externalIndex) {
            chain.forEach { (port, profile) ->
                val bean = profile.requireBean()
                val pluginConfig = pluginConfigs[port]?.second.orEmpty()
                when (bean) {
                    is TrojanGoBean -> {
                        val configFile = newCacheFile(profileId, "trojan_go", ".json")
                        configFile.writeText(pluginConfig)
                        processPool.start(listOf(initPlugin("trojan-go-plugin").path, "-config", configFile.absolutePath))
                    }
                    is HysteriaBean -> {
                        val configFile = newCacheFile(profileId, "hysteria", ".json")
                        configFile.writeText(pluginConfig)
                        val commands = mutableListOf(
                            initPlugin("hysteria-plugin").path,
                            "--no-check",
                            "--config",
                            configFile.absolutePath,
                            "--log-level",
                            if (DataStore.logLevel > 0) "trace" else "warn",
                            "client",
                        )
                        if (bean.protocol == HysteriaBean.PROTOCOL_FAKETCP) commands.addAll(0, listOf("su", "-c"))
                        processPool.start(commands)
                    }
                }
            }
        }
    }

    private fun cleanupOrphanedFiles(profileId: Long) {
        val prefix = "urltest_${profileId}_${runKey}_"
        SagerNet.application.cacheDir.listFiles { _, name -> name.startsWith(prefix) }
            ?.forEach { runCatching { it.delete() } }
    }
}
