package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import org.json.JSONArray

class SwitchActivity : ThemedActivity(R.layout.layout_empty),
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
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment(true, null, R.string.action_switch)
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

    class ClashModeSwitchFragment : ToolbarFragment(R.layout.layout_clash_mode_switch) {

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            toolbar.setTitle(R.string.clash_mode)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
            toolbar.menu.clear()
            toolbar.menu.add(R.string.action_switch).apply {
                setIcon(R.drawable.ic_baseline_view_list_24)
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener {
                    (requireActivity() as SwitchActivity).showServerChooser()
                    true
                }
            }

            val activity = requireActivity() as SwitchActivity
            val currentMode = runCatching { activity.getCurrentClashMode() }.getOrDefault("")
            val modes = runCatching { activity.getClashModeList() }.getOrDefault(emptyList())
            if (modes.isEmpty()) {
                Toast.makeText(requireContext(), R.string.clash_mode_unavailable, Toast.LENGTH_SHORT).show()
                requireActivity().finish()
                return
            }

            view.findViewById<RecyclerView>(R.id.clash_mode_list).apply {
                layoutManager = FixedLinearLayoutManager(this)
                adapter = ClashModeAdapter(activity, modes, currentMode)
            }
        }
    }

    private class ClashModeAdapter(
        private val activity: SwitchActivity,
        private val modes: List<String>,
        currentMode: String,
    ) : RecyclerView.Adapter<ClashModeAdapter.Holder>() {

        private var selectedMode = currentMode
        private var recyclerView: RecyclerView? = null

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            this.recyclerView = recyclerView
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            if (this.recyclerView === recyclerView) {
                this.recyclerView = null
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_clash_mode_item, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val mode = modes[position]
            val selected = mode.equals(selectedMode, ignoreCase = true)
            holder.setSelected(selected)
            holder.title.text = activity.displayClashMode(mode)
            holder.itemView.setOnClickListener {
                selectVisualMode(mode, holder)
                activity.selectClashMode(mode)
            }
        }

        override fun getItemCount(): Int = modes.size

        private fun selectVisualMode(mode: String, holder: Holder) {
            if (mode.equals(selectedMode, ignoreCase = true)) return

            val previousIndex = modes.indexOfFirst { it.equals(selectedMode, ignoreCase = true) }
            selectedMode = mode
            val selectedIndex = modes.indexOfFirst { it.equals(selectedMode, ignoreCase = true) }

            if (previousIndex != -1) {
                (recyclerView?.findViewHolderForAdapterPosition(previousIndex) as? Holder)
                    ?.setSelected(false)
            }
            holder.setSelected(true)
            if (previousIndex != -1) notifyItemChanged(previousIndex)
            if (selectedIndex != -1) notifyItemChanged(selectedIndex)
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val selectedView: LinearLayout = view.findViewById(R.id.selected_view)
            val title: TextView = view.findViewById(R.id.clash_mode_name)

            fun setSelected(selected: Boolean) {
                selectedView.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                itemView.isSelected = selected
            }
        }
    }
}
