package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.SingBoxConfigPreviewScreen
import io.nekohasekai.sagernet.ui.profile.ConfigJsonFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SingBoxConfigPreviewActivity : ThemedActivity() {

    private lateinit var editor: TextProcessor
    private var configText = ""
    private var configCopyable by mutableStateOf(false)
    private var wordWrap by mutableStateOf(false)
    private var loadStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wordWrap = savedInstanceState?.getBoolean(STATE_WORD_WRAP) ?: false

        setContent {
            NekoComposeTheme {
                SingBoxConfigPreviewScreen(
                    wordWrap = wordWrap,
                    copyEnabled = configCopyable && configText.isNotBlank(),
                    onClose = ::finish,
                    onCopy = ::copyConfig,
                    onToggleWordWrap = { wordWrap = !wordWrap },
                    onEditorReady = {
                        editor = it
                        loadConfig()
                    },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_WORD_WRAP, wordWrap)
        super.onSaveInstanceState(outState)
    }

    private fun loadConfig() {
        if (loadStarted) return
        loadStarted = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val profile = ProfileManager.getProfile(DataStore.selectedProxy)
                        ?: return@runCatching PreviewContent(
                            getString(R.string.preview_sing_box_config_empty),
                            copyable = false,
                        )
                    val rawConfig = buildConfig(
                        profile,
                        showSubscriptionRoutingUnavailable = false,
                    ).config
                    PreviewContent(
                        runCatching { ConfigJsonFormatter.format(rawConfig) }
                            .getOrDefault(rawConfig),
                        copyable = true,
                    )
                }.getOrElse {
                    PreviewContent(
                        getString(R.string.preview_sing_box_config_failed, it.readableMessage),
                        copyable = false,
                    )
                }
            }
            configText = result.text
            configCopyable = result.copyable
            editor.setTextContent(result.text)
        }
    }

    private fun copyConfig() {
        if (!configCopyable || configText.isBlank()) return
        val success = SagerNet.trySetPrimaryClip(configText)
        Toast.makeText(
            this,
            if (success) R.string.config_copied else R.string.action_export_err,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private data class PreviewContent(
        val text: String,
        val copyable: Boolean,
    )

    private companion object {
        const val STATE_WORD_WRAP = "wordWrap"
    }
}
