package io.nekohasekai.sagernet.ui

import android.os.Build

internal object LegacyMainViewPolicy {
    fun defaultEnabled(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt <= Build.VERSION_CODES.O_MR1
}
