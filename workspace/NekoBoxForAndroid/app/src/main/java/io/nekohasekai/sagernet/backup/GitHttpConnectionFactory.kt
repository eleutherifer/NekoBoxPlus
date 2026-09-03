package io.nekohasekai.sagernet.backup

import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.eclipse.jgit.transport.http.JDKHttpConnection
import java.net.Proxy
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Produces normal JDK-backed JGit connections with the request fingerprint expected by
 * Git-aware reverse proxies such as Anubis. JGit normally sends a Git-specific Accept value and
 * omits cache headers, which makes an otherwise valid smart-HTTP request look like a browser.
 */
internal object GitHttpConnectionFactory : HttpConnectionFactory {
    override fun create(url: URL): HttpConnection = GitHttpConnection(url)

    override fun create(url: URL, proxy: Proxy): HttpConnection = GitHttpConnection(url, proxy)
}

private class GitHttpConnection : JDKHttpConnection {
    constructor(url: URL) : super(url) {
        applyRequiredHeaders()
    }

    constructor(url: URL, proxy: Proxy) : super(url, proxy) {
        applyRequiredHeaders()
    }

    override fun setRequestProperty(key: String, value: String) {
        when {
            key.equals("Accept", ignoreCase = true) -> super.setRequestProperty(key, "*/*")
            key.equals("Cache-Control", ignoreCase = true) ->
                super.setRequestProperty(key, "no-cache")
            key.equals("Pragma", ignoreCase = true) -> super.setRequestProperty(key, "no-cache")
            key.equals("Accept-Encoding", ignoreCase = true) ->
                super.setRequestProperty(key, "gzip")
            else -> super.setRequestProperty(key, value)
        }
    }

    override fun getInputStream(): java.io.InputStream {
        val input = super.getInputStream()
        return if (getHeaderField("Content-Encoding")?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(input)
        } else {
            input
        }
    }

    private fun applyRequiredHeaders() {
        setRequestProperty("Accept", "*/*")
        setRequestProperty("Cache-Control", "no-cache")
        setRequestProperty("Pragma", "no-cache")
        setRequestProperty("Accept-Encoding", "gzip")
    }
}
