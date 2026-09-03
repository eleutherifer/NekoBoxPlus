package io.nekohasekai.sagernet.ui.compose

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr

@Composable
fun NekoComposeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val night = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    val colors = remember(context, night) {
        val primary = Color(context.getColorAttr(R.attr.colorPrimary))
        val onPrimary = Color(context.getColorAttr(R.attr.colorOnPrimary))
        val primaryContainer = Color(context.getColorAttr(R.attr.colorPrimaryContainer))
        val onPrimaryContainer = Color(context.getColorAttr(R.attr.colorOnPrimaryContainer))
        val secondary = Color(context.getColorAttr(R.attr.colorSecondary))
        val onSecondary = Color(context.getColorAttr(R.attr.colorOnSecondary))
        val secondaryContainer = Color(context.getColorAttr(R.attr.colorSecondaryContainer))
        val onSecondaryContainer = Color(context.getColorAttr(R.attr.colorOnSecondaryContainer))
        val surface = Color(context.getColorAttr(R.attr.colorSurface))
        val onSurface = Color(context.getColorAttr(R.attr.colorOnSurface))
        val surfaceVariant = Color(context.getColorAttr(R.attr.colorSurfaceVariant))
        val onSurfaceVariant = Color(context.getColorAttr(R.attr.colorOnSurfaceVariant))
        val outline = Color(context.getColorAttr(R.attr.colorOutline))
        val outlineVariant = Color(context.getColorAttr(R.attr.colorOutlineVariant))
        val base = if (night) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            background = surface,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceContainer = Color(context.getColorAttr(R.attr.colorSurfaceContainer)),
            surfaceContainerHigh = Color(context.getColorAttr(R.attr.colorSurfaceContainerHigh)),
            outline = outline,
            outlineVariant = outlineVariant,
        )
    }
    MaterialTheme(colorScheme = colors) {
        if (isTelevisionUi()) {
            CompositionLocalProvider(
                LocalIndication provides remember(colors.primary) {
                    TvFocusIndication(colors.primary)
                },
                content = content,
            )
        } else {
            content()
        }
    }
}
