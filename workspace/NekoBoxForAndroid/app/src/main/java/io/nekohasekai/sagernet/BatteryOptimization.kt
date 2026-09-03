package io.nekohasekai.sagernet

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService
import androidx.core.net.toUri

internal object BatteryOptimization {
    fun shouldRequest(
        sdkInt: Int,
        isIgnoringOptimizations: Boolean,
        wasAsked: Boolean,
    ): Boolean =
        sdkInt >= Build.VERSION_CODES.M && !isIgnoringOptimizations && !wasAsked

    fun isIgnoringOptimizations(context: Context): Boolean {
        return context.getSystemService<PowerManager>()
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    fun shouldRequest(context: Context, wasAsked: Boolean): Boolean = shouldRequest(
        sdkInt = Build.VERSION.SDK_INT,
        isIgnoringOptimizations = isIgnoringOptimizations(context),
        wasAsked = wasAsked,
    )

    // Uninterrupted networking is the VPN's core user-facing function and cannot be
    // replaced by WorkManager, so this is an acceptable direct-exemption use case.
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context) = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        "package:${context.packageName}".toUri(),
    )
}
