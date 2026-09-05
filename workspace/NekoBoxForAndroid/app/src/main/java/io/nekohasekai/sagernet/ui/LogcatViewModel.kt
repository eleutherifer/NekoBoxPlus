package io.nekohasekai.sagernet.ui

import android.os.FileObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.ktx.AnsiLogFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.utils.SendLog
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

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
    val number: Long,
    val rawText: String,
    val plainText: String,
    val severity: LogcatSeverity,
)

internal data class LogcatUiState(
    val lines: List<LogcatLine> = emptyList(),
    val cachedLines: Map<Long, LogcatLine> = emptyMap(),
    val virtualItemCount: Int = 0,
    val virtualMode: Boolean = true,
    val filteredLineMap: LogLineMap? = null,
    val paused: Boolean = false,
    val query: String = "",
    val severity: LogcatSeverity = LogcatSeverity.TRACE,
    val totalLines: Long = 0,
    val sourceLineCount: Long = 0,
    val loading: Boolean = true,
    val hasOlder: Boolean = false,
    val hasNewer: Boolean = false,
    val atTail: Boolean = true,
    val scrollRequestId: Long = 0,
    val scrollTargetLine: Long? = null,
    val generation: Long = 0,
)

internal class LogLineMap private constructor(
    private val chunks: List<LongArray>,
    val size: Int,
) {
    operator fun get(position: Int): Long {
        require(position in 0 until size)
        return chunks[position / CHUNK_SIZE][position % CHUNK_SIZE]
    }

    fun positionOf(line: Long): Int {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val value = get(middle)
            when {
                value < line -> low = middle + 1
                value > line -> high = middle - 1
                else -> return middle
            }
        }
        return low.coerceIn(0, (size - 1).coerceAtLeast(0))
    }

    fun appended(values: List<Long>): LogLineMap {
        if (values.isEmpty() || size == Int.MAX_VALUE) return this
        val result = chunks.toMutableList()
        var valueIndex = 0
        if (result.isNotEmpty() && result.last().size < CHUNK_SIZE) {
            val previous = result.removeAt(result.lastIndex)
            val added = minOf(CHUNK_SIZE - previous.size, values.size)
            result += LongArray(previous.size + added).also { merged ->
                previous.copyInto(merged)
                repeat(added) { merged[previous.size + it] = values[valueIndex++] }
            }
        }
        while (valueIndex < values.size && size + valueIndex < Int.MAX_VALUE) {
            val count = minOf(CHUNK_SIZE, values.size - valueIndex)
            result += LongArray(count) { values[valueIndex++] }
        }
        val addedCount = values.size.coerceAtMost(Int.MAX_VALUE - size)
        return LogLineMap(result, size + addedCount)
    }

    class Builder {
        private val chunks = mutableListOf<LongArray>()
        private var current = LongArray(CHUNK_SIZE)
        private var currentSize = 0
        private var totalSize = 0

        fun add(value: Long) {
            if (totalSize == Int.MAX_VALUE) return
            if (currentSize == CHUNK_SIZE) {
                chunks += current
                current = LongArray(CHUNK_SIZE)
                currentSize = 0
            }
            current[currentSize++] = value
            totalSize++
        }

        fun build(): LogLineMap {
            val result = chunks.toMutableList()
            if (currentSize > 0) result += current.copyOf(currentSize)
            return LogLineMap(result, totalSize)
        }
    }

    private companion object {
        const val CHUNK_SIZE = 4_096
    }
}

internal object LogVirtualPositionPolicy {
    fun itemCount(totalLines: Long): Int = totalLines.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun positionToLine(position: Int, itemCount: Int, totalLines: Long): Long {
        if (itemCount <= 1 || totalLines <= 1) return 0
        return (position.toDouble() * (totalLines - 1) / (itemCount - 1)).toLong()
            .coerceIn(0, totalLines - 1)
    }

    fun lineToPosition(line: Long, itemCount: Int, totalLines: Long): Int {
        if (itemCount <= 1 || totalLines <= 1) return 0
        return (line.toDouble() * (itemCount - 1) / (totalLines - 1)).roundToInt()
            .coerceIn(0, itemCount - 1)
    }
}

internal object LogPagePolicy {
    fun pageStarts(
        lines: List<Long>,
        lineCount: Long,
        pageSize: Int,
        maxPages: Int,
        isCached: (Long) -> Boolean = { false },
    ): List<Long> {
        if (lineCount <= 0 || pageSize <= 0 || maxPages <= 0) return emptyList()
        return lines.asSequence()
            .map { it.coerceIn(0, lineCount - 1) }
            .filterNot(isCached)
            .map { line -> line / pageSize * pageSize }
            .distinct()
            .take(maxPages)
            .toList()
    }
}

internal object LogcatLineParser {
    private val bracketedSeverityPattern = Regex(
        "\\[(PANIC|FATAL|ERROR|WARN(?:ING)?|INFO|DEBUG|TRACE)]",
        RegexOption.IGNORE_CASE,
    )
    private val coreSeverityPattern = Regex(
        "(?:^|\\s)(PANIC|FATAL|ERROR|WARN|INFO|DEBUG|TRACE)(?:\\[|\\s)",
    )
    private val ansiCsiPattern = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")

    fun parse(
        text: String,
        initialSeverity: LogcatSeverity = LogcatSeverity.INFO,
        firstLineNumber: Long = 0,
    ): List<LogcatLine> {
        var inheritedSeverity = initialSeverity
        val rawLines = text.split('\n').let { lines ->
            if (text.endsWith('\n')) lines.dropLast(1) else lines
        }
        return rawLines.mapIndexed { index, rawLine ->
            parseLine(firstLineNumber + index, rawLine, inheritedSeverity).also {
                inheritedSeverity = it.severity
            }
        }
    }

    fun parseLine(number: Long, rawLine: String, inheritedSeverity: LogcatSeverity): LogcatLine {
        val rawText = "$rawLine\n"
        val plainText = AnsiLogFormatter.plainText(rawLine)
        val explicitSeverity = explicitSeverity(plainText)
        return LogcatLine(number, rawText, plainText, explicitSeverity ?: inheritedSeverity)
    }

    fun explicitSeverity(plainText: String): LogcatSeverity? = (
        bracketedSeverityPattern.find(plainText) ?: coreSeverityPattern.find(plainText)
        )?.groupValues?.get(1)?.let(::parseSeverity)

    fun severityInRawText(rawText: String): LogcatSeverity? = explicitSeverity(
        if ('\u001B' in rawText) ansiCsiPattern.replace(rawText, "") else rawText,
    )

    fun parseSeverity(value: String): LogcatSeverity = when (value.uppercase()) {
        "PANIC" -> LogcatSeverity.PANIC
        "FATAL" -> LogcatSeverity.FATAL
        "ERROR" -> LogcatSeverity.ERROR
        "WARN", "WARNING" -> LogcatSeverity.WARN
        "DEBUG" -> LogcatSeverity.DEBUG
        "TRACE" -> LogcatSeverity.TRACE
        else -> LogcatSeverity.INFO
    }

    fun matches(line: LogcatLine, severity: LogcatSeverity, query: String): Boolean =
        line.severity.ordinal <= severity.ordinal &&
            (query.isEmpty() || line.plainText.contains(query, ignoreCase = true))

    fun filter(
        lines: List<LogcatLine>,
        severity: LogcatSeverity,
        query: String,
    ): List<LogcatLine> = lines.filter { matches(it, severity, query) }
}

internal data class LogCheckpoint(
    val line: Long,
    val offset: Long,
    val inheritedSeverity: LogcatSeverity,
)

/** A sparse, immutable map from physical line numbers to byte offsets in neko.log. */
internal class LogFileIndex private constructor(
    val checkpoints: List<LogCheckpoint>,
    val lineCount: Long,
    val fileLength: Long,
    private val finalSeverity: LogcatSeverity,
    private val endedWithNewline: Boolean,
) {
    fun read(file: File, startLine: Long, count: Int): List<LogcatLine> {
        if (count <= 0 || startLine >= lineCount || !file.isFile) return emptyList()
        val start = startLine.coerceAtLeast(0)
        val checkpoint = checkpointFor(start)
        return BufferedUtf8LineReader(file, checkpoint.offset).use { input ->
            var lineNumber = checkpoint.line
            var inherited = checkpoint.inheritedSeverity
            while (lineNumber < start) {
                val skipped = input.readLine() ?: break
                LogcatLineParser.severityInRawText(skipped)?.let {
                    inherited = it
                }
                lineNumber++
            }
            buildList {
                while (size < count && lineNumber < lineCount) {
                    val text = input.readLine() ?: break
                    val parsed = LogcatLineParser.parseLine(lineNumber, text, inherited)
                    add(parsed)
                    inherited = parsed.severity
                    lineNumber++
                }
            }
        }
    }

    private fun checkpointFor(line: Long): LogCheckpoint {
        var low = 0
        var high = checkpoints.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (checkpoints[middle].line <= line) low = middle + 1 else high = middle - 1
        }
        return checkpoints[high.coerceAtLeast(0)]
    }

    fun extend(file: File): LogFileIndex {
        if (!file.isFile || file.length() < fileLength || !endedWithNewline) return build(file)
        if (file.length() == fileLength) return this
        val mutableCheckpoints = checkpoints.toMutableList()
        var currentLine = lineCount
        var severity = finalSeverity
        BufferedUtf8LineReader(file, fileLength).use { input ->
            while (true) {
                if (currentLine % CHECKPOINT_INTERVAL == 0L &&
                    mutableCheckpoints.lastOrNull()?.line != currentLine
                ) {
                    mutableCheckpoints += LogCheckpoint(currentLine, input.offset, severity)
                }
                val text = input.readLine() ?: break
                LogcatLineParser.severityInRawText(text)?.let {
                    severity = it
                }
                currentLine++
            }
        }
        val length = file.length()
        return LogFileIndex(
            mutableCheckpoints,
            currentLine,
            length,
            severity,
            file.endsWithNewline(length),
        )
    }

    companion object {
        const val CHECKPOINT_INTERVAL = 256L
        private val INITIAL_CHECKPOINT = LogCheckpoint(0, 0, LogcatSeverity.INFO)

        fun build(file: File): LogFileIndex {
            if (!file.isFile || file.length() == 0L) {
                return LogFileIndex(listOf(INITIAL_CHECKPOINT), 0, 0, LogcatSeverity.INFO, true)
            }
            val checkpoints = mutableListOf<LogCheckpoint>()
            var currentLine = 0L
            var severity = LogcatSeverity.INFO
            BufferedUtf8LineReader(file).use { input ->
                while (true) {
                    if (currentLine % CHECKPOINT_INTERVAL == 0L) {
                        checkpoints += LogCheckpoint(currentLine, input.offset, severity)
                    }
                    val text = input.readLine() ?: break
                    LogcatLineParser.severityInRawText(text)?.let {
                        severity = it
                    }
                    currentLine++
                }
            }
            val length = file.length()
            return LogFileIndex(
                checkpoints.ifEmpty { listOf(INITIAL_CHECKPOINT) },
                currentLine,
                length,
                severity,
                file.endsWithNewline(length),
            )
        }
    }
}

private class BufferedUtf8LineReader(file: File, startOffset: Long = 0L) : Closeable {
    private val input = RandomAccessFile(file, "r").apply { seek(startOffset) }
    private val buffer = ByteArray(BUFFER_SIZE)
    private var bufferPosition = 0
    private var bufferLimit = 0
    var offset: Long = startOffset
        private set

    fun readLine(): String? {
        var output: ByteArrayOutputStream? = null
        var segmentStart = bufferPosition
        while (true) {
            if (bufferPosition >= bufferLimit) {
                if (bufferPosition > segmentStart) {
                    if (output == null) output = ByteArrayOutputStream()
                    output.write(buffer, segmentStart, bufferPosition - segmentStart)
                }
                bufferLimit = input.read(buffer)
                bufferPosition = 0
                segmentStart = 0
                if (bufferLimit < 0) {
                    val bytes = output?.toByteArray() ?: return null
                    return bytes.decodeLine()
                }
            }
            val newline = buffer.indexOf('\n'.code.toByte(), bufferPosition, bufferLimit)
            if (newline >= 0) {
                val count = newline - segmentStart
                val text = if (output == null) {
                    String(buffer, segmentStart, count.withoutTrailingCr(buffer, segmentStart), StandardCharsets.UTF_8)
                } else {
                    output.write(buffer, segmentStart, count)
                    output.toByteArray().decodeLine()
                }
                val consumed = newline + 1 - bufferPosition
                bufferPosition = newline + 1
                offset += consumed
                return text
            }
            val consumed = bufferLimit - bufferPosition
            bufferPosition = bufferLimit
            offset += consumed
        }
    }

    override fun close() = input.close()

    private fun ByteArray.decodeLine(): String {
        val size = if (isNotEmpty() && last() == '\r'.code.toByte()) size - 1 else size
        return String(this, 0, size, StandardCharsets.UTF_8)
    }

    private fun Int.withoutTrailingCr(bytes: ByteArray, start: Int): Int =
        if (this > 0 && bytes[start + this - 1] == '\r'.code.toByte()) this - 1 else this

    private fun ByteArray.indexOf(value: Byte, start: Int, end: Int): Int {
        for (index in start until end) if (this[index] == value) return index
        return -1
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
    }
}

private fun File.endsWithNewline(length: Long): Boolean {
    if (length == 0L) return true
    return runCatching {
        RandomAccessFile(this, "r").use {
            it.seek(length - 1)
            it.read() == '\n'.code
        }
    }.getOrDefault(false)
}

internal class LogcatViewModel : ViewModel() {
    private val logFile = SendLog.logFile
    private val fileEvents = Channel<Unit>(Channel.CONFLATED)
    private val operationMutex = Mutex()
    private val pageReadMutex = Mutex()
    private var index = LogFileIndex.build(File("/nonexistent"))
    private var initialized = false
    private var queryJob: Job? = null
    private var seekJob: Job? = null
    private var pageJob: Job? = null
    private var pausedLineCount: Long? = null
    private val forceRebuild = AtomicBoolean()
    private val seekRequests = AtomicLong()
    private val pageRequests = AtomicLong()
    private val filterRequests = AtomicLong()
    private val pageCache = object : LinkedHashMap<Long, LogcatLine>(PAGE_CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, LogcatLine>?): Boolean =
            size > PAGE_CACHE_LIMIT
    }

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
            if (path == null || path == logFile.name) {
                if (event and (CREATE or MOVED_TO or DELETE) != 0) forceRebuild.set(true)
                fileEvents.trySend(Unit)
            }
        }
    }

    fun initialize() {
        if (initialized) return
        initialized = true
        fileObserver.startWatching()
        viewModelScope.launch(Dispatchers.IO) {
            operationMutex.withLock { rebuildIndexAndOpenTail() }
            for (ignored in fileEvents) {
                delay(FILE_EVENT_DEBOUNCE_MS)
                operationMutex.withLock { handleFileChange() }
            }
        }
    }

    fun setQuery(query: String) {
        if (_uiState.value.query == query) return
        val requestId = filterRequests.incrementAndGet()
        _uiState.update { it.copy(query = query, loading = true, generation = it.generation + 1) }
        queryJob?.cancel()
        queryJob = viewModelScope.launch(Dispatchers.IO) {
            delay(SEARCH_DEBOUNCE_MS)
            operationMutex.withLock { rebuildFilterOrOpenTail(requestId) }
        }
    }

    fun setSeverity(severity: LogcatSeverity) {
        if (_uiState.value.severity == severity) return
        val requestId = filterRequests.incrementAndGet()
        _uiState.update { it.copy(severity = severity, loading = true, generation = it.generation + 1) }
        queryJob?.cancel()
        queryJob = viewModelScope.launch(Dispatchers.IO) {
            operationMutex.withLock { rebuildFilterOrOpenTail(requestId) }
        }
    }

    fun loadOlder() = launchOperation { loadOlderLocked() }

    fun loadNewer() = launchOperation { loadNewerLocked() }

    fun setTailFollowing(following: Boolean) {
        if (_uiState.value.atTail != following) {
            _uiState.update { it.copy(atTail = following) }
        }
    }

    fun requestLine(line: Long) = requestLines(listOf(line))

    fun requestLines(lines: List<Long>) {
        if (!_uiState.value.virtualMode || index.lineCount == 0L) return
        val endpoint = endpointLineCount()
        val cachedLines = _uiState.value.cachedLines
        val starts = LogPagePolicy.pageStarts(
            lines,
            endpoint,
            PAGE_SIZE,
            MAX_VIEWPORT_PAGES,
            cachedLines::containsKey,
        )
        if (starts.isEmpty()) return
        val requestId = pageRequests.incrementAndGet()
        pageJob?.cancel()
        pageJob = viewModelScope.launch(Dispatchers.IO) {
            val loaded = pageReadMutex.withLock {
                ensureActive()
                starts.flatMap { index.read(logFile, it, PAGE_SIZE) }
            }
            if (requestId != pageRequests.get()) return@launch
            operationMutex.withLock {
                if (requestId != pageRequests.get() || !_uiState.value.virtualMode) return@withLock
                cache(loaded)
                _uiState.update {
                    it.copy(cachedLines = pageCache.toMap(), loading = false)
                }
            }
        }
    }

    fun finishVirtualScroll(line: Long) {
        val state = _uiState.value
        if (!state.virtualMode || state.virtualItemCount == 0) return
        val position = state.filteredLineMap?.positionOf(line) ?: LogVirtualPositionPolicy.lineToPosition(
            line,
            state.virtualItemCount,
            state.sourceLineCount,
        )
        _uiState.update {
            it.copy(
                hasOlder = position > 0,
                hasNewer = position < state.virtualItemCount - 1,
                atTail = position == state.virtualItemCount - 1,
            )
        }
    }

    fun seekToLine(line: Long) {
        val requestId = seekRequests.incrementAndGet()
        seekJob?.cancel()
        seekJob = viewModelScope.launch(Dispatchers.IO) {
            operationMutex.withLock {
                val endpoint = endpointLineCount()
                if (endpoint == 0L) return@withLock publish(emptyList(), false, false, false)
                val target = line.coerceIn(0, endpoint - 1)
                val state = _uiState.value
                val filtered = isFiltered(state)
                if (!filtered) {
                    val pageStart = (target - PAGE_SIZE / 2).coerceAtLeast(0)
                        .coerceAtMost((endpoint - PAGE_SIZE).coerceAtLeast(0))
                    cache(index.read(logFile, pageStart, PAGE_SIZE))
                    if (requestId != seekRequests.get()) return@withLock
                    publishVirtual(target, requestId, atTail = target == endpoint - 1)
                    return@withLock
                }
                val lines = if (filtered) {
                    searchAround(target, state.severity, state.query, endpoint)
                } else {
                    val start = (target - INITIAL_WINDOW / 2).coerceAtLeast(0)
                        .coerceAtMost((endpoint - INITIAL_WINDOW).coerceAtLeast(0))
                    index.read(logFile, start, INITIAL_WINDOW)
                }
                val atTail = lines.lastOrNull()?.number == endpoint - 1 && !filtered
                if (requestId != seekRequests.get()) return@withLock
                publish(
                    lines,
                    hasOlder = lines.firstOrNull()?.number?.let { it > 0 } == true,
                    hasNewer = lines.lastOrNull()?.number?.let { it < endpoint - 1 } == true,
                    atTail = atTail,
                    scrollRequestId = requestId,
                    scrollTargetLine = target,
                )
            }
        }
    }

    fun jumpToBottom() {
        val requestId = seekRequests.incrementAndGet()
        seekJob?.cancel()
        seekJob = viewModelScope.launch(Dispatchers.IO) {
            operationMutex.withLock {
                index = currentIndex()
                val endpoint = endpointLineCount()
                if (endpoint == 0L) {
                    val lineMap = _uiState.value.filteredLineMap
                    if (lineMap != null) {
                        publishFilteredVirtual(lineMap, 0, requestId, true)
                    } else if (!isFiltered(_uiState.value)) {
                        publishVirtual(0, requestId, atTail = true)
                    } else {
                        publish(emptyList(), false, false, true)
                    }
                } else if (_uiState.value.filteredLineMap != null) {
                    val lineMap = _uiState.value.filteredLineMap ?: return@withLock
                    if (lineMap.size == 0) {
                        publishFilteredVirtual(lineMap, 0, requestId, true)
                    } else {
                        val targetPosition = lineMap.size - 1
                        val targetLine = lineMap[targetPosition]
                        val start = (targetLine - PAGE_SIZE / 2).coerceAtLeast(0)
                        cache(index.read(logFile, start, PAGE_SIZE))
                        publishFilteredVirtual(lineMap, targetPosition, requestId, true)
                    }
                } else if (!isFiltered(_uiState.value)) {
                    val start = (endpoint - PAGE_SIZE).coerceAtLeast(0)
                    cache(index.read(logFile, start, PAGE_SIZE))
                    if (requestId != seekRequests.get()) return@withLock
                    publishVirtual(endpoint - 1, requestId, atTail = true)
                } else {
                    openTail(scrollRequestId = requestId)
                }
            }
        }
    }

    fun refresh() = launchOperation { rebuildIndexAndOpenTail() }

    fun togglePause() {
        if (_uiState.value.paused) {
            pausedLineCount = null
            _uiState.update { it.copy(paused = false, loading = true) }
            launchOperation { index = currentIndex(); rebuildFilterOrOpenTail() }
        } else {
            pausedLineCount = index.lineCount
            _uiState.update { it.copy(paused = true) }
        }
    }

    fun clearLog() {
        viewModelScope.launch(Dispatchers.IO) {
            operationMutex.withLock {
                val result = runCatching {
                    Libcore.nekoLogClear()
                    Runtime.getRuntime().exec("/system/bin/logcat -c").waitFor()
                }
                result.onFailure { _errors.tryEmit(it.message ?: it.toString()) }
                if (result.isSuccess) rebuildIndexAndOpenTail()
            }
        }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { operationMutex.withLock { block() } }
    }

    private suspend fun rebuildIndexAndOpenTail() {
        _uiState.update { it.copy(loading = true) }
        pageCache.clear()
        index = runCatching { LogFileIndex.build(logFile) }.getOrElse {
            _errors.tryEmit(it.message ?: it.toString())
            LogFileIndex.build(File("/nonexistent"))
        }
        rebuildFilterOrOpenTail()
    }

    private fun currentIndex(): LogFileIndex = runCatching {
        if (forceRebuild.getAndSet(false) || logFile.length() < index.fileLength) {
            pageCache.clear()
            LogFileIndex.build(logFile)
        } else {
            index.extend(logFile)
        }
    }.getOrElse {
        _errors.tryEmit(it.message ?: it.toString())
        index
    }

    private suspend fun handleFileChange() {
        val oldCount = index.lineCount
        val oldLength = index.fileLength
        index = currentIndex()
        if (_uiState.value.paused) {
            pausedLineCount = pausedLineCount?.coerceAtMost(index.lineCount)
            return
        }
        if (_uiState.value.loading) {
            _uiState.update {
                it.copy(
                    totalLines = index.lineCount,
                    virtualItemCount = if (it.virtualMode) {
                        LogVirtualPositionPolicy.itemCount(index.lineCount)
                    } else {
                        it.virtualItemCount
                    },
                )
            }
            return
        }
        val currentState = _uiState.value
        val currentFilter = currentState.filteredLineMap
        if (currentFilter != null) {
            if (index.lineCount < oldCount || index.fileLength < oldLength ||
                (index.lineCount == oldCount && index.fileLength != oldLength)
            ) {
                rebuildFilterOrOpenTail()
                return
            }
            val matchingLines = mutableListOf<LogcatLine>()
            var cursor = oldCount
            while (cursor < index.lineCount) {
                val count = minOf(FILTER_SCAN_PAGE.toLong(), index.lineCount - cursor).toInt()
                val page = index.read(logFile, cursor, count)
                matchingLines += LogcatLineParser.filter(
                    page,
                    currentState.severity,
                    currentState.query,
                )
                cursor += count
            }
            val updatedMap = currentFilter.appended(matchingLines.map { it.number })
            cache(matchingLines)
            val targetPosition = when {
                updatedMap.size == 0 -> 0
                currentState.atTail -> updatedMap.size - 1
                else -> updatedMap.positionOf(
                    currentState.scrollTargetLine ?: currentFilter[currentFilter.size - 1],
                )
            }
            publishFilteredVirtual(
                updatedMap,
                targetPosition,
                if (currentState.atTail) seekRequests.incrementAndGet() else currentState.scrollRequestId,
                currentState.atTail,
            )
            return
        }
        if (_uiState.value.atTail) {
            val state = _uiState.value
            if (state.virtualMode) {
                if (index.lineCount > 0) {
                    val start = (index.lineCount - PAGE_SIZE).coerceAtLeast(0)
                    cache(index.read(logFile, start, PAGE_SIZE))
                    publishVirtual(
                        index.lineCount - 1,
                        seekRequests.incrementAndGet(),
                        atTail = true,
                    )
                } else {
                    publish(emptyList(), false, false, true)
                }
                return
            }
            if (index.lineCount > oldCount && index.fileLength >= oldLength) {
                val readStart = maxOf(oldCount, index.lineCount - MAX_WINDOW)
                val appended = index.read(
                    logFile,
                    readStart,
                    minOf(MAX_WINDOW.toLong(), index.lineCount - readStart).toInt(),
                ).let { lines ->
                    if (isFiltered(state)) {
                        LogcatLineParser.filter(lines, state.severity, state.query)
                    } else {
                        lines
                    }
                }
                if (appended.isEmpty()) {
                    _uiState.update { it.copy(totalLines = index.lineCount) }
                } else {
                    val combined = (state.lines + appended).distinctBy { it.number }.takeLast(MAX_WINDOW)
                    publish(
                        combined,
                        hasOlder = combined.firstOrNull()?.number?.let { it > 0 } == true,
                        hasNewer = false,
                        atTail = true,
                    )
                }
            } else if (index.fileLength != oldLength || index.lineCount != oldCount) {
                openTail()
            }
        } else if (index.lineCount != oldCount) {
            _uiState.update {
                if (it.filteredLineMap != null) {
                    it.copy(sourceLineCount = index.lineCount)
                } else {
                    it.copy(
                        totalLines = index.lineCount,
                        sourceLineCount = index.lineCount,
                        virtualItemCount = if (it.virtualMode) {
                            LogVirtualPositionPolicy.itemCount(index.lineCount)
                        } else {
                            it.virtualItemCount
                        },
                    )
                }
            }
        }
    }

    private suspend fun rebuildFilterOrOpenTail(requestId: Long = filterRequests.get()) {
        val state = _uiState.value
        if (requestId != filterRequests.get()) return
        if (!isFiltered(state)) {
            openTail()
            return
        }
        _uiState.update { it.copy(loading = true) }
        val endpoint = endpointLineCount()
        val builder = LogLineMap.Builder()
        var cursor = 0L
        while (cursor < endpoint) {
            if (requestId != filterRequests.get()) return
            val count = minOf(FILTER_SCAN_PAGE.toLong(), endpoint - cursor).toInt()
            val page = index.read(logFile, cursor, count)
            LogcatLineParser.filter(page, state.severity, state.query).forEach {
                builder.add(it.number)
            }
            cursor += count
        }
        val lineMap = builder.build()
        if (requestId != filterRequests.get()) return
        if (lineMap.size == 0) {
            publishFilteredVirtual(lineMap, targetPosition = 0, seekRequests.incrementAndGet(), true)
            return
        }
        val targetPosition = lineMap.size - 1
        val targetLine = lineMap[targetPosition]
        val pageStart = (targetLine - PAGE_SIZE / 2).coerceAtLeast(0)
            .coerceAtMost((endpoint - PAGE_SIZE).coerceAtLeast(0))
        cache(index.read(logFile, pageStart, PAGE_SIZE))
        publishFilteredVirtual(
            lineMap,
            targetPosition,
            seekRequests.incrementAndGet(),
            atTail = true,
        )
    }

    private suspend fun openTail(scrollRequestId: Long? = null) {
        val endpoint = endpointLineCount()
        val state = _uiState.value
        if (endpoint == 0L) {
            if (!isFiltered(state)) {
                publishVirtual(0, scrollRequestId ?: seekRequests.incrementAndGet(), atTail = true)
            } else {
                publish(emptyList(), false, false, true)
            }
            return
        }
        if (!isFiltered(state)) {
            val requestId = scrollRequestId ?: seekRequests.incrementAndGet()
            val start = (endpoint - PAGE_SIZE).coerceAtLeast(0)
            cache(index.read(logFile, start, PAGE_SIZE))
            publishVirtual(endpoint - 1, requestId, atTail = true)
            return
        }
        val searchResult = if (isFiltered(state)) {
            searchBackward(endpoint, SEARCH_PAGE, state.severity, state.query)
        } else {
            null
        }
        val lines = searchResult?.first ?: index.read(
            logFile,
            (endpoint - INITIAL_WINDOW).coerceAtLeast(0),
            INITIAL_WINDOW,
        )
        publish(
            lines,
            hasOlder = searchResult?.second?.let { it > 0 }
                ?: (lines.firstOrNull()?.number?.let { it > 0 } == true),
            hasNewer = false,
            atTail = true,
            scrollRequestId = scrollRequestId ?: _uiState.value.scrollRequestId,
            scrollTargetLine = lines.lastOrNull()?.number,
        )
    }

    private fun cache(lines: List<LogcatLine>) {
        lines.forEach { pageCache[it.number] = it }
    }

    private fun publishVirtual(target: Long, requestId: Long, atTail: Boolean) {
        val endpoint = endpointLineCount()
        _uiState.update {
            it.copy(
                lines = emptyList(),
                cachedLines = pageCache.toMap(),
                virtualItemCount = LogVirtualPositionPolicy.itemCount(endpoint),
                virtualMode = true,
                filteredLineMap = null,
                totalLines = endpoint,
                sourceLineCount = endpoint,
                loading = false,
                hasOlder = endpoint > 0 && target > 0,
                hasNewer = endpoint > 0 && target < endpoint - 1,
                atTail = atTail,
                scrollRequestId = requestId,
                scrollTargetLine = target,
                generation = it.generation + 1,
            )
        }
    }

    private fun publishFilteredVirtual(
        lineMap: LogLineMap,
        targetPosition: Int,
        requestId: Long,
        atTail: Boolean,
    ) {
        val targetLine = if (lineMap.size == 0) 0 else lineMap[targetPosition]
        _uiState.update {
            it.copy(
                lines = emptyList(),
                cachedLines = pageCache.toMap(),
                virtualItemCount = lineMap.size,
                virtualMode = true,
                filteredLineMap = lineMap,
                totalLines = lineMap.size.toLong(),
                sourceLineCount = endpointLineCount(),
                loading = false,
                hasOlder = targetPosition > 0,
                hasNewer = targetPosition < lineMap.size - 1,
                atTail = atTail,
                scrollRequestId = requestId,
                scrollTargetLine = targetLine,
                generation = it.generation + 1,
            )
        }
    }

    private suspend fun loadOlderLocked() {
        val state = _uiState.value
        if (state.loading || !state.hasOlder || state.lines.isEmpty()) return
        _uiState.update { it.copy(loading = true) }
        val end = state.lines.first().number
        val searchResult = if (isFiltered(state)) {
            searchBackward(end, SEARCH_PAGE, state.severity, state.query)
        } else {
            null
        }
        val older = searchResult?.first ?: run {
            val start = (end - PAGE_SIZE).coerceAtLeast(0)
            index.read(logFile, start, (end - start).toInt())
        }
        val combined = (older + state.lines).takeLast(MAX_WINDOW)
        publish(
            combined,
            hasOlder = searchResult?.second?.let { it > 0 }
                ?: (combined.firstOrNull()?.number?.let { it > 0 } == true),
            hasNewer = combined.lastOrNull()?.number?.let { it < endpointLineCount() - 1 } == true,
            atTail = false,
        )
    }

    private suspend fun loadNewerLocked() {
        val state = _uiState.value
        if (state.loading || !state.hasNewer || state.lines.isEmpty()) return
        _uiState.update { it.copy(loading = true) }
        val start = state.lines.last().number + 1
        val endpoint = endpointLineCount()
        val searchResult = if (isFiltered(state)) {
            searchForward(start, endpoint, SEARCH_PAGE, state.severity, state.query)
        } else {
            null
        }
        val newer = searchResult?.first ?: index.read(logFile, start, PAGE_SIZE)
        val combined = (state.lines + newer).takeLast(MAX_WINDOW)
        val reachesTail = searchResult?.second?.let { it >= endpoint }
            ?: (combined.lastOrNull()?.number == endpoint - 1)
        publish(
            combined,
            hasOlder = combined.firstOrNull()?.number?.let { it > 0 } == true,
            hasNewer = !reachesTail,
            atTail = reachesTail,
        )
    }

    private fun searchBackward(
        exclusiveEnd: Long,
        limit: Int,
        severity: LogcatSeverity,
        query: String,
    ): Pair<List<LogcatLine>, Long> {
        var cursor = exclusiveEnd
        var matches = emptyList<LogcatLine>()
        while (cursor > 0 && matches.size < limit) {
            val start = (cursor - SEARCH_SCAN_LINES).coerceAtLeast(0)
            val page = index.read(logFile, start, (cursor - start).toInt())
            matches = LogcatLineParser.filter(page, severity, query) + matches
            cursor = start
        }
        val selected = matches.takeLast(limit)
        val nextCursor = if (matches.size >= limit) {
            selected.firstOrNull()?.number ?: cursor
        } else {
            cursor
        }
        return selected to nextCursor
    }

    private fun searchForward(
        startLine: Long,
        exclusiveEnd: Long,
        limit: Int,
        severity: LogcatSeverity,
        query: String,
    ): Pair<List<LogcatLine>, Long> {
        var cursor = startLine
        val matches = mutableListOf<LogcatLine>()
        while (cursor < exclusiveEnd && matches.size < limit) {
            val count = minOf(SEARCH_SCAN_LINES.toLong(), exclusiveEnd - cursor).toInt()
            val page = index.read(logFile, cursor, count)
            val pageMatches = LogcatLineParser.filter(page, severity, query)
            val remaining = limit - matches.size
            if (pageMatches.size >= remaining) {
                val selected = pageMatches.take(remaining)
                matches += selected
                return matches to (selected.lastOrNull()?.number?.plus(1) ?: cursor)
            }
            matches += pageMatches
            cursor += count
        }
        return matches.take(limit) to cursor
    }

    private fun searchAround(
        target: Long,
        severity: LogcatSeverity,
        query: String,
        endpoint: Long,
    ): List<LogcatLine> {
        val older = searchBackward(target + 1, INITIAL_WINDOW / 2, severity, query).first
        val newer = searchForward(target + 1, endpoint, INITIAL_WINDOW / 2, severity, query).first
        return (older + newer).take(MAX_WINDOW)
    }

    private fun endpointLineCount(): Long = pausedLineCount ?: index.lineCount

    private fun isFiltered(state: LogcatUiState): Boolean =
        state.query.isNotEmpty() || state.severity != LogcatSeverity.TRACE

    private fun publish(
        lines: List<LogcatLine>,
        hasOlder: Boolean,
        hasNewer: Boolean,
        atTail: Boolean,
        scrollRequestId: Long = _uiState.value.scrollRequestId,
        scrollTargetLine: Long? = _uiState.value.scrollTargetLine,
    ) {
        _uiState.update {
            it.copy(
                lines = lines,
                cachedLines = emptyMap(),
                virtualItemCount = lines.size,
                virtualMode = false,
                filteredLineMap = null,
                totalLines = endpointLineCount(),
                sourceLineCount = endpointLineCount(),
                loading = false,
                hasOlder = hasOlder,
                hasNewer = hasNewer,
                atTail = atTail,
                scrollRequestId = scrollRequestId,
                scrollTargetLine = scrollTargetLine,
                generation = it.generation + 1,
            )
        }
    }

    override fun onCleared() {
        queryJob?.cancel()
        seekJob?.cancel()
        pageJob?.cancel()
        fileObserver.stopWatching()
        fileEvents.close()
    }

    private companion object {
        const val MAX_WINDOW = 3_000
        const val INITIAL_WINDOW = 1_500
        const val PAGE_SIZE = 500
        const val SEARCH_PAGE = 500
        const val SEARCH_SCAN_LINES = 512
        const val SEARCH_DEBOUNCE_MS = 300L
        const val FILE_EVENT_DEBOUNCE_MS = 100L
        const val PAGE_CACHE_LIMIT = 3_072
        const val FILTER_SCAN_PAGE = 1_024
        const val MAX_VIEWPORT_PAGES = 32
    }
}
