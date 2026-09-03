package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.backup.BackupContainerCodec
import io.nekohasekai.sagernet.backup.BackupPasswordException
import io.nekohasekai.sagernet.backup.GitBackupConfig
import io.nekohasekai.sagernet.backup.GitBackupConfigStore
import io.nekohasekai.sagernet.backup.InvalidBackupContainerException
import io.nekohasekai.sagernet.backup.GitBackupRepository
import io.nekohasekai.sagernet.backup.UnsupportedBackupVersionException
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.compose.BackupScreen
import io.nekohasekai.sagernet.ui.compose.BackupSelection
import io.nekohasekai.sagernet.ui.compose.GitRestoreOption
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.showBlockingProgressDialog
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.ktx.snackbar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.io.BufferedOutputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class BackupFragment : NamedFragment() {

    private var isBackupInProgress = false
    private var backupProgressDialog: androidx.activity.ComponentDialog? = null
    private var pendingExportOptions: BackupOptions? = null
    private var isRestoreInProgress = false
    private var currentJob: kotlinx.coroutines.Job? = null
    private var snackbar: Snackbar? = null
    private var restoreJob: kotlinx.coroutines.Job? = null
    private var isGitOperationInProgress by mutableStateOf(false)
    private var gitConfigured by mutableStateOf(false)
    private var gitProgressDialog: androidx.activity.ComponentDialog? = null
    private var gitRestoreOptions by mutableStateOf<List<GitRestoreOption>?>(null)
    private var gitRestoreConfig: GitBackupConfig? = null
    private var showGitCompactDialog by mutableStateOf(false)

    private val gitSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        updateGitButtons()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.setFragmentResultListener(
            BackupImportDialogFragment.RESULT_KEY,
            this,
        ) { _, result ->
            val file = result.getString(BackupImportDialogFragment.RESULT_FILE) ?: return@setFragmentResultListener
            importBackup(
                File(file),
                result.getBoolean(BackupImportDialogFragment.RESULT_PROFILES),
                result.getBoolean(BackupImportDialogFragment.RESULT_RULES),
                result.getBoolean(BackupImportDialogFragment.RESULT_SETTINGS),
            )
        }
    }

    override fun onDestroyView() {
        backupProgressDialog?.dismiss()
        backupProgressDialog = null
        gitProgressDialog?.dismiss()
        gitProgressDialog = null
        super.onDestroyView()
        snackbar?.dismiss()
        snackbar = null
        // 如果正在进行恢复操作，取消它
        if (isRestoreInProgress) {
            restoreJob?.cancel()
            restoreJob = null
            isRestoreInProgress = false
            MessageStore.showMessage(requireActivity(), R.string.restore_cancelled)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentJob?.cancel()
        currentJob = null
    }

    override fun name0() = app.getString(R.string.backup)

    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
        val options = pendingExportOptions
        pendingExportOptions = null
        if (data != null && options != null) {
            val activity = requireActivity()
            beginBackupOperation {
                try {
                    val backupData = doBackup(options.profile, options.rule, options.setting)
                    activity.contentResolver.openOutputStream(data)!!.use { os ->
                        os.write(backupData)
                    }
                    onMainDispatcher {
                        snackbar(getString(R.string.action_export_msg)).show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NekoComposeTheme {
                BackupScreen(
                    gitConfigured = gitConfigured,
                    gitOperationInProgress = isGitOperationInProgress,
                    onShare = ::shareBackup,
                    onExport = ::exportBackup,
                    onImport = { startFilesForResult(importFile, "*/*") },
                    onWebDavSettings = {
                        startActivity(Intent(requireContext(), WebDAVSettingsActivity::class.java))
                    },
                    onWebDavBackup = ::startWebDavBackup,
                    onWebDavRestore = ::startWebDavRestore,
                    onGitConfigure = {
                        gitSettings.launch(Intent(requireContext(), GitBackupSettingsActivity::class.java))
                    },
                    onGitBackup = ::backupToGit,
                    onGitRestore = ::restoreFromGit,
                    onGitCompact = ::showCompactDialog,
                    gitRestoreOptions = gitRestoreOptions,
                    onDismissGitRestore = ::dismissGitRestore,
                    onSelectGitRestore = ::selectGitRestore,
                    showGitCompactDialog = showGitCompactDialog,
                    onDismissGitCompact = { showGitCompactDialog = false },
                    onConfirmGitCompact = ::confirmGitCompact,
                )
            }
        }
    }

    private fun exportBackup(selection: BackupSelection) {
        if (isBackupInProgress) {
            showMessage(R.string.backup_in_progress)
            return
        }
        pendingExportOptions = BackupOptions(
            selection.configurations,
            selection.rules,
            selection.settings,
        )
        startFilesForResult(
            exportSettings, "nekobox_backup_${Date().toLocaleString()}.json"
        )
    }

    private fun shareBackup(selection: BackupSelection) {
        val activity = requireActivity()
        val options = BackupOptions(
            selection.configurations,
            selection.rules,
            selection.settings,
        )
        beginBackupOperation {
            try {
                val backupData = doBackup(options.profile, options.rule, options.setting)
                app.cacheDir.mkdirs()
                val cacheFile = File(
                    app.cacheDir, "nekobox_backup_${Date().toLocaleString()}.json"
                )
                cacheFile.writeBytes(backupData)
                onMainDispatcher {
                    if (isAdded) {
                        startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("application/json")
                                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    .putExtra(
                                        Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                            app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                        )
                                    ), app.getString(R.string.abc_shareactionprovider_share_with)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    MessageStore.showMessage(activity, e.readableMessage)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateGitButtons()
    }

    private fun updateGitButtons() {
        if (isAdded) gitConfigured = GitBackupConfigStore(requireContext()).load() != null
    }

    private fun gitConfig(): GitBackupConfig? {
        return GitBackupConfigStore(requireContext()).load().also {
            if (it == null) {
                gitSettings.launch(Intent(requireContext(), GitBackupSettingsActivity::class.java))
            }
        }
    }

    private fun gitRepository() = GitBackupRepository(app.cacheDir.resolve("git-backup-repository"))

    private fun beginGitOperation(@StringRes status: Int, block: (GitBackupConfig) -> Unit) {
        if (isGitOperationInProgress) {
            showMessage(R.string.git_operation_in_progress)
            return
        }
        val config = gitConfig() ?: return
        isGitOperationInProgress = true
        updateGitButtons()
        gitProgressDialog = requireContext().showBlockingProgressDialog(status)
        block(config)
    }

    private fun finishGitOperation() {
        isGitOperationInProgress = false
        gitProgressDialog?.dismiss()
        gitProgressDialog = null
        if (isAdded) updateGitButtons()
    }

    private fun beginBackupOperation(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        if (isBackupInProgress) {
            showMessage(R.string.backup_in_progress)
            return
        }
        isBackupInProgress = true
        backupProgressDialog =
            requireContext().showBlockingProgressDialog(R.string.backup_creating)
        runOnDefaultDispatcher {
            try {
                block()
            } finally {
                onMainDispatcher { finishBackupOperation() }
            }
        }
    }

    private fun finishBackupOperation() {
        isBackupInProgress = false
        backupProgressDialog?.dismiss()
        backupProgressDialog = null
    }

    private fun backupToGit() = beginGitOperation(R.string.git_backing_up) { config ->
        val activity = requireActivity()
        currentJob = runOnDefaultDispatcher {
            runCatching {
                val json = doBackup(profile = true, rule = true, setting = true, zip = false)
                val encrypted = BackupContainerCodec.encrypt(
                    json,
                    config.encryptionPassword.toCharArray(),
                )
                gitRepository().backup(config, encrypted)
            }.onSuccess {
                onMainDispatcher {
                    MessageStore.showMessage(activity, R.string.git_backup_success)
                }
            }.onFailure {
                Logs.w(it)
                onMainDispatcher {
                    MessageStore.showMessage(
                        activity,
                        getString(R.string.git_backup_failed, gitErrorMessage(it)),
                    )
                }
            }
            onMainDispatcher { finishGitOperation() }
        }
    }

    private fun restoreFromGit() = beginGitOperation(R.string.git_loading_versions) { config ->
        val activity = requireActivity()
        currentJob = runOnDefaultDispatcher {
            runCatching { gitRepository().listRestorePoints(config) }
                .onSuccess { points ->
                    onMainDispatcher {
                        finishGitOperation()
                        if (!isAdded) return@onMainDispatcher
                        if (points.isEmpty()) {
                            showMessage(R.string.git_no_backups)
                            return@onMainDispatcher
                        }
                        gitRestoreConfig = config
                        gitRestoreOptions = points.map {
                            val date = java.text.DateFormat.getDateTimeInstance().format(Date(it.timestampMillis))
                            GitRestoreOption(
                                commitId = it.commitId,
                                label = "$date  •  ${it.commitId.take(8)}",
                            )
                        }
                    }
                }.onFailure {
                    Logs.w(it)
                    onMainDispatcher {
                        finishGitOperation()
                        MessageStore.showMessage(
                            activity,
                            getString(R.string.git_restore_failed, gitErrorMessage(it)),
                        )
                    }
                }
        }
    }

    private fun loadGitRestore(config: GitBackupConfig, commitId: String) =
        beginGitOperation(R.string.git_loading_backup) {
            val activity = requireActivity()
            currentJob = runOnDefaultDispatcher {
                runCatching {
                    val encrypted = gitRepository().readBackup(config, commitId)
                    val json = BackupContainerCodec.decrypt(
                        encrypted,
                        config.encryptionPassword.toCharArray(),
                    )
                    JSONObject(json.toString(Charsets.UTF_8))
                }.onSuccess { json ->
                    onMainDispatcher {
                        finishGitOperation()
                        if (isAdded) showGitImport(json)
                    }
                }.onFailure {
                    Logs.w(it)
                    onMainDispatcher {
                        finishGitOperation()
                        MessageStore.showMessage(
                            activity,
                            getString(R.string.git_restore_failed, gitErrorMessage(it)),
                        )
                    }
                }
            }
        }

    private fun showGitImport(json: JSONObject) {
        showImportDialog(json, showGitWarning = true)
    }

    private fun showCompactDialog() {
        showGitCompactDialog = true
    }

    private fun confirmGitCompact(value: String) {
        showGitCompactDialog = false
        val versions = value.toIntOrNull()
        if (versions == null || versions !in 1..10000) {
            showMessage(R.string.git_invalid_configuration)
        } else compactGit(versions)
    }

    private fun dismissGitRestore() {
        gitRestoreOptions = null
        gitRestoreConfig = null
    }

    private fun selectGitRestore(commitId: String) {
        val config = gitRestoreConfig ?: return
        dismissGitRestore()
        loadGitRestore(config, commitId)
    }

    private fun compactGit(versions: Int) =
        beginGitOperation(R.string.git_compacting) { config ->
        val activity = requireActivity()
        currentJob = runOnDefaultDispatcher {
            runCatching { gitRepository().compact(config, versions) }
                .onSuccess { result ->
                    onMainDispatcher {
                        finishGitOperation()
                        MessageStore.showMessage(
                            activity,
                            if (result.changed) {
                                getString(R.string.git_compact_success, result.retainedVersions)
                            } else {
                                getString(R.string.git_compact_not_needed, result.retainedVersions)
                            },
                        )
                    }
                }.onFailure {
                    Logs.w(it)
                    onMainDispatcher {
                        finishGitOperation()
                        MessageStore.showMessage(
                            activity,
                            getString(R.string.git_backup_failed, gitErrorMessage(it)),
                        )
                    }
                }
            }
        }

    private fun startWebDavBackup() {
        if (DataStore.webdavServer.isNullOrEmpty()) {
            showMessage(R.string.webdav_server_empty)
            return
        }
        backupToWebDAV()
    }

    private fun startWebDavRestore() {
        if (DataStore.webdavServer.isNullOrEmpty()) {
            showMessage(R.string.webdav_server_empty)
            return
        }
        restoreFromWebDAV()
    }

    private fun backupToWebDAV() {
        val activity = requireActivity()
        beginBackupOperation {
            try {
                val backupData = doBackup(
                    true,  // 备份配置和分组
                    true,  // 备份路由规则
                    true,  // 备份设置
                    zip = true,
                )
                
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.MINUTES)
                    .readTimeout(5, TimeUnit.MINUTES)
                    .writeTimeout(5, TimeUnit.MINUTES)
                    .build()

                // 规范化 URL
                val baseUrl = DataStore.webdavServer!!.trimEnd('/')
                val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: "Nekobox"

                // 使用英文格式的时间戳作为文件名，修改后缀为 .zip
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val version = BuildConfig.VERSION_NAME
                val fileName = "nekobox_backup_${version}_$timestamp.zip"

                // 确保 baseUrl 是有效的 URL
                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    throw Exception("Invalid server URL: must start with http:// or https://")
                }

                // 使用 HttpUrl 构建路径，避免 # 等特殊字符被当作 fragment
                val baseHttpUrl = baseUrl.toHttpUrlOrNull()
                    ?: throw Exception("Invalid server URL: $baseUrl")

                val dirUrl = baseHttpUrl.newBuilder().apply {
                    path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                        addPathSegment(segment)
                    }
                }.build()

                val fileUrl = dirUrl.newBuilder()
                    .addPathSegment(fileName)
                    .build()

                Logs.d("WebDAV backup - Directory URL: $dirUrl")
                Logs.d("WebDAV backup - File URL: $fileUrl")

                // 先检查目录是否存在
                val propfindRequest = Request.Builder()
                    .url(dirUrl)
                    .method("PROPFIND", null)
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .header("Depth", "0")
                    .build()

                var needCreateDir = false
                client.newCall(propfindRequest).execute().use { response ->
                    Logs.d("WebDAV backup - PROPFIND response: ${response.code}")
                    when (response.code) {
                        404 -> needCreateDir = true
                        207 -> needCreateDir = false // 目录存在
                        401 -> throw Exception("Authentication failed")
                        else -> {
                            if (!response.isSuccessful) {
                                val errorBody = response.body?.string()
                                Logs.e("WebDAV backup - PROPFIND error: $errorBody")
                                throw Exception("Failed to check directory (${response.code}): ${response.message}")
                            }
                        }
                    }
                }

                // 如果需要，创建目录
                if (needCreateDir) {
                    Logs.d("WebDAV backup - Creating directory")
                    val mkcolRequest = Request.Builder()
                        .url(dirUrl)
                        .method("MKCOL", null)
                        .header("Authorization", Credentials.basic(
                            DataStore.webdavUsername ?: "",
                            DataStore.webdavPassword ?: ""
                        ))
                        .build()

                    client.newCall(mkcolRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string()
                            Logs.e("WebDAV backup - MKCOL error: $errorBody")
                            throw Exception("Failed to create directory (${response.code}): ${response.message}")
                        }
                    }
                }

                // 上传文件时使用正确的 Content-Type
                val putRequest = Request.Builder()
                    .url(fileUrl)
                    .put(backupData.toRequestBody("application/zip".toMediaType()))
                    .apply {
                        header("Authorization", Credentials.basic(
                            DataStore.webdavUsername ?: "",
                            DataStore.webdavPassword ?: ""
                        ))
                    }
                    .build()

                client.newCall(putRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV backup - PUT error: $errorBody")
                        throw Exception("Upload failed (${response.code}): ${response.message}\n$errorBody")
                    }
                    Logs.d("WebDAV backup - Upload successful")
                }

                onMainDispatcher {
                    MessageStore.showMessage(activity, R.string.webdav_backup_success)
                }
            } catch (e: Exception) {
                Logs.w(e)

                val errorMessage = try {
                    if (isAdded) {
                        getString(R.string.webdav_backup_failed, e.message ?: "")
                    } else {
                        app.getString(R.string.webdav_backup_failed, e.message ?: "")
                    }
                } catch (ex: Exception) {
                    "WebDAV backup failed: ${e.message ?: ""}"
                }
                
                onMainDispatcher {
                    MessageStore.showMessage(activity, errorMessage)
                }
            }
        }
    }

    private fun restoreFromWebDAV() {
        if (isRestoreInProgress) {
            showMessage(R.string.restore_in_progress)
            return
        }
        isRestoreInProgress = true
        val activity = requireActivity()
        restoreJob = runOnDefaultDispatcher {
            try {
                val client = OkHttpClient()
                val baseUrl = DataStore.webdavServer!!.trimEnd('/')
                val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: "Nekobox"

                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    throw Exception("Invalid server URL: must start with http:// or https://")
                }

                val baseHttpUrl = baseUrl.toHttpUrlOrNull()
                    ?: throw Exception("Invalid server URL: $baseUrl")

                val dirUrl = baseHttpUrl.newBuilder().apply {
                    path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                        addPathSegment(segment)
                    }
                }.build()

                Logs.d("WebDAV restore - Directory URL: $dirUrl")

                // 先列出目录内容找到最新的备份文件
                val propfindRequest = Request.Builder()
                    .url(dirUrl)
                    .method("PROPFIND", null)
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .header("Depth", "1")
                    .build()

                // 获取最新的备份文件名
                val latestBackup = client.newCall(propfindRequest).execute().use { response ->
                    if (!response.isSuccessful && response.code != 207) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV restore - PROPFIND error: $errorBody")
                        throw Exception("Failed to list directory: ${response.message}")
                    }

                    val responseBody = response.body?.string() ?: throw Exception("Empty response")
                    Logs.d("WebDAV restore - Directory listing: $responseBody")
                    
                    val patterns = listOf(
                        """<D:href>[^<]*?nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</D:href>""".toRegex(),
                        """<d:href>[^<]*?nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</d:href>""".toRegex(),
                        """<href>[^<]*?nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</href>""".toRegex()
                    )
                    
                    val backupFiles = mutableListOf<String>()
                    
                    for (pattern in patterns) {
                        val matches = pattern.findAll(responseBody)
                        matches.forEach { match ->
                            val href = match.value
                            Logs.d("WebDAV restore - Found backup file with pattern ${pattern.pattern}: $href")
                            val fileName = """nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip)""".toRegex()
                                .find(href)?.value
                            if (fileName != null) {
                                backupFiles.add(fileName)
                            }
                        }
                        if (backupFiles.isNotEmpty()) break
                    }
                    
                    Logs.d("WebDAV restore - Found ${backupFiles.size} backup files: ${backupFiles.joinToString()}")

                    backupFiles.maxByOrNull { fileName ->
                        """(\d{8}_\d{6})""".toRegex().find(fileName)?.value ?: ""
                    } ?: throw Exception("No backup found")
                }

                // 下载最新的备份文件
                val fileUrl = dirUrl.newBuilder()
                    .addPathSegment(latestBackup)
                    .build()
                Logs.d("WebDAV restore - File URL: $fileUrl")

                val getRequest = Request.Builder()
                    .url(fileUrl)
                    .get()
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .build()

                val content = client.newCall(getRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV restore - GET error: $errorBody")
                        throw Exception("Download failed (${response.code}): ${response.message}")
                    }
                    response.body?.bytes() ?: throw Exception("Empty backup file")
                }

                Logs.d("WebDAV restore - Successfully downloaded backup file, size: ${content.size}")

                // 根据文件类型处理内容
                val backupContent = if (latestBackup.endsWith(".zip")) {
                    // ZIP 文件处理
                    ZipInputStream(content.inputStream()).use { zis ->
                        zis.nextEntry?.let { entry ->
                            if (entry.name.endsWith(".json")) {
                                zis.readBytes().toString(Charsets.UTF_8)
                            } else {
                                throw Exception("Invalid backup file format")
                            }
                        } ?: throw Exception("Invalid backup file format")
                    }
                } else {
                    // JSON 文件处理
                    content.toString(Charsets.UTF_8)
                }

                // 解析并导入备份数据
                val json = JSONObject(backupContent)
                onMainDispatcher {
                    // 如果 Fragment 已经被销毁，取消恢复操作
                    if (!isAdded) {
                        MessageStore.showMessage(activity, R.string.restore_cancelled)
                        return@onMainDispatcher
                    }

                    showImportDialog(json)
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    MessageStore.showMessage(activity, e.readableMessage)
                }
            } finally {
                isRestoreInProgress = false
            }
        }
    }

    fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        writeToParcel(parcel, 0)
        try {
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    private fun doBackup(
        profile: Boolean,
        rule: Boolean,
        setting: Boolean,
        zip: Boolean = false,
    ): ByteArray {
        val out = JSONObject().apply {
            put("version", 1)
            if (profile) {
                put("profiles", JSONArray().apply {
                    val proxyDao = SagerDatabase.proxyDao
                    proxyDao.getAllIds().forEach { profileId ->
                        proxyDao.getById(profileId)?.let {
                            if (it.beanOrNull() == null) {
                                Logs.w("Skipping invalid profile during backup: id=${it.id}, type=${it.type}")
                                return@let
                            }
                            put(it.toBase64Str())
                        }
                    }
                })

                put("groups", JSONArray().apply {
                    SagerDatabase.groupDao.allGroups().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (rule) {
                put("rules", JSONArray().apply {
                    SagerDatabase.rulesDao.allRules().forEach {
                        put(it.toBase64Str())
                    }
                })
                put("customDnsServers", JSONArray().apply {
                    CustomDnsServerStore.allServers().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (setting) {
                put("settings", JSONArray().apply {
                    PublicDatabase.kvPairDao.all().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
        }

        val jsonContent = out.toStringPretty()
        return if (zip) {
            ByteArrayOutputStream().use { bos ->
                ZipOutputStream(bos).use { zos ->
                    zos.setLevel(Deflater.BEST_COMPRESSION)
                    
                    val entry = ZipEntry("nekobox_backup.json").apply {
                        method = ZipEntry.DEFLATED
                    }
                    
                    // 写入数据
                    zos.putNextEntry(entry)
                    val bytes = jsonContent.toByteArray(Charsets.UTF_8)
                    zos.write(bytes)
                    zos.closeEntry()
                    
                    // 确保所有数据都被写入和压缩
                    zos.finish()
                }
                bos.toByteArray()
            }
        } else {
            // 本地导出和分享功能使用 JSON 格式
            jsonContent.toByteArray()
        }
    }

    private data class BackupOptions(
        val profile: Boolean,
        val rule: Boolean,
        val setting: Boolean,
    )

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val activity = requireActivity()
        val fileName = requireContext().contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".json") && !fileName.endsWith(".zip")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        try {
            val content = requireContext().contentResolver.openInputStream(file)!!.use { input ->
                if (fileName.endsWith(".zip")) {
                    ZipInputStream(BufferedInputStream(input)).use { zis ->
                        zis.nextEntry?.let { entry ->
                            if (entry.name.endsWith(".json")) {
                                zis.readBytes().toString(Charsets.UTF_8)
                            } else {
                                throw Exception("Invalid backup file format")
                            }
                        } ?: throw Exception("Invalid backup file format")
                    }
                } else {
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }

            val json = JSONObject(content)
            onMainDispatcher {
                showImportDialog(json)
            }
        } catch (e: Exception) {
            Logs.w(e)
            onMainDispatcher {
                MessageStore.showMessage(activity, e.readableMessage)
            }
        }
    }

    private fun showImportDialog(json: JSONObject, showGitWarning: Boolean = false) {
        if (childFragmentManager.findFragmentByTag(BackupImportDialogFragment.TAG) != null) return
        val directory = File(requireContext().cacheDir, "backup-import-dialog").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.json")
        file.writeText(json.toString())
        BackupImportDialogFragment.newInstance(
            file = file.absolutePath,
            hasProfiles = json.has("profiles"),
            hasRules = json.has("rules"),
            hasSettings = json.has("settings"),
            showGitWarning = showGitWarning,
        ).show(childFragmentManager, BackupImportDialogFragment.TAG)
    }

    private fun importBackup(file: File, profile: Boolean, rule: Boolean, setting: Boolean) {
        val activity = requireActivity()
        SagerNet.stopService()
        val dialog = requireContext().showBlockingProgressDialog(R.string.backup_importing)
        runOnDefaultDispatcher {
            runCatching {
                finishImport(JSONObject(file.readText()), profile, rule, setting)
                triggerFullRestart(activity)
            }.onFailure {
                Logs.w(it)
                onMainDispatcher {
                    dialog.dismiss()
                    MessageStore.showMessage(activity, it.readableMessage)
                }
            }
            file.delete()
        }
    }

    fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ) {
        if (profile && content.has("profiles")) {
            val profiles = mutableListOf<ProxyEntity>()
            val jsonProfiles = content.getJSONArray("profiles")
            for (i in 0 until jsonProfiles.length()) {
                val data = Util.b64Decode(jsonProfiles[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                profiles.add(ProxyEntity.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            SagerDatabase.proxyDao.reset()
            SagerDatabase.proxyDao.insert(profiles)

            val groups = mutableListOf<ProxyGroup>()
            val jsonGroups = content.getJSONArray("groups")
            for (i in 0 until jsonGroups.length()) {
                val data = Util.b64Decode(jsonGroups[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                groups.add(ProxyGroup.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            SagerDatabase.groupDao.reset()
            SagerDatabase.groupDao.insert(groups)
        }
        if (rule && content.has("rules")) {
            val rules = mutableListOf<RuleEntity>()
            val jsonRules = content.getJSONArray("rules")
            for (i in 0 until jsonRules.length()) {
                val data = Util.b64Decode(jsonRules[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                rules.add(ParcelizeBridge.createRule(parcel))
                parcel.recycle()
            }
            SagerDatabase.rulesDao.reset()
            SagerDatabase.rulesDao.insert(rules)
            if (content.has("customDnsServers")) {
                val servers = mutableListOf<CustomDnsServerEntity>()
                val jsonServers = content.getJSONArray("customDnsServers")
                for (i in 0 until jsonServers.length()) {
                    val data = Util.b64Decode(jsonServers[i] as String)
                    val parcel = Parcel.obtain()
                    parcel.unmarshall(data, 0, data.size)
                    parcel.setDataPosition(0)
                    servers.add(ParcelizeBridge.createCustomDnsServer(parcel))
                    parcel.recycle()
                }
                CustomDnsServerStore.replaceAll(servers)
            }
        }
        if (setting && content.has("settings")) {
            val settings = mutableListOf<KeyValuePair>()
            val jsonSettings = content.getJSONArray("settings")
            for (i in 0 until jsonSettings.length()) {
                val data = Util.b64Decode(jsonSettings[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                settings.add(KeyValuePair.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            PublicDatabase.kvPairDao.reset()
            PublicDatabase.kvPairDao.insert(settings)
            // Never let a restore clear the first-setup flag — older backups simply
            // won't contain this key, which would default it to false and re-trigger
            // the first-setup flow on next launch.
            DataStore.proxyAppsFirstSetup = true
        }
    }

    private fun showMessage(message: String) {
        MessageStore.showMessage(message)
    }

    private fun gitErrorMessage(error: Throwable): String {
        if (error is BackupPasswordException) return getString(R.string.git_wrong_password)
        if (error is InvalidBackupContainerException) return getString(R.string.git_invalid_container)
        if (error is UnsupportedBackupVersionException) {
            return getString(R.string.git_unsupported_version, error.version)
        }
        val chain = generateSequence(error) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase(Locale.ROOT)
        return when {
            "auth" in chain || "not authorized" in chain || "401" in chain ->
                getString(R.string.git_authentication_failed)
            "force-push" in chain || "protected" in chain || "rejected" in chain ->
                getString(R.string.git_force_push_rejected)
            "merge history" in chain -> getString(R.string.git_merge_history_unsupported)
            "non-fast-forward" in chain || "remote branch after resynchronizing" in chain ->
                getString(R.string.git_history_changed)
            else -> getString(R.string.git_remote_error)
        }
    }

    private fun showMessage(@StringRes resId: Int) {
        MessageStore.showMessage(requireActivity(), resId)
    }

    private fun showMessage(@StringRes resId: Int, vararg args: Any) {
        MessageStore.showMessage(requireActivity(), resId, *args)
    }

}
