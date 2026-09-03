package io.nekohasekai.sagernet.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal val LocalTelevisionUiOverride = staticCompositionLocalOf<Boolean?> { null }

@Composable
internal fun isTelevisionUi(): Boolean = LocalTelevisionUiOverride.current ?:
    (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK ==
        Configuration.UI_MODE_TYPE_TELEVISION)

/**
 * Makes remote focus visible and keeps a focused target inside its nearest scroll container.
 *
 * This observes an existing focus target and deliberately does not add another one. Put it before
 * clickable, toggleable, or selectable modifiers so it observes the focus state they create.
 */
internal fun Modifier.tvFocusTarget(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
): Modifier = composed(
    inspectorInfo = debugInspectorInfo {
        name = "tvFocusTarget"
        properties["enabled"] = enabled
        properties["shape"] = shape
    },
) {
    if (!isTelevisionUi() || !enabled) return@composed this

    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val focusColor = MaterialTheme.colorScheme.primary

    // RecyclerView can detach a ComposeView while its focused child is being recycled without
    // dispatching another focus callback. Do not carry that visual state into the reused row.
    DisposableEffect(Unit) {
        onDispose { focused = false }
    }

    LaunchedEffect(focused) {
        if (focused) bringIntoViewRequester.bringIntoView()
    }

    this
        .bringIntoViewRequester(bringIntoViewRequester)
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(3.dp, focusColor, shape) else Modifier)
}

/** Focus-aware default indication for Compose click targets that do not need a custom outline. */
internal data class TvFocusIndication(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TvFocusIndicationNode(interactionSource, color)
}

private class TvFocusIndicationNode(
    private val interactionSource: InteractionSource,
    private val color: Color,
) : Modifier.Node(), DrawModifierNode {
    private var focused = false
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            val focuses = mutableSetOf<FocusInteraction.Focus>()
            val presses = mutableSetOf<PressInteraction.Press>()
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focuses += interaction
                    is FocusInteraction.Unfocus -> focuses -= interaction.focus
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                }
                val nextFocused = focuses.isNotEmpty()
                val nextPressed = presses.isNotEmpty()
                if (focused != nextFocused || pressed != nextPressed) {
                    focused = nextFocused
                    pressed = nextPressed
                    invalidateDraw()
                }
            }
        }
    }

    override fun onDetach() {
        focused = false
        pressed = false
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) drawRect(color.copy(alpha = 0.18f))
        if (focused) {
            val stroke = 3.dp.toPx()
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f),
                size = androidx.compose.ui.geometry.Size(
                    (size.width - stroke).coerceAtLeast(0f),
                    (size.height - stroke).coerceAtLeast(0f),
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                style = Stroke(stroke),
            )
        }
    }
}
