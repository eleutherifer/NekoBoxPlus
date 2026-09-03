package moe.matsuri.nb4a.ui

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.preferSmallIcon
import io.nekohasekai.sagernet.ui.ConnectionTestNotificationActionActivity
import io.nekohasekai.sagernet.ui.MainActivity

class ConnectionTestNotification(val context: Context, val title: String) {
    companion object {
        const val ACTION_CANCEL = "io.nekohasekai.sagernet.action.CANCEL_CONNECTION_TEST"
        private const val CHANNEL_ID = "connection-test"
        private const val NOTIFICATION_ID = 1001

        fun cancel(context: Context) {
            SagerNet.notification.cancel(NOTIFICATION_ID)
        }
    }

    private fun restoreIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_CONNECTION_TEST)
            .setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, NOTIFICATION_ID + 1, intent, flags)
    }

    private fun cancelIntent(): PendingIntent {
        val intent = Intent(context, ConnectionTestNotificationActionActivity::class.java)
            .setAction(ACTION_CANCEL)
            .setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, NOTIFICATION_ID + 2, intent, flags)
    }

    fun updateNotification(progress: Int, max: Int, finished: Boolean) {
        try {
            if (finished) {
                cancel(context)
                return
            }
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_active)
                .preferSmallIcon()
                .setContentTitle(title)
                .setOnlyAlertOnce(true)
                .setContentIntent(restoreIntent())
                .setAutoCancel(false)
                .setContentText("$progress / $max").setProgress(max, progress, false)
                .addAction(
                    R.drawable.ic_baseline_refresh_24,
                    context.getString(R.string.connection_test_notification_restore),
                    restoreIntent(),
                )
                .addAction(
                    R.drawable.ic_action_delete,
                    context.getString(R.string.connection_test_notification_cancel),
                    cancelIntent(),
                )
            SagerNet.notification.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Logs.w(e)
        }
    }
}
