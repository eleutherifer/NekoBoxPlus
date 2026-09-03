package moe.matsuri.nb4a

import moe.matsuri.nb4a.Protocols.ConnectionTestError
import moe.matsuri.nb4a.Protocols.ConnectionTestErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolsConnectionTestErrorTest {

    @Test
    fun classifiesRawDatabaseErrors() {
        val messages = mapOf(
            "dial tcp 188.72.103.3:443: failed to send: broken pipe" to ConnectionTestErrorType.CLOSED,
            "Get \"https://www.gstatic.com/generate_204\": authentication failed, status code: 405" to ConnectionTestErrorType.AUTHENTICATION,
            "Get \"https://www.gstatic.com/generate_204\": context deadline exceeded" to ConnectionTestErrorType.TIMEOUT,
            "Get \"https://www.gstatic.com/generate_204\": dial wlan0 (27): dial tcp 130.94.92.81:8443: connect: connection refused" to ConnectionTestErrorType.REFUSED,
            "Get \"https://www.gstatic.com/generate_204\": dial wlan0 (27): dial tcp 185.199.38.106:8443: connect: connection refused" to ConnectionTestErrorType.REFUSED,
            "Get \"https://www.gstatic.com/generate_204\": dial wlan0 (27): dial tcp 213.219.213.43:8443: connect: connection refused" to ConnectionTestErrorType.REFUSED,
            "Get \"https://www.gstatic.com/generate_204\": EOF" to ConnectionTestErrorType.CLOSED,
            "Get \"https://www.gstatic.com/generate_204\": net/http: TLS handshake timeout" to ConnectionTestErrorType.TIMEOUT,
            "Head \"http://64.233.161.94/generate_204\": bad status code: 502 Bad Gateway" to ConnectionTestErrorType.HTTP_STATUS,
            "Head \"http://64.233.161.94/generate_204\": EOF" to ConnectionTestErrorType.CLOSED,
            "context deadline exceeded" to ConnectionTestErrorType.TIMEOUT,
            "Head \"http://64.233.161.94/generate_204\": dial wlan0 (27): dial tcp 51.250.7.122:443: failed to send: broken pipe" to ConnectionTestErrorType.CLOSED,
            "Head \"http://64.233.161.94/generate_204\": (dial rmnet_data2 (21): dial tcp 45.12.75.242:26424: connect: network is unreachable | dial wlan0 (27): dial tcp 45.12.75.242:26424: connect: network is unreachable)" to ConnectionTestErrorType.UNREACHABLE,
            "Head \"http://64.233.161.94/generate_204\": bad status code: 500 Internal Server Error" to ConnectionTestErrorType.HTTP_STATUS,
            "Head \"http://64.233.161.94/generate_204\": read tcp 192.168.1.235:58784->62.152.59.193:443: read: software caused connection abort" to ConnectionTestErrorType.CLOSED,
            "Head \"http://64.233.161.94/generate_204\": (dial rmnet_data2 (21): dial tcp 45.12.75.242:26424: failed to send: broken pipe | dial wlan0 (27): dial tcp 45.12.75.242:26424: connect: network is unreachable)" to ConnectionTestErrorType.UNREACHABLE,
            "Head \"http://64.233.161.94/generate_204\": tls: CurvePreferences includes unsupported curve" to ConnectionTestErrorType.TLS,
            "Head \"http://64.233.161.94/generate_204\": Post \"https://stats.vk-portal.net/\": dial wlan0 (27): dial tcp 185.147.27.155:443: failed to send: broken pipe" to ConnectionTestErrorType.CLOSED,
        )

        messages.forEach { (message, expected) ->
            assertEquals(message, expected, Protocols.classifyConnectionTestError(message).type)
        }
    }

    @Test
    fun classifiesDnsResolutionFailures() {
        val messages = listOf(
            "resolve URLTest host: lookup failed",
            "URLTest DNS router is unavailable",
            "remote DNS returned no addresses",
            "exchange: network error",
            "exchange4: network error",
            "exchange6: network error",
            "dial tcp: lookup example.com: no such host",
            "could not resolve peer: example.com",
            "temporary failure in name resolution",
        )

        messages.forEach { message ->
            assertEquals(message, ConnectionTestErrorType.DNS, Protocols.classifyConnectionTestError(message).type)
        }
    }

    @Test
    fun cancellationTakesPrecedenceOverDnsFailure() {
        val messages = listOf(
            "context canceled",
            "context cancelled",
            "exchange6: context canceled",
        )

        messages.forEach { message ->
            assertEquals(message, ConnectionTestErrorType.CANCELLED, Protocols.classifyConnectionTestError(message).type)
        }
    }

    @Test
    fun classifiesAuthenticationWithoutRetainingCredentials() {
        val messages = listOf(
            "socks5: authentication failed, username=user, password=secret",
            "http: authorization failed, Proxy-Authorization=secret",
            "masque: login failed; verify enrollment",
        )

        messages.forEach { message ->
            assertEquals(
                message,
                ConnectionTestError(ConnectionTestErrorType.AUTHENTICATION),
                Protocols.classifyConnectionTestError(message),
            )
        }

        assertEquals(
            ConnectionTestError(ConnectionTestErrorType.AUTHENTICATION, statusCode = 405),
            Protocols.classifyConnectionTestError("authentication failed, status code: 405"),
        )
    }

    @Test
    fun classifiesCommonNetworkFailures() {
        val closedMessages = listOf(
            "EOF",
            "request failed: EOF",
            "unexpected EOF",
            "failed to send: broken pipe",
            "closed pipe",
            "connection reset by peer",
            "connection aborted",
            "connection closed",
            "socket closed unexpectedly",
        )
        val unreachableMessages = listOf(
            "connect: ENETDOWN",
            "connect: ENETUNREACH",
            "connect: EHOSTUNREACH",
            "network is unreachable",
            "host is unreachable",
            "no route to host",
            "no available network interface",
        )

        closedMessages.forEach { message ->
            assertEquals(message, ConnectionTestErrorType.CLOSED, Protocols.classifyConnectionTestError(message).type)
        }
        unreachableMessages.forEach { message ->
            assertEquals(message, ConnectionTestErrorType.UNREACHABLE, Protocols.classifyConnectionTestError(message).type)
        }
        assertEquals(
            ConnectionTestErrorType.REFUSED,
            Protocols.classifyConnectionTestError("connect: ECONNREFUSED").type,
        )
    }

    @Test
    fun extractsSafeHttpStatusDetails() {
        assertEquals(
            ConnectionTestError(ConnectionTestErrorType.HTTP_STATUS, 502, "Bad Gateway"),
            Protocols.classifyConnectionTestError("bad status code: 502 Bad Gateway"),
        )

        val unsafeReason = Protocols.classifyConnectionTestError("bad status code: 503 bad_gateway!")
        assertEquals(ConnectionTestErrorType.HTTP_STATUS, unsafeReason.type)
        assertEquals(503, unsafeReason.statusCode)
        assertNull(unsafeReason.reason)
    }

    @Test
    fun leavesUnknownFailuresForTheDetailedFallback() {
        assertEquals(
            ConnectionTestErrorType.TLS,
            Protocols.classifyConnectionTestError("TLS handshake failed").type,
        )
        assertEquals(
            ConnectionTestErrorType.OTHER,
            Protocols.classifyConnectionTestError("unknown transport failure").type,
        )
    }
}
