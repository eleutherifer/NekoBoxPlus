package io.nekohasekai.sagernet.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.compose.CellularNetworkScreen
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme

class CellularNetworkActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NekoComposeTheme {
                CellularNetworkScreen(
                    onClose = ::finish,
                    onRadioInfoModern = {
                        openFirstAvailable(
                            componentIntent(
                                "com.android.phone",
                                "com.android.phone.settings.RadioInfo",
                            ),
                        )
                    },
                    onRadioInfoLegacy = {
                        openFirstAvailable(
                            componentIntent(
                                "com.android.settings",
                                "com.android.settings.RadioInfo",
                            ),
                            componentIntent(
                                "com.android.settings",
                                "com.android.settings.Settings\$RadioInfoActivity",
                            ),
                        )
                    },
                    onTestingMenu = {
                        openFirstAvailable(
                            componentIntent(
                                "com.android.settings",
                                "com.android.settings.TestingSettings",
                            ),
                            componentIntent(
                                "com.android.settings",
                                "com.android.settings.Settings\$TestingSettingsActivity",
                            ),
                        )
                    },
                    onStandardSettings = {
                        openFirstAvailable(
                            Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
                            Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
                            Intent(Settings.ACTION_WIRELESS_SETTINGS),
                        )
                    },
                )
            }
        }
    }

    private fun componentIntent(packageName: String, className: String) =
        Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(packageName, className)
        }

    private fun openFirstAvailable(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(
            this,
            R.string.cellular_network_method_unavailable,
            Toast.LENGTH_LONG,
        ).show()
    }
}
