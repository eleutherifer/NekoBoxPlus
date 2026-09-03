package io.nekohasekai.sagernet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import libcore.RuleSetMatchSession

internal data class RuleSetMatchUiState(
    val isRunning: Boolean = false,
    val results: List<String> = emptyList(),
)

internal sealed interface RuleSetMatchEvent {
    data object NotFound : RuleSetMatchEvent
    data class Error(val message: String) : RuleSetMatchEvent
}

internal class RuleSetMatchViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(RuleSetMatchUiState())
    val uiState: StateFlow<RuleSetMatchUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<RuleSetMatchEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    @Volatile
    private var session: RuleSetMatchSession? = null
    private var job: Job? = null
    private var generation = 0L

    fun start(keyword: String) {
        cancelNativeSession()
        generation += 1
        val currentGeneration = generation
        val nativeSession = Libcore.newRuleSetMatchSession()
        session = nativeSession
        _uiState.value = RuleSetMatchUiState(isRunning = true)

        job = viewModelScope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                val nativeResult = nativeSession.run(keyword)
                buildList {
                    repeat(nativeResult.count().toInt()) { index ->
                        nativeResult.get(index.toLong()).takeIf(String::isNotEmpty)?.let(::add)
                    }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (currentGeneration == generation) {
                    outcome.fold(
                        onSuccess = { results ->
                            _uiState.value = RuleSetMatchUiState(results = results)
                            if (results.isEmpty()) eventChannel.trySend(RuleSetMatchEvent.NotFound)
                        },
                        onFailure = { error ->
                            _uiState.value = RuleSetMatchUiState()
                            eventChannel.trySend(
                                RuleSetMatchEvent.Error(error.message.orEmpty()),
                            )
                        },
                    )
                }
                if (session === nativeSession) session = null
            }
            nativeSession.close()
        }
    }

    fun cancel() {
        generation += 1
        cancelNativeSession()
        _uiState.value = RuleSetMatchUiState()
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
