package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutStunBinding
import io.nekohasekai.sagernet.databinding.LayoutStunServerResultBinding
import kotlinx.coroutines.launch

class StunActivity : ThemedActivity() {

    private lateinit var binding: LayoutStunBinding
    private val viewModel: StunTestViewModel by viewModels()
    private var selectedPreset = StunPreset.BALANCED
    private var detailsExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutStunBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.stun_test)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }

        configureInsets()
        configurePresets()
        binding.customServers.setText(DataStore.stunTestCustomServers)
        binding.customServers.doOnTextChanged { _, _, _, _ ->
            binding.customServersLayout.error = null
        }
        binding.stunTestAction.setOnClickListener {
            if (viewModel.uiState.value.isRunning) viewModel.cancel() else startTest()
        }
        binding.toggleDetails.setOnClickListener {
            detailsExpanded = !detailsExpanded
            renderDetailsVisibility(viewModel.uiState.value)
        }
        observeState()
    }

    override fun onPause() {
        persistValidCustomServers()
        super.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.stunScroll) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun configurePresets() {
        val presets = StunPreset.entries
        val labels = presets.map { getString(it.titleRes) }
        binding.stunPreset.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels),
        )
        selectedPreset = StunPreset.fromValue(DataStore.stunTestPreset)
        binding.stunPreset.setText(getString(selectedPreset.titleRes), false)
        binding.stunPreset.setOnItemClickListener { _, _, position, _ ->
            selectPreset(presets[position])
        }
        selectPreset(selectedPreset)
    }

    private fun selectPreset(preset: StunPreset) {
        selectedPreset = preset
        DataStore.stunTestPreset = preset.value
        binding.customServersLayout.isVisible = preset == StunPreset.CUSTOM
        binding.stunPresetDescription.setText(
            when (preset) {
                StunPreset.BALANCED -> R.string.stun_preset_balanced_description
                StunPreset.FULL -> R.string.stun_preset_full_description
                StunPreset.FAST -> R.string.stun_preset_fast_description
                StunPreset.CUSTOM -> R.string.stun_preset_custom_description
            },
        )
    }

    private fun startTest() {
        val servers = if (selectedPreset == StunPreset.CUSTOM) {
            when (val parsed = StunServerListParser.parse(binding.customServers.text?.toString().orEmpty())) {
                is StunServerParseResult.Valid -> {
                    DataStore.stunTestCustomServers = parsed.servers.joinToString("\n")
                    parsed.servers
                }
                is StunServerParseResult.Invalid -> {
                    binding.customServersLayout.error = customServerError(parsed)
                    binding.customServers.requestFocus()
                    return
                }
            }
        } else {
            selectedPreset.servers
        }
        viewModel.start(servers, DataStore.ipv6Mode)
    }

    private fun persistValidCustomServers() {
        val parsed = StunServerListParser.parse(binding.customServers.text?.toString().orEmpty())
        if (parsed is StunServerParseResult.Valid) {
            DataStore.stunTestCustomServers = parsed.servers.joinToString("\n")
        }
    }

    private fun customServerError(error: StunServerParseResult.Invalid): String = when (error.reason) {
        StunServerParseResult.Reason.EMPTY -> getString(R.string.stun_custom_error_empty)
        StunServerParseResult.Reason.FORMAT ->
            getString(R.string.stun_custom_error_format, error.line)
        StunServerParseResult.Reason.PORT ->
            getString(R.string.stun_custom_error_port, error.line)
        StunServerParseResult.Reason.TOO_MANY ->
            getString(R.string.stun_custom_error_too_many, StunServerListParser.MAX_SERVERS)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun render(state: StunUiState) {
        binding.progressContainer.isVisible = state.isRunning
        binding.progressText.text = if (state.isRunning) {
            resources.getQuantityString(
                R.plurals.stun_testing_servers,
                activeServerCount(),
                activeServerCount(),
            )
        } else {
            ""
        }
        binding.stunPresetLayout.isEnabled = !state.isRunning
        binding.customServersLayout.isEnabled = !state.isRunning
        binding.stunTestAction.apply {
            setText(if (state.isRunning) R.string.cancel else R.string.start)
            setIconResource(
                if (state.isRunning) R.drawable.ic_navigation_close
                else R.drawable.ic_baseline_play_arrow_24,
            )
        }

        val assessment = state.assessment
        binding.summaryCard.isVisible = assessment != null
        if (assessment != null) {
            binding.summaryTitle.setText(assessmentTitle(assessment.kind))
            binding.summaryImpact.setText(assessmentImpact(assessment.kind))
            binding.technicalResult.text = technicalSummary(state.results, assessment)
            renderServerResults(state.results)
        }
        renderDetailsVisibility(state)
    }

    private fun renderDetailsVisibility(state: StunUiState) {
        binding.detailsContainer.isVisible = detailsExpanded && state.assessment != null
        binding.toggleDetails.apply {
            setText(if (detailsExpanded) R.string.stun_hide_details else R.string.stun_show_details)
        }
    }

    private fun renderServerResults(results: List<StunServerUiResult>) {
        binding.serverResults.removeAllViews()
        results.forEach { result ->
            val item = LayoutStunServerResultBinding.inflate(
                layoutInflater,
                binding.serverResults,
                false,
            )
            item.serverName.text = result.server.ifBlank { getString(R.string.stun_unknown_server) }
            item.serverStatus.setText(
                when {
                    result.behaviorComplete -> R.string.stun_server_status_complete
                    result.bindingSuccess -> R.string.stun_server_status_partial
                    result.errorCode == "cancelled" -> R.string.stun_server_status_cancelled
                    else -> R.string.stun_server_status_failed
                },
            )
            item.serverDetails.text = serverDetails(result)
            binding.serverResults.addView(item.root)
        }
    }

    private fun technicalSummary(
        results: List<StunServerUiResult>,
        assessment: StunAssessment,
    ): String {
        val representative = assessment.representative
        val lines = mutableListOf<String>()
        if (representative != null) {
            lines += getString(
                R.string.stun_mapping_value,
                behaviorLabel(representative.mappingBehavior),
            )
            lines += getString(
                R.string.stun_filtering_value,
                behaviorLabel(representative.filteringBehavior),
            )
            lines += getString(R.string.stun_nat_type_value, natLabel(representative.natType))
        }
        val endpoints = results.filter { it.bindingSuccess }
            .map { formatEndpoint(it.externalAddress, it.externalPort) }
            .distinct()
        if (endpoints.isNotEmpty()) {
            lines += getString(R.string.stun_external_endpoints_value, endpoints.joinToString())
        }
        lines += getString(
            R.string.stun_successful_servers_value,
            results.count { it.bindingSuccess },
            results.size,
        )
        return lines.joinToString("\n")
    }

    private fun serverDetails(result: StunServerUiResult): String {
        val lines = mutableListOf(
            getString(R.string.stun_duration_value, result.durationMilliseconds),
        )
        if (result.bindingSuccess) {
            lines += getString(
                R.string.stun_external_address_value,
                formatEndpoint(result.externalAddress, result.externalPort),
                if (result.ipFamily == 2) "IPv6" else "IPv4",
            )
            lines += getString(R.string.stun_nat_type_value, natLabel(result.natType))
        }
        if (result.mappingBehavior != StunProtocolCodes.BEHAVIOR_UNKNOWN) {
            lines += getString(
                R.string.stun_mapping_value,
                behaviorLabel(result.mappingBehavior),
            )
        }
        if (result.filteringBehavior != StunProtocolCodes.BEHAVIOR_UNKNOWN) {
            lines += getString(
                R.string.stun_filtering_value,
                behaviorLabel(result.filteringBehavior),
            )
        }
        if (result.errorCode.isNotBlank()) {
            lines += getString(R.string.stun_issue_value, errorLabel(result.errorCode))
            if (result.errorMessage.isNotBlank()) {
                lines += getString(R.string.stun_diagnostic_value, result.errorMessage)
            }
        }
        if (result.warningCode == "response_address_mismatch") {
            lines += getString(
                R.string.stun_warning_value,
                getString(R.string.stun_warning_response_address_mismatch),
            )
        }
        return lines.joinToString("\n")
    }

    private fun activeServerCount(): Int =
        if (selectedPreset == StunPreset.CUSTOM) {
            (StunServerListParser.parse(binding.customServers.text?.toString().orEmpty())
                as? StunServerParseResult.Valid)?.servers?.size ?: 0
        } else {
            selectedPreset.servers.size
        }

    private fun formatEndpoint(address: String, port: Int): String =
        if (address.contains(':')) "[$address]:$port" else "$address:$port"

    private fun behaviorLabel(value: Int): String = getString(
        when (value) {
            StunProtocolCodes.BEHAVIOR_ENDPOINT -> R.string.stun_behavior_endpoint
            StunProtocolCodes.BEHAVIOR_ADDRESS -> R.string.stun_behavior_address
            StunProtocolCodes.BEHAVIOR_ADDRESS_AND_PORT ->
                R.string.stun_behavior_address_and_port
            else -> R.string.stun_value_unknown
        },
    )

    private fun natLabel(value: Int): String = getString(
        when (value) {
            StunProtocolCodes.NAT_NONE -> R.string.stun_nat_open
            StunProtocolCodes.NAT_BLOCKED -> R.string.stun_nat_blocked
            StunProtocolCodes.NAT_FULL -> R.string.stun_nat_full
            StunProtocolCodes.NAT_SYMMETRIC -> R.string.stun_nat_symmetric
            StunProtocolCodes.NAT_RESTRICTED -> R.string.stun_nat_restricted
            StunProtocolCodes.NAT_PORT_RESTRICTED -> R.string.stun_nat_port_restricted
            StunProtocolCodes.NAT_SYMMETRIC_UDP_FIREWALL ->
                R.string.stun_nat_symmetric_firewall
            else -> R.string.stun_value_unknown
        },
    )

    private fun errorLabel(code: String): String = getString(
        when (code) {
            "behavior_unsupported" -> R.string.stun_error_behavior_unsupported
            "dns" -> R.string.stun_error_dns
            "timeout", "deadline" -> R.string.stun_error_timeout
            "cancelled" -> R.string.stun_server_status_cancelled
            "configuration" -> R.string.stun_error_configuration
            else -> R.string.stun_error_network
        },
    )

    private fun assessmentTitle(kind: StunAssessmentKind): Int = when (kind) {
        StunAssessmentKind.FAVORABLE -> R.string.stun_assessment_favorable
        StunAssessmentKind.MODERATE -> R.string.stun_assessment_moderate
        StunAssessmentKind.RESTRICTIVE -> R.string.stun_assessment_restrictive
        StunAssessmentKind.OPEN -> R.string.stun_assessment_open
        StunAssessmentKind.INCONSISTENT -> R.string.stun_assessment_inconsistent
        StunAssessmentKind.BASIC_ONLY -> R.string.stun_assessment_basic
        StunAssessmentKind.FAILED -> R.string.stun_assessment_failed
        StunAssessmentKind.CANCELLED -> R.string.stun_assessment_cancelled
    }

    private fun assessmentImpact(kind: StunAssessmentKind): Int = when (kind) {
        StunAssessmentKind.FAVORABLE -> R.string.stun_impact_favorable
        StunAssessmentKind.MODERATE -> R.string.stun_impact_moderate
        StunAssessmentKind.RESTRICTIVE -> R.string.stun_impact_restrictive
        StunAssessmentKind.OPEN -> R.string.stun_impact_open
        StunAssessmentKind.INCONSISTENT -> R.string.stun_impact_inconsistent
        StunAssessmentKind.BASIC_ONLY -> R.string.stun_impact_basic
        StunAssessmentKind.FAILED -> R.string.stun_impact_failed
        StunAssessmentKind.CANCELLED -> R.string.stun_impact_cancelled
    }
}
