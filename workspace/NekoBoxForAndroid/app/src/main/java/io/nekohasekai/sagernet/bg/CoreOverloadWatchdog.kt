package io.nekohasekai.sagernet.bg

import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.Locale

internal class CoreOverloadWatchdog(
    private val onOverload: (Double) -> Unit,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitoringJob: Job? = null

    fun start() {
        if (monitoringJob != null) return
        monitoringJob = scope.launch {
            val detector = CoreOverloadDetector()
            while (isActive) {
                // delay() is intentionally used without a wake lock. Sleeping devices may defer
                // samples; elapsed wall time then prevents sleep from looking like CPU overload.
                detector.addSample(
                    elapsedMillis = SystemClock.elapsedRealtime(),
                    cpuMillis = Process.getElapsedCpuTime(),
                )?.let { cpuPercent ->
                    Log.e(
                        TAG,
                        "sing-box CPU overload detected: ${formatPercent(cpuPercent)}% " +
                            "over ${CoreOverloadDetector.WINDOW_MILLIS / 1000}s; " +
                            "requesting hard recovery for pid ${Process.myPid()}",
                    )
                    onOverload(cpuPercent)
                }
                delay(SAMPLE_INTERVAL_MILLIS)
            }
        }
    }

    override fun close() {
        monitoringJob = null
        scope.cancel()
    }

    private fun formatPercent(value: Double) = String.format(Locale.US, "%.1f", value)

    companion object {
        private const val TAG = "OverloadWatchdog"
        private const val SAMPLE_INTERVAL_MILLIS = 10_000L
    }
}
