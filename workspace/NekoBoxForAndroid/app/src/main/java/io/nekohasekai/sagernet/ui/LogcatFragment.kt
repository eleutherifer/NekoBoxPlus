package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.databinding.LayoutLogcatBinding
import io.nekohasekai.sagernet.ktx.AnsiLogFormatter
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.SendLog

class LogcatFragment : ToolbarFragment(R.layout.layout_logcat),
    Toolbar.OnMenuItemClickListener {

    private lateinit var binding: LayoutLogcatBinding
    private val viewModel: LogcatViewModel by viewModels()
    private var renderedState: LogcatUiState? = null
    private var followTail = true

    @SuppressLint("WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_log)
        toolbar.inflateMenu(R.menu.logcat_menu)
        toolbar.setOnMenuItemClickListener(this)

        binding = LayoutLogcatBinding.bind(view)
        renderedState = null
        followTail = true
        binding.scroolview.attachScrollbar(binding.scrollbarTrack, binding.scrollbarThumb)
        binding.textview.breakStrategy = 0

        binding.scroolview.setOnScrollChangeListener { _, _, _, _, _ ->
            followTail = !binding.scroolview.canScrollVertically(1)
            syncJumpButton()
        }
        binding.jumpToBottom.setOnClickListener { scrollToBottom() }

        configureSearch()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)
        observeState()
        viewModel.initialize()
    }

    private fun configureSearch() {
        val searchItem = toolbar.menu.findItem(R.id.action_search_logcat)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.abc_search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setQuery(newText.orEmpty())
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchView.setQuery("", false)
                viewModel.setQuery("")
                return true
            }
        })
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::renderState) }
                launch { viewModel.errors.collect { snackbar(it).show() } }
            }
        }
    }

    private fun renderState(state: LogcatUiState) {
        val previous = renderedState
        val appendOnly = previous != null &&
            previous.generation == state.generation &&
            previous.query == state.query &&
            previous.severity == state.severity &&
            state.lines.size >= previous.lines.size &&
            state.lines.subList(0, previous.lines.size) == previous.lines

        if (appendOnly) {
            val appended = state.lines.subList(previous!!.lines.size, state.lines.size)
            if (appended.isNotEmpty()) {
                val shouldFollow = followTail
                binding.textview.append(formatLines(appended, state.query))
                binding.textview.post {
                    if (shouldFollow && followTail) scrollToBottom()
                    else syncJumpButton()
                }
            }
        } else {
            val oldScrollY = binding.scroolview.scrollY
            binding.textview.text = formatLines(state.lines, state.query)
            if (previous == null) binding.textview.clearFocus()
            binding.textview.post {
                if (followTail) {
                    scrollToBottom()
                } else {
                    binding.scroolview.scrollTo(0, oldScrollY)
                    syncJumpButton()
                }
            }
        }

        renderedState = state
        syncMenu(state)
        syncSearch(state)
    }

    private fun formatLines(lines: List<LogcatLine>, query: String): CharSequence {
        if (lines.isEmpty()) return ""
        val text = buildString { lines.forEach { append(it.rawText) } }
        return AnsiLogFormatter.toSpannable(
            text = text,
            fallbackLineColor = ::getColorForLine,
            highlightQuery = query.ifEmpty { null },
            highlightForeground = requireContext().getColorAttr(R.attr.colorOnSecondaryContainer),
            highlightBackground = requireContext().getColorAttr(R.attr.colorSecondaryContainer),
        )
    }

    private fun getColorForLine(line: String): Int = when {
        line.contains("INFO[", ignoreCase = true) || line.contains(" [Info]", ignoreCase = true) ->
            (0xFF86C166).toInt()

        line.contains("ERROR[", ignoreCase = true) || line.contains(" [Error]", ignoreCase = true) ->
            Color.RED

        line.contains("WARN[", ignoreCase = true) || line.contains(" [Warning]", ignoreCase = true) ->
            Color.RED

        else -> Color.GRAY
    }

    private fun scrollToBottom() {
        followTail = true
        binding.scroolview.post {
            if (!this::binding.isInitialized) return@post
            binding.scroolview.scrollTo(0, binding.textview.height)
            followTail = true
            syncJumpButton()
        }
    }

    private fun syncJumpButton() {
        binding.jumpToBottom.visibility = if (
            !followTail && binding.scroolview.canScrollVertically(1)
        ) View.VISIBLE else View.GONE
    }

    private fun syncMenu(state: LogcatUiState) {
        toolbar.menu.findItem(R.id.action_pause_logcat)?.apply {
            setIcon(if (state.paused) R.drawable.ic_baseline_play_arrow_24 else R.drawable.ic_baseline_pause_24)
            setTitle(if (state.paused) R.string.resume_logcat else R.string.pause_logcat)
        }
        toolbar.menu.findItem(severityMenuId(state.severity))?.isChecked = true
    }

    private fun syncSearch(state: LogcatUiState) {
        val item = toolbar.menu.findItem(R.id.action_search_logcat) ?: return
        val searchView = item.actionView as? SearchView ?: return
        if (state.query.isNotEmpty() && !item.isActionViewExpanded) item.expandActionView()
        if (searchView.query.toString() != state.query) searchView.setQuery(state.query, false)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_pause_logcat -> {
            viewModel.togglePause()
            true
        }

        R.id.action_refresh -> {
            viewModel.refresh()
            true
        }

        R.id.action_clear_logcat -> {
            viewModel.clearLog()
            true
        }

        R.id.action_send_logcat -> {
            val context = requireContext()
            runOnDefaultDispatcher { SendLog.sendLog(context, "NB4A") }
            true
        }

        R.id.action_copy_logcat -> {
            copyAllLogs()
            true
        }

        R.id.action_log_level_panic -> setSeverity(item, LogcatSeverity.PANIC)
        R.id.action_log_level_fatal -> setSeverity(item, LogcatSeverity.FATAL)
        R.id.action_log_level_error -> setSeverity(item, LogcatSeverity.ERROR)
        R.id.action_log_level_warn -> setSeverity(item, LogcatSeverity.WARN)
        R.id.action_log_level_info -> setSeverity(item, LogcatSeverity.INFO)
        R.id.action_log_level_debug -> setSeverity(item, LogcatSeverity.DEBUG)
        R.id.action_log_level_trace -> setSeverity(item, LogcatSeverity.TRACE)
        else -> false
    }

    private fun setSeverity(item: MenuItem, severity: LogcatSeverity): Boolean {
        item.isChecked = true
        viewModel.setSeverity(severity)
        return true
    }

    private fun copyAllLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            val log = withContext(Dispatchers.IO) { SendLog.buildLog() }
            val copied = SagerNet.trySetPrimaryClip(log)
            Toast.makeText(
                requireContext(),
                if (copied) R.string.logs_copied else R.string.action_export_err,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun severityMenuId(severity: LogcatSeverity): Int = when (severity) {
        LogcatSeverity.PANIC -> R.id.action_log_level_panic
        LogcatSeverity.FATAL -> R.id.action_log_level_fatal
        LogcatSeverity.ERROR -> R.id.action_log_level_error
        LogcatSeverity.WARN -> R.id.action_log_level_warn
        LogcatSeverity.INFO -> R.id.action_log_level_info
        LogcatSeverity.DEBUG -> R.id.action_log_level_debug
        LogcatSeverity.TRACE -> R.id.action_log_level_trace
    }
}
