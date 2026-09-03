package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.compose.ClashModeItem
import io.nekohasekai.sagernet.ui.compose.ClashModeScreen
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import org.json.JSONArray

class SwitchActivity : ThemedActivity(),
    ConfigurationFragment.SelectCallback,
    SagerConnection.Callback {

    companion object {
        private const val EXTRA_INITIAL_CLASH_MODE = "initialClashMode"

        fun createIntent(context: Context, initialClashMode: Boolean = false): Intent {
            return Intent(context, SwitchActivity::class.java)
                .putExtra(EXTRA_INITIAL_CLASH_MODE, initialClashMode)
        }
    }

    override val isDialog = true
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_SHORTCUT)
    private var service: ISagerNetService? = null
    private var pendingShowClashMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installFragmentHost()
        connection.connect(this, this)

        if (intent.getBooleanExtra(EXTRA_INITIAL_CLASH_MODE, false)) {
            if (DataStore.serviceState.started) {
                pendingShowClashMode = true
            } else {
                Toast.makeText(this, R.string.clash_mode_unavailable, Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            showServerChooser()
        }
    }

    override fun onStart() {
        super.onStart()
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }

    private fun showServerChooser() {
        val selectedProfile = ProfileManager.getProfile(DataStore.selectedProxy)
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment(true, selectedProfile, R.string.action_switch)
            )
            .commitAllowingStateLoss()
    }

    fun showClashModeChooser() {
        if (!canShowClashModeSwitcher()) {
            Toast.makeText(this, R.string.clash_mode_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, ClashModeSwitchFragment())
            .commitAllowingStateLoss()
    }

    fun canShowClashModeSwitcher(): Boolean {
        return runCatching { getClashModeList().size > 1 }.getOrDefault(false)
    }

    fun refreshServerChooserToolbar() {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_holder)
        (fragment as? ConfigurationFragment)?.refreshSelectToolbarMenu()
    }

    fun getCurrentClashMode(): String {
        return service?.currentClashMode().orEmpty()
    }

    fun getClashModeList(): List<String> {
        val raw = service?.clashModeList() ?: return emptyList()
        val array = JSONArray(raw)
        return List(array.length()) { index -> array.getString(index) }
    }

    fun displayClashMode(mode: String): String {
        return if (mode.equals("Rule", ignoreCase = true)) {
            getString(R.string.clash_mode_rule)
        } else {
            mode
        }
    }

    fun selectClashMode(mode: String) {
        runOnDefaultDispatcher {
            try {
                service?.setClashMode(mode)
                runOnMainDispatcher {
                    window.decorView.postOnAnimation {
                        finish()
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                runOnMainDispatcher {
                    Toast.makeText(this@SwitchActivity, e.readableMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun returnProfile(profileId: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = profileId
        runOnMainDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(profileId, true)
        }
        SagerNet.reloadService()
        finish()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        if (!state.started) {
            refreshServerChooserToolbar()
        }
    }

    override fun onServiceConnected(service: ISagerNetService) {
        this.service = service
        if (pendingShowClashMode) {
            pendingShowClashMode = false
            if (canShowClashModeSwitcher()) {
                showClashModeChooser()
            } else {
                Toast.makeText(this, R.string.clash_mode_unavailable, Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }
        refreshServerChooserToolbar()
    }

    override fun onServiceDisconnected() {
        service = null
        refreshServerChooserToolbar()
    }

    class ClashModeSwitchFragment : Fragment() {
        private var selectedMode by mutableStateOf("")

        override fun onCreateView(
            inflater: android.view.LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            val activity = requireActivity() as SwitchActivity
            selectedMode = runCatching { activity.getCurrentClashMode() }.getOrDefault("")
            val modes = runCatching { activity.getClashModeList() }.getOrDefault(emptyList())
            if (modes.isEmpty()) {
                Toast.makeText(requireContext(), R.string.clash_mode_unavailable, Toast.LENGTH_SHORT).show()
                activity.finish()
            }
            val items = modes.map { ClashModeItem(it, activity.displayClashMode(it)) }
            return ComposeView(requireContext()).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    NekoComposeTheme {
                        ClashModeScreen(
                            modes = items,
                            selectedMode = selectedMode,
                            onClose = activity::finish,
                            onShowServers = activity::showServerChooser,
                            onSelect = {
                                if (!it.equals(selectedMode, ignoreCase = true)) {
                                    selectedMode = it
                                    activity.selectClashMode(it)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
