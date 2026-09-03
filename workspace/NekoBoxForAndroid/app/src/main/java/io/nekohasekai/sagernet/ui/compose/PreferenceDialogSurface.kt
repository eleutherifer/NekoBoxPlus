package io.nekohasekai.sagernet.ui.compose

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable

@Composable
internal fun PreferenceDialogSurface(
    title: CharSequence?,
    compactTitle: Boolean = false,
    showButtons: Boolean = true,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maxHeight = with(LocalDensity.current) { (windowHeight * 0.76f).toDp() }
    val hasTitle = !title.isNullOrEmpty()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(top = if (hasTitle) 18.dp else 0.dp)) {
            if (hasTitle) {
                Text(
                    text = title.toString(),
                    style = if (compactTitle) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clipToBounds(),
            ) {
                content()
            }
            if (showButtons) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.End,
                    content = buttons,
                )
            }
        }
    }
}

internal fun ComponentDialog.showAsPreferenceDialog(context: Context) {
    setCanceledOnTouchOutside(true)
    prepareAsPreferenceDialog(context)
    show()
}

internal fun ComponentDialog.prepareAsPreferenceDialog(context: Context) {
    applyPreferenceDialogWindow(context)
}

private fun ComponentDialog.applyPreferenceDialogWindow(context: Context) {
    window?.apply {
        setBackgroundDrawable(AndroidColor.TRANSPARENT.toDrawable())
        val maxWidth = (560 * context.resources.displayMetrics.density).toInt()
        val dialogWidth = (context.resources.displayMetrics.widthPixels * 0.84f).toInt()
            .coerceAtMost(maxWidth)
        setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}
