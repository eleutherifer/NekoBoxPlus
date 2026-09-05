package io.nekohasekai.sagernet.fmt.openconnect

import io.nekohasekai.sagernet.fmt.applyConfiguredDialOptions
import io.nekohasekai.sagernet.group.RawUpdater
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenConnectFmtTest {
    @Test
    fun parsesLongOptionConfigurationAndBuildsEndpoint() {
        val bean = parseOpenConnectConfig(
            """
            server=https://vpn.example.com/connect
            protocol=gp
            user=alice
            authgroup=employees
            token-mode=totp
            token-secret=secret
            no-dtls
            useragent=NekoBox
            """.trimIndent(),
        )

        assertEquals("vpn.example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        val endpoint = buildSingBoxEndpointOpenConnectBean(bean)
        assertEquals("openconnect", endpoint.type)
        assertEquals("gp", endpoint.flavor)
        assertEquals("alice", endpoint.username)
        assertTrue(endpoint.no_udp == true)
        assertEquals("totp", endpoint.token.mode)
    }

    @Test
    fun parsesCiscoServerList() {
        val beans = parseOpenConnectServerList(
            """<ServerList><HostEntry><HostName>Office</HostName><HostAddress>https://vpn.example.com</HostAddress></HostEntry></ServerList>""",
        )
        assertEquals(1, beans.size)
        assertEquals("Office", beans.single().name)
        assertEquals("vpn.example.com", beans.single().serverAddress)
    }

    @Test
    fun rejectsExternalCertificatePaths() {
        assertTrue(runCatching { parseOpenConnectConfig("server=vpn.example.com\ncertificate=/tmp/client.pem") }.isFailure)
    }

    @Test
    fun importsNativeSingBoxEndpoint() = runBlocking {
        val bean = RawUpdater.parseRaw(
            """{"type":"openconnect","tag":"office","server":"https://vpn.example.com","token":{"mode":"totp","secret":"SECRET"},"tls":{"certificate_authority":["CA"]}}""",
        )!!.single() as OpenConnectBean
        assertEquals("office", bean.name)
        assertEquals("totp", bean.tokenMode)
        assertEquals("CA", bean.caCertificates)
    }

    @Test
    fun buildsSessionMobileFortinetAndTransportOptions() {
        val bean = OpenConnectBean().apply {
            initializeDefaultValues()
            server = "vpn.example.com"
            cookie = "SVPNCOOKIE=session"
            tokenMode = "oidc"
            tokenSecret = "access-token"
            clientVersion = "v5.1"
            localHostname = "android"
            mobilePlatformVersion = "16"
            mobileDeviceType = "Pixel"
            mobileDeviceUniqueID = "device-id"
            fortinetHostCheck = "0100,10.0.19042"
            fortinetVirtualDesktopCheck = "00:11:22:33:44:55"
            compressionMode = "stateless"
            tcpKeepAliveEnabled = true
            pfs = true
            mtu = 1400
            dpdInterval = "30s"
            tlsServerName = "vpn.example.com"
            tlsPeerFingerprints = "sha256:abcd"
            tlsSystemTrustDisabled = true
            usePushedDNS = true
            expandPushedSearchDomains = true
        }

        val clone = bean.clone()
        val endpoint = buildSingBoxEndpointOpenConnectBean(clone)
        assertEquals("SVPNCOOKIE=session", endpoint.cookie)
        assertEquals("oidc", endpoint.token.mode)
        assertEquals("Pixel", endpoint.mobile.device_type)
        assertEquals("0100,10.0.19042", endpoint.fortinet_host_check.hostcheck)
        assertEquals("stateless", endpoint.compression_mode)
        assertTrue(endpoint.tcp_keep_alive_enabled == true)
        assertTrue(endpoint.pfs == true)
        assertEquals(1400, endpoint.mtu)
        assertEquals("30s", endpoint.dpd_interval)
        assertEquals(listOf("sha256:abcd"), endpoint.tls.peer_fingerprint)
        assertTrue(endpoint.tls.system_trust_disabled == true)
        assertTrue(clone.usePushedDNS)
    }

    @Test
    fun importsNewNativeSingBoxOptions() = runBlocking {
        val bean = RawUpdater.parseRaw(
            """{"type":"openconnect","server":"vpn.example.com","cookie":"session","version":"v5.1","mobile":{"platform_version":"16","device_type":"Pixel","device_unique_id":"id"},"fortinet_host_check":{"hostcheck":"0100,10.0"},"mtu":1400,"tls":{"server_name":"vpn.example.com","peer_fingerprint":["sha256:abcd"],"system_trust_disabled":true}}""",
        )!!.single() as OpenConnectBean
        assertEquals("session", bean.cookie)
        assertEquals("Pixel", bean.mobileDeviceType)
        assertEquals("0100,10.0", bean.fortinetHostCheck)
        assertEquals(1400, bean.mtu)
        assertEquals("sha256:abcd", bean.tlsPeerFingerprints)
        assertTrue(bean.tlsSystemTrustDisabled)
    }

    @Test
    fun normalizesSchemeLessIpv6Server() {
        val endpoint = buildSingBoxEndpointOpenConnectBean(OpenConnectBean().apply {
            initializeDefaultValues()
            server = "2001:db8::1"
        })

        assertEquals("https://[2001:db8::1]", endpoint.server)
    }

    @Test
    fun explicitKeepAliveDisableSuppressesEnablingOptions() {
        val bean = OpenConnectBean().apply {
            initializeDefaultValues()
            server = "vpn.example.com"
            disableTcpKeepAlive = true
            tcpKeepAliveEnabled = true
            tcpKeepAlive = "45s"
            tcpKeepAliveInterval = "15s"
        }
        val endpoint = buildSingBoxEndpointOpenConnectBean(bean).apply {
            applyConfiguredDialOptions(bean, false, false, "")
        }

        assertEquals(null, endpoint.tcp_keep_alive_enabled)
        assertEquals(true, endpoint._hack_config_map["disable_tcp_keep_alive"])
        assertFalse(endpoint._hack_config_map.containsKey("tcp_keep_alive"))
        assertFalse(endpoint._hack_config_map.containsKey("tcp_keep_alive_interval"))
    }

    @Test
    fun keepAliveEnablingOptionsRemainWhenNotDisabled() {
        val bean = OpenConnectBean().apply {
            initializeDefaultValues()
            server = "vpn.example.com"
            tcpKeepAliveEnabled = true
            tcpKeepAlive = "45s"
            tcpKeepAliveInterval = "15s"
        }
        val endpoint = buildSingBoxEndpointOpenConnectBean(bean).apply {
            applyConfiguredDialOptions(bean, false, false, "")
        }

        assertTrue(endpoint.tcp_keep_alive_enabled == true)
        assertEquals("45s", endpoint._hack_config_map["tcp_keep_alive"])
        assertEquals("15s", endpoint._hack_config_map["tcp_keep_alive_interval"])
    }
}
