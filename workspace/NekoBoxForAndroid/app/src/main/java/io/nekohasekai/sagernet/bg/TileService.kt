package io.nekohasekai.sagernet.bg

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import android.service.quicksettings.TileService as BaseTileService

@RequiresApi(24)
class TileService : BaseTileService(), SagerConnection.Callback {
    private val iconIdle by lazy { Icon.createWithResource(this, R.drawable.ic_service_idle) }
    private val iconBusy by lazy { Icon.createWithResource(this, R.drawable.ic_service_busy) }
    private val iconConnected by lazy {
        Icon.createWithResource(this, R.drawable.ic_service_active)
    }

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_TILE)
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) =
        updateTile(state, profileName)

    override fun onServiceConnected(service: ISagerNetService) {
        updateTile(BaseService.State.values()[service.state], service.profileName)
    }

    override fun cbSelectorUpdate(id: Long) {
        val profile = SagerDatabase.proxyDao.getById(id) ?: return
        updateTile(BaseService.State.Connected, profile.displayName())
    }

    override fun onStartListening() {
        super.onStartListening()
        connection.connect(this, this)
    }

    override fun onStopListening() {
        connection.disconnect(this)
        super.onStopListening()
    }

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(this::toggle) else toggle()
    }

    private fun updateTile(serviceState: BaseService.State, profileName: String?) {
        qsTile?.apply {
            label = null
            when (serviceState) {
                BaseService.State.Idle -> error("serviceState")
                BaseService.State.Connecting -> {
                    icon = iconBusy
                    state = Tile.STATE_ACTIVE
                }

                BaseService.State.Connected -> {
                    icon = iconConnected
                    label = profileName
                    state = Tile.STATE_ACTIVE
                }

                BaseService.State.Stopping -> {
                    icon = iconBusy
                    state = Tile.STATE_UNAVAILABLE
                }

                BaseService.State.Stopped -> {
                    icon = iconIdle
                    state = Tile.STATE_INACTIVE
                }
            }
            label = label ?: getString(R.string.app_name)
            updateTile()
        }
    }

    private fun toggle() {
        // The tile and proxy services run in :bg. Use their live local state so a
        // cold-start tap does not depend on an asynchronous binding surviving
        // onStopListening (for example, while the user unlocks the device).
        val state = DataStore.baseService?.data?.state ?: BaseService.State.Stopped
        when {
            state.canStop -> SagerNet.stopService()
            state == BaseService.State.Stopped -> requestStart()
        }
    }

    // The Intent overload is used only below API 34; lint flags it even in this guarded branch.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun requestStart() {
        // Share the main app's direct-start path when no permission prompts are
        // needed. A tile tap permits starting the foreground service directly;
        // launching an activity unconditionally flashes the app and closes QS.
        if (VpnRequestActivity.StartService().getSynchronousResult(this, null) != null) return

        // Only open the permission activity when user interaction is required.
        val intent = Intent(this, VpnRequestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
