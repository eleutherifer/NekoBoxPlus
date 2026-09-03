package io.nekohasekai.sagernet.bg

import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.content.getSystemService
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class CoreRecoveryService : Service() {
    private val binder = Binder()
    private var serviceMode: String? = null
    private var connectionRecoveryArmed = false
    private var disarmed = true
    private var stopWatchdogJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> {
                serviceMode = intent.getStringExtra(EXTRA_SERVICE_MODE)
                connectionRecoveryArmed = intent.getBooleanExtra(EXTRA_CONNECTION_GUARD, false)
                disarmed = false
                DataStore.coreRecoveryExpectedStop = false
                Log.d(TAG, "Core recovery armed: connectionGuard=$connectionRecoveryArmed")
            }

            ACTION_DISARM -> {
                disarmed = true
                connectionRecoveryArmed = false
                DataStore.coreRecoveryExpectedStop = true
                if (stopWatchdogJob == null) {
                    stopSelf()
                }
                Log.d(TAG, "Core recovery disarmed")
            }

            ACTION_ARM_STOP_WATCHDOG -> armStopWatchdog(intent)

            ACTION_CANCEL_STOP_WATCHDOG -> cancelStopWatchdog(intent)
        }
        return START_NOT_STICKY
    }

    private fun armStopWatchdog(intent: Intent) {
        val mode = intent.getStringExtra(EXTRA_SERVICE_MODE)
        if (mode == null) {
            Log.e(TAG, "Rejecting stop watchdog without service mode")
            if (disarmed) {
                stopSelf()
            }
            return
        }
        val requestedPid = intent.getIntExtra(EXTRA_PID, -1)
        val pid = requestedPid.takeIf { it > 0 } ?: findBackgroundProcessPid()
        if (pid == null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Stop watchdog skipped: background process is not running")
            }
            if (disarmed) {
                stopSelf()
            }
            return
        }
        val restartService = intent.getBooleanExtra(EXTRA_RESTART_SERVICE, false)
        DataStore.coreRecoveryExpectedStop = true
        stopWatchdogJob?.cancel()
        stopWatchdogJob = recoveryScope.launch {
            try {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "Stop watchdog armed: pid=$pid restartService=$restartService"
                    )
                }
                delay(STOP_WATCHDOG_TIMEOUT_MILLIS)
                if (!File("/proc/$pid").exists()) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Stop watchdog completed: pid $pid exited normally")
                    }
                    return@launch
                }
                CoreRecoveryCoordinator.recover(
                    context = applicationContext,
                    serviceMode = mode,
                    reason = "service stop timeout",
                    pidToKill = pid,
                    restartService = restartService,
                )
            } finally {
                if (stopWatchdogJob === coroutineContext[Job]) {
                    stopWatchdogJob = null
                    if (disarmed) {
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun cancelStopWatchdog(intent: Intent) {
        val reason = intent.getStringExtra(EXTRA_REASON) ?: "unspecified"
        if (stopWatchdogJob != null && Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Stop watchdog cancelled: reason=$reason")
        }
        stopWatchdogJob?.cancel()
        stopWatchdogJob = null
        if (disarmed) {
            stopSelf()
        }
    }

    private fun findBackgroundProcessPid(): Int? {
        val processName = "$packageName:bg"
        return getSystemService<ActivityManager>()
            ?.runningAppProcesses
            ?.firstOrNull { it.processName == processName }
            ?.pid
    }

    override fun onBind(intent: Intent?): IBinder? {
        serviceMode = intent?.getStringExtra(EXTRA_SERVICE_MODE) ?: serviceMode
        connectionRecoveryArmed = intent?.getBooleanExtra(EXTRA_CONNECTION_GUARD, false) == true
        disarmed = false
        DataStore.coreRecoveryExpectedStop = false
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val mode = serviceMode
        if (
            mode != null &&
            CoreRecoveryPolicy.shouldRecoverOnConnectionLoss(
                connectionGuardEnabled = DataStore.connectionGuard,
                connectionRecoveryArmed = connectionRecoveryArmed,
                disarmed = disarmed,
                expectedStop = DataStore.coreRecoveryExpectedStop,
            )
        ) {
            runCatching {
                startService(armIntent(this, mode, connectionGuard = true))
            }.onFailure { Log.w(TAG, "Unable to keep recovery service alive", it) }
            recoveryScope.launch {
                CoreRecoveryCoordinator.recover(
                    context = applicationContext,
                    serviceMode = mode,
                    reason = "connection guard",
                )
            }
        } else {
            stopSelf()
        }
        return false
    }

    companion object {
        private const val TAG = "CoreRecovery"
        private const val ACTION_ARM = "io.nekohasekai.sagernet.RECOVERY_ARM"
        private const val ACTION_DISARM = "io.nekohasekai.sagernet.RECOVERY_DISARM"
        private const val ACTION_ARM_STOP_WATCHDOG =
            "io.nekohasekai.sagernet.RECOVERY_ARM_STOP_WATCHDOG"
        private const val ACTION_CANCEL_STOP_WATCHDOG =
            "io.nekohasekai.sagernet.RECOVERY_CANCEL_STOP_WATCHDOG"
        private const val EXTRA_SERVICE_MODE = "serviceMode"
        private const val EXTRA_CONNECTION_GUARD = "connectionGuard"
        private const val EXTRA_PID = "pid"
        private const val EXTRA_RESTART_SERVICE = "restartService"
        private const val EXTRA_REASON = "reason"
        private const val STOP_WATCHDOG_TIMEOUT_MILLIS = 10_000L
        private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun armIntent(context: Context, serviceMode: String, connectionGuard: Boolean): Intent {
            return Intent(context, CoreRecoveryService::class.java)
                .setAction(ACTION_ARM)
                .putExtra(EXTRA_SERVICE_MODE, serviceMode)
                .putExtra(EXTRA_CONNECTION_GUARD, connectionGuard)
        }

        fun bindIntent(context: Context, serviceMode: String, connectionGuard: Boolean): Intent {
            return Intent(context, CoreRecoveryService::class.java)
                .putExtra(EXTRA_SERVICE_MODE, serviceMode)
                .putExtra(EXTRA_CONNECTION_GUARD, connectionGuard)
        }

        fun disarmIntent(context: Context): Intent {
            return Intent(context, CoreRecoveryService::class.java).setAction(ACTION_DISARM)
        }

        fun armStopWatchdog(
            context: Context,
            serviceMode: String,
            restartService: Boolean,
            pid: Int = -1,
            reason: String = "disconnect",
        ) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Stop watchdog requested: action=arm reason=$reason")
            }
            runCatching {
                context.startService(
                    Intent(context, CoreRecoveryService::class.java)
                        .setAction(ACTION_ARM_STOP_WATCHDOG)
                        .putExtra(EXTRA_SERVICE_MODE, serviceMode)
                        .putExtra(EXTRA_RESTART_SERVICE, restartService)
                        .putExtra(EXTRA_PID, pid)
                        .putExtra(EXTRA_REASON, reason)
                )
            }.onFailure {
                Log.e(TAG, "Unable to arm stop watchdog", it)
            }
        }

        fun cancelStopWatchdog(context: Context, reason: String = "connection requested") {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Stop watchdog requested: action=cancel reason=$reason")
            }
            runCatching {
                context.startService(
                    Intent(context, CoreRecoveryService::class.java)
                        .setAction(ACTION_CANCEL_STOP_WATCHDOG)
                        .putExtra(EXTRA_REASON, reason)
                )
            }.onFailure {
                Log.e(TAG, "Unable to cancel stop watchdog", it)
            }
        }

        internal fun updateStopWatchdog(
            context: Context,
            serviceMode: String,
            connectionIntent: ServiceLifecyclePolicy.ConnectionIntent,
        ) {
            when (ServiceLifecyclePolicy.stopWatchdogAction(connectionIntent)) {
                ServiceLifecyclePolicy.StopWatchdogAction.Arm -> armStopWatchdog(
                    context = context,
                    serviceMode = serviceMode,
                    restartService = false,
                    reason = connectionIntent.name,
                )
                ServiceLifecyclePolicy.StopWatchdogAction.Cancel -> cancelStopWatchdog(
                    context = context,
                    reason = connectionIntent.name,
                )
            }
        }
    }
}
