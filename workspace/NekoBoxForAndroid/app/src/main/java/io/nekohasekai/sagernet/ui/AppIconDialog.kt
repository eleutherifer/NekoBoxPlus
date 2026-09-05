package io.nekohasekai.sagernet.ui

import android.content.Context
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.AppIcon
import io.nekohasekai.sagernet.AppIconManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ui.compose.AppIconDialogContent
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme

internal object AppIconDialog {
    fun show(
        context: Context,
        title: CharSequence,
        onSelected: (AppIcon) -> Unit = {},
    ) {
        val selectedIcon = AppIconManager.current(context)
        val density = context.resources.displayMetrics.density
        val iconSize = (56 * density).toInt()
        val previews = AppIcon.entries.mapNotNull { icon ->
            AppIconManager.loadIcon(context, icon)?.let {
                icon to it.toBitmap(iconSize, iconSize).asImageBitmap()
            }
        }.toMap()
        val dialog = ComponentDialog(context)
        val content = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NekoComposeTheme {
                    val maxHeight = with(LocalDensity.current) {
                        (LocalWindowInfo.current.containerSize.height * 0.76f).toDp()
                    }
                    Surface(
                        Modifier.fillMaxWidth().heightIn(max = maxHeight),
                        RoundedCornerShape(28.dp),
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 6.dp,
                    ) {
                        Column {
                            Text(title.toString(), style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp))
                            AppIconDialogContent(
                                AppIcon.entries, previews, selectedIcon,
                                Color(context.getColorAttr(R.attr.selectedColorPrimary)),
                                onSelected = { icon ->
                                    if (icon != selectedIcon) AppIconManager.set(context, icon)
                                    onSelected(icon)
                                    dialog.dismiss()
                                },
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = dialog::dismiss) { Text(stringResource(android.R.string.cancel)) }
                            }
                        }
                    }
                }
            }
        }
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
        dialog.show()
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.84f).toInt().coerceAtMost((560 * density).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
}
