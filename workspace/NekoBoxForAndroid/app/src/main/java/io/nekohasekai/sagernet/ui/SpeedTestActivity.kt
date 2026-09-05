package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedTestData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.SpeedTestScreen
import kotlinx.coroutines.launch

class SpeedTestActivity : ThemedActivity(), SagerConnection.Callback {
    companion object {
        private const val STATE_RUN_ID = "speed_test_run_id"
    }

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_SPEED_TEST)
    private val snackbarHostState = SnackbarHostState()
    private var service: ISagerNetService? = null
    private var serviceConnected by mutableStateOf(false)
    private var runId = 0L
    private var status by mutableStateOf(SpeedTestData())
    private var settingsDialog by mutableStateOf<SpeedTestSettings?>(null)
    private var showMeteredConfirmation by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runId = savedInstanceState?.getLong(STATE_RUN_ID) ?: 0L
        setContent {
            NekoComposeTheme {
                SpeedTestScreen(
                    status = status,
                    actionEnabled = serviceConnected,
                    settings = settingsDialog,
                    showMeteredConfirmation = showMeteredConfirmation,
                    snackbarHostState = snackbarHostState,
                    onBack = ::finish,
                    onShowSettings = { settingsDialog = loadSettings() },
                    onDismissSettings = { settingsDialog = null },
                    onSaveSettings = {
                        saveSettings(it)
                        settingsDialog = null
                    },
                    onAction = {
                        if (status.isRunning) stopTest() else confirmAndStartTest()
                    },
                    onDismissMeteredConfirmation = { showMeteredConfirmation = false },
                    onConfirmMetered = {
                        showMeteredConfirmation = false
                        startTest()
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connection.connect(this, this)
    }

    override fun onStop() {
        if (!isChangingConfigurations && status.isRunning) {
            service?.stopSpeedTest(runId)
            render(status.copy(phase = SpeedTestData.PHASE_CANCELLED, progress = 0))
        }
        connection.disconnect(this)
        service = null
        serviceConnected = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_RUN_ID, runId)
        super.onSaveInstanceState(outState)
    }

    override fun onServiceConnected(service: ISagerNetService) {
        this.service = service
        serviceConnected = true
        val current = runCatching { service.speedTestStatus() }.getOrNull()
        if (current != null && runId != 0L && current.runId == runId) render(current)
    }

    override fun onServiceDisconnected() {
        service = null
        serviceConnected = false
    }

    override fun onBinderDied() {
        onServiceDisconnected()
        if (status.isRunning) {
            render(
                status.copy(
                    phase = SpeedTestData.PHASE_ERROR,
                    errorCode = "service_unavailable",
                ),
            )
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) = Unit

    override fun cbSpeedTestUpdate(status: SpeedTestData) {
        if (status.runId == runId) render(status)
    }

    private fun confirmAndStartTest() {
        if (SagerNet.connectivity.activeNetwork == null) {
            showMessage(R.string.speed_test_no_network)
            return
        }
        if (SagerNet.connectivity.isActiveNetworkMetered) {
            showMeteredConfirmation = true
        } else {
            startTest()
        }
    }

    private fun startTest() {
        val activeService = service
        if (activeService == null) {
            showMessage(R.string.speed_test_service_unavailable)
            return
        }
        val settings = loadSettings()
        if (!validateSpeedTestSettings(settings)) {
            showMessage(R.string.speed_test_invalid_settings)
            return
        }
        runId = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        render(SpeedTestData(runId = runId, phase = SpeedTestData.PHASE_FINDING_SERVER))
        activeService.startSpeedTest(
            runId,
            settings.durationMillis,
            settings.connections,
            settings.serverMode,
            settings.serverValue,
            settings.finalResult,
        )
    }

    private fun stopTest() {
        service?.stopSpeedTest(runId)
        render(status.copy(phase = SpeedTestData.PHASE_CANCELLED, progress = 0))
    }

    private fun render(newStatus: SpeedTestData) {
        status = newStatus
    }

    private fun loadSettings() = SpeedTestSettings(
        durationMillis = DataStore.speedTestDuration,
        connections = DataStore.speedTestConnections,
        serverMode = DataStore.speedTestServerMode,
        serverValue = DataStore.speedTestServerValue,
        finalResult = DataStore.speedTestFinalResult,
    )

    private fun saveSettings(settings: SpeedTestSettings) {
        DataStore.speedTestDuration = settings.durationMillis
        DataStore.speedTestConnections = settings.connections
        DataStore.speedTestServerMode = settings.serverMode
        DataStore.speedTestServerValue = settings.serverValue
        DataStore.speedTestFinalResult = settings.finalResult
    }

    private fun showMessage(message: Int) {
        lifecycleScope.launch { snackbarHostState.showSnackbar(getString(message)) }
    }
}
