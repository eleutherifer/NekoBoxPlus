package io.nekohasekai.sagernet.fmt.trusttunnel

import moe.matsuri.nb4a.SingBoxOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustTunnelFmtTest {

    @Test
    fun parseTrustTunnelSupportsThroneLink() {
        val bean = parseTrustTunnel(
            "tt://user%40name:pass%3Aword@192.0.2.1:8443" +
                "?security=tls&sni=edge.example.com#Office%20TT%20%2B%20RU",
        )

        assertEquals("192.0.2.1", bean.serverAddress)
        assertEquals(8443, bean.serverPort)
        assertEquals("edge.example.com", bean.serverName)
        assertEquals("user@name", bean.username)
        assertEquals("pass:word", bean.password)
        assertEquals("Office TT + RU", bean.name)
    }

    @Test
    fun parseTrustTunnelDefaultsThronePortAndSni() {
        val bean = parseTrustTunnel("tt://login:password@example.com?security=tls")

        assertEquals("example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("example.com", bean.serverName)
        assertEquals("login", bean.username)
        assertEquals("password", bean.password)
        assertEquals("", bean.name)
    }

    @Test
    fun buildSingBoxOutboundTrustTunnelBeanMapsTlsAndConnectionFields() {
        val bean = TrustTunnelBean().apply {
            serverAddress = "example.com"
            serverPort = 443
            username = "user"
            password = "pass"
            healthCheck = true
            clientRandomPrefix = "a0b0/f0f0"
            quic = true
            quicCongestionControl = "bbr"
            serverName = "sni.example.com"
            allowInsecure = true
            alpn = "h2,http/1.1"
            certificates = "cert-a\ncert-b"
            certPublicKeySha256 = "sha-a\nsha-b"
            clientCert = "client-cert"
            clientKey = "client-key"
            utlsFingerprint = "chrome"
            tlsFragment = true
            tlsFragmentFallbackDelay = "200ms"
            ech = true
            echConfig = "cfg-1\ncfg-2"
            echQueryServerName = "ech.example.com"
        }

        val outbound = buildSingBoxOutboundTrustTunnelBean(bean)

        assertEquals(SingBoxOptions.TYPE_TRUST_TUNNEL, outbound.type)
        assertEquals("example.com", outbound.server)
        assertEquals(443, outbound.server_port)
        assertEquals("user", outbound.username)
        assertEquals("pass", outbound.password)
        assertTrue(outbound.health_check == true)
        assertEquals("a0b0/f0f0", outbound.client_random_prefix)
        assertTrue(outbound.quic == true)
        assertEquals("bbr", outbound.quic_congestion_control)

        val tls = outbound.tls
        assertNotNull(tls)
        tls!!
        assertTrue(tls.enabled == true)
        assertEquals("sni.example.com", tls.server_name)
        assertTrue(tls.insecure == true)
        assertEquals(listOf("h2", "http/1.1"), tls.alpn)
        assertEquals(listOf("cert-a", "cert-b"), tls.certificate)
        assertEquals(listOf("sha-a", "sha-b"), tls.certificate_public_key_sha256)
        assertEquals(listOf("client-cert"), tls.client_certificate)
        assertEquals(listOf("client-key"), tls.client_key)
        val utls = tls.utls
        assertNotNull(utls)
        assertEquals("chrome", utls!!.fingerprint)
        assertTrue(tls.fragment == true)
        assertEquals("200ms", tls.fragment_fallback_delay)
        val ech = tls.ech
        assertNotNull(ech)
        assertEquals(listOf("cfg-1", "cfg-2"), ech!!.config)
        assertEquals("ech.example.com", tls.ech.query_server_name)
    }

    @Test
    fun buildSingBoxOutboundTrustTunnelBeanSetsRecordFragmentWhenTlsFragmentIsDisabled() {
        val bean = TrustTunnelBean().apply {
            serverAddress = "example.com"
            serverPort = 443
            allowInsecure = true
            tlsFragment = false
            tlsRecordFragment = true
        }

        val tls = buildSingBoxOutboundTrustTunnelBean(bean).tls
        assertNotNull(tls)

        assertTrue(tls!!.record_fragment == true)
        assertNull(tls.fragment_fallback_delay)
    }

    @Test
    fun buildSingBoxOutboundTrustTunnelBeanIgnoresEmptyCertificateBlock() {
        val bean = TrustTunnelBean().apply {
            serverAddress = "example.com"
            serverPort = 443
            allowInsecure = true
            certificates = """
                -----BEGIN CERTIFICATE-----
                -----END CERTIFICATE-----
            """.trimIndent()
        }

        val tls = buildSingBoxOutboundTrustTunnelBean(bean).tls
        assertNotNull(tls)

        assertNull(tls!!.certificate)
    }

    @Test
    fun buildSingBoxOutboundTrustTunnelBeanOmitsBlankClientRandomPrefix() {
        val bean = TrustTunnelBean().apply {
            serverAddress = "example.com"
            serverPort = 443
            allowInsecure = true
            clientRandomPrefix = ""
        }

        val outbound = buildSingBoxOutboundTrustTunnelBean(bean)

        assertNull(outbound.client_random_prefix)
    }

    @Test
    fun buildSingBoxOutboundTrustTunnelBeanForceQuicWithoutCronetOmitsFallbackOnlyOptions() {
        val bean = TrustTunnelBean().apply {
            serverAddress = "example.com"
            serverPort = 443
            quic = true
            forceQuic = true
            useCronetQuic = false
            utlsFingerprint = "chrome"
            allowInsecure = true
            tlsFragment = true
            tlsFragmentFallbackDelay = "200ms"
            clientRandomPrefix = "aabbcc"
            alpn = "h2,http/1.1"
        }

        val outbound = buildSingBoxOutboundTrustTunnelBean(bean)

        assertTrue(outbound.quic == true)
        assertTrue(outbound.force_quic == true)
        assertNull(outbound.use_cronet_quic)
        assertNull(outbound.use_cronet_https)
        assertEquals("aabbcc", outbound.client_random_prefix)
        assertNotNull(outbound.tls)
        assertNull(outbound.tls!!.utls)
        assertNull(outbound.tls.alpn)
        assertTrue(outbound.tls.insecure == true)
        assertNull(outbound.tls.fragment)
        assertNull(outbound.tls.fragment_fallback_delay)
    }
}
