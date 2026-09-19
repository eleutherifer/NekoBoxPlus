package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import moe.matsuri.nb4a.SingBoxOptions.Outbound
import moe.matsuri.nb4a.SingBoxOptions.Outbound_MasterDnsVPNOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundTLSOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxSharedOptionsTest {

    @Test
    fun sharedDialOptionsMapConnectionAndKeepAliveFields() {
        val bean = NaiveBean().apply {
            initializeDefaultValues()
            tcpFastOpen = true
            tcpMultiPath = true
            udpFragment = false
            disableTcpKeepAlive = true
            tcpKeepAlive = "45s"
            tcpKeepAliveInterval = "15s"
        }
        val outbound = Outbound().apply { applySharedDialOptions(bean) }

        assertEquals(true, outbound._hack_config_map["tcp_fast_open"])
        assertEquals(true, outbound._hack_config_map["tcp_multi_path"])
        assertEquals(false, outbound._hack_config_map["udp_fragment"])
        assertEquals(true, outbound._hack_config_map["disable_tcp_keep_alive"])
        assertEquals("45s", outbound._hack_config_map["tcp_keep_alive"])
        assertEquals("15s", outbound._hack_config_map["tcp_keep_alive_interval"])
    }

    @Test
    fun sharedDialOptionsOmitDefaultsAndCanEnableUdpFragmentation() {
        val defaults = NaiveBean().apply { initializeDefaultValues() }
        val defaultOutbound = Outbound().apply { applySharedDialOptions(defaults) }

        assertFalse(defaultOutbound._hack_config_map.containsKey("tcp_fast_open"))
        assertFalse(defaultOutbound._hack_config_map.containsKey("tcp_multi_path"))
        assertFalse(defaultOutbound._hack_config_map.containsKey("udp_fragment"))

        defaults.udpFragment = true
        val enabledOutbound = Outbound().apply { applySharedDialOptions(defaults) }
        assertEquals(true, enabledOutbound._hack_config_map["udp_fragment"])
    }

    @Test
    fun disabledGlobalSwitchesAndDefaultUdpPreserveProfileOptions() {
        val defaults = Outbound().apply {
            applyGlobalDialOverrides(
                tcpFastOpen = false,
                tcpMultiPath = false,
                udpFragment = "",
            )
        }
        assertFalse(defaults._hack_config_map.containsKey("tcp_fast_open"))
        assertFalse(defaults._hack_config_map.containsKey("tcp_multi_path"))
        assertFalse(defaults._hack_config_map.containsKey("udp_fragment"))

        val outbound = Outbound().apply {
            _hack_config_map["tcp_fast_open"] = true
            _hack_config_map["tcp_multi_path"] = true
            _hack_config_map["udp_fragment"] = false
            applyGlobalDialOverrides(
                tcpFastOpen = false,
                tcpMultiPath = false,
                udpFragment = "",
            )
        }

        assertEquals(true, outbound._hack_config_map["tcp_fast_open"])
        assertEquals(true, outbound._hack_config_map["tcp_multi_path"])
        assertEquals(false, outbound._hack_config_map["udp_fragment"])
    }

    @Test
    fun enabledGlobalSwitchesAndSelectedUdpOverrideProfileOptions() {
        val enabledUdp = Outbound().apply {
            _hack_config_map["tcp_fast_open"] = false
            _hack_config_map["tcp_multi_path"] = false
            _hack_config_map["udp_fragment"] = false
            applyGlobalDialOverrides(
                tcpFastOpen = true,
                tcpMultiPath = true,
                udpFragment = "true",
            )
        }

        assertEquals(true, enabledUdp._hack_config_map["tcp_fast_open"])
        assertEquals(true, enabledUdp._hack_config_map["tcp_multi_path"])
        assertEquals(true, enabledUdp._hack_config_map["udp_fragment"])

        enabledUdp.applyGlobalDialOverrides(
            tcpFastOpen = false,
            tcpMultiPath = false,
            udpFragment = "false",
        )
        assertEquals(false, enabledUdp._hack_config_map["udp_fragment"])
    }

    @Test
    fun configuredDialOptionsAreNotAppliedToMasterDnsVPN() {
        val bean = MasterDnsVPNBean().apply {
            initializeDefaultValues()
            tcpFastOpen = true
            tcpMultiPath = true
            udpFragment = true
        }
        val nativeOutbound = Outbound_MasterDnsVPNOptions().apply { type = "masterdnsvpn" }

        nativeOutbound.applyConfiguredDialOptions(
            bean,
            tcpFastOpen = true,
            tcpMultiPath = true,
            udpFragment = "false",
        )

        assertFalse(nativeOutbound._hack_config_map.containsKey("tcp_fast_open"))
        assertFalse(nativeOutbound._hack_config_map.containsKey("tcp_multi_path"))
        assertFalse(nativeOutbound._hack_config_map.containsKey("udp_fragment"))
    }

    @Test
    fun configuredDialOptionsAreNotAppliedToCustomMasterDnsVPNOutbound() {
        val bean = NaiveBean().apply {
            initializeDefaultValues()
            tcpFastOpen = true
            tcpMultiPath = true
            udpFragment = true
        }
        val customOutbound = Outbound().apply { type = "masterdnsvpn" }

        customOutbound.applyConfiguredDialOptions(
            bean,
            tcpFastOpen = true,
            tcpMultiPath = true,
            udpFragment = "false",
        )

        assertFalse(customOutbound._hack_config_map.containsKey("tcp_fast_open"))
        assertFalse(customOutbound._hack_config_map.containsKey("tcp_multi_path"))
        assertFalse(customOutbound._hack_config_map.containsKey("udp_fragment"))
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
    fun sharedTlsOptionsMapXrayCertificatePins() {
        val bean = NaiveBean().apply {
            initializeDefaultValues()
            tlsXrayCertificateSha256 = "pin-a\npin-b"
        }
        val tls = OutboundTLSOptions().apply { applySharedTLSOptions(bean) }

        assertEquals(listOf("pin-a", "pin-b"), tls.xray_certificate_sha256)
        assertEquals(null, tls.certificate_public_key_sha256)
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
