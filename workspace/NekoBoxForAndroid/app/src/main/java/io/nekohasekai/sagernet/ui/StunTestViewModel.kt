package io.nekohasekai.sagernet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import libcore.StunTestSession

internal data class StunUiState(
    val isRunning: Boolean = false,
    val results: List<StunServerUiResult> = emptyList(),
    val assessment: StunAssessment? = null,
)

internal class StunTestViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(StunUiState())
    val uiState: StateFlow<StunUiState> = _uiState.asStateFlow()

    @Volatile
    private var session: StunTestSession? = null
    private var job: Job? = null
    private var generation = 0L

    fun start(servers: List<String>, ipv6Mode: Int) {
        cancelNativeSession()
        generation += 1
        val currentGeneration = generation
        val nativeSession = Libcore.newStunTestSession(ipv6Mode)
        try {
            servers.forEach(nativeSession::addServer)
        } catch (error: Exception) {
            nativeSession.close()
            _uiState.value = StunUiState(
                results = listOf(
                    StunServerUiResult(
                        server = "",
                        bindingSuccess = false,
                        behaviorSupported = false,
                        behaviorComplete = false,
                        natType = StunProtocolCodes.NAT_ERROR,
                        mappingBehavior = StunProtocolCodes.BEHAVIOR_UNKNOWN,
                        filteringBehavior = StunProtocolCodes.BEHAVIOR_UNKNOWN,
                        externalAddress = "",
                        externalPort = 0,
                        ipFamily = 0,
                        durationMilliseconds = 0,
                        errorCode = "configuration",
                        errorMessage = error.message.orEmpty(),
                    ),
                ),
                assessment = StunAssessment(StunAssessmentKind.FAILED),
            )
            return
        }
        session = nativeSession
        _uiState.update { it.copy(isRunning = true, assessment = null) }
        job = viewModelScope.launch(Dispatchers.IO) {
            val results = runCatching {
                val nativeResult = nativeSession.run()
                buildList {
                    repeat(nativeResult.count().toInt()) { index ->
                        val result = nativeResult.get(index.toLong()) ?: return@repeat
                        add(
                            StunServerUiResult(
                                server = result.server,
                                bindingSuccess = result.bindingSuccess,
                                behaviorSupported = result.behaviorSupported,
                                behaviorComplete = result.behaviorComplete,
                                natType = result.natType,
                                mappingBehavior = result.mappingBehavior,
                                filteringBehavior = result.filteringBehavior,
                                externalAddress = result.externalAddress,
                                externalPort = result.externalPort,
                                ipFamily = result.ipFamily,
                                durationMilliseconds = result.durationMilliseconds,
                                errorCode = result.errorCode,
                                errorMessage = result.errorMessage,
                                warningCode = result.warningCode,
                            ),
                        )
                    }
                }
            }.getOrElse { error ->
                listOf(
                    StunServerUiResult(
                        server = "",
                        bindingSuccess = false,
                        behaviorSupported = false,
                        behaviorComplete = false,
                        natType = StunProtocolCodes.NAT_ERROR,
                        mappingBehavior = StunProtocolCodes.BEHAVIOR_UNKNOWN,
                        filteringBehavior = StunProtocolCodes.BEHAVIOR_UNKNOWN,
                        externalAddress = "",
                        externalPort = 0,
                        ipFamily = 0,
                        durationMilliseconds = 0,
                        errorCode = "network",
                        errorMessage = error.message.orEmpty(),
                    ),
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (currentGeneration == generation) {
                    _uiState.value = StunUiState(
                        results = results,
                        assessment = StunAssessmentPolicy.assess(results),
                    )
                }
                if (session === nativeSession) session = null
            }
            nativeSession.close()
        }
    }

    fun cancel() {
        if (!_uiState.value.isRunning) return
        generation += 1
        cancelNativeSession()
        _uiState.update {
            it.copy(
                isRunning = false,
                assessment = StunAssessment(StunAssessmentKind.CANCELLED),
            )
        }
    }

    private fun cancelNativeSession() {
        session?.close()
        session = null
        job?.cancel()
        job = null
    }

    override fun onCleared() {
        cancelNativeSession()
    }
}
