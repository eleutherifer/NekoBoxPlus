package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedTestData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutSpeedTestBinding
import io.nekohasekai.sagernet.databinding.LayoutSpeedTestSettingsBinding

class SpeedTestActivity : ThemedActivity(), SagerConnection.Callback {
    companion object {
        private const val STATE_RUN_ID = "speed_test_run_id"
    }

    private lateinit var binding: LayoutSpeedTestBinding
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_SPEED_TEST)
    private var service: ISagerNetService? = null
    private var runId = 0L
    private var status = SpeedTestData()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutSpeedTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.speed_test)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }
        runId = savedInstanceState?.getLong(STATE_RUN_ID) ?: 0L
        binding.speedTestAction.isEnabled = false
        binding.speedTestAction.setOnClickListener {
            if (status.isRunning) {
                stopTest()
            } else {
                confirmAndStartTest()
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
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
        render(SpeedTestData())
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
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_RUN_ID, runId)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.speed_test_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_speed_test_settings -> {
            showSettings()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onServiceConnected(service: ISagerNetService) {
        this.service = service
        binding.speedTestAction.isEnabled = true
        val current = runCatching { service.speedTestStatus() }.getOrNull()
        if (current != null && runId != 0L && current.runId == runId) {
            render(current)
        }
    }

    override fun onServiceDisconnected() {
        service = null
        binding.speedTestAction.isEnabled = false
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

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG)

    private fun confirmAndStartTest() {
        if (SagerNet.connectivity.activeNetwork == null) {
            snackbar(R.string.speed_test_no_network).show()
            return
        }
        if (SagerNet.connectivity.isActiveNetworkMetered) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.speed_test_metered_title)
                .setMessage(R.string.speed_test_metered_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.speed_test_continue) { _, _ -> startTest() }
                .show()
        } else {
            startTest()
        }
    }

    private fun startTest() {
        val activeService = service
        if (activeService == null) {
            snackbar(R.string.speed_test_service_unavailable).show()
            return
        }
        val settings = loadSettings()
        if (!validateSpeedTestSettings(settings)) {
            snackbar(R.string.speed_test_invalid_settings).show()
            return
        }
        runId = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        render(
            SpeedTestData(
                runId = runId,
                phase = SpeedTestData.PHASE_FINDING_SERVER,
            ),
        )
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
        binding.speedTestPhase.setText(
            when (newStatus.phase) {
                SpeedTestData.PHASE_FINDING_SERVER -> R.string.speed_test_finding_server
                SpeedTestData.PHASE_DOWNLOAD -> R.string.speed_test_downloading
                SpeedTestData.PHASE_UPLOAD -> R.string.speed_test_uploading
                SpeedTestData.PHASE_COMPLETE -> R.string.speed_test_complete
                SpeedTestData.PHASE_ERROR -> R.string.speed_test_failed
                SpeedTestData.PHASE_CANCELLED -> R.string.speed_test_cancelled
                else -> R.string.speed_test_ready
            },
        )
        binding.speedTestProgress.apply {
            isIndeterminate = newStatus.phase == SpeedTestData.PHASE_FINDING_SERVER
            if (!isIndeterminate) progress = newStatus.progress
        }
        binding.downloadRate.text = formatSpeedBits(newStatus.downloadRate)
        binding.uploadRate.text = formatSpeedBits(newStatus.uploadRate)
        binding.latencyValue.text = if (newStatus.latencyMilliseconds > 0) {
            getString(R.string.speed_test_latency, newStatus.latencyMilliseconds)
        } else {
            getString(R.string.speed_test_latency_empty)
        }
        binding.transferredValue.text = if (
            newStatus.downloadedBytes > 0 || newStatus.uploadedBytes > 0
        ) {
            getString(
                R.string.speed_test_transferred,
                Formatter.formatFileSize(this, newStatus.downloadedBytes),
                Formatter.formatFileSize(this, newStatus.uploadedBytes),
            )
        } else {
            getString(R.string.speed_test_transferred_empty)
        }
        binding.serverValue.text = when {
            newStatus.serverName.isBlank() -> getString(R.string.speed_test_server_empty)
            newStatus.serverCountry.isBlank() || newStatus.serverCountry == "?" ->
                getString(R.string.speed_test_server, newStatus.serverName)
            else -> getString(
                R.string.speed_test_server_with_country,
                newStatus.serverName,
                newStatus.serverCountry,
            )
        }
        binding.routeValue.text = if (newStatus.runId == 0L) {
            getString(R.string.speed_test_route_empty)
        } else {
            getString(
                R.string.speed_test_route,
                getString(
                    if (newStatus.usingProxy) {
                        R.string.speed_test_route_proxy
                    } else {
                        R.string.speed_test_route_direct
                    },
                ),
            )
        }
        val errorText = localizedSpeedTestError(newStatus)
        binding.speedTestError.isVisible = errorText.isNotBlank()
        binding.speedTestError.text = errorText
        binding.speedTestAction.apply {
            setText(
                when {
                    newStatus.isRunning -> R.string.stop
                    newStatus.phase in SpeedTestData.PHASE_COMPLETE..SpeedTestData.PHASE_CANCELLED ->
                        R.string.speed_test_again
                    else -> R.string.start
                },
            )
        }
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

    private fun showSettings() {
        val dialogBinding = LayoutSpeedTestSettingsBinding.inflate(layoutInflater)
        val labels = listOf(
            getString(R.string.speed_test_server_auto),
            getString(R.string.speed_test_server_id),
            getString(R.string.speed_test_server_search),
            getString(R.string.speed_test_server_custom),
        )
        val finalResultLabels = listOf(
            getString(R.string.speed_test_final_average),
            getString(R.string.speed_test_final_last),
            getString(R.string.speed_test_final_minimum),
            getString(R.string.speed_test_final_maximum),
        )
        val initialSettings = loadSettings()
        var selectedMode = initialSettings.serverMode.coerceIn(0, labels.lastIndex)
        var selectedFinalResult =
            initialSettings.finalResult.coerceIn(0, finalResultLabels.lastIndex)

        fun updateServerValueField(mode: Int) {
            dialogBinding.serverValueLayout.isVisible = mode != SPEED_TEST_SERVER_AUTO
            dialogBinding.serverValueLayout.hint = when (mode) {
                SPEED_TEST_SERVER_ID -> getString(R.string.speed_test_server_id_hint)
                SPEED_TEST_SERVER_SEARCH -> getString(R.string.speed_test_server_search_hint)
                SPEED_TEST_SERVER_CUSTOM -> getString(R.string.speed_test_server_custom_hint)
                else -> ""
            }
            dialogBinding.serverValue.inputType = when (mode) {
                SPEED_TEST_SERVER_ID -> InputType.TYPE_CLASS_NUMBER
                SPEED_TEST_SERVER_CUSTOM ->
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                else -> InputType.TYPE_CLASS_TEXT
            }
        }

        fun populate(settings: SpeedTestSettings) {
            selectedMode = settings.serverMode
            selectedFinalResult = settings.finalResult
            dialogBinding.serverMode.setText(labels[selectedMode], false)
            dialogBinding.finalResult.setText(finalResultLabels[selectedFinalResult], false)
            dialogBinding.serverValue.setText(settings.serverValue)
            dialogBinding.duration.setText((settings.durationMillis / 1000).toString())
            dialogBinding.connections.setText(settings.connections.toString())
            dialogBinding.durationLayout.error = null
            dialogBinding.connectionsLayout.error = null
            dialogBinding.serverValueLayout.error = null
            updateServerValueField(selectedMode)
        }

        dialogBinding.serverMode.setSimpleItems(labels.toTypedArray())
        dialogBinding.serverMode.setOnItemClickListener { _, _, position, _ ->
            selectedMode = position
            updateServerValueField(position)
        }
        dialogBinding.finalResult.setSimpleItems(finalResultLabels.toTypedArray())
        dialogBinding.finalResult.setOnItemClickListener { _, _, position, _ ->
            selectedFinalResult = position
        }
        populate(initialSettings)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.speed_test_settings)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.speed_test_reset, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                populate(SpeedTestSettings())
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val settings = SpeedTestSettings(
                    durationMillis = (dialogBinding.duration.text?.toString()?.toIntOrNull() ?: 0) * 1000,
                    connections = dialogBinding.connections.text?.toString()?.toIntOrNull() ?: 0,
                    serverMode = selectedMode,
                    serverValue = dialogBinding.serverValue.text?.toString()?.trim().orEmpty(),
                    finalResult = selectedFinalResult,
                )
                dialogBinding.durationLayout.error =
                    if (settings.durationMillis !in 1000..30000) {
                        getString(R.string.speed_test_duration_range)
                    } else {
                        null
                    }
                dialogBinding.connectionsLayout.error =
                    if (settings.connections !in 1..16) {
                        getString(R.string.speed_test_connections_range)
                    } else {
                        null
                    }
                dialogBinding.serverValueLayout.error =
                    if (!validateSpeedTestSettings(settings) &&
                        settings.durationMillis in 1000..30000 &&
                        settings.connections in 1..16
                    ) {
                        getString(R.string.speed_test_invalid_settings)
                    } else {
                        null
                    }
                if (validateSpeedTestSettings(settings)) {
                    saveSettings(settings)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun localizedSpeedTestError(status: SpeedTestData): String = when (status.errorCode) {
        "" -> ""
        "no_server" -> getString(R.string.speed_test_error_no_server)
        "no_reachable_server" -> getString(R.string.speed_test_error_no_reachable_server)
        "server_list_failed" -> getString(R.string.speed_test_error_server_list)
        "server_list_timeout" -> getString(R.string.speed_test_error_server_timeout)
        "latency_failed" -> getString(R.string.speed_test_error_latency)
        "download_failed" -> getString(R.string.speed_test_error_download)
        "upload_failed" -> getString(R.string.speed_test_error_upload)
        "invalid_configuration" -> getString(R.string.speed_test_error_invalid_configuration)
        "proxy_changing" -> getString(R.string.speed_test_proxy_changing)
        "service_unavailable" -> getString(R.string.speed_test_service_unavailable)
        else -> getString(
            R.string.speed_test_error_generic,
            status.errorMessage.ifBlank { status.errorCode },
        )
    }
}
