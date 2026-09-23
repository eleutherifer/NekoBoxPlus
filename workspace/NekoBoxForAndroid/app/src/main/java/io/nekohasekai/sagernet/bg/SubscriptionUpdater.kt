package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.preferSmallIcon
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"

    suspend fun reconfigureUpdater() {
        val subscriptions = SagerDatabase.groupDao.subscriptions()
            .filter { it.subscription!!.autoUpdate }
        syncBootReceiverEnabled(subscriptions.isNotEmpty())
        if (subscriptions.isEmpty()) {
            RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            return
        }

        val schedule = SubscriptionUpdateSchedulePolicy.schedule(
            subscriptions = subscriptions.map {
                val subscription = it.subscription!!
                SubscriptionUpdateSchedulePolicy.SubscriptionState(
                    autoUpdateDelayMinutes = subscription.autoUpdateDelay,
                    lastUpdatedSeconds = subscription.lastUpdated,
                )
            },
            nowSeconds = System.currentTimeMillis() / 1000L,
        ) ?: return

        // main process
        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(UpdateTask::class.java, schedule.intervalMinutes, TimeUnit.MINUTES)
                .apply {
                    if (schedule.initialDelaySeconds > 0) {
                        setInitialDelay(schedule.initialDelaySeconds, TimeUnit.SECONDS)
                    }
                }
                .build()
        )
        Logs.d(
            "reconfigureUpdater, interval: ${schedule.intervalMinutes} min" +
                    if (schedule.initialDelaySeconds > 0) ", initial delay: ${schedule.initialDelaySeconds} s" else ""
        )
    }

    suspend fun syncBootReceiverEnabled() {
        syncBootReceiverEnabled(
            SagerDatabase.groupDao.subscriptions()
                .any { it.subscription!!.autoUpdate }
        )
    }

    private fun syncBootReceiverEnabled(hasAutoUpdateSubscriptions: Boolean) {
        BootReceiver.enabled = SubscriptionBootReceiverPolicy.shouldEnableReceiver(
            persistAcrossReboot = DataStore.persistAcrossReboot,
            hasAutoUpdateSubscriptions = hasAutoUpdateSubscriptions,
        )
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        val nm = NotificationManagerCompat.from(applicationContext)

        val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
            .setWhen(0)
            .setTicker(applicationContext.getString(R.string.forward_success))
            .setContentTitle(applicationContext.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_service_active)
            .preferSmallIcon()
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        override suspend fun doWork(): Result {
            var subscriptions =
                SagerDatabase.groupDao.subscriptions().filter { it.subscription!!.autoUpdate }
            if (!DataStore.serviceState.connected) {
                Logs.d("work: not connected")
                subscriptions = subscriptions.filter { !it.subscription!!.updateWhenConnectedOnly }
            }

            if (subscriptions.isNotEmpty()) for (profile in subscriptions) {
                val subscription = profile.subscription!!

                if (((System.currentTimeMillis() / 1000).toInt() - subscription.lastUpdated) < subscription.autoUpdateDelay * 60) {
                    Logs.d("work: not updating " + profile.displayName())
                    continue
                }
                Logs.d("work: updating " + profile.displayName())

                notification.setContentText(
                    applicationContext.getString(
                        R.string.subscription_update_message, profile.displayName()
                    )
                )
                nm.notify(2, notification.build())

                GroupUpdater.executeUpdate(profile, false)
            }

            nm.cancel(2)

            return Result.success()
        }
    }

}
