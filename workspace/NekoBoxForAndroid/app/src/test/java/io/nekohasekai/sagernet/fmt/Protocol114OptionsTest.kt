package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.snell.buildSingBoxOutboundSnellBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Protocol114OptionsTest {

    @Test
    fun hysteria2MapsGeckoRealmAndQuicOptions() {
        val bean = HysteriaBean().apply {
            initializeDefaultValues()
            protocolVersion = 2
            authPayload = "password"
            obfuscation = "obfs-password"
            obfsType = "gecko"
            geckoMinPacketSize = 100
            geckoMaxPacketSize = 1400
            realmServerUrl = "https://realm.example.com"
            realmId = "realm"
            realmStunServers = "stun.example.com:3478"
            realmPortMapping = true
            realmPortMappingTimeout = "10s"
            hopIntervalMax = "30s"
            bbrProfile = "mobile"
            quicInitialPacketSize = 1350
        }

        val outbound = buildSingBoxOutboundHysteriaBean(bean, false, true).asMap()

        assertEquals("hysteria2", outbound["type"])
        assertFalse(outbound.containsKey("server"))
        assertEquals("30s", outbound["hop_interval_max"])
        assertEquals("mobile", outbound["bbr_profile"])
        assertEquals(true, outbound["disable_chrome_parrot"])
        assertEquals(1350L, outbound["initial_packet_size"])
        @Suppress("UNCHECKED_CAST")
        val obfs = outbound["obfs"] as Map<String, Any?>
        assertEquals("gecko", obfs["type"])
        assertEquals(100L, obfs["min_packet_size"])
        @Suppress("UNCHECKED_CAST")
        val realm = outbound["realm"] as Map<String, Any?>
        assertEquals("realm", realm["realm_id"])
    }

    @Test
    fun legacySnellAndTlsObfsArePreserved() {
        val bean = SnellBean().apply {
            version = 3
            psk = "secret"
            obfsMode = "tls"
            obfsHost = "example.com"
        }

        val outbound = buildSingBoxOutboundSnellBean(bean)

        assertEquals(3, outbound.version)
        assertEquals("tls", outbound.obfs_mode)
        assertEquals("example.com", outbound.obfs_host)
    }

    @Test
    fun snellV6AndSshAlgorithmsAreSerialized() {
        val snell = buildSingBoxOutboundSnellBean(SnellBean().apply {
            initializeDefaultValues()
            version = 6
            psk = "secret"
            userKey = "user-key"
            mode = "unsafe-raw"
        })
        assertEquals("user-key", snell.userkey)
        assertEquals("unsafe-raw", snell.mode)

        val ssh = buildSingBoxOutboundSSHBean(SSHBean().apply {
            initializeDefaultValues()
            cipher = "aes128-gcm@openssh.com"
            mac = "hmac-sha2-256"
            kexAlgorithm = "curve25519-sha256"
        })
        assertTrue(ssh.cipher.contains("aes128-gcm@openssh.com"))
        assertTrue(ssh.mac.contains("hmac-sha2-256"))
        assertTrue(ssh.kex_algorithm.contains("curve25519-sha256"))
    }
}
