package io.nekohasekai.sagernet

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class BootReceiver : BroadcastReceiver() {
    companion object {
        private val componentName by lazy { ComponentName(app, BootReceiver::class.java) }
        var enabled: Boolean
            get() = app.packageManager.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            set(value) = app.packageManager.setComponentEnabledSetting(
                componentName, if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        runOnDefaultDispatcher {
            try {
                SubscriptionUpdater.reconfigureUpdater()
            } finally {
                pendingResult.finish()
            }
        }

        if (BootReceiverPolicy.shouldStartService(
                action = intent.action,
                persistAcrossReboot = DataStore.persistAcrossReboot,
                selectedProxy = DataStore.selectedProxy,
                sdkInt = Build.VERSION.SDK_INT,
                userUnlocked = SagerNet.user.isUserUnlocked,
            )
        ) {
            SagerNet.startService()
        }
    }
}
