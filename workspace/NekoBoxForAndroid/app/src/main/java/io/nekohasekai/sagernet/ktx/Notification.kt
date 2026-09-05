package io.nekohasekai.sagernet.ktx

import android.os.Bundle
import androidx.core.app.NotificationCompat

private const val EXTRA_PREFER_SMALL_ICON = "android.app.preferSmallIcon"

/**
 * Requests the notification small icon in place of the launcher icon on supported systems.
 *
 * Notification.EXTRA_PREFER_SMALL_ICON was added in API 37. Using its stable key keeps this
 * request source-compatible with the current compile SDK; older systems ignore the extra.
 */
fun NotificationCompat.Builder.preferSmallIcon(): NotificationCompat.Builder =
    addExtras(Bundle().apply { putBoolean(EXTRA_PREFER_SMALL_ICON, true) })
