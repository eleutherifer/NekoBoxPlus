package io.nekohasekai.sagernet.ui.compose

import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.addTextChangedListener
import com.blacksquircle.ui.editorkit.plugin.textscroller.TextScrollerPlugin
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import com.blacksquircle.ui.editorkit.widget.TextScroller
import com.blacksquircle.ui.language.json.JsonLanguage
import io.nekohasekai.sagernet.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditorScreen(
    initialText: String,
    showUnsavedDialog: Boolean,
    onEditorReady: (TextProcessor) -> Unit,
    onChanged: () -> Unit,
    onClose: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onDismissUnsavedDialog: () -> Unit,
    onInsert: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFormat: () -> Unit,
    errorMessage: String?,
    onDismissError: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.config_settings)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painterResource(R.drawable.ic_navigation_close),
                            contentDescription = stringResource(R.string.mal_close),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onApply) {
                        Icon(
                            painterResource(R.drawable.ic_action_done),
                            contentDescription = stringResource(R.string.apply),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            HorizontalDivider()
            NativeJsonEditor(
                initialText = initialText,
                onEditorReady = onEditorReady,
                onChanged = onChanged,
                modifier = Modifier.weight(1f),
            )
            EditorKeyboard(
                onInsert = onInsert,
                onUndo = onUndo,
                onRedo = onRedo,
                onFormat = onFormat,
            )
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = onDismissUnsavedDialog,
            title = { Text(stringResource(R.string.unsaved_changes_prompt)) },
            confirmButton = {
                TextButton(onClick = onApply) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onDiscard) { Text(stringResource(R.string.no)) }
                    TextButton(onClick = onDismissUnsavedDialog) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            },
        )
    }
    errorMessage?.let {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(it) },
            confirmButton = {
                TextButton(onClick = onDismissError) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}

@Composable
fun NativeJsonEditor(
    initialText: String,
    onEditorReady: (TextProcessor) -> Unit,
    modifier: Modifier = Modifier,
    wordWrap: Boolean = false,
    scrollerWidth: Dp = 30.dp,
    contentPadding: Dp = 0.dp,
    onChanged: (() -> Unit)? = null,
    configure: TextProcessor.() -> Unit = {},
) {
    val density = LocalDensity.current
    val scrollerWidthPx = with(density) { scrollerWidth.roundToPx() }
    val contentPaddingPx = with(density) { contentPadding.roundToPx() }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            NativeJsonEditorHost(context).apply {
                val processor = TextProcessor(context).apply {
                    language = JsonLanguage()
                    background = null
                    threshold = 2
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                    }
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    setHorizontallyScrolling(!wordWrap)
                    setPadding(
                        contentPaddingPx,
                        contentPaddingPx,
                        contentPaddingPx,
                        contentPaddingPx,
                    )
                    configure()
                    setTextContent(initialText)
                    onChanged?.let { changed -> addTextChangedListener { changed() } }
                }
                val scroller = TextScroller(context)
                processor.installPlugin(TextScrollerPlugin().apply { this.scroller = scroller })
                editor = processor
                addView(
                    processor,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    scroller,
                    FrameLayout.LayoutParams(
                        scrollerWidthPx,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.END,
                    ),
                )
                onEditorReady(processor)
            }
        },
        update = { host ->
            host.editor.setHorizontallyScrolling(!wordWrap)
        },
    )
}

private class NativeJsonEditorHost(context: Context) : FrameLayout(context) {
    lateinit var editor: TextProcessor
}

@Composable
private fun EditorKeyboard(
    onInsert: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFormat: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            EditorAction(
                icon = R.drawable.baseline_keyboard_tab_24,
                description = R.string.config_editor_insert_tab,
                onClick = { onInsert("\t") },
            )
            "{},:_\"".forEach { character ->
                TextButton(
                    onClick = { onInsert(character.toString()) },
                    modifier = Modifier.width(42.dp),
                ) {
                    Text(character.toString())
                }
            }
            EditorAction(R.drawable.baseline_undo_24, R.string.undo, onUndo)
            EditorAction(R.drawable.baseline_redo_24, R.string.config_editor_redo, onRedo)
            EditorAction(
                R.drawable.ic_baseline_format_align_left_24,
                R.string.config_editor_format,
                onFormat,
            )
        }
    }
}

@Composable
private fun EditorAction(
    icon: Int,
    description: Int,
    onClick: () -> Unit,
) {
    Box(Modifier.size(42.dp)) {
        IconButton(onClick = onClick) {
            Icon(painterResource(icon), contentDescription = stringResource(description))
        }
    }
}
