package io.nekohasekai.sagernet.ui.compose

import android.app.Dialog
import android.view.WindowManager

/**
 * AlertDialog cannot detect text editors hosted inside ComposeView and consequently adds
 * FLAG_ALT_FOCUSABLE_IM while setting up its content. Clear it after the dialog is shown so
 * Compose text fields can create an input connection.
 */
@Suppress("DEPRECATION")
internal fun Dialog.enableComposeTextInput(alwaysVisible: Boolean = false) {
    window?.apply {
        clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        if (alwaysVisible) {
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
        }
    }
}
