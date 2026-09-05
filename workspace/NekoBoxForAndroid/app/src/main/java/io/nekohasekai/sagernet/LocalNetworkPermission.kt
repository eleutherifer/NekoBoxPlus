package io.nekohasekai.sagernet

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal object LocalNetworkPermission {
    const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"

    fun isRequired(
        sdkInt: Int,
        tunImplementation: Int,
        permissionGranted: Boolean,
    ): Boolean {
        if (sdkInt < Build.VERSION_CODES.CINNAMON_BUN || permissionGranted) return false
        return tunImplementation == TunImplementation.SYSTEM ||
            tunImplementation == TunImplementation.MIXED
    }

    fun isRequired(context: Context, tunImplementation: Int): Boolean = isRequired(
        sdkInt = Build.VERSION.SDK_INT,
        tunImplementation = tunImplementation,
        permissionGranted = ContextCompat.checkSelfPermission(
            context,
            NAME,
        ) == PackageManager.PERMISSION_GRANTED,
    )
}
