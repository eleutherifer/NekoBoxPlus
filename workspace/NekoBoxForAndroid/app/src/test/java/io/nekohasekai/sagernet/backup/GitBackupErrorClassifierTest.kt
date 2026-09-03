package io.nekohasekai.sagernet.backup

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

class GitBackupErrorClassifierTest {
    @Test
    fun `classifies typed network failures through cause chain`() {
        assertEquals(
            GitBackupError.DNS,
            GitBackupErrorClassifier.classify(RuntimeException(UnknownHostException("host"))),
        )
        assertEquals(
            GitBackupError.TLS,
            GitBackupErrorClassifier.classify(RuntimeException(SSLHandshakeException("certificate"))),
        )
        assertEquals(
            GitBackupError.TIMEOUT,
            GitBackupErrorClassifier.classify(RuntimeException(SocketTimeoutException())),
        )
    }

    @Test
    fun `distinguishes authentication repository and web challenge failures`() {
        assertEquals(
            GitBackupError.AUTHENTICATION,
            GitBackupErrorClassifier.classify(RuntimeException("not authorized (HTTP 401)")),
        )
        assertEquals(
            GitBackupError.REPOSITORY_NOT_FOUND,
            GitBackupErrorClassifier.classify(RuntimeException("repository not found")),
        )
        assertEquals(
            GitBackupError.CLIENT_REJECTED,
            GitBackupErrorClassifier.classify(RuntimeException("invalid advertisement: text/html from Anubis")),
        )
    }

    @Test
    fun `identifies packaged resource failures as internal`() {
        assertEquals(
            GitBackupError.INTERNAL,
            GitBackupErrorClassifier.classify(
                RuntimeException("org.eclipse.jgit.nls.TranslationBundleLoadingException"),
            ),
        )
    }
}
