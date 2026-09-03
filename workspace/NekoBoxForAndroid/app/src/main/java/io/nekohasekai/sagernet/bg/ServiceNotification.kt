package io.nekohasekai.sagernet.bg

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.preferSmallIcon
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.SwitchActivity
import io.nekohasekai.sagernet.utils.ProfileCountryResolver
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.CountryFlagRenderer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * User can customize visibility of notification since Android 8.
 * The default visibility:
 *
 * Android 8.x: always visible due to system limitations
 * VPN:         always invisible because of VPN notification/icon
 * Other:       always visible
 *
 * See also: https://github.com/aosp-mirror/platform_frameworks_base/commit/070d142993403cc2c42eca808ff3fafcee220ac4
 */
class ServiceNotification(
    private val service: BaseService.Interface, title: String,
    channel: String, visible: Boolean = false, private var profile: ProxyEntity? = null,
) : BroadcastReceiver() {
    companion object {
        private const val ACTION_NOTIFICATION_DELETED =
            "io.nekohasekai.sagernet.SERVICE_NOTIFICATION_DELETED"
        private const val REQUEST_NOTIFICATION_DELETE = 3
        const val notificationId = 1
        const val persistentStatusChannel = "service-vpn-persistent"
        const val flags = PendingIntent.FLAG_IMMUTABLE

        fun genTitle(ent: ProxyEntity): String {
            return genTitle(ent, ent.displayName())
        }

        fun genNotificationTitle(ent: ProxyEntity, countryIndicatorEnabled: Boolean): String {
            return genTitle(
                ent,
                ProfileCountryResolver.presentationName(ent, countryIndicatorEnabled),
            )
        }

        private fun genTitle(ent: ProxyEntity, profileName: String): String {
            val gn = if (DataStore.showGroupInNotification)
                SagerDatabase.groupDao.getById(ent.groupId)?.displayName() else null
            return if (gn == null) profileName else "[$gn] $profileName"
        }
    }

    var listenPostSpeed = true

    suspend fun postNotificationSpeedUpdate(stats: SpeedDisplayData) {
        useBuilder {
            if (showDirectSpeed) {
                val speedDetail = (service as Context).getString(
                    R.string.speed_detail, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.txRateDirect)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.rxRateDirect)
                    )
                )
                it.setStyle(NotificationCompat.BigTextStyle().bigText(speedDetail))
                it.setContentText(speedDetail)
            } else {
                val speedSimple = (service as Context).getString(
                    R.string.traffic, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    )
                )
                it.setContentText(speedSimple)
            }
            it.setSubText(
                service.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(service, stats.txTotal),
                    Formatter.formatFileSize(service, stats.rxTotal)
                )
            )
        }
        update()
    }

    suspend fun postNotificationTitle(newTitle: String) {
        useBuilder {
            it.setContentTitle(newTitle)
        }
        update()
    }

    suspend fun postNotificationCountryIndicator(enabled: Boolean) {
        profile = profile?.let { ProfileManager.getProfile(it.id) ?: it }
        useBuilder {
            profile?.let { activeProfile ->
                it.setContentTitle(genNotificationTitle(activeProfile, enabled))
            }
            it.setLargeIcon(countryIndicatorIcon(enabled))
        }
        update()
    }

    suspend fun postNotificationWakeLockStatus(acquired: Boolean) {
        updateActions()
        useBuilder {
            it.priority =
                if (persistent) {
                    NotificationCompat.PRIORITY_LOW
                } else if (acquired) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_LOW
                }
        }
        update()
    }

    private val showDirectSpeed = DataStore.showDirectSpeed
    private val persistent = DataStore.persistentStatusNotification
    private val notificationChannel = if (persistent) persistentStatusChannel else channel

    private val builder = NotificationCompat.Builder(service as Context, notificationChannel)
        .setWhen(0)
        .setTicker(service.getString(R.string.forward_success))
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setAutoCancel(false)
        .setContentIntent(SagerNet.configureIntent(service))
        .setDeleteIntent(
            PendingIntent.getBroadcast(
                service,
                REQUEST_NOTIFICATION_DELETE,
                Intent(ACTION_NOTIFICATION_DELETED).setPackage(service.packageName),
                flags
            )
        )
        .setSmallIcon(R.drawable.ic_service_active)
        .preferSmallIcon()
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(
            if (persistent || visible) NotificationCompat.PRIORITY_LOW
            else NotificationCompat.PRIORITY_MIN
        )
        .setOngoing(persistent)
        .setSilent(persistent)

    private val buildLock = Mutex()

    private suspend fun useBuilder(f: (NotificationCompat.Builder) -> Unit) {
        buildLock.withLock {
            f(builder)
        }
    }

    private fun NotificationCompat.Builder.buildServiceNotification(): Notification =
        build().apply {
            if (persistent) {
                flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
            }
        }

    init {
        service as Context

        Theme.apply(app)
        Theme.apply(service)
        builder.color = service.getColorAttr(R.attr.colorPrimary)
        builder.setLargeIcon(countryIndicatorIcon(DataStore.notificationCountryIndicator))

        val intentFilter = IntentFilter().apply {
            addAction(ACTION_NOTIFICATION_DELETED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(this, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            service.registerReceiver(this, intentFilter)
        }

        runOnMainDispatcher {
            updateActions()
            show()
        }
    }

    private fun countryIndicatorIcon(enabled: Boolean) = if (enabled) {
        profile?.let(ProfileCountryResolver::effectiveCountryCode)?.let { countryCode ->
            CountryFlagRenderer.renderNotificationIcon(service as Context, countryCode)
        }
    } else {
        null
    }

    private suspend fun updateActions() {
        service as Context
        useBuilder {
            it.clearActions()

            val closeAction = NotificationCompat.Action.Builder(
                0, service.getText(R.string.stop), PendingIntent.getBroadcast(
                    service, 0, Intent(Action.CLOSE).setPackage(service.packageName), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(closeAction)

            val switchAction = NotificationCompat.Action.Builder(
                0, service.getString(R.string.action_switch), PendingIntent.getActivity(
                    service, 0, Intent(service, SwitchActivity::class.java), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(switchAction)

            val resetUpstreamAction = NotificationCompat.Action.Builder(
                0, service.getString(R.string.reset_connections),
                PendingIntent.getBroadcast(
                    service, 0, Intent(Action.RESET_UPSTREAM_CONNECTIONS), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(resetUpstreamAction)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_NOTIFICATION_DELETED) {
            if (persistent && service.data.state.started) runOnMainDispatcher {
                show()
            }
            return
        }
        if (service.data.state == BaseService.State.Connected) {
            listenPostSpeed = intent.action == Intent.ACTION_SCREEN_ON
        }
    }


    private suspend fun show() =
        useBuilder {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val foregroundServiceType = if (
                        service.hasActiveWifiRules() &&
                        SagerNet.application.nativeInterface.canReadWifiIdentityInBackground()
                    ) {
                        FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED or FOREGROUND_SERVICE_TYPE_LOCATION
                    } else {
                        FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                    }
                    (service as Service).startForeground(
                        notificationId,
                        it.buildServiceNotification(),
                        foregroundServiceType
                    )
                } else {
                    (service as Service).startForeground(notificationId, it.buildServiceNotification())
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "startForeground: $e",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private suspend fun update() = useBuilder {
        try {
            NotificationManagerCompat.from(service as Service)
                .notify(notificationId, it.buildServiceNotification())
        } catch (_: SecurityException) {
            // Notification permission can be revoked while the foreground service is running.
        }
    }

    fun destroy() {
        listenPostSpeed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (service as Service).stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            (service as Service).stopForeground(true)
        }
        service.unregisterReceiver(this)
    }
}
