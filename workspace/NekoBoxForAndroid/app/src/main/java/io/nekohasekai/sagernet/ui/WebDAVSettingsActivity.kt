package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.backup.WebDavConnectionResult
import io.nekohasekai.sagernet.backup.WebDavConnectionSettings
import io.nekohasekai.sagernet.backup.WebDavConnectionTester
import io.nekohasekai.sagernet.backup.WebDavFailureReason
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.WebDavField
import io.nekohasekai.sagernet.ui.compose.WebDavNotice
import io.nekohasekai.sagernet.ui.compose.WebDavSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebDAVSettingsActivity : ThemedActivity() {
    private val tester = WebDavConnectionTester()
    private var server by mutableStateOf("")
    private var username by mutableStateOf("")
    private var password by mutableStateOf("")
    private var path by mutableStateOf("")
    private var editingField by mutableStateOf<WebDavField?>(null)
    private var testing by mutableStateOf(false)
    private var notice by mutableStateOf<WebDavNotice?>(null)
    private var nextNoticeId = 0L
    private var lastTestClickTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = DataStore.webdavServer.orEmpty()
        username = DataStore.webdavUsername.orEmpty()
        password = DataStore.webdavPassword.orEmpty()
        path = DataStore.webdavPath.orEmpty()
        setContent {
            NekoComposeTheme {
                WebDavSettingsScreen(
                    server = server,
                    username = username,
                    password = password,
                    path = path,
                    editingField = editingField,
                    testing = testing,
                    notice = notice,
                    onClose = ::finish,
                    onEdit = { editingField = it },
                    onDismissEdit = { editingField = null },
                    onSave = ::saveField,
                    onTest = ::testConnection,
                    onNoticeShown = { notice = null },
                )
            }
        }
    }

    private fun saveField(field: WebDavField, value: String) {
        when (field) {
            WebDavField.Server -> {
                server = value
                DataStore.webdavServer = value
            }
            WebDavField.Username -> {
                username = value
                DataStore.webdavUsername = value
            }
            WebDavField.Password -> {
                password = value
                DataStore.webdavPassword = value
            }
            WebDavField.Path -> {
                path = value
                DataStore.webdavPath = value
            }
        }
        editingField = null
    }

    private fun testConnection() {
        val now = System.currentTimeMillis()
        if (testing || now - lastTestClickTime < TEST_DEBOUNCE_MILLIS) {
            showNotice(getString(R.string.webdav_test_in_progress))
            return
        }
        lastTestClickTime = now
        testing = true
        val settings = WebDavConnectionSettings(server, username, password, path)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { tester.test(settings) }
            testing = false
            showNotice(resultMessage(result))
        }
    }

    private fun resultMessage(result: WebDavConnectionResult): String = when (result) {
        WebDavConnectionResult.Success -> getString(R.string.webdav_test_success)
        is WebDavConnectionResult.Failure -> getString(
            R.string.webdav_test_failed,
            when (result.reason) {
                WebDavFailureReason.EmptyServer -> getString(R.string.webdav_server_empty)
                WebDavFailureReason.Authentication -> getString(R.string.webdav_auth_error)
                WebDavFailureReason.PermissionDenied -> getString(R.string.webdav_permission_denied)
                WebDavFailureReason.NotFound -> getString(R.string.webdav_server_not_found)
                WebDavFailureReason.ServerError -> getString(R.string.webdav_server_error)
                WebDavFailureReason.Connection -> getString(
                    R.string.webdav_connect_failed,
                    result.responseCode ?: 0,
                )
                WebDavFailureReason.CreateDirectory -> getString(R.string.webdav_create_dir_failed)
                WebDavFailureReason.Other -> result.detail ?: getString(R.string.webdav_server_error)
            },
        )
    }

    private fun showNotice(message: String) {
        notice = WebDavNotice(++nextNoticeId, message)
    }

    private companion object {
        const val TEST_DEBOUNCE_MILLIS = 1_000L
    }
}
