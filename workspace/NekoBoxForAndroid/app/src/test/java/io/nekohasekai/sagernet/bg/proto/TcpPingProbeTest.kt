package io.nekohasekai.sagernet.bg.proto

import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class TcpPingProbeTest {
    @Test
    fun `falls back to the next address only for address-family failures`() {
        val attempted = mutableListOf<String>()
        val probe = probe(
            addresses = listOf("2001:db8::1", "192.0.2.1"),
            ping = { address ->
                attempted += address
                if (address.contains(':')) error("connect: ENETUNREACH")
                42
            },
        )

        assertEquals(TcpPingOutcome.Success(42, "192.0.2.1"), probe.execute(request()))
        assertEquals(listOf("2001:db8::1", "192.0.2.1"), attempted)
    }

    @Test
    fun `does not hide a refused connection behind later addresses`() {
        val attempted = mutableListOf<String>()
        val outcome = probe(
            addresses = listOf("192.0.2.1", "192.0.2.2"),
            ping = { address -> attempted += address; error("ECONNREFUSED") },
        ).execute(request())

        assertEquals(TcpPingOutcome.Failure(PingFailureKind.Refused, "ECONNREFUSED"), outcome)
        assertEquals(listOf("192.0.2.1"), attempted)
    }

    @Test
    fun `empty or failed resolution is reported as domain not found`() {
        assertEquals(
            PingFailureKind.DomainNotFound,
            (probe(emptyList()) { 1 }.execute(request()) as TcpPingOutcome.Failure).kind,
        )
        val failed = TcpPingProbe(
            resolveHost = { throw UnknownHostException("unknown host") },
            pingAddress = { _, _, _ -> 1 },
            pingHostHardened = { _, _, _ -> error("unused") },
        ).execute(request()) as TcpPingOutcome.Failure
        assertEquals(PingFailureKind.DomainNotFound, failed.kind)
    }

    @Test
    fun `hardened probing keeps the resolved address`() {
        val probe = TcpPingProbe(
            resolveHost = { error("unused") },
            pingAddress = { _, _, _ -> error("unused") },
            pingHostHardened = { _, _, _ -> TcpPingOutcome.Success(17, "203.0.113.7") },
        )
        assertEquals(
            TcpPingOutcome.Success(17, "203.0.113.7"),
            probe.execute(request(hardened = true)),
        )
    }

    private fun probe(
        addresses: List<String>,
        ping: (String) -> Int,
    ) = TcpPingProbe(
        resolveHost = { addresses },
        pingAddress = { address, _, _ -> ping(address) },
        pingHostHardened = { _, _, _ -> error("unused") },
    )

    private fun request(hardened: Boolean = false) = TcpPingRequest(
        host = "example.test",
        port = "443",
        timeoutMillis = 3_000,
        hardened = hardened,
        hostIsIpAddress = false,
    )
}
