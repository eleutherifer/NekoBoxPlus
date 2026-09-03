package io.nekohasekai.sagernet.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import io.nekohasekai.sagernet.Action
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CoreRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Action.RECOVER_CORE) return

        val pid = intent.getIntExtra(EXTRA_PID, -1)
        val serviceMode = intent.getStringExtra(EXTRA_SERVICE_MODE)
        val reason = intent.getStringExtra(EXTRA_REASON) ?: "core overload"
        val restartService = intent.getBooleanExtra(EXTRA_RESTART_SERVICE, true)
        if (serviceMode == null) {
            Log.e(TAG, "Rejecting core recovery request without service mode")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                CoreRecoveryCoordinator.recover(
                    context = context,
                    serviceMode = serviceMode,
                    reason = reason,
                    pidToKill = pid,
                    restartService = restartService,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "CoreRecovery"
        private const val EXTRA_PID = "pid"
        private const val EXTRA_SERVICE_MODE = "serviceMode"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_RESTART_SERVICE = "restartService"

        fun request(
            context: Context,
            serviceMode: String,
            reason: String = "core overload",
            restartService: Boolean = true,
        ) {
            context.sendBroadcast(
                Intent(context, CoreRecoveryReceiver::class.java)
                    .setAction(Action.RECOVER_CORE)
                    .putExtra(EXTRA_PID, Process.myPid())
                    .putExtra(EXTRA_SERVICE_MODE, serviceMode)
                    .putExtra(EXTRA_REASON, reason)
                    .putExtra(EXTRA_RESTART_SERVICE, restartService)
            )
        }
    }
}
