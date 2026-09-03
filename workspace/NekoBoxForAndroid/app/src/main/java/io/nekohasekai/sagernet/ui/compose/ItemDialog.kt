package io.nekohasekai.sagernet.ui.compose

import android.content.Context
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.utils.Theme

internal fun Context.showComposeItemDialog(
    title: CharSequence?,
    items: List<CharSequence>,
    negativeButton: CharSequence? = null,
    cancelable: Boolean = true,
    onItemSelected: (Int) -> Unit,
    onNegative: () -> Unit = {},
    onCancel: () -> Unit = {},
): ComponentDialog {
    lateinit var dialog: ComponentDialog
    val content = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NekoComposeTheme {
                ItemDialogContent(
                    title = title,
                    items = items,
                    negativeButton = negativeButton,
                    onItemSelected = { index ->
                        dialog.dismiss()
                        onItemSelected(index)
                    },
                    onNegative = {
                        dialog.dismiss()
                        onNegative()
                    },
                )
            }
        }
    }
    dialog = ComponentDialog(this, Theme.getDialogTheme()).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(cancelable)
        setCanceledOnTouchOutside(cancelable)
        setOnCancelListener { onCancel() }
        setContentView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        prepareAsPreferenceDialog(this@showComposeItemDialog)
        show()
    }
    return dialog
}

internal fun Context.showComposeSingleChoiceDialog(
    title: CharSequence?,
    items: List<CharSequence>,
    selectedIndex: Int,
    negativeButton: CharSequence? = null,
    cancelable: Boolean = true,
    onItemSelected: (Int) -> Unit,
    onNegative: () -> Unit = {},
    onCancel: () -> Unit = {},
): ComponentDialog {
    lateinit var dialog: ComponentDialog
    val content = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NekoComposeTheme {
                PreferenceDialogSurface(
                    title = title,
                    showButtons = negativeButton != null,
                    buttons = {
                        negativeButton?.let { label ->
                            TextButton(onClick = {
                                dialog.dismiss()
                                onNegative()
                            }) {
                                Text(label.toString())
                            }
                        }
                    },
                ) {
                    SingleChoicePreferenceDialogContent(
                        labels = items.map(CharSequence::toString),
                        selectedIndex = selectedIndex,
                        onSelected = { index ->
                            dialog.dismiss()
                            onItemSelected(index)
                        },
                    )
                }
            }
        }
    }
    dialog = ComponentDialog(this, Theme.getDialogTheme()).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(cancelable)
        setCanceledOnTouchOutside(cancelable)
        setOnCancelListener { onCancel() }
        setContentView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        prepareAsPreferenceDialog(this@showComposeSingleChoiceDialog)
        show()
    }
    return dialog
}

@Composable
private fun ItemDialogContent(
    title: CharSequence?,
    items: List<CharSequence>,
    negativeButton: CharSequence?,
    onItemSelected: (Int) -> Unit,
    onNegative: () -> Unit,
) {
    PreferenceDialogSurface(
        title = title,
        showButtons = negativeButton != null,
        buttons = {
            negativeButton?.let { label ->
                TextButton(onClick = onNegative) {
                    Text(label.toString())
                }
            }
        },
    ) {
        ItemList(items = items, onItemSelected = onItemSelected)
    }
}

@Composable
private fun ItemList(
    items: List<CharSequence>,
    onItemSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onItemSelected(index) }
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.toString(),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
