package io.nekohasekai.sagernet.backup

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebDavConnectionTesterTest {
    @Test
    fun directoryPathIsAppendedAsEncodedSegments() {
        val result = WebDavConnectionTester.buildDirectoryUrl(
            "https://example.com/dav/".toHttpUrl(),
            "/Neko Box/device/",
        )

        assertEquals("https://example.com/dav/Neko%20Box/device", result.toString())
    }

    @Test
    fun successfulStatusCodesHaveNoFailure() {
        assertNull(WebDavConnectionTester.classifyResponse(207))
    }

    @Test
    fun authenticationAndServerFailuresAreClassified() {
        assertEquals(
            WebDavFailureReason.Authentication,
            WebDavConnectionTester.classifyResponse(401)?.reason,
        )
        assertEquals(
            WebDavFailureReason.ServerError,
            WebDavConnectionTester.classifyResponse(503)?.reason,
        )
    }

    @Test
    fun unexpectedHttpStatusIsAConnectionFailure() {
        val result = WebDavConnectionTester.classifyResponse(302)

        assertEquals(WebDavFailureReason.Connection, result?.reason)
        assertEquals(302, result?.responseCode)
    }
}
