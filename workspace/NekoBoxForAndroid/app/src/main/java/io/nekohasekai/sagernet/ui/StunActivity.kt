package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.StunResultPresentation
import io.nekohasekai.sagernet.ui.compose.StunScreen
import io.nekohasekai.sagernet.ui.compose.StunServerPresentation

class StunActivity : ThemedActivity() {
    private val viewModel: StunTestViewModel by viewModels()
    private var selectedPreset by mutableStateOf(StunPreset.BALANCED)
    private var customServers by mutableStateOf("")
    private var customServersError by mutableStateOf<String?>(null)
    private var detailsExpanded by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedPreset = StunPreset.fromValue(DataStore.stunTestPreset)
        customServers = DataStore.stunTestCustomServers
        setContent {
            NekoComposeTheme {
                val state = viewModel.uiState.collectAsStateWithLifecycle().value
                StunScreen(
                    selectedPreset = selectedPreset,
                    customServers = customServers,
                    customServersError = customServersError,
                    isRunning = state.isRunning,
                    progressText = progressText(state),
                    result = resultPresentation(state),
                    detailsExpanded = detailsExpanded,
                    onClose = ::finish,
                    onPresetSelected = ::selectPreset,
                    onCustomServersChanged = {
                        customServers = it
                        customServersError = null
                    },
                    onAction = {
                        if (state.isRunning) viewModel.cancel() else startTest()
                    },
                    onToggleDetails = { detailsExpanded = !detailsExpanded },
                )
            }
        }
    }

    override fun onPause() {
        persistValidCustomServers()
        super.onPause()
    }

    private fun selectPreset(preset: StunPreset) {
        selectedPreset = preset
        DataStore.stunTestPreset = preset.value
    }

    private fun startTest() {
        val servers = if (selectedPreset == StunPreset.CUSTOM) {
            when (val parsed = StunServerListParser.parse(customServers)) {
                is StunServerParseResult.Valid -> {
                    DataStore.stunTestCustomServers = parsed.servers.joinToString("\n")
                    parsed.servers
                }
                is StunServerParseResult.Invalid -> {
                    customServersError = customServerError(parsed)
                    return
                }
            }
        } else {
            selectedPreset.servers
        }
        viewModel.start(servers, DataStore.ipv6Mode)
    }

    private fun persistValidCustomServers() {
        val parsed = StunServerListParser.parse(customServers)
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

    private fun progressText(state: StunUiState): String = if (state.isRunning) {
        resources.getQuantityString(
            R.plurals.stun_testing_servers,
            activeServerCount(),
            activeServerCount(),
        )
    } else {
        ""
    }

    private fun resultPresentation(state: StunUiState): StunResultPresentation? {
        val assessment = state.assessment ?: return null
        return StunResultPresentation(
            assessmentTitle = getString(assessmentTitle(assessment.kind)),
            assessmentImpact = getString(assessmentImpact(assessment.kind)),
            technicalSummary = technicalSummary(state.results, assessment),
            servers = state.results.map { result ->
                StunServerPresentation(
                    name = result.server.ifBlank { getString(R.string.stun_unknown_server) },
                    status = getString(
                        when {
                            result.behaviorComplete -> R.string.stun_server_status_complete
                            result.bindingSuccess -> R.string.stun_server_status_partial
                            result.errorCode == "cancelled" -> R.string.stun_server_status_cancelled
                            else -> R.string.stun_server_status_failed
                        },
                    ),
                    details = serverDetails(result),
                )
            },
        )
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
            (StunServerListParser.parse(customServers)
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
