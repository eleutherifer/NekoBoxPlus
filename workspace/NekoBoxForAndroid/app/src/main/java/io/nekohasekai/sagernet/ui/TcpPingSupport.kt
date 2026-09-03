package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.proto.PingFailureKind
import io.nekohasekai.sagernet.bg.proto.TcpPingOutcome
import io.nekohasekai.sagernet.bg.proto.TcpPingProbe
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl

internal fun libcoreTcpPingProbe(resolveHost: (String) -> List<String>) = TcpPingProbe(
    resolveHost = resolveHost,
    pingAddress = { address, port, timeout ->
        Libcore.tcpPing(address, port, timeout, false, LocalResolverImpl)
    },
    pingHostHardened = { host, port, timeout ->
        Libcore.tcpPingWithAddress(host, port, timeout, true, LocalResolverImpl).let {
            TcpPingOutcome.Success(it.latency, it.address)
        }
    },
    errorMessage = Throwable::readableMessage,
)

internal fun PingFailureKind.localizedMessage(detail: String): String = when (this) {
    PingFailureKind.DomainNotFound -> app.getString(R.string.connection_test_domain_not_found)
    PingFailureKind.Refused -> app.getString(R.string.connection_test_refused)
    PingFailureKind.Unreachable -> app.getString(R.string.connection_test_unreachable)
    PingFailureKind.Timeout -> app.getString(R.string.connection_test_timeout_error)
    PingFailureKind.Other -> detail
}
