package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.Param
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.bg.calculateLibcoreMemoryLimit
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteria1Config
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.masque.applyConfigJson
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.*
import libcore.BoxInstance
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.io.File

internal fun ConfigBuildResult.normalizeConfig() =
    Libcore.normalizeConfig(config).also { normalizeResult ->
        if (normalizeResult.result.isNotEmpty()) {
            config = normalizeResult.result
        }
    }

abstract class BoxInstance(
    val profile: ProxyEntity,
) : AbstractInstance {
    lateinit var config: ConfigBuildResult
    lateinit var box: BoxInstance

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool
    private var cacheFiles = ArrayList<File>()
    protected open val syncMasqueCache = true
    var configNormalizationViolations: List<String> = emptyList()
        private set

    fun isInitialized(): Boolean = ::config.isInitialized && ::box.isInitialized

    protected fun initPlugin(name: String): PluginManager.InitResult = pluginPath.getOrPut(name) { PluginManager.init(name)!! }

    protected open fun buildConfig() {
        config = buildConfig(profile)
    }

    protected open suspend fun loadConfig() {
        val normalizeResult = config.normalizeConfig()
        if (normalizeResult.result.isNotEmpty()) {
            configNormalizationViolations =
                List(normalizeResult.violationCount) { index ->
                    normalizeResult.getViolation(index)
                }
        }
        box = if (config.routingAssetsPath != null && config.routingCachePath != null) {
            Libcore.newSingBoxInstanceWithPaths(
                config.config,
                LocalResolverImpl,
                config.routingAssetsPath,
                config.routingCachePath,
            )
        } else {
            Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
        }
    }

    suspend fun syncMasqueConfigFromCache(
        disableRecreate: Boolean,
        respectRecreate: Boolean,
    ): Boolean {
        if (!syncMasqueCache) return false
        val bean = profile.requireBean() as? MasqueBean ?: return false
        if (respectRecreate && bean.profileRecreate == true) return false
        if (!::config.isInitialized) return false
        val tag = config.profileTagMap[profile.id].orEmpty()
        if (tag.isBlank()) return false
        val configJson =
            runCatching {
                Libcore.loadMASQUEConfigFromCache(tag, config.singBoxCachePath)
            }.onFailure {
                Logs.w(it)
            }.getOrNull()
                .orEmpty()
        if (configJson.isBlank()) return false
        var changed =
            runCatching {
                bean.applyConfigJson(configJson)
            }.onFailure {
                Logs.w(it)
            }.getOrDefault(false)
        if (disableRecreate && bean.profileRecreate == true) {
            bean.profileRecreate = false
            changed = true
        }
        if (changed) {
            ProfileManager.updateProfile(profile)
        }
        return changed
    }

    open suspend fun init() {
        buildConfig()
        if (syncMasqueConfigFromCache(disableRecreate = false, respectRecreate = true)) {
            buildConfig()
        }
        for ((chain) in config.externalIndex) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                when (val bean = profile.requireBean()) {
                    is TrojanGoBean -> {
                        initPlugin("trojan-go-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(port)
                    }

                    is HysteriaBean -> {
                        initPlugin("hysteria-plugin")
                        pluginConfigs[port] = profile.type to
                            bean.buildHysteria1Config(port) {
                                File(
                                    app.cacheDir,
                                    "hysteria_" + SystemClock.elapsedRealtime() + ".ca",
                                ).apply {
                                    parentFile?.mkdirs()
                                    cacheFiles.add(this)
                                }
                            }
                    }
                }
            }
        }
        loadConfig()
    }

    override fun launch() {
        // TODO move, this is not box
        val cacheDir = File(SagerNet.application.cacheDir, "tmpcfg")
        cacheDir.mkdirs()

        for ((chain) in config.externalIndex) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                val bean = profile.requireBean()
                val needChain = index != chain.size - 1
                val (profileType, config) = pluginConfigs[port] ?: (0 to "")

                when {
                    externalInstances.containsKey(port) -> {
                        externalInstances[port]!!.launch()
                    }

                    bean is TrojanGoBean -> {
                        val configFile =
                            File(
                                cacheDir,
                                "trojan_go_" + SystemClock.elapsedRealtime() + ".json",
                            )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands =
                            mutableListOf(
                                initPlugin("trojan-go-plugin").path,
                                "-config",
                                configFile.absolutePath,
                            )

                        processes.start(commands)
                    }

                    bean is HysteriaBean -> {
                        val configFile =
                            File(
                                cacheDir,
                                "hysteria_" + SystemClock.elapsedRealtime() + ".json",
                            )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands =
                            mutableListOf(
                                initPlugin("hysteria-plugin").path,
                                "--no-check",
                                "--config",
                                configFile.absolutePath,
                                "--log-level",
                                if (DataStore.logLevel > 0) "trace" else "warn",
                                "client",
                            )

                        if (bean.protocol == HysteriaBean.PROTOCOL_FAKETCP) {
                            commands.addAll(0, listOf("su", "-c"))
                        }

                        processes.start(commands)
                    }
                }
            }
        }

        Libcore.enableMemoryLimit(calculateLibcoreMemoryLimit(DataStore.memoryLimit))
        box.start()
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    open fun close(timeoutMillis: Long) {
        runBlocking {
            syncMasqueConfigFromCache(disableRecreate = true, respectRecreate = false)
        }
        for (instance in externalInstances.values) {
            runCatching {
                instance.close()
            }
        }

        cacheFiles.removeAll {
            it.delete()
            true
        }

        if (::processes.isInitialized) {
            runBlocking {
                processes.closeAndJoin()
            }
        }

        if (::box.isInitialized) {
            try {
                box.closeTimeout(timeoutMillis)
            } catch (e: Exception) {
                Logs.w(e)
                throw e
            }
        }
    }

    override fun close() {
        close(60_000L)
    }
}
