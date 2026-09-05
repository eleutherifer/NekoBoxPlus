package io.nekohasekai.sagernet.ui.compose

import android.graphics.Color as AndroidColor
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.AnsiLogFormatter
import io.nekohasekai.sagernet.ui.LogVirtualPositionPolicy
import io.nekohasekai.sagernet.ui.LogcatLine
import io.nekohasekai.sagernet.ui.LogcatSeverity
import io.nekohasekai.sagernet.ui.LogcatUiState
import io.nekohasekai.sagernet.ui.LogcatViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class LogcatMenuPage { Main, Severity }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogcatScreen(
    viewModel: LogcatViewModel,
    onOpenDrawer: () -> Unit,
    onSendLog: () -> Unit,
    onCopyLog: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var searchExpanded by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var menuPage by remember { mutableStateOf(LogcatMenuPage.Main) }
    var draggingScrollbar by remember { mutableStateOf(false) }
    val closeSearchFocusRequester = remember { FocusRequester() }

    val closeSearch = {
        searchExpanded = false
        viewModel.setQuery("")
    }
    BackHandler(enabled = searchExpanded, onBack = closeSearch)

    LogcatListEffects(state, listState, viewModel, draggingScrollbar)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchExpanded) {
                        LogcatSearchField(
                            query = state.query,
                            onQueryChange = viewModel::setQuery,
                            closeFocusRequester = closeSearchFocusRequester,
                            onClose = closeSearch,
                        )
                    } else {
                        Text(stringResource(R.string.menu_log))
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (searchExpanded) {
                                closeSearch()
                            } else {
                                onOpenDrawer()
                            }
                        },
                        modifier = Modifier
                            .tvFocusTarget()
                            .focusRequester(closeSearchFocusRequester),
                    ) {
                        Icon(
                            painterResource(
                                if (searchExpanded) {
                                    R.drawable.baseline_arrow_back_24
                                } else {
                                    R.drawable.ic_navigation_menu
                                },
                            ),
                            contentDescription = stringResource(
                                if (searchExpanded) {
                                    R.string.abc_toolbar_collapse_description
                                } else {
                                    R.string.menu_log
                                },
                            ),
                        )
                    }
                },
                actions = {
                    if (!searchExpanded) {
                        IconButton(onClick = { searchExpanded = true }) {
                            Icon(
                                painterResource(R.drawable.ic_baseline_manage_search_24),
                                contentDescription = stringResource(R.string.search_menu_title),
                            )
                        }
                        IconButton(onClick = viewModel::togglePause) {
                            Icon(
                                painterResource(
                                    if (state.paused) {
                                        R.drawable.ic_baseline_play_arrow_24
                                    } else {
                                        R.drawable.ic_baseline_pause_24
                                    },
                                ),
                                contentDescription = stringResource(
                                    if (state.paused) R.string.resume_logcat else R.string.pause_logcat,
                                ),
                            )
                        }
                        IconButton(onClick = onSendLog) {
                            Icon(
                                painterResource(R.drawable.baseline_send_24),
                                contentDescription = stringResource(R.string.logcat),
                            )
                        }
                        Box {
                            IconButton(
                                onClick = {
                                    menuPage = LogcatMenuPage.Main
                                    overflowExpanded = true
                                },
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_baseline_more_vert_24),
                                    contentDescription = stringResource(R.string.toolbar_more_actions),
                                )
                            }
                            DropdownMenu(
                                expanded = overflowExpanded,
                                onDismissRequest = { overflowExpanded = false },
                            ) {
                                when (menuPage) {
                                    LogcatMenuPage.Main -> {
                                        LogcatMenuItem(R.string.copy_all_logs) {
                                            overflowExpanded = false
                                            onCopyLog()
                                        }
                                        LogcatMenuItem(R.string.log_level) {
                                            menuPage = LogcatMenuPage.Severity
                                        }
                                        LogcatMenuItem(R.string.group_update) {
                                            overflowExpanded = false
                                            viewModel.refresh()
                                        }
                                        LogcatMenuItem(R.string.clear_logcat) {
                                            overflowExpanded = false
                                            viewModel.clearLog()
                                        }
                                    }

                                    LogcatMenuPage.Severity -> LogcatSeverity.entries.forEach { severity ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(if (severity == state.severity) "✓  " else "   ")
                                                    Text(stringResource(severity.labelResource))
                                                }
                                            },
                                            onClick = {
                                                overflowExpanded = false
                                                viewModel.setSeverity(severity)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        LogcatList(
            state = state,
            listState = listState,
            contentPadding = contentPadding,
            onJumpToBottom = viewModel::jumpToBottom,
            onDragStateChange = { draggingScrollbar = it },
            onDragFinished = viewModel::seekToLine,
        )
    }
}

@Composable
private fun LogcatSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    closeFocusRequester: FocusRequester,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) onClose()
                    true
                } else if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP
                ) {
                    onClose()
                    true
                } else false
            },
        textStyle = MaterialTheme.typography.titleLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.abc_search_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                inner()
            }
        },
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun LogcatListEffects(
    state: LogcatUiState,
    listState: LazyListState,
    viewModel: LogcatViewModel,
    draggingScrollbar: Boolean,
) {
    var handledRequest by remember { mutableLongStateOf(0L) }

    LaunchedEffect(listState, state.virtualMode, state.generation, draggingScrollbar) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            visible.firstOrNull()?.index to visible.lastOrNull()?.index
        }.distinctUntilChanged().collect { (first, last) ->
            if (first == null || last == null) return@collect
            viewModel.setTailFollowing(last >= state.itemCount - 1 && !state.hasNewer)
            if (draggingScrollbar) return@collect
            if (state.virtualMode) {
                viewModel.requestLines((first..last).map { state.sourceLineAt(it) })
            } else {
                if (first <= 50) viewModel.loadOlder()
                if (last >= state.itemCount - 51) viewModel.loadNewer()
            }
        }
    }
    LaunchedEffect(state.scrollRequestId, state.scrollTargetLine, state.itemCount) {
        val target = state.scrollTargetLine ?: return@LaunchedEffect
        if (state.scrollRequestId == handledRequest || state.itemCount == 0) return@LaunchedEffect
        handledRequest = state.scrollRequestId
        listState.scrollToItem(state.positionOfLine(target).coerceIn(0, state.itemCount - 1))
    }
    LaunchedEffect(state.atTail, state.itemCount, state.generation) {
        if (state.atTail && state.itemCount > 0) listState.scrollToItem(state.itemCount - 1)
    }
}

@Composable
private fun LogcatList(
    state: LogcatUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onJumpToBottom: () -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    onDragFinished: (Long) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(
                count = state.itemCount,
                key = { state.sourceLineAt(it) },
            ) { position ->
                val sourceLine = state.sourceLineAt(position)
                val line = if (state.virtualMode) {
                    state.cachedLines[sourceLine]
                } else {
                    state.lines.getOrNull(position)
                }
                LogcatRow(line, state.query)
            }
        }
        if (state.loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(32.dp)
                    .align(Alignment.TopCenter),
            )
        }
        LogcatScrollbar(
            state = state,
            listState = listState,
            onDragStateChange = onDragStateChange,
            onDragFinished = onDragFinished,
        )
        if (!state.atTail || state.hasNewer) {
            FloatingActionButton(
                onClick = onJumpToBottom,
                modifier = Modifier
                    .padding(16.dp)
                    .size(40.dp)
                    .align(Alignment.BottomEnd),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(
                    painterResource(R.drawable.ic_baseline_keyboard_arrow_down_24),
                    contentDescription = stringResource(R.string.scroll_to_bottom),
                )
            }
        }
    }
}

@Composable
private fun LogcatRow(line: LogcatLine?, query: String) {
    SelectionContainer {
        Text(
            text = line?.toAnnotatedString(query) ?: AnnotatedString("…"),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 24.dp),
            fontFamily = FontFamily(Font(R.font.jetbrains_mono)),
            fontSize = 14.sp,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun LogcatScrollbar(
    state: LogcatUiState,
    listState: LazyListState,
    onDragStateChange: (Boolean) -> Unit,
    onDragFinished: (Long) -> Unit,
) {
    if (state.itemCount <= 1) return
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val trackHeightPx = with(density) { maxHeight.toPx() }
        val minimumThumbPx = with(density) { 48.dp.toPx() }
        val visibleCount by remember(listState) {
            derivedStateOf { listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) }
        }
        val thumbHeightPx = (trackHeightPx * visibleCount / state.itemCount)
            .coerceIn(minimumThumbPx, trackHeightPx)
        val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
        val calculatedTop by remember(listState, state.itemCount, travelPx) {
            derivedStateOf {
                travelPx * listState.firstVisibleItemIndex / (state.itemCount - 1).coerceAtLeast(1)
            }
        }
        var dragging by remember { mutableStateOf(false) }
        var dragTop by remember { mutableFloatStateOf(0f) }
        var targetLine by remember { mutableLongStateOf(0L) }
        var scrollJob by remember { mutableStateOf<Job?>(null) }
        val top = if (dragging) dragTop else calculatedTop

        fun updateDrag(y: Float) {
            dragTop = y.coerceIn(0f, travelPx)
            val position = if (travelPx == 0f) 0 else {
                (dragTop / travelPx * (state.itemCount - 1)).roundToInt()
            }
            targetLine = state.sourceLineAt(position)
            scrollJob?.cancel()
            scrollJob = scope.launch { listState.scrollToItem(position) }
        }

        Box(
            Modifier
                .fillMaxHeight()
                .width(24.dp)
                .align(Alignment.CenterEnd)
                .pointerInput(state.itemCount, travelPx) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            onDragStateChange(true)
                            updateDrag(offset.y - thumbHeightPx / 2f)
                        },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            updateDrag(dragTop + amount)
                        },
                        onDragEnd = {
                            dragging = false
                            onDragStateChange(false)
                            onDragFinished(targetLine)
                        },
                        onDragCancel = {
                            dragging = false
                            onDragStateChange(false)
                        },
                    )
                },
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(-8.dp.roundToPx(), top.roundToInt()) }
                .width(8.dp)
                .height(with(density) { thumbHeightPx.toDp() })
                .align(Alignment.TopEnd)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primary),
        )
        if (dragging) {
            val position = state.positionOfLine(targetLine)
            val preview = state.cachedLines[targetLine]?.plainText
                ?.trim()?.take(80)?.takeIf(String::isNotEmpty)
            Text(
                text = buildString {
                    append(stringResource(R.string.log_line_position, position + 1L, state.itemCount.toLong()))
                    if (preview != null) append('\n').append(preview)
                },
                modifier = Modifier
                    .padding(end = 32.dp)
                    .widthIn(max = 280.dp)
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, top.roundToInt()) }
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LogcatMenuItem(label: Int, action: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = action)
}

private val LogcatUiState.itemCount: Int
    get() = if (virtualMode) virtualItemCount else lines.size

private fun LogcatUiState.sourceLineAt(position: Int): Long = if (virtualMode) {
    filteredLineMap?.get(position) ?: LogVirtualPositionPolicy.positionToLine(
        position,
        virtualItemCount,
        sourceLineCount,
    )
} else {
    lines.getOrNull(position)?.number ?: 0L
}

private fun LogcatUiState.positionOfLine(line: Long): Int = if (virtualMode) {
    filteredLineMap?.positionOf(line) ?: LogVirtualPositionPolicy.lineToPosition(
        line,
        virtualItemCount,
        sourceLineCount,
    )
} else {
    lines.indices.minByOrNull { abs(lines[it].number - line) } ?: 0
}

private val LogcatSeverity.labelResource: Int
    get() = when (this) {
        LogcatSeverity.PANIC -> R.string.log_level_panic
        LogcatSeverity.FATAL -> R.string.log_level_fatal
        LogcatSeverity.ERROR -> R.string.log_level_error
        LogcatSeverity.WARN -> R.string.log_level_warning
        LogcatSeverity.INFO -> R.string.log_level_info
        LogcatSeverity.DEBUG -> R.string.log_level_debug
        LogcatSeverity.TRACE -> R.string.log_level_trace
    }

@Composable
private fun LogcatLine.toAnnotatedString(query: String): AnnotatedString {
    val parsed = remember(rawText) {
        AnsiLogFormatter.parse(rawText.removeSuffix("\n"), ::fallbackLogColor)
    }
    val highlightForeground = MaterialTheme.colorScheme.onSecondaryContainer
    val highlightBackground = MaterialTheme.colorScheme.secondaryContainer
    return remember(parsed, query, highlightForeground, highlightBackground) {
        AnnotatedString.Builder(parsed.text).apply {
            parsed.spans.forEach { span ->
                if (span.start >= span.end) return@forEach
                addStyle(
                    when (span.type) {
                        AnsiLogFormatter.SpanType.Foreground -> SpanStyle(color = Color(span.color!!))
                        AnsiLogFormatter.SpanType.Background -> SpanStyle(background = Color(span.color!!))
                        AnsiLogFormatter.SpanType.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
                        AnsiLogFormatter.SpanType.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                        AnsiLogFormatter.SpanType.Underline -> SpanStyle(
                            textDecoration = TextDecoration.Underline,
                        )
                    },
                    span.start,
                    span.end,
                )
            }
            AnsiLogFormatter.findHighlightRanges(parsed.text, query).forEach { range ->
                addStyle(
                    SpanStyle(color = highlightForeground, background = highlightBackground),
                    range.first,
                    range.last + 1,
                )
            }
        }.toAnnotatedString()
    }
}

private fun fallbackLogColor(line: String): Int = when {
    line.contains("INFO[", ignoreCase = true) || line.contains(" [Info]", ignoreCase = true) ->
        0xFF86C166.toInt()

    line.contains("ERROR[", ignoreCase = true) || line.contains(" [Error]", ignoreCase = true) ->
        AndroidColor.RED

    line.contains("WARN[", ignoreCase = true) || line.contains(" [Warning]", ignoreCase = true) ->
        AndroidColor.RED

    else -> AndroidColor.GRAY
}
