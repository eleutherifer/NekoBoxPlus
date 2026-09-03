package io.nekohasekai.sagernet.fmt.shadowsocks

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ShadowsocksFmtTest {

    @Test
    fun supportedCipherIsPreservedOnImport() {
        assertEquals(
            "aes-256-gcm",
            parseShadowsocks("ss://aes-256-gcm:pass@example.com:1234").method,
        )
    }

    @Test
    fun unsupportedCipherIsNormalizedToNoneOnImport() {
        assertEquals(
            "none",
            parseShadowsocks("ss://crypt:pass@example.com:1234").method,
        )
        assertEquals(
            "none",
            JSONObject()
                .put("server", "example.com")
                .put("server_port", 1234)
                .put("password", "pass")
                .put("method", "crypt")
                .put("plugin", "")
                .parseShadowsocks()
                .method,
        )
    }
}
