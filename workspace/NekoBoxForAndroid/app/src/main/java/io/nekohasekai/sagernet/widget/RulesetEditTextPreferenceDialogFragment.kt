package io.nekohasekai.sagernet.widget

import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.PreferenceDialogSurface
import io.nekohasekai.sagernet.ui.compose.enableComposeTextInput
import io.nekohasekai.sagernet.ui.compose.prepareAsPreferenceDialog
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.utils.GeoAssetSuggestionRepository
import io.nekohasekai.sagernet.utils.PrefixedSuggestionCatalog
import io.nekohasekai.sagernet.utils.RulesetSuggestionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RouteEditTextPreferenceDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_KEY = "key"
        private const val ARG_TITLE = "title"
        private const val ARG_VALUE = "value"
        private const val ARG_MODE = "mode"
        private const val ARG_STORAGE_TARGET = "storage_target"
        private const val STATE_VALUE = "value"
        private const val STATE_SELECTION_START = "selection_start"
        private const val STATE_SELECTION_END = "selection_end"
        fun newInstance(
            key: String,
            title: String,
            value: String,
            mode: EditorMode,
            storageTarget: StorageTarget = StorageTarget.PROFILE_CACHE,
        ) = RouteEditTextPreferenceDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_KEY, key)
                putString(ARG_TITLE, title)
                putString(ARG_VALUE, value)
                putString(ARG_MODE, mode.name)
                putString(ARG_STORAGE_TARGET, storageTarget.name)
            }
        }
    }

    interface PreferenceSaveListener {
        fun onRouteEditorPreferenceSaved(key: String, value: String)
    }

    enum class StorageTarget { PROFILE_CACHE, CONFIGURATION }

    enum class EditorMode(val operatorSuggestions: List<String>) {
        PLAIN_MULTILINE(emptyList()),
        RULESET(emptyList()),
        ROUTE_DOMAIN(listOf("full:", "domain:", "regexp:", "keyword:")),
        ROUTE_IP(listOf("geoip:private")),
        DNS_DOMAIN_OVERRIDES(emptyList());

        fun suggestionErrorMessage(fragment: DialogFragment, message: String): String = when (this) {
            PLAIN_MULTILINE, DNS_DOMAIN_OVERRIDES -> message
            RULESET -> fragment.getString(R.string.ruleset_suggestions_load_failed, message)
            ROUTE_DOMAIN, ROUTE_IP ->
                fragment.getString(R.string.route_suggestions_load_failed, message)
        }

        fun editorHint(fragment: DialogFragment): String = when (this) {
            PLAIN_MULTILINE -> ""
            RULESET -> fragment.getString(R.string.ruleset_editor_hint)
            ROUTE_DOMAIN -> fragment.getString(R.string.geosite_editor_hint)
            ROUTE_IP -> fragment.getString(R.string.geoip_editor_hint)
            DNS_DOMAIN_OVERRIDES -> fragment.getString(R.string.dns_domain_overrides_editor_hint)
        }

        fun shouldHidePopupForLine(line: String): Boolean {
            val normalized = line.lowercase()
            return when (this) {
                PLAIN_MULTILINE, DNS_DOMAIN_OVERRIDES -> true
                RULESET -> !normalized.startsWith("r") ||
                    normalized.startsWith("http:") ||
                    normalized.startsWith("https:") ||
                    normalized.startsWith("rsip:http:") ||
                    normalized.startsWith("rsip:https:") ||
                    normalized.startsWith("rssite:http:") ||
                    normalized.startsWith("rssite:https:")
                ROUTE_DOMAIN -> normalized.isBlank() ||
                    normalized.first() !in setOf('g', 'f', 'd', 'r', 'k')
                ROUTE_IP -> normalized.isBlank() || !normalized.startsWith("g")
            }
        }
    }

    private val editorMode: EditorMode
        get() = EditorMode.valueOf(
            requireArguments().getString(ARG_MODE) ?: EditorMode.RULESET.name,
        )
    private val storageTarget: StorageTarget
        get() = StorageTarget.valueOf(
            requireArguments().getString(ARG_STORAGE_TARGET) ?: StorageTarget.PROFILE_CACHE.name,
        )

    private var editorValue by mutableStateOf(TextFieldValue())
    private var catalog by mutableStateOf<PrefixedSuggestionCatalog?>(null)
    private var loading by mutableStateOf(false)
    private var loadSuggestionsJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = savedInstanceState?.getString(STATE_VALUE)
            ?: requireArguments().getString(ARG_VALUE).orEmpty()
        val start = savedInstanceState?.getInt(STATE_SELECTION_START, text.length) ?: text.length
        val end = savedInstanceState?.getInt(STATE_SELECTION_END, start) ?: start
        editorValue = TextFieldValue(
            text,
            TextRange(start.coerceIn(0, text.length), end.coerceIn(0, text.length)),
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_VALUE, editorValue.text)
        outState.putInt(STATE_SELECTION_START, editorValue.selection.start)
        outState.putInt(STATE_SELECTION_END, editorValue.selection.end)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        lateinit var dialog: ComponentDialog
        val content = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NekoComposeTheme {
                    PreferenceDialogSurface(
                        title = requireArguments().getString(ARG_TITLE).orEmpty(),
                        buttons = {
                            TextButton(onClick = dialog::dismiss) {
                                Text(stringResource(android.R.string.cancel))
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                modifier = Modifier.widthIn(min = 64.dp),
                                onClick = {
                                    saveValue()
                                    dialog.dismiss()
                                },
                            ) {
                                Text(stringResource(android.R.string.ok))
                            }
                        },
                    ) {
                        RouteMultilineEditor(
                            value = editorValue,
                            onValueChange = { editorValue = it },
                            hint = editorMode.editorHint(
                                this@RouteEditTextPreferenceDialogFragment,
                            ),
                            catalog = catalog,
                            mode = editorMode,
                            loading = loading,
                        )
                    }
                }
            }
        }
        dialog = ComponentDialog(requireContext(), Theme.getDialogTheme()).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
            setContentView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            setOnShowListener {
                prepareAsPreferenceDialog(requireContext())
                enableComposeTextInput(alwaysVisible = true)
                startLoadingSuggestions()
            }
        }
        return dialog
    }

    override fun onDestroy() {
        loadSuggestionsJob?.cancel()
        loadSuggestionsJob = null
        super.onDestroy()
    }

    private fun startLoadingSuggestions() {
        loadSuggestionsJob?.cancel()
        catalog = null
        if (editorMode == EditorMode.PLAIN_MULTILINE ||
            editorMode == EditorMode.DNS_DOMAIN_OVERRIDES
        ) {
            loading = false
            return
        }
        loading = true
        loadSuggestionsJob = lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { loadCatalog() } }
            if (!isAdded) return@launch
            loading = false
            result.onSuccess { catalog = it }.onFailure { error ->
                catalog = null
                val message = error.message?.takeIf(String::isNotBlank)
                    ?: error.javaClass.simpleName
                Toast.makeText(
                    requireContext(),
                    editorMode.suggestionErrorMessage(
                        this@RouteEditTextPreferenceDialogFragment,
                        message,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
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
        return PrefixedSuggestionCatalog(suggestions)
    }

    private fun saveValue() {
        val key = requireArguments().getString(ARG_KEY).orEmpty()
        val value = editorValue.text
        when (storageTarget) {
            StorageTarget.PROFILE_CACHE -> {
                DataStore.profileCacheStore.putString(key, value)
            }
            StorageTarget.CONFIGURATION -> DataStore.configurationStore.putString(key, value)
        }
        (parentFragment as? PreferenceSaveListener)?.onRouteEditorPreferenceSaved(key, value)
    }
}

@Composable
private fun RouteMultilineEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    hint: String,
    catalog: PrefixedSuggestionCatalog?,
    mode: RouteEditTextPreferenceDialogFragment.EditorMode,
    loading: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    var verticalScroll by remember { mutableIntStateOf(0) }
    val editorState = rememberTextFieldState(value.text, value.selection)
    var textLayout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    var editorWidth by remember { mutableIntStateOf(0) }
    val editorValue = TextFieldValue(editorState.text.toString(), editorState.selection)
    val line = editorValue.currentLine()
    var suggestions by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(editorState) {
        snapshotFlow { TextFieldValue(editorState.text.toString(), editorState.selection) }
            .collect(onValueChange)
    }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collect { verticalScroll = it }
    }
    LaunchedEffect(editorValue.text, editorValue.selection, catalog, mode) {
        suggestions = if (catalog == null || mode.shouldHidePopupForLine(line.text)) {
            emptyList()
        } else {
            catalog.allSuggestions
                .filter { it.startsWith(line.text, ignoreCase = true) }
                .filterNot { it.equals(line.text, ignoreCase = true) }
        }
    }

    var lastCursorRect by remember { mutableStateOf<Rect?>(null) }
    val currentCursorRect = textLayout
        ?.takeIf { it.layoutInput.text.text == editorValue.text }
        ?.getCursorRect(editorValue.selection.start)
    val cursorRect = currentCursorRect ?: lastCursorRect
    SideEffect {
        if (currentCursorRect != null && currentCursorRect != lastCursorRect) {
            lastCursorRect = currentCursorRect
        }
    }
    val popupPositionProvider = remember(cursorRect, verticalScroll, density) {
        CursorPopupPositionProvider(
            cursorRect = cursorRect ?: Rect.Zero,
            contentPadding = with(density) { EDITOR_CONTENT_PADDING.roundToPx() },
            verticalScroll = verticalScroll,
            horizontalInset = with(density) { POPUP_HORIZONTAL_INSET.roundToPx() },
            verticalGap = with(density) { POPUP_VERTICAL_GAP.roundToPx() },
            minimumSpace = with(density) { POPUP_MINIMUM_SPACE.roundToPx() },
            edgeInset = with(density) { POPUP_EDGE_INSET.roundToPx() },
        )
    }
    val popupWidth = with(density) {
        (editorWidth - 32.dp.roundToPx())
            .coerceIn(POPUP_MIN_WIDTH.roundToPx(), POPUP_MAX_WIDTH.roundToPx())
            .toDp()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    state = editorState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp)
                        .focusRequester(focusRequester)
                        .onSizeChanged { editorWidth = it.width },
                    placeholder = hint.takeIf(String::isNotEmpty)?.let { { Text(it) } },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    ),
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 10),
                    onTextLayout = { getResult -> textLayout = getResult() },
                    scrollState = scrollState,
                    contentPadding = PaddingValues(EDITOR_CONTENT_PADDING),
                )
                if (suggestions.isNotEmpty() && cursorRect != null) Popup(
                    popupPositionProvider = popupPositionProvider,
                    onDismissRequest = { suggestions = emptyList() },
                    properties = PopupProperties(focusable = false),
                ) {
                    Surface(
                        modifier = Modifier
                            .width(popupWidth)
                            .heightIn(max = 240.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 12.dp,
                        shadowElevation = 12.dp,
                    ) {
                        LazyColumn {
                            items(suggestions, key = { it }) { suggestion ->
                                DropdownMenuItem(
                                    text = { Text(suggestion) },
                                    onClick = {
                                        editorState.edit {
                                            replace(line.start, line.end, suggestion)
                                            selection = TextRange(line.start + suggestion.length)
                                        }
                                        suggestions = emptyList()
                                    },
                                )
                            }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        }
    }
}

private class CursorPopupPositionProvider(
    private val cursorRect: Rect,
    private val contentPadding: Int,
    private val verticalScroll: Int,
    private val horizontalInset: Int,
    private val verticalGap: Int,
    private val minimumSpace: Int,
    private val edgeInset: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val cursorX = anchorBounds.left + contentPadding + cursorRect.left.toInt()
        val cursorTop = anchorBounds.top + contentPadding + cursorRect.top.toInt() - verticalScroll
        val cursorBottom = anchorBounds.top + contentPadding + cursorRect.bottom.toInt() - verticalScroll
        val maximumX = (windowSize.width - popupContentSize.width - edgeInset).coerceAtLeast(edgeInset)
        val x = (cursorX - horizontalInset).coerceIn(edgeInset, maximumX)

        val spaceBelow = (windowSize.height - cursorBottom - verticalGap).coerceAtLeast(0)
        val spaceAbove = (cursorTop - verticalGap).coerceAtLeast(0)
        val showBelow = spaceBelow >= minOf(popupContentSize.height, minimumSpace) ||
            spaceBelow >= spaceAbove
        val preferredY = if (showBelow) {
            cursorBottom - verticalGap
        } else {
            cursorTop - popupContentSize.height + verticalGap
        }
        val maximumY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, preferredY.coerceIn(0, maximumY))
    }
}

private data class CurrentLine(val start: Int, val end: Int, val text: String)

private fun TextFieldValue.currentLine(): CurrentLine {
    val cursor = selection.start.coerceIn(0, text.length)
    val start = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let {
        if (cursor == 0 || it == -1) 0 else it + 1
    }
    val end = text.indexOf('\n', cursor).let { if (it == -1) text.length else it }
    return CurrentLine(start, end, text.substring(start, end))
}

private val EDITOR_CONTENT_PADDING = 12.dp
private val POPUP_MIN_WIDTH = 200.dp
private val POPUP_MAX_WIDTH = 320.dp
private val POPUP_HORIZONTAL_INSET = 4.dp
private val POPUP_VERTICAL_GAP = 2.dp
private val POPUP_MINIMUM_SPACE = 120.dp
private val POPUP_EDGE_INSET = 8.dp
