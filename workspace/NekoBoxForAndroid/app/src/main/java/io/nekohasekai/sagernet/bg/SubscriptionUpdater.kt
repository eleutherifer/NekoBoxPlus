package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onIoDispatcher
import io.nekohasekai.sagernet.ktx.preferSmallIcon
import io.nekohasekai.sagernet.routing.SubscriptionRoutingIntervals
import io.nekohasekai.sagernet.routing.SubscriptionRoutingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object SubscriptionUpdater {

    suspend fun cleanupOrphanedWork() {
        cleanupSubscriptionWork(RemoteSubscriptionWorkScheduler) {
            AppData.groups.subscriptions().any { it.needsAutomaticSubscriptionUpdates() }
        }
    }

    suspend fun reconfigureUpdater() {
        val subscriptions = AppData.groups.subscriptions()
            .filter { it.needsAutomaticSubscriptionUpdates() }
        syncBootReceiverEnabled(subscriptions.isNotEmpty())
        if (subscriptions.isEmpty()) {
            RemoteSubscriptionWorkScheduler.cancelCanonicalWork()
            return
        }

        val schedule = SubscriptionUpdateSchedulePolicy.schedule(
            subscriptions = subscriptions.flatMap {
                val subscription = it.subscription!!
                buildList {
                    if (subscription.autoUpdate) {
                        add(
                            SubscriptionUpdateSchedulePolicy.SubscriptionState(
                                autoUpdateDelayMinutes = subscription.autoUpdateDelay,
                                lastUpdatedSeconds = subscription.lastUpdated,
                            ),
                        )
                    }
                    if (subscription.routingEnabled && subscription.autoRoutingUrl.isNotBlank()) {
                        add(
                            SubscriptionUpdateSchedulePolicy.SubscriptionState(
                                autoUpdateDelayMinutes =
                                    SubscriptionRoutingIntervals.normalize(subscription.routingUpdateInterval) / 60,
                                lastUpdatedSeconds = subscription.routingLastUpdated.toInt(),
                            ),
                        )
                    }
                }
            },
            nowSeconds = System.currentTimeMillis() / 1000L,
        ) ?: return

        RemoteSubscriptionWorkScheduler.schedule(schedule)
        Logs.d(
            "reconfigureUpdater, interval: ${schedule.intervalMinutes} min" +
                    if (schedule.initialDelaySeconds > 0) ", initial delay: ${schedule.initialDelaySeconds} s" else ""
        )
    }

    suspend fun syncBootReceiverEnabled() {
        syncBootReceiverEnabled(
            AppData.groups.subscriptions()
                .any { it.needsAutomaticSubscriptionUpdates() }
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
            try {
                updateSubscriptions()
                return Result.success()
            } finally {
                nm.cancel(2)
            }
        }

        private suspend fun updateSubscriptions() {
            for (candidate in AppData.groups.subscriptions()) {
                currentCoroutineContext().ensureActive()
                val profile = onIoDispatcher { AppData.groups.getById(candidate.id) } ?: continue
                if (profile.type != GroupType.SUBSCRIPTION) continue
                val subscription = profile.subscription ?: continue

                val now = System.currentTimeMillis() / 1000L
                val subscriptionDue =
                    subscription.autoUpdate &&
                        (DataStore.serviceState.connected || !subscription.updateWhenConnectedOnly) &&
                        now - subscription.lastUpdated >= subscription.autoUpdateDelay.toLong() * 60L
                if (subscriptionDue) {
                    Logs.d("work: updating " + profile.displayName())
                    notification.setContentText(
                        applicationContext.getString(
                            R.string.subscription_update_message,
                            profile.displayName(),
                        ),
                    )
                    if (android.os.Build.VERSION.SDK_INT < 33 ||
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            applicationContext, android.Manifest.permission.POST_NOTIFICATIONS,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        try {
                            nm.notify(2, notification.build())
                        } catch (error: SecurityException) {
                            // Revoking notifications must not prevent subscription updates.
                            Logs.w(error)
                        }
                    }
                    GroupUpdater.executeUpdate(profile, false)
                }

                currentCoroutineContext().ensureActive()
                val routingProfile = onIoDispatcher { AppData.groups.getById(candidate.id) } ?: continue
                if (routingProfile.type != GroupType.SUBSCRIPTION) continue
                val routingSubscription = routingProfile.subscription ?: continue
                val routingDue =
                    routingSubscription.routingEnabled &&
                        routingSubscription.autoRoutingUrl.isNotBlank() &&
                        now - routingSubscription.routingLastUpdated >=
                        SubscriptionRoutingIntervals.normalize(routingSubscription.routingUpdateInterval)
                if (routingDue) {
                    try {
                        if (SubscriptionRoutingRepository.refreshAutoRouting(routingProfile)) {
                            currentCoroutineContext().ensureActive()
                            onIoDispatcher {
                                val current = AppData.groups.getById(routingProfile.id)
                                val currentSubscription = current?.subscription
                                if (current?.type == GroupType.SUBSCRIPTION &&
                                    currentSubscription != null && currentSubscription.routingEnabled &&
                                    currentSubscription.autoRoutingUrl == routingSubscription.autoRoutingUrl
                                ) {
                                    currentSubscription.routingPayload = routingSubscription.routingPayload
                                    currentSubscription.routingFormat = routingSubscription.routingFormat
                                    currentSubscription.routingLastUpdated = routingSubscription.routingLastUpdated
                                    currentSubscription.routingOff = routingSubscription.routingOff
                                    AppData.groups.updateGroup(current)
                                }
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Logs.w(error)
                    }
                }
            }
        }
    }

}
