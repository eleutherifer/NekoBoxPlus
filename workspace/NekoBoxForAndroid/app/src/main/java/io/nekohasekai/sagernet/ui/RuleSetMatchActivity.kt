package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.RuleSetMatchScreen
import kotlinx.coroutines.launch

class RuleSetMatchActivity : ThemedActivity() {
    private val viewModel: RuleSetMatchViewModel by viewModels()
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NekoComposeTheme {
                val state = viewModel.uiState.collectAsStateWithLifecycle().value
                RuleSetMatchScreen(
                    state = state,
                    onClose = ::finish,
                    onSearch = viewModel::start,
                    onCopy = ::copyEntry,
                    onCopyAll = ::copyAll,
                    errorMessage = errorMessage,
                    onDismissError = { errorMessage = null },
                )
            }
        }
        observeEvents()
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.cancel()
        super.onDestroy()
    }

    private fun copyEntry(entry: String) {
        showCopyResult(SagerNet.trySetPrimaryClip(entry))
    }

    private fun copyAll() {
        val text = viewModel.uiState.value.results.joinToString("\n")
        if (text.isNotEmpty()) showCopyResult(SagerNet.trySetPrimaryClip(text))
    }

    private fun showCopyResult(success: Boolean) {
        Toast.makeText(
            this,
            if (success) R.string.ruleset_match_copied else R.string.action_export_err,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        RuleSetMatchEvent.NotFound -> {
                            errorMessage = getString(R.string.ruleset_match_not_found)
                        }
                        is RuleSetMatchEvent.Error -> {
                            errorMessage = event.message.ifBlank { getString(R.string.action_export_err) }
                        }
                    }
                }
            }
        }
    }
}
