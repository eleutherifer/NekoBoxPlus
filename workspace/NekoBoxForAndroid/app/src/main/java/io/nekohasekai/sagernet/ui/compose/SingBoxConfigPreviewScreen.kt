package io.nekohasekai.sagernet.ui.compose

import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import io.nekohasekai.sagernet.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingBoxConfigPreviewScreen(
    wordWrap: Boolean,
    copyEnabled: Boolean,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onToggleWordWrap: () -> Unit,
    onEditorReady: (TextProcessor) -> Unit,
) {
    BackHandler(onBack = onClose)
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preview_sing_box_config)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painterResource(R.drawable.baseline_arrow_back_24),
                            contentDescription = stringResource(R.string.mal_close),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onCopy, enabled = copyEnabled) {
                        Icon(
                            painterResource(R.drawable.ic_baseline_content_copy_24),
                            contentDescription = stringResource(R.string.action_copy),
                        )
                    }
                    val wrapDescription = if (wordWrap) {
                        R.string.disable_word_wrap
                    } else {
                        R.string.enable_word_wrap
                    }
                    IconButton(onClick = onToggleWordWrap) {
                        Icon(
                            painterResource(
                                if (wordWrap) {
                                    R.drawable.ic_baseline_format_align_left_24
                                } else {
                                    R.drawable.baseline_wrap_text_24
                                },
                            ),
                            contentDescription = stringResource(wrapDescription),
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
                initialText = "",
                wordWrap = wordWrap,
                scrollerWidth = 12.dp,
                contentPadding = 12.dp,
                onEditorReady = onEditorReady,
                modifier = Modifier.weight(1f),
                configure = {
                    inputType = InputType.TYPE_NULL
                    keyListener = null
                    isCursorVisible = false
                    showSoftInputOnFocus = false
                    setTextIsSelectable(true)
                    setSingleLine(false)
                    gravity = Gravity.START or Gravity.TOP
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = ResourcesCompat.getFont(
                        context,
                        R.font.jetbrains_mono,
                    ) ?: Typeface.MONOSPACE
                },
            )
        }
    }
}
