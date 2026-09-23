package io.nekohasekai.sagernet.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.MenuItemCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.blacksquircle.ui.editorkit.plugin.textscroller.TextScrollerPlugin
import com.blacksquircle.ui.language.json.JsonLanguage
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.LayoutSingBoxConfigPreviewBinding
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.JavaUtil.gson

class SingBoxConfigPreviewActivity : ThemedActivity() {

    private lateinit var binding: LayoutSingBoxConfigPreviewBinding
    private var configText = ""
    private var configCopyable = false
    private var copyMenuItem: MenuItem? = null
    private var wordWrap = false
    private var wrapMenuItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wordWrap = savedInstanceState?.getBoolean(STATE_WORD_WRAP) ?: false

        binding = LayoutSingBoxConfigPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.preview_sing_box_config)
            setDisplayHomeAsUpEnabled(true)
        }

        binding.editor.apply {
            language = JsonLanguage()
            inputType = InputType.TYPE_NULL
            keyListener = null
            isCursorVisible = false
            showSoftInputOnFocus = false
            setTextIsSelectable(true)
            setSingleLine(false)
            gravity = Gravity.START or Gravity.TOP
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = ResourcesCompat.getFont(
                this@SingBoxConfigPreviewActivity,
                R.font.jetbrains_mono,
            ) ?: Typeface.MONOSPACE
            setHorizontallyScrolling(!wordWrap)
            installPlugin(TextScrollerPlugin().apply { scroller = binding.scroller })
            setTextContent("")
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)
        loadConfig()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_WORD_WRAP, wordWrap)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.sing_box_config_preview_menu, menu)
        copyMenuItem = menu.findItem(R.id.action_copy_config)
        wrapMenuItem = menu.findItem(R.id.action_toggle_word_wrap)
        syncCopyMenuItem()
        syncWrapMenuItem()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_copy_config -> {
                copyConfig()
                true
            }

            R.id.action_toggle_word_wrap -> {
                wordWrap = !wordWrap
                binding.editor.setHorizontallyScrolling(!wordWrap)
                syncWrapMenuItem()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    private fun loadConfig() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val profile = ProfileManager.getProfile(DataStore.selectedProxy)
                        ?: return@runCatching PreviewContent(
                            getString(R.string.preview_sing_box_config_empty),
                            copyable = false,
                        )
                    PreviewContent(prettyConfig(buildConfig(profile).config), copyable = true)
                }.getOrElse {
                    PreviewContent(
                        getString(R.string.preview_sing_box_config_failed, it.readableMessage),
                        copyable = false,
                    )
                }
            }
            configText = result.text
            configCopyable = result.copyable
            binding.editor.setTextContent(result.text)
            syncCopyMenuItem()
        }
    }

    private fun prettyConfig(rawConfig: String): String {
        return runCatching {
            gson.toJson(JsonParser.parseString(rawConfig))
        }.getOrDefault(rawConfig)
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

    private fun syncCopyMenuItem() {
        copyMenuItem?.isEnabled = configCopyable && configText.isNotBlank()
    }

    private fun syncWrapMenuItem() {
        wrapMenuItem?.apply {
            val titleRes = if (wordWrap) R.string.disable_word_wrap else R.string.enable_word_wrap
            setTitle(titleRes)
            setIcon(if (wordWrap) R.drawable.ic_baseline_format_align_left_24 else R.drawable.baseline_wrap_text_24)
            MenuItemCompat.setTooltipText(this, getString(titleRes))
        }
    }

    private data class PreviewContent(
        val text: String,
        val copyable: Boolean,
    )

    companion object {
        private const val STATE_WORD_WRAP = "wordWrap"
    }
}
