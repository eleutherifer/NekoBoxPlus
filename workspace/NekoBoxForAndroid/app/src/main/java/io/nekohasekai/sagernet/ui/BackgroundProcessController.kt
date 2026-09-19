package io.nekohasekai.sagernet.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.widget.Toast
import androidx.core.content.getSystemService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.CoreRecoveryService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import kotlinx.coroutines.delay

object BackgroundProcessController {
    fun confirmKill(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.kill_background_process)
            .setMessage(R.string.kill_background_process_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.kill_background_process) { _, _ ->
                killAndStayStopped(context.applicationContext)
            }
            .show()
    }

    private fun killAndStayStopped(context: Context) {
        runOnDefaultDispatcher {
            DataStore.coreRecoveryExpectedStop = true
            runCatching { context.startService(CoreRecoveryService.disarmIntent(context)) }
            SagerNet.stopService()
            delay(750)

            val processName = "${context.packageName}:bg"
            val pid = context.getSystemService<ActivityManager>()
                ?.runningAppProcesses
                ?.firstOrNull { it.processName == processName }
                ?.pid
            if (pid != null && pid > 0 && pid != Process.myPid()) {
                Process.killProcess(pid)
            }
            runOnMainDispatcher {
                Toast.makeText(
                    context,
                    if (pid == null) {
                        R.string.background_process_not_running
                    } else {
                        R.string.background_process_killed
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}
