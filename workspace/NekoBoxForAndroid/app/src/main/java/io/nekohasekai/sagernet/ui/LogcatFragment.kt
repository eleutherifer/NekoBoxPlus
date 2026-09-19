package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import kotlin.math.max
import kotlin.math.roundToInt

class LogcatFragment : ToolbarFragment(R.layout.layout_logcat),
    Toolbar.OnMenuItemClickListener {

    private lateinit var binding: LayoutLogcatBinding
    private val viewModel: LogcatViewModel by viewModels()
    private val adapter = LogcatAdapter()
    private lateinit var layoutManager: LinearLayoutManager
    private var renderedState = LogcatUiState()
    private var draggingScrollbar = false
    private var scrollbarDragOffset = 0f
    private var scrollbarTargetLine = 0L
    private var restoringViewport = false
    private var handledScrollRequestId = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_log)
        toolbar.inflateMenu(R.menu.logcat_menu)
        toolbar.setOnMenuItemClickListener(this)

        binding = LayoutLogcatBinding.bind(view)
        restoringViewport = false
        handledScrollRequestId = 0
        layoutManager = LinearLayoutManager(requireContext())
        binding.logList.layoutManager = layoutManager
        binding.logList.adapter = adapter
        binding.logList.itemAnimator = null
        binding.logList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!draggingScrollbar && !restoringViewport && adapter.itemCount > 0) {
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()
                    viewModel.setTailFollowing(
                        last >= adapter.itemCount - 1 && !renderedState.hasNewer,
                    )
                    if (renderedState.virtualMode) {
                        requestVisiblePages(first, last)
                    } else {
                        if (first <= LOAD_THRESHOLD) viewModel.loadOlder()
                        if (last >= adapter.itemCount - LOAD_THRESHOLD) viewModel.loadNewer()
                    }
                }
                updateScrollbar()
                syncJumpButton()
            }
        })
        binding.jumpToBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                restoringViewport = true
                binding.logList.stopScroll()
                layoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
            viewModel.jumpToBottom()
        }
        configureVirtualScrollbar()
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

    @SuppressLint("ClickableViewAccessibility")
    private fun configureVirtualScrollbar() {
        binding.scrollbarThumb.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6.dp.toFloat()
            setColor(requireContext().getColorAttr(R.attr.colorPrimary))
        }
        binding.scrollbarPopup.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12.dp.toFloat()
            setColor(requireContext().getColorAttr(R.attr.colorSecondaryContainer))
        }
        binding.scrollbarPopup.setTextColor(
            requireContext().getColorAttr(R.attr.colorOnSecondaryContainer),
        )
        binding.scrollbarTrackContainer.setOnTouchListener { view, event ->
            handleScrollbarTouch(view, event, fromThumb = false)
        }
        binding.scrollbarThumb.setOnTouchListener { view, event ->
            handleScrollbarTouch(view, event, fromThumb = true)
        }
    }

    private fun handleScrollbarTouch(view: View, event: MotionEvent, fromThumb: Boolean): Boolean {
        val trackY = if (fromThumb) binding.scrollbarThumb.top + event.y else event.y
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingScrollbar = true
                binding.scrollbarPopup.visibility = View.VISIBLE
                scrollbarDragOffset = if (fromThumb) event.y else binding.scrollbarThumb.height / 2f
                dragScrollbarTo(trackY)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (draggingScrollbar) dragScrollbarTo(trackY)
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    dragScrollbarTo(trackY)
                    viewModel.seekToLine(scrollbarTargetLine)
                    view.performClick()
                }
                draggingScrollbar = false
                binding.scrollbarPopup.visibility = View.GONE
                true
            }

            else -> false
        }
    }

    private fun dragScrollbarTo(trackY: Float) {
        val thumb = binding.scrollbarThumb
        val trackHeight = binding.scrollbarTrackContainer.height
        val travel = trackHeight - thumb.height
        if (travel <= 0 || adapter.itemCount <= 0) return
        val top = (trackY - scrollbarDragOffset).coerceIn(0f, travel.toFloat())
        (thumb.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = top.roundToInt()
            thumb.layoutParams = this
        }
        val fraction = top / travel
        val targetPosition = (fraction * (adapter.itemCount - 1)).roundToInt()
            .coerceIn(0, adapter.itemCount - 1)
        scrollbarTargetLine = adapter.sourceLineAt(targetPosition) ?: return
        val preview = renderedState.cachedLines[scrollbarTargetLine]?.plainText
            ?.trim()
            ?.take(80)
            ?.takeIf { it.isNotEmpty() }
        binding.scrollbarPopup.text = buildString {
            append(getString(R.string.log_line_position, targetPosition + 1L, adapter.itemCount.toLong()))
            if (preview != null) append("\n").append(preview)
        }
        binding.scrollbarPopup.post {
            val popupTravel = (trackHeight - binding.scrollbarPopup.height).coerceAtLeast(0)
            binding.scrollbarPopup.translationY = top.coerceIn(0f, popupTravel.toFloat())
        }
        if (renderedState.virtualMode) {
            layoutManager.scrollToPositionWithOffset(targetPosition, 0)
        } else {
            renderedState.lines.indices.minByOrNull { position ->
                kotlin.math.abs(renderedState.lines[position].number - scrollbarTargetLine)
            }?.let { layoutManager.scrollToPositionWithOffset(it, 0) }
        }
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
        val firstPosition = layoutManager.findFirstVisibleItemPosition()
        val anchorLine = adapter.sourceLineAt(firstPosition)
        val anchorOffset = if (firstPosition == RecyclerView.NO_POSITION) 0 else {
            layoutManager.findViewByPosition(firstPosition)?.top ?: 0
        }
        val wasAtTail = renderedState.atTail

        renderedState = state
        restoringViewport = true
        adapter.submit(state)
        binding.loading.visibility = if (state.loading) View.VISIBLE else View.GONE

        binding.logList.post {
            var navigatedToDragTarget = false
            when {
                state.scrollRequestId != handledScrollRequestId && state.scrollTargetLine != null -> {
                    handledScrollRequestId = state.scrollRequestId
                    navigatedToDragTarget = true
                    val target = state.scrollTargetLine
                    val targetPosition = if (state.virtualMode) {
                        adapter.positionOfLine(target)
                    } else state.lines.indices.minByOrNull { position ->
                        kotlin.math.abs(state.lines[position].number - target)
                    }
                    if (targetPosition != null) {
                        layoutManager.scrollToPositionWithOffset(targetPosition, 0)
                    }
                }

                state.atTail && (wasAtTail || anchorLine == null) && adapter.itemCount > 0 ->
                    binding.logList.scrollToPosition(adapter.itemCount - 1)

                anchorLine != null -> {
                    val newPosition = adapter.positionOfLine(anchorLine)
                    if (newPosition >= 0) layoutManager.scrollToPositionWithOffset(newPosition, anchorOffset)
                }
            }
            if (!navigatedToDragTarget) updateScrollbar()
            syncJumpButton()
            binding.logList.post {
                restoringViewport = false
                requestVisiblePages(
                    layoutManager.findFirstVisibleItemPosition(),
                    layoutManager.findLastVisibleItemPosition(),
                )
            }
        }
        syncMenu(state)
        syncSearch(state)
    }

    private fun updateScrollbar() {
        if (!this::binding.isInitialized) return
        if (draggingScrollbar) return
        val total = renderedState.totalLines
        val height = binding.scrollbarTrackContainer.height
        if (total <= 1 || height <= 0) {
            binding.scrollbarThumb.visibility = View.GONE
            return
        }
        binding.scrollbarThumb.visibility = View.VISIBLE
        val firstPosition = layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val lastPosition = layoutManager.findLastVisibleItemPosition().coerceAtLeast(firstPosition)
        val visibleLines = (lastPosition - firstPosition + 1).coerceAtLeast(1).toLong()
        val thumbHeight = max(MIN_THUMB_DP.dp, (height * visibleLines / total).toInt()).coerceAtMost(height)
        val travel = height - thumbHeight
        val firstAdapterPosition = layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val denominator = (adapter.itemCount - 1).coerceAtLeast(1)
        val top = (travel * firstAdapterPosition.toDouble() / denominator).roundToInt()
            .coerceIn(0, travel)
        (binding.scrollbarThumb.layoutParams as FrameLayout.LayoutParams).apply {
            this.height = thumbHeight
            topMargin = top
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            binding.scrollbarThumb.layoutParams = this
        }
    }

    private fun requestVisiblePages(first: Int, last: Int) {
        if (!renderedState.virtualMode || first < 0 || last < first) return
        viewModel.requestLines((first..last).mapNotNull(adapter::sourceLineAt))
    }

    private fun syncJumpButton() {
        binding.jumpToBottom.visibility = if (
            !renderedState.atTail || renderedState.hasNewer
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

    private inner class LogcatAdapter : RecyclerView.Adapter<LogcatAdapter.Holder>() {
        private var state = LogcatUiState()

        fun submit(value: LogcatUiState) {
            state = value
            notifyDataSetChanged()
        }

        fun sourceLineAt(position: Int): Long? {
            if (position !in 0 until itemCount) return null
            return if (state.virtualMode) {
                state.filteredLineMap?.get(position) ?: LogVirtualPositionPolicy.positionToLine(
                    position, state.virtualItemCount, state.sourceLineCount,
                )
            } else {
                state.lines.getOrNull(position)?.number
            }
        }

        fun positionOfLine(line: Long): Int = if (state.virtualMode) {
            state.filteredLineMap?.positionOf(line) ?: LogVirtualPositionPolicy.lineToPosition(
                line, state.virtualItemCount, state.sourceLineCount,
            )
        } else {
            state.lines.indexOfFirst { it.number == line }
        }

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = sourceLineAt(position) ?: RecyclerView.NO_ID

        @SuppressLint("WrongConstant")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val horizontal = 8.dp
            return Holder(TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setPadding(horizontal, 0, horizontal + 16.dp, 0)
                setTextIsSelectable(true)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
                includeFontPadding = false
                setLineSpacing(0f, 1f)
                breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            })
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val sourceLine = sourceLineAt(position) ?: return
            val line = if (state.virtualMode) {
                state.cachedLines[sourceLine]
            } else {
                state.lines.getOrNull(position)
            }
            if (line == null) {
                holder.text.text = "…"
                return
            }
            holder.text.text = AnsiLogFormatter.toSpannable(
                text = line.rawText.removeSuffix("\n"),
                fallbackLineColor = ::getColorForLine,
                highlightQuery = state.query.ifEmpty { null },
                highlightForeground = requireContext().getColorAttr(R.attr.colorOnSecondaryContainer),
                highlightBackground = requireContext().getColorAttr(R.attr.colorSecondaryContainer),
            )
        }

        override fun getItemCount(): Int = if (state.virtualMode) {
            state.virtualItemCount
        } else {
            state.lines.size
        }

        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
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

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val LOAD_THRESHOLD = 50
        const val MIN_THUMB_DP = 48
    }
}
