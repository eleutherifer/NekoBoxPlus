package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.blacksquircle.ui.editorkit.insert
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.compose.ConfigEditorScreen
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme

class ConfigEditActivity : ThemedActivity() {

    private lateinit var editor: TextProcessor
    private var dirty = false
    private var key = Key.SERVER_CONFIG
    private var useConfigStore = false
    private var showUnsavedDialog by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.extras?.apply {
            getString(EXTRA_KEY)?.let { key = it }
            useConfigStore = containsKey(EXTRA_USE_CONFIG_STORE)
        }
        val initialText = if (useConfigStore) {
            DataStore.configurationStore.getString(key).orEmpty()
        } else {
            DataStore.profileCacheStore.getString(key).orEmpty()
        }

        setContent {
            NekoComposeTheme {
                ConfigEditorScreen(
                    initialText = initialText,
                    showUnsavedDialog = showUnsavedDialog,
                    onEditorReady = { editor = it },
                    onChanged = {
                        if (!dirty) {
                            dirty = true
                            DataStore.dirty = true
                        }
                    },
                    onClose = ::requestClose,
                    onApply = ::saveAndExit,
                    onDiscard = ::finish,
                    onDismissUnsavedDialog = { showUnsavedDialog = false },
                    onInsert = ::insert,
                    onUndo = { runCatching { editor.undo() } },
                    onRedo = { runCatching { editor.redo() } },
                    onFormat = { formatText()?.let(editor::setTextContent) },
                    errorMessage = errorMessage,
                    onDismissError = { errorMessage = null },
                )
            }
        }
    }

    private fun insert(value: String) {
        if (!::editor.isInitialized) return
        runCatching { editor.insert(value) }
    }

    private fun requestClose() {
        if (dirty) showUnsavedDialog = true else finish()
    }

    fun formatText(): String? {
        if (!::editor.isInitialized) return null
        return try {
            ConfigJsonFormatter.format(editor.text.toString())
        } catch (exception: Exception) {
            errorMessage = exception.readableMessage
            null
        }
    }

    fun saveAndExit() {
        formatText()?.let { formatted ->
            if (useConfigStore) {
                DataStore.configurationStore.putString(key, formatted)
            } else {
                DataStore.profileCacheStore.putString(key, formatted)
            }
            finish()
        }
    }

    private companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_USE_CONFIG_STORE = "useConfigStore"
    }
}
