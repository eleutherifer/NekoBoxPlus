package io.nekohasekai.sagernet.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutCellularNetworkBinding

class CellularNetworkActivity : ThemedActivity() {

    private lateinit var binding: LayoutCellularNetworkBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutCellularNetworkBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.cellular_network_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.cellularNetworkScroll) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }

        binding.radioInfoModern.setOnClickListener {
            openFirstAvailable(
                componentIntent(
                    "com.android.phone",
                    "com.android.phone.settings.RadioInfo",
                ),
            )
        }
        binding.radioInfoLegacy.setOnClickListener {
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
        }
        binding.testingMenu.setOnClickListener {
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
        }
        binding.mobileNetworkSettings.setOnClickListener {
            openFirstAvailable(
                Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
                Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
            )
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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
