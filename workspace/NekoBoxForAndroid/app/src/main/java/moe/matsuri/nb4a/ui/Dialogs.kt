package moe.matsuri.nb4a.ui

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.compose.showComposeMessageDialog

object Dialogs {
    fun logExceptionAndShow(context: Context, e: Exception, callback: Runnable) {
        Logs.e(e)
        runOnMainDispatcher {
            context.showComposeMessageDialog(
                title = context.getText(R.string.error_title),
                message = e.readableMessage,
                cancelable = false,
                onPositive = callback::run,
            )
        }
    }

    fun message(context: Context, title: String, message: String) {
        runOnMainDispatcher {
            context.showComposeMessageDialog(
                title = title,
                message = message,
                positiveButton = null,
            )
        }
    }
}
