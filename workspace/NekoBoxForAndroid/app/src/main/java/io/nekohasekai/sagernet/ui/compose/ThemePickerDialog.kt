package io.nekohasekai.sagernet.ui.compose

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toDrawable
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.CustomTheme
import io.nekohasekai.sagernet.utils.Theme
import java.util.Locale

internal fun Context.showThemePickerDialog(
    title: CharSequence?,
    includeCustom: Boolean,
    onThemeSelected: (Int) -> Unit,
) {
    lateinit var dialog: ComponentDialog
    val colors = resources.getIntArray(R.array.material_colors)
    dialog = ComponentDialog(this, Theme.getDialogTheme()).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(true)
        setContentView(
            ComposeView(this@showThemePickerDialog).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    NekoComposeTheme {
                        ThemePickerDialogContent(
                            title = title?.toString(),
                            colors = colors,
                            includeCustom = includeCustom,
                            customSupported = CustomTheme.isSupported,
                            onDismiss = { dialog.dismiss() },
                            onThemeSelected = { theme ->
                                dialog.dismiss()
                                onThemeSelected(theme)
                            },
                        )
                    }
                }
            },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(AndroidColor.TRANSPARENT.toDrawable())
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}

private data class ThemeChoice(
    val id: Int,
    val color: Int? = null,
    val custom: Boolean = false,
)

@Composable
private fun ThemePickerDialogContent(
    title: String?,
    colors: IntArray,
    includeCustom: Boolean,
    customSupported: Boolean,
    onDismiss: () -> Unit,
    onThemeSelected: (Int) -> Unit,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val columns = if (windowSize.width > windowSize.height) 8 else 4
    val cancelLabel = stringResource(android.R.string.cancel)
    val choices = buildList {
        add(ThemeChoice(Theme.MATERIAL_YOU))
        colors.forEachIndexed { index, color -> add(ThemeChoice(index + 1, color)) }
        if (includeCustom) add(ThemeChoice(Theme.CUSTOM, custom = true))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Column {
                if (title != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                    }
                }
                Column(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    choices.chunked(columns).forEach { row ->
                        Row {
                            row.forEach { choice ->
                                ThemeChoiceCell(
                                    choice = choice,
                                    customSupported = customSupported,
                                    onClick = { onThemeSelected(choice.id) },
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .padding(end = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1F))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .height(48.dp)
                            .clearAndSetSemantics {
                                contentDescription = cancelLabel
                                role = Role.Button
                                onClick {
                                    onDismiss()
                                    true
                                }
                            },
                    ) {
                        Text(cancelLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeChoiceCell(
    choice: ThemeChoice,
    customSupported: Boolean,
    onClick: () -> Unit,
) {
    val dynamicDescription = stringResource(R.string.app_icon_dynamic)
    val customDescription = stringResource(R.string.custom_theme)
    val description = when {
        choice.custom -> customDescription
        choice.color == null -> dynamicDescription
        else -> String.format(Locale.ROOT, "#%06X", choice.color and 0xFFFFFF)
    }
    Box(
        modifier = Modifier
            .size(64.dp)
            .alpha(if (choice.custom && !customSupported) 0.45F else 1F)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            choice.custom -> {
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "?",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 28.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            choice.color == null -> {
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_color_lens_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
            else -> {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_fiber_manual_record_24),
                    contentDescription = null,
                    tint = Color(choice.color),
                    modifier = Modifier.size(64.dp),
                )
            }
        }
    }
}
