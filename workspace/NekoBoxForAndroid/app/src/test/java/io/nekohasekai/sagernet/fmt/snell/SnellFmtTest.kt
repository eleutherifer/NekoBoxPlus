package io.nekohasekai.sagernet.fmt.snell

import io.nekohasekai.sagernet.fmt.KryoConverters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnellFmtTest {

    @Test
    fun v6FieldsRoundTripThroughPersistence() {
        val original = SnellBean().apply {
            initializeDefaultValues()
            serverAddress = "example.com"
            serverPort = 443
            psk = "shared secret"
            userKey = "user secret"
            version = 6
            mode = "unshaped"
            quicProxyMode = true
            name = "Snell v6"
        }

        val restored = KryoConverters.deserialize(
            SnellBean(),
            KryoConverters.serialize(original),
        )

        assertEquals(6, restored.version)
        assertEquals("user secret", restored.userKey)
        assertEquals("unshaped", restored.mode)
        assertEquals(true, restored.quicProxyMode)
    }

    @Test
    fun v6BuildUsesUserKeyAndModeInsteadOfLegacyObfs() {
        val outbound = buildSingBoxOutboundSnellBean(
            SnellBean().apply {
                initializeDefaultValues()
                serverAddress = "example.com"
                psk = "secret"
                userKey = "user"
                version = 6
                mode = "unsafe-raw"
                quicProxyMode = true
                obfsMode = "http"
                obfsHost = "legacy.example"
            }
        )

        assertEquals("user", outbound.userkey)
        assertEquals("unsafe-raw", outbound.mode)
        assertEquals(true, outbound.quic_proxy_mode)
        assertNull(outbound.obfs_mode)
        assertNull(outbound.obfs_host)
    }

    @Test
    fun clashVersionFiveIsPreserved() {
        assertEquals(
            5,
            parseClashSnell(
                mapOf(
                    "server" to "example.com",
                    "port" to 443,
                    "psk" to "secret",
                    "version" to 5,
                )
            ).version,
        )
    }
}
