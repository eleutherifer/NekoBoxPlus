package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import moe.matsuri.nb4a.ui.ConnectionTestNotification

class ConnectionTestNotificationActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ConnectionTestNotification.ACTION_CANCEL) return

        ConnectionTestNotification.cancel(this)
        GroupConnectionTestController.cancelFromNotification()
    }
}
