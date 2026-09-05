package io.nekohasekai.sagernet.ui.compose

import android.content.Context
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.utils.Theme

internal fun Context.showComposeMessageDialog(
    title: CharSequence?,
    message: CharSequence? = null,
    positiveButton: CharSequence? = getText(android.R.string.ok),
    negativeButton: CharSequence? = null,
    neutralButton: CharSequence? = null,
    cancelable: Boolean = true,
    onPositive: () -> Unit = {},
    onNegative: () -> Unit = {},
    onNeutral: () -> Unit = {},
    onCancel: () -> Unit = {},
): ComponentDialog {
    return createComposeMessageDialog(
        title = title,
        message = message,
        positiveButton = positiveButton,
        negativeButton = negativeButton,
        neutralButton = neutralButton,
        cancelable = cancelable,
        onPositive = onPositive,
        onNegative = onNegative,
        onNeutral = onNeutral,
        onCancel = onCancel,
    ).also { it.show() }
}

internal fun Context.createComposeMessageDialog(
    title: CharSequence?,
    message: CharSequence? = null,
    positiveButton: CharSequence? = getText(android.R.string.ok),
    negativeButton: CharSequence? = null,
    neutralButton: CharSequence? = null,
    cancelable: Boolean = true,
    onPositive: () -> Unit = {},
    onNegative: () -> Unit = {},
    onNeutral: () -> Unit = {},
    onCancel: () -> Unit = {},
): ComponentDialog {
    lateinit var dialog: ComponentDialog
    val content = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NekoComposeTheme {
                MessageDialogContent(
                    title = title,
                    message = message,
                    positiveButton = positiveButton,
                    negativeButton = negativeButton,
                    neutralButton = neutralButton,
                    onPositive = {
                        dialog.dismiss()
                        onPositive()
                    },
                    onNegative = {
                        dialog.dismiss()
                        onNegative()
                    },
                    onNeutral = {
                        dialog.dismiss()
                        onNeutral()
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
        prepareAsPreferenceDialog(this@createComposeMessageDialog)
    }
    return dialog
}

internal fun Context.showComposeDynamicMessageDialog(
    message: @Composable () -> CharSequence,
    positiveButton: CharSequence,
    negativeButton: CharSequence,
    onPositive: () -> Unit,
    onNegative: () -> Unit,
    onCancel: () -> Unit,
): ComponentDialog {
    lateinit var dialog: ComponentDialog
    val content = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NekoComposeTheme {
                MessageDialogContent(
                    title = null,
                    message = message(),
                    positiveButton = positiveButton,
                    negativeButton = negativeButton,
                    neutralButton = null,
                    onPositive = {
                        dialog.dismiss()
                        onPositive()
                    },
                    onNegative = {
                        dialog.dismiss()
                        onNegative()
                    },
                    onNeutral = {},
                )
            }
        }
    }
    return ComponentDialog(this, Theme.getDialogTheme()).also { created ->
        dialog = created
        created.requestWindowFeature(Window.FEATURE_NO_TITLE)
        created.setCanceledOnTouchOutside(true)
        created.setOnCancelListener { onCancel() }
        created.setContentView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        created.prepareAsPreferenceDialog(this)
        created.show()
    }
}

@Composable
private fun MessageDialogContent(
    title: CharSequence?,
    message: CharSequence?,
    positiveButton: CharSequence?,
    negativeButton: CharSequence?,
    neutralButton: CharSequence?,
    onPositive: () -> Unit,
    onNegative: () -> Unit,
    onNeutral: () -> Unit,
) {
    PreferenceDialogSurface(
        title = title,
        compactTitle = true,
        showButtons = positiveButton != null || negativeButton != null || neutralButton != null,
        buttons = {
            neutralButton?.let { label ->
                TextButton(
                    modifier = Modifier.widthIn(min = 64.dp),
                    onClick = onNeutral,
                ) {
                    Text(label.toString())
                }
                Spacer(Modifier.weight(1f))
            }
            negativeButton?.let { label ->
                TextButton(
                    modifier = Modifier.widthIn(min = 64.dp),
                    onClick = onNegative,
                ) {
                    Text(label.toString())
                }
                Spacer(Modifier.width(8.dp))
            }
            positiveButton?.let { label ->
                TextButton(
                    modifier = Modifier.widthIn(min = 64.dp),
                    onClick = onPositive,
                ) {
                    Text(label.toString())
                }
            }
        },
    ) {
        message?.let {
            SelectionContainer {
                Text(
                    text = it.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(
                            horizontal = 24.dp,
                            vertical = if (title.isNullOrEmpty()) 8.dp else 16.dp,
                        ),
                )
            }
        }
    }
}
