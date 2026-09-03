package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateFormat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.compose.AssetUiItem
import io.nekohasekai.sagernet.ui.compose.AssetsScreen
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.utils.RulesetSuggestionRepository
import io.nekohasekai.sagernet.utils.RulesetSuggestionRepository.Source
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

class AssetsActivity : ThemedActivity() {
    companion object {
        private const val CUSTOM_ASSET_VERSION_PREFIX = "custom:"
        private const val SHORT_COMMIT_HASH_LENGTH = 7
        private const val THRONE_RULESET_DOWNLOAD_URL =
            "https://raw.githubusercontent.com/throneproj/routeprofiles/refs/heads/rule-set/srslist.h"
        private const val THRONE_RULESET_COMMIT_API =
            "https://api.github.com/repos/throneproj/routeprofiles/commits/rule-set"
        private const val ITDOG_RELEASE_API =
            "https://api.github.com/repos/itdoginfo/allow-domains/releases/latest"
    }

    private data class AssetItem(
        val file: File,
        val displayName: String,
        val versionFile: File = File(file.parentFile, "${file.nameWithoutExtension}.version.txt"),
        val bundledVersionAssetPath: String? = null,
        val managed: Boolean = true,
    )

    private data class RuleAssetsProvider(
        val repoByFileName: Map<String, String>,
        val remoteFileNamesByLocalFileName: Map<String, List<String>>,
    ) {
        constructor(
            repo: String,
            geoipRepo: String = repo,
            geositeRepo: String = geoipRepo,
            remoteFileNamesByLocalFileName: Map<String, List<String>> =
                mapOf(
                    RuleAssetNamePolicy.GEOIP_LOCAL to listOf(RuleAssetNamePolicy.GEOIP_LOCAL),
                    RuleAssetNamePolicy.GEOSITE_LOCAL to listOf(RuleAssetNamePolicy.GEOSITE_LOCAL),
                ),
        ) : this(
            repoByFileName =
                mapOf(
                    RuleAssetNamePolicy.GEOIP_LOCAL to geoipRepo,
                    RuleAssetNamePolicy.GEOSITE_LOCAL to geositeRepo,
                ),
            remoteFileNamesByLocalFileName = remoteFileNamesByLocalFileName,
        )

        fun displayNameFor(localFileName: String): String = remoteFileNamesByLocalFileName[localFileName]?.firstOrNull() ?: localFileName
    }

    private val rulesProviders =
        mapOf(
            DataStore.RULES_PROVIDER_OFFICIAL to
                RuleAssetsProvider(
                    repo = "SagerNet/sing-geoip",
                    geositeRepo = "SagerNet/sing-geosite",
                ),
            DataStore.RULES_PROVIDER_LOYALSOLDIER to
                RuleAssetsProvider(
                    repo = "soffchen/sing-geoip",
                    geositeRepo = "soffchen/sing-geosite",
                ),
            DataStore.RULES_PROVIDER_IRAN to RuleAssetsProvider(repo = "Chocolate4U/Iran-sing-box-rules"),
            DataStore.RULES_PROVIDER_ANTIZAPRET to RuleAssetsProvider(repo = "savely-krasovsky/antizapret-sing-box-geo"),
            DataStore.RULES_PROVIDER_ITDOG to
                RuleAssetsProvider(
                    repo = "itdoginfo/allow-domains",
                    remoteFileNamesByLocalFileName =
                        mapOf(
                            RuleAssetNamePolicy.GEOIP_LOCAL to listOf(RuleAssetNamePolicy.GEOIP_DAT),
                            RuleAssetNamePolicy.GEOSITE_LOCAL to listOf(RuleAssetNamePolicy.GEOSITE_DAT),
                        ),
                ),
            DataStore.RULES_PROVIDER_V2RAY_DAT to
                RuleAssetsProvider(
                    repo = "Loyalsoldier/v2ray-rules-dat",
                    remoteFileNamesByLocalFileName =
                        mapOf(
                            RuleAssetNamePolicy.GEOIP_LOCAL to listOf(RuleAssetNamePolicy.GEOIP_DAT),
                            RuleAssetNamePolicy.GEOSITE_LOCAL to listOf(RuleAssetNamePolicy.GEOSITE_DAT),
                        ),
                ),
            DataStore.RULES_PROVIDER_RUNETFREEDOM_DAT to
                RuleAssetsProvider(
                    repo = "runetfreedom/russia-v2ray-rules-dat",
                    remoteFileNamesByLocalFileName =
                        mapOf(
                            RuleAssetNamePolicy.GEOIP_LOCAL to listOf(RuleAssetNamePolicy.GEOIP_DAT),
                            RuleAssetNamePolicy.GEOSITE_LOCAL to listOf(RuleAssetNamePolicy.GEOSITE_DAT),
                        ),
                ),
        )

    private lateinit var adapter: AssetAdapter
    private lateinit var coordinator: CoordinatorLayout
    lateinit var undoManager: UndoSnackbarManager<File>
    private val crc32Cache = linkedMapOf<String, String>()
    private val updatingAssets = mutableStateMapOf<String, Boolean>()
    private var assetRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        adapter = AssetAdapter()
        undoManager = UndoSnackbarManager(this, adapter)
        coordinator = CoordinatorLayout(this)
        val content = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NekoComposeTheme {
                    assetRevision
                    AssetsScreen(
                        assets = adapter.uiItems(),
                        updatesInProgress = updatingAssets.isNotEmpty(),
                        onClose = ::finish,
                        onImport = { startFilesForResult(importFile, "*/*") },
                        onRefresh = adapter::reloadAssets,
                        onUpdate = ::startAssetUpdate,
                        onRemove = ::removeAsset,
                    )
                }
            }
        }
        coordinator.addView(
            content,
            CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(coordinator)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(coordinator, text, Snackbar.LENGTH_LONG)

    val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) {
                val fileName =
                    contentResolver
                        .query(file, null, null, null, null)
                        ?.use { cursor ->
                            cursor.moveToFirst()
                            cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
                        }?.takeIf { it.isNotBlank() } ?: file.pathSegments
                        .last()
                        .substringAfterLast('/')
                        .substringAfter(':')

                if (!fileName.endsWith(".db") && !fileName.endsWith(".dat")) {
                    alert(getString(R.string.route_not_asset, fileName)).show()
                    return@registerForActivityResult
                }
                val filesDir = getExternalFilesDir(null) ?: filesDir

                runOnDefaultDispatcher {
                    val outFile =
                        File(filesDir, fileName).apply {
                            parentFile?.mkdirs()
                        }

                    contentResolver.openInputStream(file)?.use(outFile.outputStream())

                    File(outFile.parentFile, outFile.nameWithoutExtension + ".version.txt").apply {
                        ensureVersionFile(this, newCustomAssetVersion())
                    }

                    adapter.reloadAssets()
                }
            }
        }

    private inner class AssetAdapter : UndoSnackbarManager.Interface<File> {
        private val assets = mutableStateListOf<AssetItem>()

        init {
            reloadAssets()
        }

        fun reloadAssets() {
            val filesDir = getExternalFilesDir(null) ?: filesDir
            val managedAssets = managedAssets(filesDir)
            val managedFileNames = managedAssets.map { it.file.name }.toHashSet()
            val files =
                filesDir
                    .listFiles()
                    ?.filter { it.isFile && (it.name.endsWith(".db") || it.name.endsWith(".dat")) && it.name !in managedFileNames }
                    ?.map { AssetItem(file = it, displayName = it.name, managed = false) }

            val reloaded = managedAssets + files.orEmpty()
            runOnMainDispatcher {
                assets.clear()
                assets.addAll(reloaded)
                preloadCrc32()
            }
        }

        fun remove(index: Int) {
            assets.removeAt(index)
        }

        override fun undo(actions: List<Pair<Int, File>>) {
            for ((index, file) in actions) {
                assets.add(index, AssetItem(file = file, displayName = file.name, managed = false))
            }
        }

        override fun commit(actions: List<Pair<Int, File>>) {
            val groups = actions.map { it.second }.toTypedArray()
            runOnDefaultDispatcher {
                groups.forEach { it.deleteRecursively() }
            }
        }

        private fun preloadCrc32() {
            assets.forEach(::ensureCrc32)
        }

        private fun ensureCrc32(item: AssetItem) {
            val file = item.file
            val key = file.absolutePath
            if (crc32Cache.containsKey(key)) return
            if (!file.isFile) {
                crc32Cache[key] = "<unknown>"
                assetRevision++
                return
            }

            runOnDefaultDispatcher {
                val crc32Value = calculateCrc32(file)
                crc32Cache[key] = crc32Value
                onMainDispatcher { assetRevision++ }
            }
        }

        fun item(path: String): AssetItem? = assets.firstOrNull { it.file.absolutePath == path }

        fun indexOf(path: String): Int = assets.indexOfFirst { it.file.absolutePath == path }

        fun uiItems(): List<AssetUiItem> = assets.map { item ->
            val localVersion = localVersion(item)
            val crc32Value = crc32Cache[item.file.absolutePath]
                ?: getString(R.string.route_asset_crc32_pending)
            AssetUiItem(
                path = item.file.absolutePath,
                name = item.displayName,
                status = getString(R.string.route_asset_status, displayVersion(localVersion)) + "\n" +
                    getString(R.string.route_asset_crc32, crc32Value),
                managed = item.managed,
                updating = updatingAssets.containsKey(item.file.absolutePath),
            )
        }
    }

    private fun localVersion(item: AssetItem): String {
        val file = item.file
        return if (file.isFile) {
            if (item.versionFile.isFile) {
                runCatching { item.versionFile.readText().trim() }
                    .getOrElse {
                        snackbar(it.readableMessage)
                        "<unknown>"
                    }
            } else if (item.bundledVersionAssetPath != null) {
                readBundledVersion(item)
            } else {
                "Unknown-" + DateFormat.getDateFormat(app).format(Date(file.lastModified()))
            }
        } else if (item.bundledVersionAssetPath != null) {
            readBundledVersion(item)
        } else {
            "<unknown>"
        }
    }

    private fun startAssetUpdate(path: String) {
        val item = adapter.item(path) ?: return
        val localVersion = localVersion(item)
        if (updatingAssets.put(path, true) != null) return
        runOnDefaultDispatcher {
            runCatching {
                updateAsset(item, localVersion)
            }.onFailure {
                onMainDispatcher {
                    alert(it.readableMessage).tryToShow()
                }
            }

            onMainDispatcher {
                updatingAssets.remove(path)
            }
        }
    }

    private fun removeAsset(path: String) {
        val index = adapter.indexOf(path)
        val item = adapter.item(path) ?: return
        if (index < 0 || item.managed) return
        adapter.remove(index)
        undoManager.remove(index to item.file)
    }

    private fun managedAssets(filesDir: File): List<AssetItem> {
        RulesetSuggestionRepository.ensureExternalFile(Source.THRONE, this)
        RulesetSuggestionRepository.ensureExternalFile(Source.ITDOG, this)
        val geoipDisplayName = ruleAssetDisplayName(RuleAssetNamePolicy.GEOIP_LOCAL)
        val geositeDisplayName = ruleAssetDisplayName(RuleAssetNamePolicy.GEOSITE_LOCAL)
        return listOf(
            AssetItem(
                file = File(filesDir, RuleAssetNamePolicy.GEOIP_LOCAL),
                displayName = geoipDisplayName,
                bundledVersionAssetPath = "sing-box/geoip.version.txt",
            ),
            AssetItem(
                file = File(filesDir, RuleAssetNamePolicy.GEOSITE_LOCAL),
                displayName = geositeDisplayName,
                bundledVersionAssetPath = "sing-box/geosite.version.txt",
            ),
            AssetItem(
                file = RulesetSuggestionRepository.resolveExternalFile(Source.THRONE, this),
                displayName = getString(R.string.throne_rule_sets),
                versionFile = RulesetSuggestionRepository.resolveVersionFile(Source.THRONE, this),
                bundledVersionAssetPath = "sing-box/${Source.THRONE.versionFileName}",
            ),
            AssetItem(
                file = RulesetSuggestionRepository.resolveExternalFile(Source.ITDOG, this),
                displayName = getString(R.string.itdog_rule_sets),
                versionFile = RulesetSuggestionRepository.resolveVersionFile(Source.ITDOG, this),
                bundledVersionAssetPath = "sing-box/${Source.ITDOG.versionFileName}",
            ),
        )
    }

    private fun ruleAssetDisplayName(localFileName: String): String {
        if (DataStore.rulesProvider == DataStore.RULES_PROVIDER_CUSTOM) {
            val url =
                when (localFileName) {
                    RuleAssetNamePolicy.GEOIP_LOCAL -> DataStore.rulesGeoipUrl
                    RuleAssetNamePolicy.GEOSITE_LOCAL -> DataStore.rulesGeositeUrl
                    else -> return localFileName
                }
            return RuleAssetNamePolicy.displayNameForCustom(localFileName, url)
        }
        return rulesProviders[DataStore.rulesProvider]?.displayNameFor(localFileName) ?: localFileName
    }

    private fun readBundledVersion(item: AssetItem): String {
        val assetPath = item.bundledVersionAssetPath ?: return "<unknown>"
        return runCatching {
            assets.open(assetPath).bufferedReader().use { it.readText().trim() }
        }.getOrDefault("<unknown>")
    }

    private fun calculateCrc32(file: File): String =
        runCatching {
            val crc32 = CRC32()
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    crc32.update(buffer, 0, read)
                }
            }
            crc32.value
                .toString(16)
                .padStart(8, '0')
                .uppercase()
        }.getOrDefault("<unknown>")

    private fun replaceAssetFile(
        cacheFile: File,
        targetFile: File,
    ) {
        if (!cacheFile.isFile) {
            throw IOException("Downloaded file is missing: ${cacheFile.absolutePath}")
        }

        if (targetFile.exists() && !targetFile.delete()) {
            throw IOException("Failed to replace ${targetFile.name}: unable to delete old file")
        }

        if (cacheFile.renameTo(targetFile)) {
            return
        }

        runCatching {
            cacheFile.copyTo(targetFile, overwrite = true)
        }.getOrElse { error ->
            throw IOException("Failed to replace ${targetFile.name}", error)
        }

        if (!cacheFile.delete()) {
            targetFile.delete()
            throw IOException("Failed to finalize ${targetFile.name}: unable to remove temp file")
        }
        crc32Cache.remove(targetFile.absolutePath)
    }

    private suspend fun updateAsset(
        item: AssetItem,
        localVersion: String,
    ) {
        val file = item.file
        val versionFile = item.versionFile

        when (file.name) {
            Source.THRONE.externalFileName -> return updateThroneRulesetAsset(file, versionFile, localVersion)
            Source.ITDOG.externalFileName -> return updateITDogRulesetAsset(file, versionFile, localVersion)
        }

        if (DataStore.rulesProvider == DataStore.RULES_PROVIDER_CUSTOM) {
            return updateCustomAsset(file, versionFile)
        }
        val provider =
            rulesProviders[DataStore.rulesProvider]
                ?: error("Rule assets provider not found: ${DataStore.rulesProvider}")
        val repo =
            provider.repoByFileName[file.name]
                ?: error("Repository mapping not found for ${file.name}")
        val remoteFileNames =
            provider.remoteFileNamesByLocalFileName[file.name]
                ?: error("Remote filename mapping not found for ${file.name}")

        val client =
            Libcore.newHttpClient().apply {
                withUTLS(DataStore.appUTLSFingerprint)
                modernTLS()
                keepAlive()
            }

        try {
            var response =
                client
                    .newRequest()
                    .apply {
                        setURL("https://api.github.com/repos/$repo/releases/latest")
                    }.execute()

            val release = JSONObject(Util.getStringBox(response.contentString))
            val tagName = release.optString("tag_name")

            if (tagName == localVersion) {
                ensureVersionFile(versionFile, tagName)
                onMainDispatcher {
                    snackbar(R.string.route_asset_no_update).show()
                }
                return
            }

            val releaseAssets = release.getJSONArray("assets").filterIsInstance<JSONObject>()
            val assetToDownload =
                releaseAssets.firstOrNull { asset ->
                    remoteFileNames.any { it == asset.getStr("name") }
                }
            if (assetToDownload == null) {
                if (DataStore.rulesProvider == DataStore.RULES_PROVIDER_ITDOG && file.name == "geoip.db") {
                    ensureVersionFile(versionFile, tagName)
                    onMainDispatcher {
                        snackbar(R.string.route_asset_no_update).show()
                    }
                    return
                }
                error("File ${remoteFileNames.joinToString(" or ")} not found in release ${release["url"]}")
            }
            val browserDownloadUrl = assetToDownload.getStr("browser_download_url")

            response =
                client
                    .newRequest()
                    .apply {
                        setURL(browserDownloadUrl)
                    }.execute()

            val cacheFile = File(file.parentFile, file.name + ".tmp")
            cacheFile.parentFile?.mkdirs()
            response.writeTo(cacheFile.canonicalPath)

            if (file.name.endsWith(".xz")) {
                Libcore.unxz(cacheFile.absolutePath, file.absolutePath)
                cacheFile.delete()
            } else {
                replaceAssetFile(cacheFile, file)
            }

            ensureVersionFile(versionFile, tagName)
            updateCrc32Cache(file)
            adapter.reloadAssets()

            onMainDispatcher {
                snackbar(R.string.route_asset_updated).show()
            }
        } finally {
            client.close()
        }
    }

    suspend fun updateCustomAsset(
        file: File,
        versionFile: File,
    ) {
        val url: String =
            when (file.name) {
                "geoip.db" -> DataStore.rulesGeoipUrl
                "geosite.db" -> DataStore.rulesGeositeUrl
                else -> return
            }
        val client =
            Libcore.newHttpClient().apply {
                withUTLS(DataStore.appUTLSFingerprint)
                modernTLS()
                keepAlive()
                trySocks5(
                    DataStore.mixedListener,
                    DataStore.mixedPort,
                    DataStore.mixedUsername,
                    DataStore.mixedPassword,
                )
            }
        try {
            val response =
                client
                    .newRequest()
                    .apply {
                        setURL(url)
                    }.execute()
            val cacheFile = File(file.parentFile, file.name + ".tmp")
            cacheFile.parentFile?.mkdirs()
            response.writeTo(cacheFile.canonicalPath)
            replaceAssetFile(cacheFile, file)
            ensureVersionFile(versionFile, newCustomAssetVersion())

            updateCrc32Cache(file)
            adapter.reloadAssets()
            onMainDispatcher {
                snackbar(R.string.route_asset_updated).show()
            }
        } finally {
            client.close()
        }
    }

    suspend fun updateThroneRulesetAsset(
        file: File,
        versionFile: File,
        localVersion: String,
    ) {
        val client =
            Libcore.newHttpClient().apply {
                withUTLS(DataStore.appUTLSFingerprint)
                modernTLS()
                keepAlive()
            }
        try {
            var response =
                client
                    .newRequest()
                    .apply {
                        setURL(THRONE_RULESET_COMMIT_API)
                    }.execute()
            val commit = JSONObject(Util.getStringBox(response.contentString))
            val sha = commit.optString("sha")
            val shortSha = sha.takeIf { it.isNotBlank() }?.take(SHORT_COMMIT_HASH_LENGTH).orEmpty()
            if (sha.isNotBlank() && (localVersion == sha || localVersion == shortSha)) {
                onMainDispatcher {
                    snackbar(R.string.route_asset_no_update).show()
                }
                return
            }

            response =
                client
                    .newRequest()
                    .apply {
                        setURL(THRONE_RULESET_DOWNLOAD_URL)
                    }.execute()
            val cacheFile = File(file.parentFile, file.name + ".tmp")
            cacheFile.parentFile?.mkdirs()
            response.writeTo(cacheFile.canonicalPath)
            wipeRulesetSuggestions(file, versionFile)
            replaceAssetFile(cacheFile, file)
            versionFile.writeText(
                if (shortSha.isBlank()) System.currentTimeMillis().toString() else shortSha,
            )

            updateCrc32Cache(file)
            adapter.reloadAssets()
            onMainDispatcher {
                snackbar(R.string.route_asset_updated).show()
            }
        } finally {
            client.close()
        }
    }

    suspend fun updateITDogRulesetAsset(
        file: File,
        versionFile: File,
        localVersion: String,
    ) {
        val client =
            Libcore.newHttpClient().apply {
                withUTLS(DataStore.appUTLSFingerprint)
                modernTLS()
                keepAlive()
            }
        try {
            val response =
                client
                    .newRequest()
                    .apply {
                        setURL(ITDOG_RELEASE_API)
                    }.execute()
            val release = JSONObject(Util.getStringBox(response.contentString))
            val tagName = release.optString("tag_name")
            if (tagName == localVersion) {
                ensureVersionFile(versionFile, tagName)
                onMainDispatcher {
                    snackbar(R.string.route_asset_no_update).show()
                }
                return
            }

            val root = JSONObject()
            release
                .getJSONArray("assets")
                .filterIsInstance<JSONObject>()
                .forEach { asset ->
                    val name = asset.optString("name")
                    if (!name.endsWith(".srs") || name.endsWith("_domain.srs")) return@forEach
                    val alias = "itdog-" + name.removeSuffix(".srs")
                    val apiUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: return@forEach
                    val entry = root.optJSONObject(alias) ?: JSONObject().also { root.put(alias, it) }
                    entry.put("rsip", apiUrl)
                }

            val cacheFile = File(file.parentFile, file.name + ".tmp")
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(root.toString())

            wipeRulesetSuggestions(file, versionFile)
            replaceAssetFile(cacheFile, file)
            versionFile.writeText(tagName)

            updateCrc32Cache(file)
            adapter.reloadAssets()
            onMainDispatcher {
                snackbar(R.string.route_asset_updated).show()
            }
        } finally {
            client.close()
        }
    }

    private fun wipeRulesetSuggestions(
        file: File,
        versionFile: File,
    ) {
        if (file.exists() && !file.delete()) {
            throw IOException("Failed to clear stale suggestions for ${file.name}")
        }
        if (versionFile.exists() && !versionFile.delete()) {
            throw IOException("Failed to clear stale version for ${file.name}")
        }
        crc32Cache.remove(file.absolutePath)
    }

    private fun ensureVersionFile(
        versionFile: File,
        version: String,
    ) {
        versionFile.parentFile?.mkdirs()
        if (!versionFile.isFile || versionFile.readText() != version) {
            versionFile.writeText(version)
        }
    }

    private fun newCustomAssetVersion(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        return CUSTOM_ASSET_VERSION_PREFIX + timestamp
    }

    private fun displayVersion(version: String): String = version.removePrefix(CUSTOM_ASSET_VERSION_PREFIX)

    private fun updateCrc32Cache(file: File) {
        crc32Cache.remove(file.absolutePath)
        crc32Cache[file.absolutePath] =
            if (file.isFile) {
                calculateCrc32(file)
            } else {
                "<unknown>"
            }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        if (::undoManager.isInitialized) undoManager.flush()
        super.onDestroy()
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onResume() {
        super.onResume()

        if (::adapter.isInitialized) {
            adapter.reloadAssets()
        }
    }
}
