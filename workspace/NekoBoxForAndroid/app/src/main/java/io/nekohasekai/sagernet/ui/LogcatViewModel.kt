package io.nekohasekai.sagernet.ui

import android.os.FileObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.AnsiLogFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.SendLog
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

internal enum class LogcatSeverity {
    PANIC,
    FATAL,
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE,
}

internal data class LogcatLine(
    val rawText: String,
    val plainText: String,
    val severity: LogcatSeverity,
)

internal data class LogcatUiState(
    val lines: List<LogcatLine> = emptyList(),
    val paused: Boolean = false,
    val query: String = "",
    val severity: LogcatSeverity = LogcatSeverity.TRACE,
    val generation: Long = 0,
)

internal object LogcatLineParser {
    private val bracketedSeverityPattern = Regex(
        "\\[(PANIC|FATAL|ERROR|WARN(?:ING)?|INFO|DEBUG|TRACE)]",
        RegexOption.IGNORE_CASE,
    )
    private val coreSeverityPattern = Regex(
        "(?:^|\\s)(PANIC|FATAL|ERROR|WARN|INFO|DEBUG|TRACE)(?:\\[|\\s)",
    )

    fun parse(text: String, initialSeverity: LogcatSeverity = LogcatSeverity.INFO): List<LogcatLine> {
        var inheritedSeverity = initialSeverity
        val rawLines = text.split('\n').let { lines ->
            if (text.endsWith('\n')) lines.dropLast(1) else lines
        }
        return rawLines.asSequence()
            .map { rawLine ->
                val rawText = "$rawLine\n"
                val plainText = AnsiLogFormatter.parse(rawLine).text
                val explicitSeverity = (
                    bracketedSeverityPattern.find(plainText)
                        ?: coreSeverityPattern.find(plainText)
                    )?.groupValues?.get(1)
                    ?.let(::parseSeverity)
                val severity = explicitSeverity ?: inheritedSeverity
                if (explicitSeverity != null) inheritedSeverity = explicitSeverity
                LogcatLine(rawText, plainText, severity)
            }
            .toList()
    }

    fun parseSeverity(value: String): LogcatSeverity = when (value.uppercase()) {
        "PANIC" -> LogcatSeverity.PANIC
        "FATAL" -> LogcatSeverity.FATAL
        "ERROR" -> LogcatSeverity.ERROR
        "WARN", "WARNING" -> LogcatSeverity.WARN
        "DEBUG" -> LogcatSeverity.DEBUG
        "TRACE" -> LogcatSeverity.TRACE
        else -> LogcatSeverity.INFO
    }

    fun filter(
        lines: List<LogcatLine>,
        severity: LogcatSeverity,
        query: String,
    ): List<LogcatLine> = lines.filter { line ->
        line.severity.ordinal <= severity.ordinal &&
            (query.isEmpty() || line.plainText.contains(query, ignoreCase = true))
    }
}

internal data class LogTailSnapshot(
    val text: String,
    val offset: Long,
)

internal object LogTailReader {
    fun readInitial(file: File, maxBytes: Long): LogTailSnapshot {
        if (!file.isFile) return LogTailSnapshot("", 0)
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            var start = (length - maxBytes).coerceAtLeast(0)
            if (start > 0) {
                input.seek(start)
                while (start < length) {
                    start++
                    if (input.read() == '\n'.code) break
                }
            }
            input.seek(start)
            val bytes = ByteArray((length - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            input.readFully(bytes)
            return LogTailSnapshot(bytes.toString(Charsets.UTF_8), length)
        }
    }

    fun readAppended(file: File, offset: Long): LogTailSnapshot {
        if (!file.isFile) return LogTailSnapshot("", 0)
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length < offset) return LogTailSnapshot("", length)
            input.seek(offset)
            val bytes = ByteArray((length - offset).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            input.readFully(bytes)
            return LogTailSnapshot(bytes.toString(Charsets.UTF_8), offset + bytes.size)
        }
    }
}

internal class LogcatViewModel : ViewModel() {
    private val logFile = SendLog.logFile
    private val fileEvents = Channel<Unit>(Channel.CONFLATED)
    private val allLines = mutableListOf<LogcatLine>()
    private var pausedLines: List<LogcatLine>? = null
    private var lastSeverity = LogcatSeverity.INFO
    private var offset = 0L
    private var initialized = false
    private val forceReload = AtomicBoolean()

    private val _uiState = MutableStateFlow(LogcatUiState())
    val uiState: StateFlow<LogcatUiState> = _uiState.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    @Suppress("DEPRECATION")
    private val fileObserver = object : FileObserver(
        logFile.parentFile?.absolutePath ?: logFile.absolutePath,
        MODIFY or CLOSE_WRITE or CREATE or MOVED_TO or DELETE or ATTRIB,
    ) {
        override fun onEvent(event: Int, path: String?) {
            if (path == null || path == logFile.name) fileEvents.trySend(Unit)
        }
    }

    fun initialize() {
        if (initialized) return
        initialized = true
        fileObserver.startWatching()
        viewModelScope.launch(Dispatchers.IO) {
            reloadFromDisk()
            fileEvents.trySend(Unit)
            for (ignored in fileEvents) {
                if (forceReload.getAndSet(false)) reloadFromDisk() else drainAppended()
            }
        }
    }

    fun refresh() {
        forceReload.set(true)
        fileEvents.trySend(Unit)
    }

    fun togglePause() {
        if (_uiState.value.paused) {
            pausedLines = null
            _uiState.update { state ->
                state.copy(
                    paused = false,
                    lines = visibleLines(allLines, state.severity, state.query),
                    generation = state.generation + 1,
                )
            }
        } else {
            pausedLines = allLines.toList()
            _uiState.update { it.copy(paused = true) }
        }
    }

    fun setQuery(query: String) {
        if (_uiState.value.query == query) return
        _uiState.update { state ->
            state.copy(
                query = query,
                lines = visibleLines(sourceLines(), state.severity, query),
                generation = state.generation + 1,
            )
        }
    }

    fun setSeverity(severity: LogcatSeverity) {
        if (_uiState.value.severity == severity) return
        _uiState.update { state ->
            state.copy(
                severity = severity,
                lines = visibleLines(sourceLines(), severity, state.query),
                generation = state.generation + 1,
            )
        }
    }

    fun clearLog() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                Libcore.nekoLogClear()
                Runtime.getRuntime().exec("/system/bin/logcat -c").waitFor()
            }
            result.onFailure { _errors.tryEmit(it.message ?: it.toString()) }
            if (result.isSuccess) withContext(Dispatchers.Main.immediate) { resetLines() }
        }
    }

    private suspend fun reloadFromDisk() {
        val configuredKb = DataStore.logBufSize.takeIf { it > 0 } ?: 50
        val snapshot = runCatching {
            LogTailReader.readInitial(logFile, configuredKb.toLong() * 1024)
        }.getOrElse {
            _errors.tryEmit(it.message ?: it.toString())
            LogTailSnapshot("", 0)
        }
        val lines = LogcatLineParser.parse(snapshot.text)
        offset = snapshot.offset
        withContext(Dispatchers.Main.immediate) {
            allLines.clear()
            allLines.addAll(lines)
            lastSeverity = lines.lastOrNull()?.severity ?: LogcatSeverity.INFO
            pausedLines = if (_uiState.value.paused) lines.toList() else null
            _uiState.update { state ->
                state.copy(
                    lines = visibleLines(sourceLines(), state.severity, state.query),
                    generation = state.generation + 1,
                )
            }
        }
    }

    private suspend fun drainAppended() {
        val previousOffset = offset
        val snapshot = runCatching { LogTailReader.readAppended(logFile, previousOffset) }
            .getOrElse {
                _errors.tryEmit(it.message ?: it.toString())
                return
            }
        if (snapshot.offset < previousOffset || !logFile.exists()) {
            reloadFromDisk()
            return
        }
        offset = snapshot.offset
        if (snapshot.text.isEmpty()) return
        val newLines = LogcatLineParser.parse(snapshot.text, lastSeverity)
        if (newLines.isEmpty()) return
        withContext(Dispatchers.Main.immediate) {
            allLines.addAll(newLines)
            lastSeverity = newLines.last().severity
            if (!_uiState.value.paused) {
                _uiState.update { state ->
                    val visible = LogcatLineParser.filter(newLines, state.severity, state.query)
                    if (visible.isEmpty()) state else state.copy(lines = state.lines + visible)
                }
            }
        }
    }

    private fun sourceLines(): List<LogcatLine> = pausedLines ?: allLines

    private fun visibleLines(
        source: List<LogcatLine>,
        severity: LogcatSeverity,
        query: String,
    ) = LogcatLineParser.filter(source, severity, query)

    private fun resetLines() {
        offset = 0
        allLines.clear()
        pausedLines = if (_uiState.value.paused) emptyList() else null
        lastSeverity = LogcatSeverity.INFO
        _uiState.update { it.copy(lines = emptyList(), generation = it.generation + 1) }
    }

    override fun onCleared() {
        fileObserver.stopWatching()
        fileEvents.close()
        super.onCleared()
    }
}
