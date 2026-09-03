package io.nekohasekai.sagernet.bg

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.Key
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal object CoreRecoveryCoordinator {
    private const val TAG = "CoreRecovery"
    private const val PROCESS_EXIT_TIMEOUT_MILLIS = 5_000L
    private const val PROCESS_EXIT_POLL_MILLIS = 100L
    private const val RESTART_SETTLE_MILLIS = 500L
    private val recovering = AtomicBoolean(false)

    suspend fun recover(
        context: Context,
        serviceMode: String,
        reason: String,
        pidToKill: Int? = null,
        restartService: Boolean = true,
    ) {
        if (!recovering.compareAndSet(false, true)) return
        try {
            val serviceClass = serviceClassForMode(serviceMode)
            if (serviceClass == null) {
                Log.e(TAG, "Rejecting core recovery request with invalid service mode: $serviceMode")
                return
            }
            if (pidToKill != null) {
                if (pidToKill <= 0 || pidToKill == Process.myPid()) {
                    Log.e(TAG, "Rejecting core recovery request with invalid pid: $pidToKill")
                    return
                }
                Log.w(TAG, "Killing background process pid $pidToKill for $reason")
                Process.killProcess(pidToKill)
                waitForProcessExit(pidToKill)
            } else {
                Log.w(TAG, "Background process lost for $reason")
            }
            if (restartService) {
                delay(RESTART_SETTLE_MILLIS)
                ContextCompat.startForegroundService(context, Intent(context, serviceClass))
                Log.w(TAG, "Restarted ${serviceClass.simpleName} after $reason")
            } else {
                Log.w(TAG, "Stopped background process after $reason")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Core recovery failed after $reason", error)
        } finally {
            recovering.set(false)
        }
    }

    private fun serviceClassForMode(serviceMode: String) = when (serviceMode) {
        Key.MODE_VPN -> VpnService::class.java
        Key.MODE_PROXY -> ProxyService::class.java
        else -> null
    }

    private suspend fun waitForProcessExit(pid: Int) {
        val processDirectory = File("/proc/$pid")
        val deadline = android.os.SystemClock.elapsedRealtime() + PROCESS_EXIT_TIMEOUT_MILLIS
        while (processDirectory.exists() && android.os.SystemClock.elapsedRealtime() < deadline) {
            delay(PROCESS_EXIT_POLL_MILLIS)
        }
        if (processDirectory.exists()) {
            Log.w(TAG, "Background process pid $pid still appears present after timeout")
        }
    }
}
