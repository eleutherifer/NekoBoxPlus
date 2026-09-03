package io.nekohasekai.sagernet.ktx

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentDialog
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.SubscriptionLinkImportPolicy
import io.nekohasekai.sagernet.ui.compose.createComposeMessageDialog

fun Context.alert(text: String): ComponentDialog {
    return createComposeMessageDialog(
        title = getText(R.string.error_title),
        message = text,
    )
}

fun Fragment.alert(text: String) = requireContext().alert(text)

fun Context.happCryptUnsupportedDialog(): ComponentDialog {
    return createComposeMessageDialog(
        title = getText(R.string.happ_crypt_unsupported_title),
        message = getText(R.string.happ_crypt_unsupported_message),
        positiveButton = getText(R.string.action_open),
        negativeButton = getText(android.R.string.cancel),
        onPositive = { launchCustomTab(SubscriptionLinkImportPolicy.HAPP_DECRYPTOR_URL) },
    )
}

fun ComponentDialog.tryToShow() {
    try {
        val activity = context as Activity
        if (!activity.isFinishing) {
            show()
        }
    } catch (e: Exception) {
        Logs.e(e)
    }
}
