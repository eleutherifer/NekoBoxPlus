package io.nekohasekai.sagernet.fmt.openvpn

import io.nekohasekai.sagernet.group.RawUpdater
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenVPNFmtTest {
    @Test
    fun parsesInlineProfileAndBuildsEndpoint() {
        val bean = parseOpenVPNConfig(
            """
            client
            proto udp
            remote vpn.example.com 443 udp
            remote backup.example.com 1194 tcp-client
            auth-user-pass
            data-ciphers AES-256-GCM:AES-128-GCM
            auth SHA256
            route-nopull
            route 10.0.0.0 255.0.0.0
            redirect-gateway def1 ipv6
            <ca>
            -----BEGIN CERTIFICATE-----
            CA
            -----END CERTIFICATE-----
            </ca>
            <tls-crypt>
            secret
            </tls-crypt>
            """.trimIndent(),
        )

        assertEquals("vpn.example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertTrue(bean.additionalRemotes.contains("tcp://backup.example.com:1194"))
        assertTrue(bean.caCertificates.contains("BEGIN CERTIFICATE"))
        assertEquals("tls-crypt", bean.controlWrapType)

        val endpoint = buildSingBoxEndpointOpenVPNBean(bean)
        assertEquals("openvpn-client", endpoint.type)
        assertEquals(listOf("AES-256-GCM", "AES-128-GCM"), endpoint.data_ciphers)
        assertTrue(endpoint.route_no_pull == true)
        assertEquals(listOf("10.0.0.0/8"), endpoint.routes)
        assertTrue(endpoint.redirect_gateway == true)
        assertEquals(listOf("def1", "ipv6"), endpoint.redirect_gateway_flags)
        assertEquals(null, endpoint.server)
        assertEquals(listOf("vpn.example.com", "backup.example.com"), endpoint.servers.map { it.server })
        assertEquals("tls_crypt", endpoint.tls.control_wrap.type)
    }

    @Test
    fun rejectsExternalKeyMaterial() {
        val result = runCatching { parseOpenVPNConfig("client\nremote vpn.example.com\nca ca.crt") }
        assertTrue(result.isFailure)
    }

    @Test
    fun defaultTlsEndpointEmitsRequiredEmptyTlsOptions() {
        val endpoint = buildSingBoxEndpointOpenVPNBean(OpenVPNBean().apply {
            initializeDefaultValues(); serverAddress = "vpn.example.com"
        })
        assertFalse(endpoint.remote_random == true)
        assertTrue(endpoint.tls != null)
        assertEquals("vpn.example.com", endpoint.server)
        assertEquals(null, endpoint.servers)
    }

    @Test
    fun normalizesIpv6AndMutuallyExclusiveTlsOptions() {
        val endpoint = buildSingBoxEndpointOpenVPNBean(OpenVPNBean().apply {
            initializeDefaultValues()
            serverAddress = "[2001:db8::1]"
            remoteCertificateEKU = "TLS Web Server Authentication"
            remoteCertificateTLS = "server"
            controlWrapType = "tls-crypt"
            controlWrapKey = "secret"
            controlWrapDirection = "1"
        })

        assertEquals("2001:db8::1", endpoint.server)
        assertEquals("TLS Web Server Authentication", endpoint.tls.remote_certificate_eku)
        assertEquals(null, endpoint.tls.remote_certificate_tls)
        assertEquals("tls_crypt", endpoint.tls.control_wrap.type)
        assertEquals(null, endpoint.tls.control_wrap.direction)
    }

    @Test
    fun importsNativeSingBoxEndpoint() = runBlocking {
        val bean = RawUpdater.parseRaw(
            """{"type":"openvpn-client","tag":"office","server":"vpn.example.com","server_port":443,"tls":{"server_name":"vpn.example.com","certificate":["CA"]}}""",
        )!!.single() as OpenVPNBean
        assertEquals("office", bean.name)
        assertEquals("vpn.example.com", bean.tlsServerName)
        assertEquals("CA", bean.caCertificates)
    }

    @Test
    fun parsesStaticKeyProfileAndBuildsCompatibilityOptions() {
        val bean = parseOpenVPNConfig(
            """
            remote vpn.example.com 1194 udp
            ifconfig 10.8.0.2 10.8.0.1
            topology p2p
            cipher AES-256-CBC
            auth SHA256
            key-direction client
            replay-window 256 30
            mssfix 1400 mtu
            ping-restart 0
            reneg-sec 0
            reneg-bytes 1048576
            hand-window 90
            <secret>
            STATIC-KEY
            </secret>
            """.trimIndent(),
        )

        assertEquals("static_key", bean.mode)
        assertEquals("10.8.0.2/32", bean.addresses)
        assertEquals("10.8.0.1", bean.peerAddress)
        val endpoint = buildSingBoxEndpointOpenVPNBean(bean)
        assertEquals("static_key", endpoint.mode)
        assertEquals(listOf("STATIC-KEY"), endpoint.static_key)
        assertEquals("client", endpoint.key_direction)
        assertEquals("AES-256-CBC", endpoint.cipher)
        assertEquals(256, endpoint.replay_window)
        assertEquals("30s", endpoint.replay_window_time)
        assertTrue(endpoint.ping_restart_disabled == true)
        assertEquals(null, endpoint.renegotiate_disabled)
        assertEquals(null, endpoint.renegotiate_bytes)
        assertEquals(null, endpoint.tls)
    }

    @Test
    fun mapsNewTlsAndDnsProfileFieldsThroughClone() {
        val bean = OpenVPNBean().apply {
            initializeDefaultValues()
            serverAddress = "vpn.example.com"
            remoteCertificateTLS = "server"
            certificateProfile = "legacy"
            nsCertificateType = "server"
            mssFixDisabled = true
            redirectPrivate = true
            blockIPv6 = true
            tlsTimeout = "3s"
            usePushedDNS = true
            acceptPushedDefaultResolvers = true
            expandPushedSearchDomains = true
        }
        val clone = bean.clone()
        assertEquals("legacy", clone.certificateProfile)
        assertTrue(clone.usePushedDNS)
        val endpoint = buildSingBoxEndpointOpenVPNBean(clone)
        assertEquals("server", endpoint.tls.remote_certificate_tls)
        assertTrue(endpoint.mss_fix_disabled == true)
        assertTrue(endpoint.redirect_private == true)
        assertTrue(endpoint.block_ipv6 == true)
        assertEquals("3s", endpoint.tls_timeout)
    }
}
