package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.openconnect.OpenConnectBean
import io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointAwgBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireguardBean
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.SingBoxOptions.Outbound
import moe.matsuri.nb4a.SingBoxOptions.Outbound_MasterDnsVPNOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundTLSOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxSharedOptionsTest {

    private val dialOptionNames = setOf(
        "tcp_fast_open",
        "tcp_multi_path",
        "udp_fragment",
        "disable_tcp_keep_alive",
        "tcp_keep_alive",
        "tcp_keep_alive_interval",
    )

    private fun <T : AbstractBean> T.enableAllDialOptions(): T = apply {
        initializeDefaultValues()
        tcpFastOpen = true
        tcpMultiPath = true
        udpFragment = true
        disableTcpKeepAlive = true
        tcpKeepAlive = "45s"
        tcpKeepAliveInterval = "15s"
    }

    private fun Outbound.applyAllConfiguredDialOptions(bean: AbstractBean) {
        applyConfiguredDialOptions(
            bean,
            tcpFastOpen = true,
            tcpMultiPath = true,
            udpFragment = "false",
        )
    }

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
        val outbound = Outbound().apply {
            applySharedDialOptions(bean, DialOptionCapabilities.TCP_AND_UDP)
        }

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
        val defaultOutbound = Outbound().apply {
            applySharedDialOptions(defaults, DialOptionCapabilities.TCP_AND_UDP)
        }

        assertFalse(defaultOutbound._hack_config_map.containsKey("tcp_fast_open"))
        assertFalse(defaultOutbound._hack_config_map.containsKey("tcp_multi_path"))
        assertFalse(defaultOutbound._hack_config_map.containsKey("udp_fragment"))

        defaults.udpFragment = true
        val enabledOutbound = Outbound().apply {
            applySharedDialOptions(defaults, DialOptionCapabilities.TCP_AND_UDP)
        }
        assertEquals(true, enabledOutbound._hack_config_map["udp_fragment"])
    }

    @Test
    fun disabledGlobalSwitchesAndDefaultUdpPreserveProfileOptions() {
        val defaults = Outbound().apply {
            applyGlobalDialOverrides(
                tcpFastOpen = false,
                tcpMultiPath = false,
                udpFragment = "",
                capabilities = DialOptionCapabilities.TCP_AND_UDP,
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
                capabilities = DialOptionCapabilities.TCP_AND_UDP,
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
                capabilities = DialOptionCapabilities.TCP_AND_UDP,
            )
        }

        assertEquals(true, enabledUdp._hack_config_map["tcp_fast_open"])
        assertEquals(true, enabledUdp._hack_config_map["tcp_multi_path"])
        assertEquals(true, enabledUdp._hack_config_map["udp_fragment"])

        enabledUdp.applyGlobalDialOverrides(
            tcpFastOpen = false,
            tcpMultiPath = false,
            udpFragment = "false",
            capabilities = DialOptionCapabilities.TCP_AND_UDP,
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
    fun configuredDialOptionsDoNotInjectTcpFastOpenForAnyTLS() {
        val bean = AnyTLSBean().enableAllDialOptions()
        val outbound = Outbound().apply {
            type = "anytls"
            applyAllConfiguredDialOptions(bean)
        }

        assertFalse(outbound._hack_config_map.containsKey("tcp_fast_open"))
        assertEquals(true, outbound._hack_config_map["tcp_multi_path"])
        assertEquals(true, outbound._hack_config_map["disable_tcp_keep_alive"])
        assertEquals("45s", outbound._hack_config_map["tcp_keep_alive"])
        assertEquals("15s", outbound._hack_config_map["tcp_keep_alive_interval"])
    }

    @Test
    fun endpointDialOptionsRespectTransportCapabilities() {
        val wireGuardBean = WireGuardBean().enableAllDialOptions()
        val wireGuard = buildSingBoxEndpointWireguardBean(wireGuardBean).apply {
            type = "wireguard"
            applyConfiguredDialOptions(wireGuardBean, true, true, "false")
        }
        assertEquals(setOf("udp_fragment"), wireGuard._hack_config_map.keys.intersect(dialOptionNames))
        assertEquals(false, wireGuard._hack_config_map["udp_fragment"])

        val amneziaBean = AmneziaWGBean().enableAllDialOptions()
        val amnezia = buildSingBoxEndpointAwgBean(amneziaBean).apply {
            applyConfiguredDialOptions(amneziaBean, true, true, "false")
        }
        assertTrue(amnezia._hack_config_map.keys.intersect(dialOptionNames).isEmpty())

        val tailscale = Outbound().apply {
            type = "tailscale"
            applyAllConfiguredDialOptions(NaiveBean().enableAllDialOptions())
        }
        assertEquals(dialOptionNames, tailscale._hack_config_map.keys.intersect(dialOptionNames))
    }

    @Test
    fun configuredDialOptionsFollowDynamicTransportModes() {
        val tcpMieru = MieruBean().enableAllDialOptions().apply { protocol = MieruBean.PROTOCOL_TCP }
        val udpMieru = MieruBean().enableAllDialOptions().apply { protocol = MieruBean.PROTOCOL_UDP }
        assertEquals(DialOptionCapabilities.TCP, Outbound().apply { type = "mieru" }.resolveDialOptionCapabilities(tcpMieru))
        assertEquals(DialOptionCapabilities.UDP, Outbound().apply { type = "mieru" }.resolveDialOptionCapabilities(udpMieru))

        val naiveQuic = NaiveBean().enableAllDialOptions().apply { proto = "quic" }
        assertEquals(DialOptionCapabilities.UDP, Outbound().apply { type = "naive" }.resolveDialOptionCapabilities(naiveQuic))

        val trustTunnelFallback = TrustTunnelBean().enableAllDialOptions().apply {
            quic = true
            forceQuic = false
        }
        val trustTunnelQuic = TrustTunnelBean().enableAllDialOptions().apply {
            quic = true
            forceQuic = true
        }
        assertEquals(
            DialOptionCapabilities.TCP_AND_UDP,
            Outbound().apply { type = "trusttunnel" }.resolveDialOptionCapabilities(trustTunnelFallback),
        )
        assertEquals(
            DialOptionCapabilities.UDP,
            Outbound().apply { type = "trusttunnel" }.resolveDialOptionCapabilities(trustTunnelQuic),
        )

        val masqueHttp2 = MasqueBean().enableAllDialOptions().apply { useHTTP2 = true }
        val masqueHttp3 = MasqueBean().enableAllDialOptions().apply { useHTTP2 = false }
        assertEquals(DialOptionCapabilities.TCP, Outbound().apply { type = "masque" }.resolveDialOptionCapabilities(masqueHttp2))
        assertEquals(DialOptionCapabilities.UDP, Outbound().apply { type = "masque" }.resolveDialOptionCapabilities(masqueHttp3))
    }

    @Test
    fun openVpnAndOpenConnectCapabilitiesFollowConfiguredNetworks() {
        val tcpOpenVpn = OpenVPNBean().enableAllDialOptions().apply { network = "tcp" }
        val udpOpenVpn = OpenVPNBean().enableAllDialOptions().apply { network = "udp" }
        val mixedOpenVpn = OpenVPNBean().enableAllDialOptions().apply {
            network = "tcp"
            additionalRemotes = "udp://vpn.example.com:1194"
        }
        val openVpn = Outbound().apply { type = "openvpn-client" }
        assertEquals(DialOptionCapabilities.TCP, openVpn.resolveDialOptionCapabilities(tcpOpenVpn))
        assertEquals(DialOptionCapabilities.UDP, openVpn.resolveDialOptionCapabilities(udpOpenVpn))
        assertEquals(DialOptionCapabilities.TCP_AND_UDP, openVpn.resolveDialOptionCapabilities(mixedOpenVpn))

        val openConnect = Outbound().apply { type = "openconnect" }
        assertEquals(
            DialOptionCapabilities.TCP_AND_UDP,
            openConnect.resolveDialOptionCapabilities(OpenConnectBean().enableAllDialOptions()),
        )
        assertEquals(
            DialOptionCapabilities.TCP,
            openConnect.resolveDialOptionCapabilities(
                OpenConnectBean().enableAllDialOptions().apply { noUDP = true },
            ),
        )
    }

    @Test
    fun customOutboundsOnlyReceiveOptionsForKnownTypes() {
        val bean = NaiveBean().enableAllDialOptions()
        val known = CustomSingBoxOption("""{"type":"wireguard"}""").apply {
            applyConfiguredDialOptions(bean, true, true, "true")
        }
        assertEquals(setOf("udp_fragment"), known._hack_config_map.keys.intersect(dialOptionNames))

        val unknown = CustomSingBoxOption("""{"type":"third-party"}""").apply {
            applyConfiguredDialOptions(bean, true, true, "true")
        }
        assertTrue(unknown._hack_config_map.keys.intersect(dialOptionNames).isEmpty())
    }

    @Test
    fun selectorAndUrlTestDoNotReceiveDialOptions() {
        for (type in listOf("selector", "urltest")) {
            val outbound = Outbound().apply {
                this.type = type
                applyAllConfiguredDialOptions(NaiveBean().enableAllDialOptions())
            }
            assertTrue(outbound._hack_config_map.keys.intersect(dialOptionNames).isEmpty())
        }
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
            tlsHandshakeTimeout = "8s"
        }
        val tls = OutboundTLSOptions().apply { applySharedTLSOptions(bean) }

        assertEquals(listOf("X25519", "X25519MLKEM768"), tls.curve_preferences)
        assertEquals(listOf("pin-a", "pin-b"), tls.certificate_public_key_sha256)
        assertEquals(listOf("certificate"), tls.client_certificate)
        assertEquals(listOf("private-key"), tls.client_key)
        assertNotNull(tls.ech)
        assertTrue(tls.ech.enabled == true)
        assertEquals("ech.example.com", tls.ech.query_server_name)
        assertEquals("8s", tls.handshake_timeout)
    }

    @Test
    fun sharedQuicOptionsMapSingBox114Fields() {
        val bean = NaiveBean().apply {
            initializeDefaultValues()
            quicIdleTimeout = "30s"
            quicKeepAlivePeriod = "10s"
            quicStreamReceiveWindow = 1_048_576L
            quicConnectionReceiveWindow = 2_097_152L
            quicMaxConcurrentStreams = 128
            quicInitialPacketSize = 1350
            quicDisablePathMtuDiscovery = true
        }
        val outbound = Outbound().apply { applySharedQUICOptions(bean) }

        assertEquals("30s", outbound._hack_config_map["idle_timeout"])
        assertEquals("10s", outbound._hack_config_map["keep_alive_period"])
        assertEquals(1_048_576L, outbound._hack_config_map["stream_receive_window"])
        assertEquals(2_097_152L, outbound._hack_config_map["connection_receive_window"])
        assertEquals(128, outbound._hack_config_map["max_concurrent_streams"])
        assertEquals(1350, outbound._hack_config_map["initial_packet_size"])
        assertEquals(true, outbound._hack_config_map["disable_path_mtu_discovery"])
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
