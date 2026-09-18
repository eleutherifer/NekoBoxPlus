package io.nekohasekai.sagernet.bg

import android.os.SystemClock
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel as cancelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One instance per core lifetime. All power/recovery work runs off the receiver thread. */
internal class ConnectionRecoveryQueue(
    private val urlTestRunning: () -> Boolean,
    private val pause: (Boolean) -> Unit,
    private val recover: (Boolean, Boolean, ServiceRestartCause) -> Unit,
    private val log: (String) -> Unit = { Logs.d(it) },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable {
    private sealed interface Event {
        data class Idle(val idle: Boolean, val reconnect: Boolean, val reset: Boolean) : Event
        data class Screen(
            val on: Boolean,
            val reconnect: Boolean,
            val reset: Boolean,
            val elapsedRealtime: Long,
        ) : Event
        data class Network(val available: Boolean) : Event
        data class Request(val reconnect: Boolean, val reset: Boolean, val cause: ServiceRestartCause) : Event
        data object Ready : Event
        data object Flush : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val pending = PendingConnectionRecovery()

    init {
        scope.launch {
            var ready = false
            var idle = false
            var networkAvailable = false
            var screenOffAt: Long? = null
            var wakeRecoveryRequested = false
            var flushJob: Job? = null
            for (event in events) {
                try {
                    when (event) {
                        is Event.Idle -> {
                            val wasIdle = idle
                            idle = event.idle
                            if (idle) wakeRecoveryRequested = false
                            if (wasIdle != idle && ready) pause(idle)
                            if (wasIdle && !idle && !wakeRecoveryRequested) {
                                pending.request(event.reconnect, event.reset, ServiceRestartCause.WakeReconnect)
                                wakeRecoveryRequested = event.reconnect || event.reset
                            }
                        }
                        is Event.Screen -> {
                            if (!event.on) {
                                screenOffAt = event.elapsedRealtime
                                wakeRecoveryRequested = false
                            } else {
                                val recordedScreenOffAt = screenOffAt
                                screenOffAt = null
                                if (
                                    recordedScreenOffAt != null &&
                                    event.elapsedRealtime - recordedScreenOffAt >= SCREEN_OFF_RECOVERY_THRESHOLD_MS &&
                                    !wakeRecoveryRequested
                                ) {
                                    pending.request(
                                        event.reconnect,
                                        event.reset,
                                        ServiceRestartCause.WakeReconnect,
                                    )
                                    wakeRecoveryRequested = event.reconnect || event.reset
                                }
                            }
                        }
                        is Event.Network -> networkAvailable = event.available
                        is Event.Request -> pending.request(event.reconnect, event.reset, event.cause)
                        Event.Ready -> {
                            ready = true
                            pause(idle)
                        }
                        Event.Flush -> {
                            flushJob = null
                            pending.take(ready && !idle && networkAvailable && !urlTestRunning())?.let {
                                log("Recover upstream connections: cause=${it.cause} reconnect=${it.reconnect}")
                                recover(it.reconnect, it.reset, it.cause)
                            }
                        }
                    }
                    if (event != Event.Flush && pending.hasPending) {
                        flushJob?.cancel()
                        flushJob = launch {
                            delay(1_000)
                            events.trySend(Event.Flush)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Logs.w(error)
                }
            }
        }
    }

    fun idle(idle: Boolean, reconnect: Boolean, reset: Boolean) {
        events.trySend(Event.Idle(idle, reconnect, reset))
    }

    fun network(available: Boolean) {
        events.trySend(Event.Network(available))
    }

    fun screen(on: Boolean, reconnect: Boolean, reset: Boolean) {
        events.trySend(Event.Screen(on, reconnect, reset, elapsedRealtime()))
    }

    fun ready() {
        events.trySend(Event.Ready)
    }

    fun urlTestFinished() {
        events.trySend(Event.Flush)
    }

    fun request(reconnect: Boolean, reset: Boolean, cause: ServiceRestartCause) {
        events.trySend(Event.Request(reconnect, reset, cause))
    }

    override fun close() {
        events.close()
        scope.cancelScope()
    }

    private companion object {
        const val SCREEN_OFF_RECOVERY_THRESHOLD_MS = 2 * 60 * 1_000L
    }
}

internal class PendingConnectionRecovery {
    data class Request(val reconnect: Boolean, val reset: Boolean, val cause: ServiceRestartCause)
    private var pending: Request? = null
    val hasPending: Boolean get() = pending != null

    fun request(reconnect: Boolean, reset: Boolean, cause: ServiceRestartCause) {
        if (!reconnect && !reset) return
        val previous = pending
        pending = Request(reconnect || previous?.reconnect == true, reset || previous?.reset == true, cause)
    }

    fun take(allowed: Boolean): Request? {
        if (!allowed) return null
        return pending.also { pending = null }
    }
}
