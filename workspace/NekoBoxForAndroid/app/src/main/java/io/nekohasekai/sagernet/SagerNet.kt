package io.nekohasekai.sagernet

import android.annotation.SuppressLint
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import android.os.StrictMode
import android.os.SystemClock
import android.os.UserManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import go.Seq
import io.nekohasekai.sagernet.bg.CoreRecoveryService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.ServiceLifecyclePolicy
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.bg.shouldSweepLibcoreMemory
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isOss
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.*
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import libcore.Libcore
import moe.matsuri.nb4a.NativeInterface
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.cleanWebview
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import androidx.work.Configuration as WorkConfiguration

class SagerNet : Application(),
    WorkConfiguration.Provider {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        application = this
    }

    val nativeInterface = NativeInterface()

    val externalAssets: File by lazy { getExternalFilesDir(null) ?: filesDir }
    val process: String = JavaUtil.getProcessName()
    private val isMainProcess = process == BuildConfig.APPLICATION_ID
    val isBgProcess = process.endsWith(":bg")

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler)

        if (isMainProcess && isTv) {
            AppIconManager.set(this, AppIcon.NEKOBOX_PLUS)
        }

        if (isMainProcess || isBgProcess) {
            clearCacheAfterAppUpdate()
            externalAssets.mkdirs()
            Seq.setContext(this)
            val logLevel = AppLogLevelController.initialize(DataStore.logLevel)
            Libcore.initCore(
                process,
                cacheDir.absolutePath + "/",
                filesDir.absolutePath + "/",
                externalAssets.absolutePath + "/",
                DataStore.logBufSize,
                logLevel.outputEnabled,
                nativeInterface, nativeInterface, LocalResolverImpl
            )
            if (!DataStore.enableCoreProfiling) {
                deleteCoreProfilerData()
            }
            loadRootCACerts()

            // fix multi process issue in Android 9+
            JavaUtil.handleWebviewDir(this)

            runOnDefaultDispatcher {
                PackageCache.register()
                cleanWebview()
            }
        }

        if (isMainProcess) {
            DynamicColors.applyToActivitiesIfAvailable(
                this,
                DynamicColorsOptions.Builder()
                    .setPrecondition { _, _ -> Theme.isMaterialYou() || CustomTheme.useDynamicColors() }
                    .build()
            )
            Theme.apply(this)
            Theme.applyNightTheme()
            AppLocale.apply()
            if (DataStore.runningTest) {
                DataStore.runningTest = false
                ConnectionTestNotification.cancel(this)
            }
            runOnDefaultDispatcher {
                DefaultNetworkListener.start(this) {
                    underlyingNetwork = it
                    nativeInterface.syncNetworkState(it)
                }

                runCatching {
                    SubscriptionUpdater.reconfigureUpdater()
                }.onFailure {
                    Logs.w("Unable to reconfigure subscription updater: ${it.message}")
                }

                updateNotificationChannels()
            }
        }

        if (BuildConfig.DEBUG) {
            System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }

    private fun clearCacheAfterAppUpdate() {
        runCatching {
            val currentVersionCode = BuildConfig.VERSION_CODE
            if (!AppVersionCachePolicy.shouldClearCache(
                    DataStore.lastStartedVersionCode,
                    currentVersionCode,
                )
            ) {
                return
            }

            DataStore.lastStartedVersionCode = currentVersionCode
            AppCache.clear(cacheDir)
        }.onFailure {
            Logs.w("Unable to clear app cache after version change: ${it.message}")
        }
    }

    private fun deleteCoreProfilerData() {
        runCatching {
            File(cacheDir, "core-profiler").deleteRecursively()
            File(cacheDir, "core-profiler-export").deleteRecursively()
        }.onFailure {
            Logs.w(it)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNotificationChannels()
    }

    override fun getWorkManagerConfiguration(): WorkConfiguration {
        return WorkConfiguration.Builder()
            .setDefaultProcessName("${BuildConfig.APPLICATION_ID}:bg")
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        if (isBgProcess && shouldSweepLibcoreMemory(level)) {
            scheduleLibcoreGCSweep()
        }
    }

    @SuppressLint("InlinedApi")
    companion object {

        private val libcoreGCSweepRunning = AtomicBoolean()

        lateinit var application: SagerNet

        internal fun scheduleLibcoreGCSweep() {
            if (!libcoreGCSweepRunning.compareAndSet(false, true)) return
            runOnDefaultDispatcher {
                try {
                    Libcore.performLibcoreGCSweep()
                } finally {
                    libcoreGCSweepRunning.set(false)
                }
            }
        }

        val isTv by lazy {
            uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }

        val configureIntent: (Context) -> PendingIntent by lazy {
            {
                PendingIntent.getActivity(
                    it,
                    0,
                    Intent(
                        application, MainActivity::class.java
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                )
            }
        }
        val activity by lazy { application.getSystemService<ActivityManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val user by lazy { application.getSystemService<UserManager>()!! }
        val uiMode by lazy { application.getSystemService<UiModeManager>()!! }
        val power by lazy { application.getSystemService<PowerManager>()!! }

        fun getClipboardText(): String {
            return clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString() ?: ""
        }

        fun trySetPrimaryClip(clip: String) = try {
            clipboard.setPrimaryClip(ClipData.newPlainText(null, clip))
            true
        } catch (e: RuntimeException) {
            Logs.w(e)
            false
        }

        fun updateNotificationChannels() {
            if (Build.VERSION.SDK_INT >= 26) @RequiresApi(26) {
                notification.createNotificationChannels(
                    listOf(
                        NotificationChannel(
                            "service-vpn",
                            application.getText(R.string.service_vpn),
                            if (Build.VERSION.SDK_INT >= 28) NotificationManager.IMPORTANCE_MIN
                            else NotificationManager.IMPORTANCE_LOW
                        ),   // #1355
                        NotificationChannel(
                            "service-proxy",
                            application.getText(R.string.service_proxy),
                            NotificationManager.IMPORTANCE_LOW
                        ), NotificationChannel(
                            "service-vpn-persistent",
                            application.getText(R.string.service_vpn_persistent),
                            NotificationManager.IMPORTANCE_LOW
                        ).apply {
                            setSound(null, null)
                            enableVibration(false)
                            setShowBadge(false)
                        }, NotificationChannel(
                            "service-subscription",
                            application.getText(R.string.service_subscription),
                            NotificationManager.IMPORTANCE_DEFAULT
                        ), NotificationChannel(
                            "connection-test",
                            application.getText(R.string.connection_test),
                            NotificationManager.IMPORTANCE_DEFAULT
                        ), NotificationChannel(
                            "sing-box-authentication",
                            application.getText(R.string.sing_box_authentication),
                            NotificationManager.IMPORTANCE_HIGH
                        )
                    )
                )
            }
        }

        fun startService() {
            CoreRecoveryService.updateStopWatchdog(
                context = application,
                serviceMode = DataStore.serviceMode,
                connectionIntent = ServiceLifecyclePolicy.ConnectionIntent.Start,
            )
            ContextCompat.startForegroundService(
                application,
                Intent(application, SagerConnection.serviceClass)
                    .putExtra(Action.EXTRA_PROFILE_ID, DataStore.selectedProxy)
                    .putExtra(Action.EXTRA_REQUEST_ID, SystemClock.elapsedRealtimeNanos()),
            )
        }

        fun reloadService(profileId: Long = DataStore.selectedProxy) {
            CoreRecoveryService.updateStopWatchdog(
                context = application,
                serviceMode = DataStore.serviceMode,
                connectionIntent = ServiceLifecyclePolicy.ConnectionIntent.Reload,
            )
            ContextCompat.startForegroundService(
                application,
                Intent(application, SagerConnection.serviceClass)
                    .setAction(Action.RELOAD)
                    .putExtra(Action.EXTRA_PROFILE_ID, profileId)
                    .putExtra(Action.EXTRA_REQUEST_ID, SystemClock.elapsedRealtimeNanos()),
            )
        }

        fun stopService() {
            CoreRecoveryService.updateStopWatchdog(
                context = application,
                serviceMode = DataStore.serviceMode,
                connectionIntent = ServiceLifecyclePolicy.ConnectionIntent.Disconnect,
            )
            application.sendBroadcast(
                Intent(Action.CLOSE)
                    .setPackage(application.packageName)
                    .putExtra(Action.EXTRA_REQUEST_ID, SystemClock.elapsedRealtimeNanos())
            )
        }

        fun updateNotificationCountryIndicator(enabled: Boolean) {
            application.sendBroadcast(
                Intent(Action.UPDATE_NOTIFICATION_COUNTRY_INDICATOR)
                    .setPackage(application.packageName)
                    .putExtra(Action.EXTRA_REQUEST_ID, SystemClock.elapsedRealtimeNanos())
                    .putExtra(Action.EXTRA_NOTIFICATION_COUNTRY_INDICATOR_ENABLED, enabled)
            )
        }

        var underlyingNetwork: Network? = null

        var appVersionNameForDisplay = {
            var n = BuildConfig.VERSION_NAME
            if (isPreview) {
                n += " " + BuildConfig.PRE_VERSION_NAME
            } else if (!isOss) {
                n += " ${BuildConfig.FLAVOR}"
            }
            if (BuildConfig.DEBUG) {
                n += " DEBUG"
            }
            n
        }()
    }

}
