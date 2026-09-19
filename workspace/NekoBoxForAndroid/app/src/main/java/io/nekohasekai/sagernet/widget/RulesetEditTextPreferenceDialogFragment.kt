package io.nekohasekai.sagernet.widget

import android.app.Dialog
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutRulesetEditorBinding
import io.nekohasekai.sagernet.ui.RouteSettingsActivity
import io.nekohasekai.sagernet.utils.GeoAssetSuggestionRepository
import io.nekohasekai.sagernet.utils.PrefixedSuggestionCatalog
import io.nekohasekai.sagernet.utils.RulesetSuggestionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class RouteEditTextPreferenceDialogFragment : DialogFragment() {
    companion object {
        private const val ARG_KEY = "key"
        private const val ARG_TITLE = "title"
        private const val ARG_VALUE = "value"
        private const val ARG_MODE = "mode"
        private const val ARG_STORAGE_TARGET = "storage_target"
        private const val DEBOUNCE_MS = 500L
        private const val MAX_VISIBLE_POPUP_ITEMS = 5

        fun newInstance(
            key: String,
            title: String,
            value: String,
            mode: EditorMode,
            storageTarget: StorageTarget = StorageTarget.PROFILE_CACHE,
        ): RouteEditTextPreferenceDialogFragment {
            return RouteEditTextPreferenceDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_KEY, key)
                    putString(ARG_TITLE, title)
                    putString(ARG_VALUE, value)
                    putString(ARG_MODE, mode.name)
                    putString(ARG_STORAGE_TARGET, storageTarget.name)
                }
            }
        }
    }

    interface PreferenceSaveListener {
        fun onRouteEditorPreferenceSaved(key: String, value: String)
    }

    enum class StorageTarget {
        PROFILE_CACHE,
        CONFIGURATION,
    }

    enum class EditorMode(
        val operatorSuggestions: List<String>,
    ) {
        PLAIN_MULTILINE(
            operatorSuggestions = emptyList(),
        ),
        RULESET(
            operatorSuggestions = emptyList(),
        ),
        ROUTE_DOMAIN(
            operatorSuggestions = listOf("full:", "domain:", "regexp:", "keyword:"),
        ),
        ROUTE_IP(
            operatorSuggestions = listOf("geoip:private"),
        ),
        DNS_DOMAIN_OVERRIDES(
            operatorSuggestions = emptyList(),
        );

        fun suggestionErrorMessage(fragment: DialogFragment, message: String): String {
            return when (this) {
                PLAIN_MULTILINE, DNS_DOMAIN_OVERRIDES -> message
                RULESET -> fragment.getString(R.string.ruleset_suggestions_load_failed, message)
                ROUTE_DOMAIN, ROUTE_IP -> fragment.getString(R.string.route_suggestions_load_failed, message)
            }
        }

        fun editorHint(fragment: DialogFragment): String {
            return when (this) {
                PLAIN_MULTILINE -> ""
                RULESET -> fragment.getString(R.string.ruleset_editor_hint)
                ROUTE_DOMAIN -> fragment.getString(R.string.geosite_editor_hint)
                ROUTE_IP -> fragment.getString(R.string.geoip_editor_hint)
                DNS_DOMAIN_OVERRIDES -> fragment.getString(R.string.dns_domain_overrides_editor_hint)
            }
        }

        fun shouldHidePopupForLine(line: String): Boolean {
            val normalized = line.lowercase()
            return when (this) {
                PLAIN_MULTILINE, DNS_DOMAIN_OVERRIDES -> true
                RULESET -> {
                    !normalized.startsWith("r") ||
                            normalized.startsWith("http:") ||
                            normalized.startsWith("https:") ||
                            normalized.startsWith("rsip:http:") ||
                            normalized.startsWith("rsip:https:") ||
                            normalized.startsWith("rssite:http:") ||
                            normalized.startsWith("rssite:https:")
                }
                ROUTE_DOMAIN -> {
                    normalized.isBlank() ||
                            normalized.first() !in setOf('g', 'f', 'd', 'r', 'k')
                }
                ROUTE_IP -> {
                    normalized.isBlank() || !normalized.startsWith("g")
                }
            }
        }
    }

    private var _binding: LayoutRulesetEditorBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private val editorMode: EditorMode
        get() = EditorMode.valueOf(requireArguments().getString(ARG_MODE) ?: EditorMode.RULESET.name)
    private val storageTarget: StorageTarget
        get() = StorageTarget.valueOf(
            requireArguments().getString(ARG_STORAGE_TARGET) ?: StorageTarget.PROFILE_CACHE.name
        )
    private var catalog: PrefixedSuggestionCatalog? = null
    private var loadSuggestionsJob: Job? = null
    private lateinit var popupAdapter: ArrayAdapter<String>
    private lateinit var popupWindow: PopupWindow
    private lateinit var popupListView: ListView
    private val popupUpdateRunnable = Runnable { updatePopup() }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = LayoutRulesetEditorBinding.inflate(layoutInflater)
        binding.rulesetEditor.hint = editorMode.editorHint(this)
        binding.rulesetEditor.setText(arguments?.getString(ARG_VALUE).orEmpty())
        binding.rulesetEditor.setSelection(binding.rulesetEditor.text?.length ?: 0)

        popupAdapter = ArrayAdapter(requireContext(), R.layout.item_dropdown_suggestion, mutableListOf<String>())

        popupListView = ListView(requireContext()).apply {
            adapter = popupAdapter
            divider = null
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                popupAdapter.getItem(position)?.let { replaceCurrentLine(it) }
            }
        }

        popupWindow = PopupWindow(
            popupListView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            isOutsideTouchable = false
            isFocusable = false
            isClippingEnabled = true
            elevation = resources.displayMetrics.density * 12
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            setBackgroundDrawable(createPopupBackground())
        }

        binding.rulesetEditor.onSelectionChangedListener = { _, _ ->
            updatePopupImmediately()
        }
        binding.rulesetEditor.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                updatePopupImmediately()
            } else {
                hidePopup()
            }
        }
        binding.rulesetEditor.addTextChangedListener(SimpleTextWatcher { schedulePopupUpdate() })

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(arguments?.getString(ARG_TITLE).orEmpty())
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val key = arguments?.getString(ARG_KEY).orEmpty()
                val value = binding.rulesetEditor.text?.toString().orEmpty()
                when (storageTarget) {
                    StorageTarget.PROFILE_CACHE -> {
                        DataStore.profileCacheStore.putString(key, value)
                        (activity as? RouteSettingsActivity)?.child
                            ?.findPreference<androidx.preference.EditTextPreference>(key)
                            ?.text = value
                    }
                    StorageTarget.CONFIGURATION -> {
                        DataStore.configurationStore.putString(key, value)
                    }
                }
                (parentFragment as? PreferenceSaveListener)?.onRouteEditorPreferenceSaved(key, value)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            startLoadingSuggestions()
        }
        return dialog
    }

    override fun onDestroyView() {
        loadSuggestionsJob?.cancel()
        loadSuggestionsJob = null
        handler.removeCallbacks(popupUpdateRunnable)
        hidePopup()
        _binding = null
        super.onDestroyView()
    }

    private fun startLoadingSuggestions() {
        loadSuggestionsJob?.cancel()
        catalog = null
        if (editorMode == EditorMode.PLAIN_MULTILINE || editorMode == EditorMode.DNS_DOMAIN_OVERRIDES) {
            setEditorLoading(false)
            focusEditor(restoreCursorToEnd = true)
            return
        }
        setEditorLoading(true)
        loadSuggestionsJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    loadCatalog()
                }
            }
            if (_binding == null) return@launch
            result.onSuccess { loadedCatalog ->
                catalog = loadedCatalog
                setEditorLoading(false)
                focusEditor(restoreCursorToEnd = true)
            }.onFailure { error ->
                catalog = null
                setEditorLoading(false)
                focusEditor(restoreCursorToEnd = false)
                hidePopup()
                val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                context?.let {
                    Toast.makeText(
                        it,
                        editorMode.suggestionErrorMessage(this@RouteEditTextPreferenceDialogFragment, message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setEditorLoading(isLoading: Boolean) {
        binding.rulesetLoadingContainer.isVisible = isLoading
        binding.rulesetEditor.isVisible = !isLoading
        if (isLoading) {
            hidePopup()
        }
    }

    private fun focusEditor(restoreCursorToEnd: Boolean) {
        binding.rulesetEditor.requestFocus()
        binding.rulesetEditor.post {
            if (_binding == null) return@post
            if (restoreCursorToEnd) {
                binding.rulesetEditor.setSelection(binding.rulesetEditor.text?.length ?: 0)
                updatePopupImmediately()
            }
        }
    }

    private fun schedulePopupUpdate() {
        handler.removeCallbacks(popupUpdateRunnable)
        handler.postDelayed(popupUpdateRunnable, DEBOUNCE_MS)
    }

    private fun updatePopupImmediately() {
        handler.removeCallbacks(popupUpdateRunnable)
        updatePopup()
    }

    private fun updatePopup() {
        val loadedCatalog = catalog ?: run {
            hidePopup()
            return
        }
        val lineContext = currentLineContext() ?: run {
            hidePopup()
            return
        }
        val line = lineContext.text
        if (editorMode.shouldHidePopupForLine(line)) {
            hidePopup()
            return
        }
        val suggestions = filterSuggestions(loadedCatalog, line)
        if (suggestions.isEmpty() || (suggestions.size == 1 && suggestions[0].equals(line, ignoreCase = true))) {
            hidePopup()
            return
        }

        popupAdapter.clear()
        popupAdapter.addAll(suggestions)
        popupAdapter.notifyDataSetChanged()
        showPopupAtCursor(suggestions.size)
    }

    private fun loadCatalog(): PrefixedSuggestionCatalog {
        val suggestions = when (editorMode) {
            EditorMode.PLAIN_MULTILINE, EditorMode.DNS_DOMAIN_OVERRIDES -> emptyList()
            EditorMode.RULESET -> RulesetSuggestionRepository.load().allSuggestions
            EditorMode.ROUTE_DOMAIN -> GeoAssetSuggestionRepository.loadGeosite().allSuggestions +
                    editorMode.operatorSuggestions
            EditorMode.ROUTE_IP -> GeoAssetSuggestionRepository.loadGeoIp().allSuggestions +
                    editorMode.operatorSuggestions
        }.distinct()
        return PrefixedSuggestionCatalog(allSuggestions = suggestions)
    }

    private fun filterSuggestions(catalog: PrefixedSuggestionCatalog, line: String): List<String> {
        if (line.isEmpty()) return catalog.allSuggestions
        return catalog.allSuggestions.filter { it.startsWith(line, ignoreCase = true) }
    }

    private fun replaceCurrentLine(replacement: String) {
        val editor = binding.rulesetEditor
        val lineContext = currentLineContext() ?: return
        editor.text?.replace(lineContext.start, lineContext.end, replacement)
        val newCursor = lineContext.start + replacement.length
        editor.setSelection(newCursor)
        hidePopup()
    }

    private fun currentLineContext(): LineContext? {
        val text = binding.rulesetEditor.text ?: return null
        val cursor = binding.rulesetEditor.selectionStart.coerceAtLeast(0)
        val content = text.toString()
        val start = content.lastIndexOf('\n', cursor - 1).let {
            if (it == -1) 0 else it + 1
        }
        val end = content.indexOf('\n', cursor).let {
            if (it == -1) text.length else it
        }
        return LineContext(start = start, end = end, text = content.substring(start, end))
    }

    private fun showPopupAtCursor(itemCount: Int) {
        val editor = binding.rulesetEditor
        val text = editor.text ?: return
        val layout = editor.layout ?: return

        val cursor = editor.selectionStart.coerceIn(0, text.length)
        val line = layout.getLineForOffset(cursor)
        val horizontal = layout.getPrimaryHorizontal(cursor).roundToInt()
        val lineTop = layout.getLineTop(line)
        val lineBottom = layout.getLineBottom(line)

        val position = IntArray(2)
        editor.getLocationInWindow(position)
        val horizontalInset = dp(4)
        val verticalGap = dp(2)

        val width = min(dp(320), max(dp(200), editor.width - dp(32)))

        val x = (position[0] + editor.totalPaddingLeft + horizontal - editor.scrollX - horizontalInset)
            .coerceAtMost(resources.displayMetrics.widthPixels - width - dp(16))
            .coerceAtLeast(dp(8))

        val cursorTopInWindow = position[1] + editor.totalPaddingTop + lineTop - editor.scrollY
        val cursorBottomInWindow = position[1] + editor.totalPaddingTop + lineBottom - editor.scrollY

        val visibleFrame = Rect()
        editor.getWindowVisibleDisplayFrame(visibleFrame)

        val spaceBelow = (visibleFrame.bottom - cursorBottomInWindow - verticalGap).coerceAtLeast(0)
        val spaceAbove = (cursorTopInWindow - visibleFrame.top - verticalGap).coerceAtLeast(0)

        val desiredHeight = measurePopupHeight(width, itemCount, MAX_VISIBLE_POPUP_ITEMS)
        val showBelow = spaceBelow >= min(desiredHeight, dp(120)) || spaceBelow >= spaceAbove
        val availableHeight = if (showBelow) spaceBelow else spaceAbove
        val finalHeight = min(desiredHeight, availableHeight).coerceAtLeast(dp(48))

        val y = if (showBelow) {
            cursorBottomInWindow - verticalGap
        } else {
            cursorTopInWindow - finalHeight + verticalGap
        }

        if (finalHeight <= 0) {
            hidePopup()
            return
        }

        if (popupWindow.isShowing) {
            popupWindow.update(x, y, width, finalHeight)
        } else {
            popupWindow.width = width
            popupWindow.height = finalHeight
            popupWindow.showAtLocation(editor, Gravity.NO_GRAVITY, x, y)
        }
    }

    private fun measurePopupHeight(width: Int, itemCount: Int, maxVisibleItems: Int): Int {
        val visibleItems = min(itemCount, maxVisibleItems)
        if (visibleItems <= 0) return 0

        var total = popupListView.paddingTop + popupListView.paddingBottom
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        for (i in 0 until visibleItems) {
            val itemView = popupAdapter.getView(i, null, popupListView)
            itemView.measure(widthSpec, heightSpec)
            total += itemView.measuredHeight
        }

        return total
    }

    private fun createPopupBackground(): MaterialShapeDrawable {
        val surfaceColor = MaterialColors.getColor(
            requireContext(),
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            0
        )

        return MaterialShapeDrawable(
            ShapeAppearanceModel.builder()
                .setAllCornerSizes(dp(12).toFloat())
                .build()
        ).apply {
            fillColor = android.content.res.ColorStateList.valueOf(surfaceColor)
            elevation = dp(12).toFloat()
            initializeElevationOverlay(requireContext())
        }
    }

    private fun hidePopup() {
        if (::popupWindow.isInitialized && popupWindow.isShowing) {
            popupWindow.dismiss()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private data class LineContext(
        val start: Int,
        val end: Int,
        val text: String,
    )

    private class SimpleTextWatcher(
        val onTextChanged: () -> Unit,
    ) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) {
            onTextChanged()
        }
    }
}
