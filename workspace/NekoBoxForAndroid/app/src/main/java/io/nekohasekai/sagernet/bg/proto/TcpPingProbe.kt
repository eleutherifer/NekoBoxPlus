package io.nekohasekai.sagernet.bg.proto

import java.net.UnknownHostException

internal enum class PingFailureKind {
    DomainNotFound,
    Refused,
    Unreachable,
    Timeout,
    Other,
}

internal sealed interface TcpPingOutcome {
    data class Success(val latency: Int, val address: String) : TcpPingOutcome
    data class Failure(val kind: PingFailureKind, val detail: String) : TcpPingOutcome
}

internal data class TcpPingRequest(
    val host: String,
    val port: String,
    val timeoutMillis: Int,
    val hardened: Boolean,
    val hostIsIpAddress: Boolean,
)

/** Runs TCP probing policy independently from Android networking and libcore bindings. */
internal class TcpPingProbe(
    private val resolveHost: (String) -> List<String>,
    private val pingAddress: (String, String, Int) -> Int,
    private val pingHostHardened: (String, String, Int) -> TcpPingOutcome.Success,
    private val errorMessage: (Throwable) -> String = { it.message ?: it.toString() },
) {
    fun execute(request: TcpPingRequest): TcpPingOutcome {
        if (request.hardened) {
            return runCatching {
                pingHostHardened(request.host, request.port, request.timeoutMillis)
            }.getOrElse(::failure)
        }

        val addresses = if (request.hostIsIpAddress) {
            listOf(request.host)
        } else {
            runCatching { resolveHost(request.host) }
                .getOrElse { return failure(it, resolution = it is UnknownHostException) }
        }
        if (addresses.isEmpty()) return TcpPingOutcome.Failure(PingFailureKind.DomainNotFound, "")

        var lastFailure: TcpPingOutcome.Failure? = null
        for (address in addresses) {
            val result = runCatching {
                TcpPingOutcome.Success(
                    latency = pingAddress(address, request.port, request.timeoutMillis),
                    address = address,
                )
            }.getOrElse { error ->
                failure(error).also { lastFailure = it }
            }
            if (result is TcpPingOutcome.Success) return result
            if ((result as TcpPingOutcome.Failure).kind != PingFailureKind.Unreachable) return result
        }
        return lastFailure ?: TcpPingOutcome.Failure(PingFailureKind.Unreachable, "")
    }

    private fun failure(error: Throwable, resolution: Boolean = false): TcpPingOutcome.Failure {
        val message = errorMessage(error)
        return TcpPingOutcome.Failure(classifyPingFailure(message, resolution), message)
    }
}

internal fun classifyPingFailure(message: String, resolution: Boolean = false): PingFailureKind {
    if (resolution || message.contains("resolve TCP ping host", ignoreCase = true)) {
        return PingFailureKind.DomainNotFound
    }
    return when {
        message.contains("ECONNREFUSED", ignoreCase = true) -> PingFailureKind.Refused
        message.contains("ENETUNREACH", ignoreCase = true) ||
            message.contains("EHOSTUNREACH", ignoreCase = true) ||
            message.contains("EAFNOSUPPORT", ignoreCase = true) -> PingFailureKind.Unreachable
        message.contains("deadline exceeded", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) -> PingFailureKind.Timeout
        else -> PingFailureKind.Other
    }
}
