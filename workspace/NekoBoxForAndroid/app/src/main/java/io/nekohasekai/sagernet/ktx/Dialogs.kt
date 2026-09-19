package io.nekohasekai.sagernet.ktx

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.SubscriptionLinkImportPolicy

fun Context.alert(text: String): AlertDialog {
    return MaterialAlertDialogBuilder(this).setTitle(R.string.error_title)
        .setMessage(text)
        .setPositiveButton(android.R.string.ok, null)
        .create()
}

fun Fragment.alert(text: String) = requireContext().alert(text)

fun Context.happCryptUnsupportedDialog(): AlertDialog {
    return MaterialAlertDialogBuilder(this)
        .setTitle(R.string.happ_crypt_unsupported_title)
        .setMessage(R.string.happ_crypt_unsupported_message)
        .setPositiveButton(R.string.action_open) { _, _ ->
            launchCustomTab(SubscriptionLinkImportPolicy.HAPP_DECRYPTOR_URL)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
}

fun AlertDialog.tryToShow() {
    try {
        val activity = context as Activity
        if (!activity.isFinishing) {
            show()
        }
    } catch (e: Exception) {
        Logs.e(e)
    }
}
