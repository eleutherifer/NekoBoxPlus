package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import io.nekohasekai.sagernet.BatteryOptimization
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.LocalNetworkPermission
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.broadcastReceiver

class VpnRequestActivity : AppCompatActivity() {
    private var receiver: BroadcastReceiver? = null
    private var connectionFlowStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionFlowStarted = savedInstanceState?.getBoolean(STATE_CONNECTION_FLOW_STARTED) == true
        if (connectionFlowStarted) return
        if (getSystemService<KeyguardManager>()!!.isKeyguardLocked) {
            receiver = broadcastReceiver { _, _ -> startConnectionFlow() }
            if (SDK_INT >= 33) {
                registerReceiver(
                    receiver,
                    IntentFilter(Intent.ACTION_USER_PRESENT),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            }
        } else startConnectionFlow()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_CONNECTION_FLOW_STARTED, connectionFlowStarted)
    }

    private val requestLocalNetworkPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) DataStore.tunImplementation = TunImplementation.GVISOR
        requestVpnPermission()
    }

    private val requestBatteryOptimization = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (!BatteryOptimization.isIgnoringOptimizations(this)) {
            Toast.makeText(
                this,
                R.string.battery_optimization_declined_hint,
                Toast.LENGTH_LONG,
            ).show()
        }
        continueConnectionFlow()
    }

    private val requestVpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startServiceAndFinish()
        } else {
            Logs.e("Failed to start VpnService: ${result.data}")
            finishWithVpnPermissionDenied()
        }
    }

    private fun startConnectionFlow() {
        if (connectionFlowStarted) return
        connectionFlowStarted = true
        if (
            BatteryOptimization.shouldRequest(
                this,
                DataStore.batteryOptimizationPromptAsked,
            )
        ) {
            DataStore.batteryOptimizationPromptAsked = true
            runCatching {
                requestBatteryOptimization.launch(BatteryOptimization.requestIntent(this))
            }.onFailure { error ->
                Logs.w("Unable to request battery optimization exemption: ${error.message}")
                continueConnectionFlow()
            }
            return
        }
        continueConnectionFlow()
    }

    private fun continueConnectionFlow() {
        if (
            DataStore.serviceMode == Key.MODE_VPN &&
            LocalNetworkPermission.isRequired(this, DataStore.tunImplementation)
        ) {
            requestLocalNetworkPermission.launch(LocalNetworkPermission.NAME)
        } else {
            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        if (DataStore.serviceMode == Key.MODE_VPN) {
            VpnService.prepare(this)?.let {
                requestVpnPermissionLauncher.launch(it)
                return
            }
        }
        startServiceAndFinish()
    }

    private fun startServiceAndFinish() {
        SagerNet.startService()
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun finishWithVpnPermissionDenied() {
        setResult(Activity.RESULT_CANCELED)
        if (!intent.getBooleanExtra(EXTRA_CALLER_HANDLES_DENIAL, false)) {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiver != null) unregisterReceiver(receiver)
    }

    class StartService : ActivityResultContract<Void?, Boolean>() {
        override fun getSynchronousResult(
            context: Context,
            input: Void?,
        ): SynchronousResult<Boolean>? {
            if (
                BatteryOptimization.shouldRequest(
                    context,
                    DataStore.batteryOptimizationPromptAsked,
                )
            ) {
                return null
            }
            if (DataStore.serviceMode == Key.MODE_VPN) {
                if (
                    LocalNetworkPermission.isRequired(context, DataStore.tunImplementation) ||
                    VpnService.prepare(context) != null
                ) {
                    return null
                }
            }
            SagerNet.startService()
            return SynchronousResult(false)
        }

        override fun createIntent(context: Context, input: Void?) = Intent(
            context,
            VpnRequestActivity::class.java,
        ).putExtra(EXTRA_CALLER_HANDLES_DENIAL, true)

        override fun parseResult(resultCode: Int, intent: Intent?) = resultCode != Activity.RESULT_OK
    }

    companion object {
        private const val STATE_CONNECTION_FLOW_STARTED = "connectionFlowStarted"
        private const val EXTRA_CALLER_HANDLES_DENIAL = "callerHandlesDenial"
    }

}
