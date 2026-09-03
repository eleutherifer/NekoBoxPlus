package io.nekohasekai.sagernet.ui.compose

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp

/** Remote-only click/long-click handling for a focusable card body. */
internal fun Modifier.tvCardActions(
    enabled: Boolean = true,
    showFocusIndicator: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = composed {
    if (!isTelevisionUi()) return@composed this

    var centerPressed by remember { mutableStateOf(false) }
    var longClickHandled by remember { mutableStateOf(false) }
    var cardFocused by remember { mutableStateOf(false) }
    this
        .tvFocusTarget(enabled && showFocusIndicator)
        .onPreviewKeyEvent { event ->
            if (!enabled || !cardFocused || event.nativeKeyEvent.keyCode !in TV_CENTER_KEYS) {
                return@onPreviewKeyEvent false
            }
            when (event.nativeKeyEvent.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        centerPressed = true
                        longClickHandled = false
                    } else if (centerPressed && !longClickHandled) {
                        longClickHandled = true
                        onLongClick()
                    }
                    true
                }
                KeyEvent.ACTION_UP -> {
                    if (centerPressed && !longClickHandled) onClick()
                    centerPressed = false
                    longClickHandled = false
                    true
                }
                else -> false
            }
        }
        .onFocusChanged {
            cardFocused = it.isFocused
            if (!cardFocused) {
                centerPressed = false
                longClickHandled = false
            }
        }
        .focusable(enabled)
}

private val TV_CENTER_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
)

/** Keeps the key-up that completed a card long press away from actionable menu rows. */
@Composable
internal fun TvMenuInitialFocus(focusRequester: FocusRequester, expanded: Boolean) {
    Spacer(
        Modifier
            .size(1.dp)
            .focusRequester(focusRequester)
            .focusable(),
    )
    LaunchedEffect(expanded) {
        if (expanded) focusRequester.requestFocus()
    }
}
