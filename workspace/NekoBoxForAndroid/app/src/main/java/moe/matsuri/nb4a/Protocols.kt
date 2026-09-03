package moe.matsuri.nb4a

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NEKO
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr

// Settings for all protocols, built-in or plugin
object Protocols {

    // Deduplication

    class Deduplication(
        val bean: AbstractBean
    ) {

        fun hash(): String = bean.hash

        override fun hashCode(): Int {
            return hash().toByteArray().contentHashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Deduplication

            return hash() == other.hash()
        }

    }

    // Display

    fun Context.getProtocolColor(type: Int): Int {
        return when (type) {
            TYPE_NEKO -> getColorAttr(android.R.attr.textColorPrimary)
            else -> getColorAttr(R.attr.colorPrimary)
        }
    }

    // Test

    fun genFriendlyMsg(msg: String): String {
        val error = classifyConnectionTestError(msg)
        return when (error.type) {
            ConnectionTestErrorType.CANCELLED -> app.getString(R.string.connection_test_cancelled)
            ConnectionTestErrorType.DNS -> app.getString(R.string.connection_test_dns_error)
            ConnectionTestErrorType.AUTHENTICATION -> error.statusCode?.let {
                app.getString(R.string.connection_test_authentication_error_status_code, it)
            } ?: app.getString(R.string.connection_test_authentication_error)
            ConnectionTestErrorType.TIMEOUT -> app.getString(R.string.connection_test_timeout_error)
            ConnectionTestErrorType.REFUSED -> app.getString(R.string.connection_test_refused)
            ConnectionTestErrorType.UNREACHABLE -> app.getString(R.string.connection_test_unreachable)
            ConnectionTestErrorType.CLOSED -> app.getString(R.string.connection_test_connection_closed)
            ConnectionTestErrorType.TLS -> app.getString(R.string.connection_test_tls_error)
            ConnectionTestErrorType.HTTP_STATUS -> if (error.reason != null) {
                app.getString(R.string.connection_test_http_status_error_with_reason, error.statusCode, error.reason)
            } else {
                app.getString(R.string.connection_test_http_status_error, error.statusCode)
            }
            ConnectionTestErrorType.OTHER -> app.getString(R.string.connection_test_error, msg)
        }
    }

    internal fun classifyConnectionTestError(msg: String): ConnectionTestError {
        val normalizedMessage = msg.lowercase()
        return when {
            normalizedMessage.contains("context canceled") ||
                    normalizedMessage.contains("context cancelled") -> ConnectionTestError(ConnectionTestErrorType.CANCELLED)

            normalizedMessage.contains("resolve urltest host") ||
                    normalizedMessage.contains("urltest dns") ||
                    normalizedMessage.contains("remote dns") ||
                    normalizedMessage.contains("exchange:") ||
                    normalizedMessage.contains("exchange4:") ||
                    normalizedMessage.contains("exchange6:") ||
                    normalizedMessage.contains("no such host") ||
                    normalizedMessage.contains("lookup failed") ||
                    normalizedMessage.contains("could not resolve") ||
                    normalizedMessage.contains("name resolution") -> ConnectionTestError(ConnectionTestErrorType.DNS)

            normalizedMessage.contains("authentication failed") ||
                    normalizedMessage.contains("authorization failed") ||
                    normalizedMessage.contains("login failed") -> ConnectionTestError(
                ConnectionTestErrorType.AUTHENTICATION,
                statusCodeRegex.find(msg)?.groupValues?.get(1)?.toIntOrNull(),
            )

            normalizedMessage.contains("timeout") ||
                    normalizedMessage.contains("timed out") ||
                    normalizedMessage.contains("deadline") -> ConnectionTestError(ConnectionTestErrorType.TIMEOUT)

            normalizedMessage.contains("refused") ||
                    normalizedMessage.contains("econnrefused") -> ConnectionTestError(ConnectionTestErrorType.REFUSED)

            normalizedMessage.contains("enetdown") ||
                    normalizedMessage.contains("enetunreach") ||
                    normalizedMessage.contains("ehostunreach") ||
                    normalizedMessage.contains("network is down") ||
                    normalizedMessage.contains("network is unreachable") ||
                    normalizedMessage.contains("host is unreachable") ||
                    normalizedMessage.contains("no route to host") ||
                    normalizedMessage.contains("no available network interface") -> ConnectionTestError(ConnectionTestErrorType.UNREACHABLE)

            normalizedMessage == "eof" ||
                    normalizedMessage.contains(": eof") ||
                    normalizedMessage.contains("unexpected eof") ||
                    normalizedMessage.contains("broken pipe") ||
                    normalizedMessage.contains("closed pipe") ||
                    normalizedMessage.contains("connection reset") ||
                    normalizedMessage.contains("connection aborted") ||
                    normalizedMessage.contains("software caused connection abort") ||
                    normalizedMessage.contains("connection closed") ||
                    normalizedMessage.contains("socket closed unexpectedly") -> ConnectionTestError(ConnectionTestErrorType.CLOSED)

            normalizedMessage.startsWith("tls:") ||
                    normalizedMessage.contains(": tls:") ||
                    normalizedMessage.contains("tls handshake") -> ConnectionTestError(ConnectionTestErrorType.TLS)

            else -> badStatusCodeRegex.find(msg)?.let { match ->
                val statusCode = match.groupValues[1].toInt()
                val reason = match.groupValues.getOrNull(2)
                    ?.trim()
                    ?.takeIf { it.matches(httpReasonRegex) }
                ConnectionTestError(ConnectionTestErrorType.HTTP_STATUS, statusCode, reason)
            } ?: ConnectionTestError(ConnectionTestErrorType.OTHER)
        }
    }

    internal data class ConnectionTestError(
        val type: ConnectionTestErrorType,
        val statusCode: Int? = null,
        val reason: String? = null,
    )

    internal enum class ConnectionTestErrorType {
        CANCELLED,
        DNS,
        AUTHENTICATION,
        TIMEOUT,
        REFUSED,
        UNREACHABLE,
        CLOSED,
        TLS,
        HTTP_STATUS,
        OTHER,
    }

    private val statusCodeRegex = Regex("""\bstatus code:\s*(\d{3})\b""", RegexOption.IGNORE_CASE)
    private val badStatusCodeRegex = Regex(
        """\bbad status code:\s*(\d{3})(?:\s+([^\r\n]+))?""",
        RegexOption.IGNORE_CASE,
    )
    private val httpReasonRegex = Regex("""[A-Za-z][A-Za-z -]{0,63}""")

}
