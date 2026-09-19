package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import moe.matsuri.nb4a.SingBoxOptions.Outbound
import moe.matsuri.nb4a.SingBoxOptions.OutboundTLSOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxSharedOptionsTest {

    @Test
    fun sharedDialOptionsMapAllNewKeepAliveFields() {
        val bean = NaiveBean().apply {
            initializeDefaultValues()
            disableTcpKeepAlive = true
            tcpKeepAlive = "45s"
            tcpKeepAliveInterval = "15s"
        }
        val outbound = Outbound().apply { applySharedDialOptions(bean) }

        assertEquals(true, outbound._hack_config_map["disable_tcp_keep_alive"])
        assertEquals("45s", outbound._hack_config_map["tcp_keep_alive"])
        assertEquals("15s", outbound._hack_config_map["tcp_keep_alive_interval"])
    }

    @Test
    fun sharedTlsOptionsMapCurvesPinsClientIdentityAndEchQuery() {
        val bean = NaiveBean().apply {
            initializeDefaultValues()
            tlsCurvePreferences = "X25519\nX25519MLKEM768"
            tlsCertificatePublicKeySha256 = "pin-a,pin-b"
            tlsClientCertificate = "certificate"
            tlsClientKey = "private-key"
            echQueryServerName = "ech.example.com"
        }
        val tls = OutboundTLSOptions().apply { applySharedTLSOptions(bean) }

        assertEquals(listOf("X25519", "X25519MLKEM768"), tls.curve_preferences)
        assertEquals(listOf("pin-a", "pin-b"), tls.certificate_public_key_sha256)
        assertEquals(listOf("certificate"), tls.client_certificate)
        assertEquals(listOf("private-key"), tls.client_key)
        assertNotNull(tls.ech)
        assertTrue(tls.ech.enabled == true)
        assertEquals("ech.example.com", tls.ech.query_server_name)
    }

    @Test
    fun profileOwnedTlsFieldSkipsAllSharedTlsOptions() {
        val bean = TrustTunnelBean().apply {
            initializeDefaultValues()
            tlsCurvePreferences = "X25519"
            tlsCertificatePublicKeySha256 = "pin"
        }
        val tls = OutboundTLSOptions().apply { applySharedTLSOptions(bean) }

        assertFalse(bean.supportsSharedTLSFieldInjection())
        assertEquals(null, tls.curve_preferences)
        assertEquals(null, tls.certificate_public_key_sha256)
    }

    @Test
    fun gsonAcceptsProfileThatShadowsSharedTlsField() {
        val bean = MasqueBean().apply { initializeDefaultValues() }

        assertFalse(bean.supportsSharedTLSFieldInjection())
        assertTrue(moe.matsuri.nb4a.utils.JavaUtil.gson.toJson(bean).contains("tlsCurvePreferences"))
    }
}
