package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.databinding.LayoutRulesetMatchBinding
import io.nekohasekai.sagernet.databinding.LayoutRulesetMatchItemBinding
import kotlinx.coroutines.launch

class RuleSetMatchActivity : ThemedActivity() {
    private lateinit var binding: LayoutRulesetMatchBinding
    private val viewModel: RuleSetMatchViewModel by viewModels()
    private val adapter = ResultAdapter(::copyEntry)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutRulesetMatchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.ruleset_match_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }

        if (savedInstanceState == null) {
            binding.destination.setText(R.string.ruleset_match_default_destination)
            binding.destination.setSelection(binding.destination.text?.length ?: 0)
        }
        binding.results.layoutManager = LinearLayoutManager(this)
        binding.results.adapter = adapter
        binding.start.setOnClickListener { startSearch() }
        binding.copyAll.setOnClickListener { copyAll() }
        binding.destination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH && binding.start.isEnabled) {
                startSearch()
                true
            } else {
                false
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }
        observeViewModel()
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.cancel()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun startSearch() {
        viewModel.start(binding.destination.text?.toString().orEmpty())
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

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        binding.progress.isVisible = state.isRunning
                        binding.start.isEnabled = !state.isRunning
                        binding.destination.isEnabled = !state.isRunning
                        binding.copyAll.isEnabled = !state.isRunning && state.results.isNotEmpty()
                        adapter.submit(state.results)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            RuleSetMatchEvent.NotFound -> showAlert(
                                getString(R.string.ruleset_match_not_found),
                            )
                            is RuleSetMatchEvent.Error -> showAlert(
                                event.message.ifBlank { getString(R.string.action_export_err) },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun showAlert(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private class ResultAdapter(
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<ResultHolder>() {
        private var entries: List<String> = emptyList()

        fun submit(newEntries: List<String>) {
            if (entries == newEntries) return
            entries = newEntries
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultHolder {
            return ResultHolder(
                LayoutRulesetMatchItemBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                ),
                onClick,
            )
        }

        override fun onBindViewHolder(holder: ResultHolder, position: Int) {
            holder.bind(entries[position])
        }

        override fun getItemCount() = entries.size
    }

    private class ResultHolder(
        private val binding: LayoutRulesetMatchItemBinding,
        private val onClick: (String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var entry = ""

        init {
            binding.root.setOnClickListener { onClick(entry) }
        }

        fun bind(value: String) {
            entry = value
            binding.text.text = value
        }
    }
}
