package io.nekohasekai.sagernet.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class SpeedTestData(
    val runId: Long = 0L,
    val phase: Int = PHASE_IDLE,
    val progress: Int = 0,
    val downloadRate: Long = 0L,
    val uploadRate: Long = 0L,
    val downloadedBytes: Long = 0L,
    val uploadedBytes: Long = 0L,
    val latencyMilliseconds: Int = 0,
    val serverName: String = "",
    val serverCountry: String = "",
    val usingProxy: Boolean = false,
    val errorCode: String = "",
    val errorMessage: String = "",
) : Parcelable {
    companion object {
        const val PHASE_IDLE = 0
        const val PHASE_FINDING_SERVER = 1
        const val PHASE_DOWNLOAD = 2
        const val PHASE_UPLOAD = 3
        const val PHASE_COMPLETE = 4
        const val PHASE_ERROR = 5
        const val PHASE_CANCELLED = 6
    }

    val isRunning: Boolean
        get() = phase in PHASE_FINDING_SERVER..PHASE_UPLOAD
}
