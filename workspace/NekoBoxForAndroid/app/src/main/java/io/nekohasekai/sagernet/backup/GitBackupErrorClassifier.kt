package io.nekohasekai.sagernet.backup

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

enum class GitBackupError {
    AUTHENTICATION,
    CLIENT_REJECTED,
    REPOSITORY_NOT_FOUND,
    DNS,
    TLS,
    TIMEOUT,
    INTERNAL,
    REMOTE,
}

object GitBackupErrorClassifier {
    fun classify(error: Throwable): GitBackupError {
        val causes = generateSequence(error) { it.cause }.toList()
        if (causes.any { it is UnknownHostException }) return GitBackupError.DNS
        if (causes.any { it is SSLException }) return GitBackupError.TLS
        if (causes.any { it is SocketTimeoutException }) return GitBackupError.TIMEOUT

        val details = causes.joinToString(" ") {
            "${it.javaClass.name} ${it.message.orEmpty()}"
        }.lowercase(Locale.ROOT)
        return when {
            "translationbundleloadingexception" in details ||
                "missingresourceexception" in details -> GitBackupError.INTERNAL
            "anubis" in details || "invalid advertisement" in details ||
                "expected git-upload-pack" in details || "text/html" in details ||
                "client rejected" in details -> GitBackupError.CLIENT_REJECTED
            "not authorized" in details || "authentication" in details ||
                "auth fail" in details || "401" in details -> GitBackupError.AUTHENTICATION
            "repository not found" in details || "not found" in details ||
                "404" in details -> GitBackupError.REPOSITORY_NOT_FOUND
            "timed out" in details || "timeout" in details -> GitBackupError.TIMEOUT
            "certificate" in details || "ssl" in details || "tls" in details ->
                GitBackupError.TLS
            "unknown host" in details || "unable to resolve host" in details ->
                GitBackupError.DNS
            else -> GitBackupError.REMOTE
        }
    }
}
