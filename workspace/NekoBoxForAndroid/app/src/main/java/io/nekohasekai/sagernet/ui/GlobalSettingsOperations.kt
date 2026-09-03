package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Process
import android.os.StatFs
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.AppIcon
import io.nekohasekai.sagernet.AppIconManager
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RuleType
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.triggerFullRestart
import io.nekohasekai.sagernet.ui.compose.showBlockingProgressDialog
import io.nekohasekai.sagernet.ui.compose.showComposeMessageDialog
import io.nekohasekai.sagernet.ui.compose.showComposeItemDialog
import io.nekohasekai.sagernet.utils.AdblockRepository
import io.nekohasekai.sagernet.utils.AppCache
import io.nekohasekai.sagernet.utils.CrashHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.listByLineOrComma
import moe.matsuri.nb4a.utils.SendLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class GlobalSettingsOperations(private val fragment: SettingsFragment) {
    private val context get() = fragment.requireContext()
    private var profilerProgress: ComponentDialog? = null

    private enum class CrashOption(val type: String, val title: Int) {
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

    private sealed interface SaveResult {
        data class Saved(val file: File) : SaveResult
        data class Failed(val error: Throwable) : SaveResult
        data object Missing : SaveResult
    }

    fun handle(key: String) {
        when (key) {
            "resetSettings" -> resetSettings()
            Key.CLEAR_CACHE -> confirmClearCache()
            Key.RUN_STORAGE_MAINTENANCE -> startStorageMaintenance()
            Key.KILL_BACKGROUND_PROCESS -> BackgroundProcessController.confirmKill(context)
            Key.PERFORM_LIBCORE_GC_SWEEP -> performGcSweep()
            Key.PERFORM_LIBCORE_MANUAL_CRASH -> performManualCrash()
            Key.SAVE_CORE_PROFILER_SNAPSHOT -> saveProfilerSnapshot()
            Key.DELETE_CORE_PROFILER_SNAPSHOT -> deleteProfilerSnapshot()
            else -> error("Unhandled global settings action: $key")
        }
    }

    fun profilingChanged(enabled: Boolean) {
        if (!DataStore.serviceState.connected) return
        val service = (fragment.activity as? MainActivity)?.connection?.service ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.Default) {
                runCatching {
                    if (enabled) service.startCoreProfiling(DataStore.coreProfilerMode)
                    else service.stopCoreProfiling()
                }.exceptionOrNull()
            }
            if (error != null && fragment.isAdded) showProfilerError(error)
        }
    }

    private fun resetSettings() {
        context.showComposeMessageDialog(
            title = context.getText(R.string.confirm),
            message = context.getText(R.string.reset_settings_message),
            positiveButton = context.getText(R.string.yes),
            negativeButton = context.getText(R.string.no),
            onPositive = {
                AppIconManager.set(context, AppIcon.NEKOBOX_PLUS)
                DataStore.configurationStore.reset()
                triggerFullRestart(context)
            },
        )
    }

    private fun confirmClearCache() {
        context.showComposeMessageDialog(
            title = context.getText(R.string.clear_cache),
            message = context.getText(R.string.clear_cache_confirm),
            positiveButton = context.getText(android.R.string.ok),
            negativeButton = context.getText(android.R.string.cancel),
            onPositive = ::clearAppCache,
        )
    }

    private fun clearAppCache() {
        val appContext = SagerNet.application
        runCatching { AppCache.clear(appContext.cacheDir) }
            .onSuccess {
                Toast.makeText(appContext, R.string.clear_cache_success, Toast.LENGTH_SHORT).show()
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    delay(500)
                    fragment.needReload()
                }
            }
            .onFailure {
                Toast.makeText(appContext,
                    appContext.getString(R.string.clear_cache_failed, it.message), Toast.LENGTH_SHORT).show()
            }
    }

    private fun performGcSweep() {
        val service = (fragment.activity as? MainActivity)?.connection?.service
        if (service == null || !DataStore.serviceState.started) {
            Toast.makeText(context, R.string.service_is_not_running, Toast.LENGTH_SHORT).show()
            return
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.Default) { runCatching { service.performLibcoreGcSweep() }.isSuccess }
            if (fragment.isAdded) Toast.makeText(context,
                if (success) R.string.done else R.string.service_is_not_running, Toast.LENGTH_SHORT).show()
        }
    }

    private fun performManualCrash() {
        val service = (fragment.activity as? MainActivity)?.connection?.service
        if (service == null || !DataStore.serviceState.started) {
            Toast.makeText(context, R.string.service_is_not_running, Toast.LENGTH_SHORT).show()
            return
        }
        val options = CrashOption.entries
        context.showComposeItemDialog(
            title = context.getText(R.string.perform_libcore_fake_crash),
            items = options.map { context.getString(it.title) },
            negativeButton = context.getText(android.R.string.cancel),
            onItemSelected = { index ->
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    val crashed = withContext(Dispatchers.Default) {
                        try {
                            service.triggerLibcoreCrash(options[index].type)
                            false
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            true
                        }
                    }
                    if (fragment.isAdded) Toast.makeText(context,
                        if (crashed) R.string.done else R.string.fail_no_crash, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    private fun saveProfilerSnapshot() {
        val service = (fragment.activity as? MainActivity)?.connection?.service
        profilerProgress?.dismiss()
        profilerProgress = context.showBlockingProgressDialog(R.string.core_profiler_saving)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val app = SagerNet.application
                    val root = File(app.cacheDir, "core-profiler-export").apply {
                        deleteRecursively(); mkdirs()
                    }
                    val profiler = File(root, "profiler").apply { mkdirs() }
                    if (service != null && DataStore.serviceState.started) {
                        service.writeCoreProfilerSnapshot(profiler.absolutePath)
                    } else if (!copyLocalProfilerSnapshot(profiler)) return@withContext SaveResult.Missing
                    val file = File(File(app.cacheDir, "log").also(File::mkdirs),
                        "NB4A-profiler-${timestamp()}.zip")
                    writeProfilerZip(file, profiler)
                    SaveResult.Saved(file)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    SaveResult.Failed(error)
                }
            }
            profilerProgress?.dismiss()
            profilerProgress = null
            if (!fragment.isAdded) return@launch
            when (result) {
                is SaveResult.Saved -> {
                    shareProfilerZip(result.file)
                    Toast.makeText(context, R.string.core_profiler_snapshot_saved, Toast.LENGTH_SHORT).show()
                }
                is SaveResult.Failed -> showProfilerError(result.error)
                SaveResult.Missing -> Toast.makeText(context, R.string.core_profiler_no_snapshot, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteProfilerSnapshot() {
        val service = (fragment.activity as? MainActivity)?.connection?.service
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.Default) {
                runCatching {
                    service?.deleteCoreProfilerSnapshot()
                    File(SagerNet.application.cacheDir, "core-profiler").deleteRecursively()
                    File(SagerNet.application.cacheDir, "core-profiler-export").deleteRecursively()
                }.exceptionOrNull()
            }
            if (!fragment.isAdded) return@launch
            if (error == null) Toast.makeText(context,
                R.string.core_profiler_snapshot_deleted, Toast.LENGTH_SHORT).show()
            else showProfilerError(error)
        }
    }

    private fun showProfilerError(error: Throwable) {
        val message = error.readableMessage
        val resource = when {
            message.contains("Core is not started yet", true) -> R.string.core_not_started_yet
            message.contains("no profiler snapshot", true) -> R.string.core_profiler_no_snapshot
            else -> 0
        }
        Toast.makeText(context,
            if (resource != 0) context.getString(resource)
            else context.getString(R.string.core_profiler_failed, message), Toast.LENGTH_LONG).show()
    }

    private fun copyLocalProfilerSnapshot(output: File): Boolean {
        val files = File(SagerNet.application.cacheDir, "core-profiler").listFiles()
            ?.filter { it.isFile && it.length() > 0 } ?: return false
        if (files.isEmpty()) return false
        files.forEach { it.copyTo(File(output, it.name), overwrite = true) }
        return true
    }

    private fun writeProfilerZip(output: File, profiler: File) {
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            addText(zip, "report-header.txt", CrashHandler.buildReportHeader())
            try {
                Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream.use {
                    zip.putNextEntry(ZipEntry("logcat.txt")); it.copyTo(zip); zip.closeEntry()
                }
            } catch (error: IOException) {
                addText(zip, "logcat.txt", "Export logcat error: ${CrashHandler.formatThrowable(error)}")
            }
            addBytes(zip, "neko.log", SendLog.getNekoLog(0))
            profiler.walkTopDown().filter(File::isFile)
                .sortedBy { it.relativeTo(profiler).invariantSeparatorsPath }.forEach { file ->
                    zip.putNextEntry(ZipEntry("profiler/${file.relativeTo(profiler).invariantSeparatorsPath}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private fun addText(zip: ZipOutputStream, name: String, value: String) =
        addBytes(zip, name, value.toByteArray(Charsets.UTF_8))
    private fun addBytes(zip: ZipOutputStream, name: String, value: ByteArray) {
        zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
    }

    private fun shareProfilerZip(file: File) {
        context.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/zip")
                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                    context, BuildConfig.APPLICATION_ID + ".cache", file)),
            context.getString(R.string.abc_shareactionprovider_share_with),
        ))
    }

    private fun timestamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun startStorageMaintenance() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val stopped = withContext(Dispatchers.Default) { stopServiceForStorageMaintenance() }
            if (!fragment.isAdded) return@launch
            if (!stopped) {
                context.showComposeMessageDialog(null, context.getText(R.string.storage_maintenance_stop_failed))
                return@launch
            }
            if (!hasEnoughStorageForMaintenance()) {
                context.showComposeMessageDialog(null, context.getText(R.string.storage_maintenance_insufficient_space))
                return@launch
            }
            context.showComposeMessageDialog(
                title = context.getText(R.string.run_storage_maintenance),
                message = context.getText(R.string.storage_maintenance_confirm),
                positiveButton = context.getText(android.R.string.ok),
                negativeButton = context.getText(android.R.string.cancel),
                onPositive = ::runStorageMaintenance,
            )
        }
    }

    private suspend fun stopServiceForStorageMaintenance(): Boolean {
        if (DataStore.serviceState in setOf(BaseService.State.Stopped, BaseService.State.Idle)) return true
        SagerNet.stopService()
        repeat(300) {
            if (DataStore.serviceState in setOf(BaseService.State.Stopped, BaseService.State.Idle)) return true
            delay(100)
        }
        return false
    }

    private fun hasEnoughStorageForMaintenance(): Boolean {
        val appData = File(SagerNet.application.applicationInfo.dataDir)
        val used = appData.walkTopDown().filter(File::isFile).sumOf { it.length().coerceAtLeast(0) }
        return StatFs(appData.absolutePath).availableBytes >= used.coerceAtLeast(1) * 2
    }

    private fun runStorageMaintenance() {
        val dialog = context.showBlockingProgressDialog(R.string.storage_maintenance_working)
        val filterUrls = enabledAdblockFilterUrls().joinToString("\n")
        val routingKeys = enabledRoutingRuleCacheKeys().joinToString("\n")
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val failure = withContext(Dispatchers.Default) {
                runCatching {
                    Libcore.performStorageMaintenance(DataStore.adblockEnabled, filterUrls, routingKeys)
                    vacuumDatabases()
                    clearDisposableCache()
                }.exceptionOrNull()
            }
            if (!fragment.isAdded) return@launch
            dialog.dismiss()
            if (failure == null) {
                ProcessPhoenix.triggerRebirth(context, Intent(context, MainActivity::class.java))
            } else {
                Log.e("StorageMaintenance", "maintenance failed", failure)
                Logs.e(failure)
                context.showComposeMessageDialog(
                    title = null,
                    message = context.getText(R.string.storage_maintenance_failed),
                    cancelable = false,
                    onPositive = {
                        fragment.activity?.finishAffinity()
                        Process.killProcess(Process.myPid())
                    },
                )
            }
        }
    }

    private fun enabledAdblockFilterUrls(): Set<String> {
        if (!DataStore.adblockEnabled) return emptySet()
        val bundled = AdblockRepository.ensureBundledDefaults()
        return buildSet {
            AdblockRepository.catalog.filter { it.id in bundled }.flatMap { it.sources }
                .map { it.url.trim() }.filterTo(this) { it.isNotBlank() }
            AdblockRepository.customFilters().filter(AdblockRepository::customFilterEnabled)
                .map { it.url.trim() }.filterTo(this) { it.isNotBlank() }
        }
    }

    private fun enabledRoutingRuleCacheKeys(): Set<String> =
        SagerDatabase.rulesDao.enabledRules().asSequence().flatMap { rule ->
            if (RuleType.fromValue(rule.type) == RuleType.DNS) rule.ruleset.listByLineOrComma().asSequence()
            else sequenceOf(rule.domains.listByLineOrComma(), rule.ip.listByLineOrComma()).flatten()
        }.map(String::trim).mapNotNull { key -> when {
            key.startsWith("geoip:", true) -> "geoip:${key.substringAfter(':')}"
            key.startsWith("geosite:", true) -> "geosite:${key.substringAfter(':')}"
            else -> null
        } }.filterNot { it == "geoip:private" }.toSet()

    private fun vacuumDatabases() {
        SagerDatabase.proxyDao.clearTestResults()
        listOf(SagerDatabase.instance, PublicDatabase.instance).forEach {
            it.openHelper.writableDatabase.execSQL("VACUUM")
        }
    }

    private fun clearDisposableCache() {
        val app = SagerNet.application
        val preserved = setOf("cache.db", "adblock.db", "routing-rules-cache.db")
        app.cacheDir.listFiles()?.forEach { file -> when {
            file.name == "neko.log" -> file.writeText("")
            file.name in preserved -> Unit
            !file.deleteRecursively() && file.exists() -> error("Unable to remove ${file.name}")
        } }
        removePath(app.codeCacheDir)
        removePath(File(app.applicationInfo.dataDir, "app_textures"))
        if (!DataStore.enableClashAPI) {
            removePath(File(app.filesDir, "metacubexd"))
            removePath(File(app.filesDir, "metacubexd.version.txt"))
        }
    }

    private fun removePath(path: File) {
        if (path.exists() && !path.deleteRecursively() && path.exists()) error("Unable to remove ${path.name}")
    }
}
