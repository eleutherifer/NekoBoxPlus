package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseClashHysteria
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1Json
import io.nekohasekai.sagernet.fmt.hysteria.toUri
import io.nekohasekai.sagernet.fmt.snell.parseClashSnell
import io.nekohasekai.sagernet.fmt.snell.parseSnell
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.snell.toUri
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.parseClashSSH
import io.nekohasekai.sagernet.fmt.ssh.parseSSH
import io.nekohasekai.sagernet.fmt.ssh.toUri
import io.nekohasekai.sagernet.fmt.tuic.parseClashTuic
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.fmt.tuic.toUri
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class Subscription114ParsingTest {

    @Test
    fun hysteria2UriRoundTripPreserves114Options() {
        val source = HysteriaBean().applyDefaultValues().apply {
            protocolVersion = 2
            serverAddress = "hy.example.com"
            serverPorts = "443,8443-8450"
            authPayload = "user:password"
            sni = "sni.example.com"
            allowInsecure = true
            hopInterval = 7
            hopIntervalMax = "15s"
            uploadMbps = 120
            downloadMbps = 450
            obfsType = "gecko"
            obfuscation = "obfs secret"
            geckoMinPacketSize = 48
            geckoMaxPacketSize = 1280
            bbrProfile = "mobile"
            brutalDebug = true
            tlsHandshakeTimeout = "8s"
            quicIdleTimeout = "30s"
            quicKeepAlivePeriod = "10s"
            quicStreamReceiveWindow = 1_048_576
            quicConnectionReceiveWindow = 2_097_152
            quicMaxConcurrentStreams = 64
            quicInitialPacketSize = 1350
            quicDisablePathMtuDiscovery = true
            realmServerUrl = "https://realm.example.com/api"
            realmToken = "realm-token"
            realmId = "realm-id"
            realmStunServers = "stun1.example.com\nstun2.example.com"
            realmIpVersion = 6
            realmPortMapping = true
            realmPortMappingTimeout = "5s"
            realmPortMappingLifetime = "2m"
            name = "Hysteria 1.14"
        }

        val parsed = parseHysteria2(source.toUri()).applyDefaultValues()

        assertEquals(source.serverPorts, parsed.serverPorts)
        assertEquals(source.authPayload, parsed.authPayload)
        assertEquals("gecko", parsed.obfsType)
        assertEquals(48, parsed.geckoMinPacketSize)
        assertEquals(1280, parsed.geckoMaxPacketSize)
        assertEquals("15s", parsed.hopIntervalMax)
        assertEquals("mobile", parsed.bbrProfile)
        assertTrue(parsed.brutalDebug)
        assertSharedQuic(source, parsed)
        assertEquals(source.realmServerUrl, parsed.realmServerUrl)
        assertEquals(source.realmStunServers, parsed.realmStunServers)
        assertTrue(parsed.realmPortMapping)
        assertEquals("2m", parsed.realmPortMappingLifetime)
    }

    @Test
    fun hysteriaUriParsesShared114Options() {
        val parsed = parseHysteria1(
            "hysteria://hy1.example.com:443?auth=secret&hop_interval=8&handshake_timeout=6s" +
                "&idle_timeout=20s&keep_alive_period=5s&stream_receive_window=1000" +
                "&connection_receive_window=2000&max_concurrent_streams=12" +
                "&initial_packet_size=1250&disable_path_mtu_discovery=true",
        ).applyDefaultValues()

        assertEquals(8, parsed.hopInterval)
        assertEquals("6s", parsed.tlsHandshakeTimeout)
        assertEquals("20s", parsed.quicIdleTimeout)
        assertEquals(1000L, parsed.quicStreamReceiveWindow)
        assertEquals(2000L, parsed.quicConnectionReceiveWindow)
        assertEquals(12, parsed.quicMaxConcurrentStreams)
        assertTrue(parsed.quicDisablePathMtuDiscovery)
    }

    @Test
    fun hysteriaJsonUsesCorrectStreamAndConnectionWindows() {
        val parsed = JSONObject(
            """{"server":"hy1.example.com:443","up_mbps":10,"down_mbps":50,"recv_window":1000,"recv_window_conn":2000}""",
        ).parseHysteria1Json()

        assertEquals(1000, parsed.streamReceiveWindow)
        assertEquals(2000, parsed.connectionReceiveWindow)
    }

    @Test
    fun clashHysteriaFormatsParseOldAnd114Options() {
        val hy1 = parseClashHysteria(
            mapOf(
                "name" to "hy1",
                "server" to "hy1.example.com",
                "ports" to "443,8443",
                "auth-str" to "secret",
                "recv-window" to 1000,
                "recv-window-conn" to 2000,
                "disable-mtu-discovery" to true,
                "stream_receive_window" to 3000,
                "connection_receive_window" to 4000,
            ),
            1,
        )
        assertEquals(HysteriaBean.TYPE_STRING, hy1.authPayloadType)
        assertEquals(1000, hy1.streamReceiveWindow)
        assertEquals(2000, hy1.connectionReceiveWindow)
        assertEquals(3000L, hy1.quicStreamReceiveWindow)
        assertEquals(4000L, hy1.quicConnectionReceiveWindow)

        val hy2 = parseClashHysteria(
            mapOf(
                "name" to "hy2",
                "server" to "hy2.example.com",
                "port" to 443,
                "password" to "secret",
                "obfs" to "gecko",
                "obfs-password" to "cover",
                "gecko-min-packet-size" to 64,
                "gecko-max-packet-size" to 1400,
                "hop-interval-max" to "12s",
                "bbr-profile" to "mobile",
                "brutal-debug" to true,
                "handshake-timeout" to "7s",
                "initial-packet-size" to 1320,
                "realm" to mapOf(
                    "server_url" to "https://realm.example.com",
                    "token" to "token",
                    "realm_id" to "id",
                    "stun_servers" to listOf("stun1", "stun2"),
                    "ip_version" to 4,
                    "port_mapping" to mapOf("enabled" to true, "timeout" to "4s", "lifetime" to "1m"),
                ),
            ),
            2,
        )
        assertEquals("gecko", hy2.obfsType)
        assertEquals(1400, hy2.geckoMaxPacketSize)
        assertEquals("12s", hy2.hopIntervalMax)
        assertEquals("7s", hy2.tlsHandshakeTimeout)
        assertEquals(1320, hy2.quicInitialPacketSize)
        assertEquals("stun1\nstun2", hy2.realmStunServers)
        assertTrue(hy2.realmPortMapping)
    }

    @Test
    fun tuicUriAndClashPreserveShared114Options() {
        val uriBean = parseTuic(
            "tuic://uuid:password@tuic.example.com:443?congestion_control=bbr&udp_relay_mode=quic" +
                "&zero_rtt_handshake=1&handshake_timeout=4s&idle_timeout=25s" +
                "&keep_alive_period=6s&stream_receive_window=1111&connection_receive_window=2222" +
                "&max_concurrent_streams=16&initial_packet_size=1300&disable_path_mtu_discovery=1",
        ).applyDefaultValues()
        assertTrue(uriBean.reduceRTT)
        assertEquals("4s", uriBean.tlsHandshakeTimeout)
        assertEquals(1300, uriBean.quicInitialPacketSize)

        val reparsed = parseTuic(uriBean.toUri()).applyDefaultValues()
        assertSharedQuic(uriBean, reparsed)
        assertTrue(reparsed.reduceRTT)

        val clashBean = parseClashTuic(
            mapOf(
                "name" to "tuic",
                "server" to "tuic.example.com",
                "port" to 443,
                "uuid" to "uuid",
                "password" to "password",
                "zero-rtt-handshake" to true,
                "handshake_timeout" to "5s",
                "idle_timeout" to "40s",
                "max_concurrent_streams" to 32,
                "disable_path_mtu_discovery" to true,
            ),
        )
        assertEquals(5, clashBean.protocolVersion)
        assertEquals("5s", clashBean.tlsHandshakeTimeout)
        assertEquals(32, clashBean.quicMaxConcurrentStreams)
        assertTrue(clashBean.quicDisablePathMtuDiscovery)
    }

    @Test
    fun snellUriAndClashParseV6Values() {
        val uri = parseSnell(
            "snell://server-psk@snell.example.com:443?version=6&userkey=user-key&mode=unshaped&reuse=true",
        ).applyDefaultValues()
        assertEquals(6, uri.version)
        assertEquals("user-key", uri.userKey)
        assertEquals("unshaped", uri.mode)
        assertEquals("user-key", parseSnell(uri.toUri()).userKey)

        val clash = parseClashSnell(
            mapOf(
                "name" to "snell",
                "server" to "snell.example.com",
                "port" to 443,
                "psk" to "server-psk",
                "version" to 6,
                "userkey" to "user-key",
                "mode" to "unshaped",
                "udp" to true,
            ),
        ).applyDefaultValues()
        assertEquals(6, clash.version)
        assertEquals("user-key", clash.userKey)
        assertEquals("unshaped", clash.mode)
        assertFalse(clash.network == "tcp")
    }

    @Test
    fun sshUriAndClashParse114Algorithms() {
        val source = SSHBean().applyDefaultValues().apply {
            serverAddress = "ssh.example.com"
            serverPort = 2222
            username = "alice"
            authType = SSHBean.AUTH_TYPE_PASSWORD
            password = "secret"
            publicKey = "ssh-ed25519 AAAA"
            hostKeyAlgorithms = "ssh-ed25519\nrsa-sha2-512"
            clientVersion = "SSH-2.0-NB4A"
            cipher = "aes128-gcm@openssh.com\nchacha20-poly1305@openssh.com"
            mac = "hmac-sha2-256"
            kexAlgorithm = "curve25519-sha256"
        }
        val uri = parseSSH(source.toUri())
        assertEquals(source.hostKeyAlgorithms, uri.hostKeyAlgorithms)
        assertEquals(source.clientVersion, uri.clientVersion)
        assertEquals(source.cipher, uri.cipher)
        assertEquals(source.mac, uri.mac)
        assertEquals(source.kexAlgorithm, uri.kexAlgorithm)

        val clash = parseClashSSH(
            mapOf(
                "name" to "ssh",
                "server" to "ssh.example.com",
                "port" to 22,
                "user" to "root",
                "private_key" to "PRIVATE KEY",
                "private_key_passphrase" to "passphrase",
                "host_key" to listOf("key-one", "key-two"),
                "host_key_algorithms" to listOf("ssh-ed25519", "rsa-sha2-512"),
                "client_version" to "SSH-2.0-Test",
                "cipher" to listOf("aes128-gcm@openssh.com"),
                "mac" to listOf("hmac-sha2-256"),
                "kex_algorithm" to listOf("curve25519-sha256"),
            ),
        )
        assertEquals(SSHBean.AUTH_TYPE_PRIVATE_KEY, clash.authType)
        assertEquals("key-one\nkey-two", clash.publicKey)
        assertEquals("ssh-ed25519\nrsa-sha2-512", clash.hostKeyAlgorithms)
        assertEquals("curve25519-sha256", clash.kexAlgorithm)
    }

    @Test
    fun rawClashSubscriptionUses114ProtocolConverters() = runBlocking {
        val profiles = RawUpdater.parseRaw(
            """
            proxies:
              - name: hy2
                type: hysteria2
                server: hy.example.com
                port: 443
                password: secret
                obfs:
                  type: gecko
                  password: cover
                  min_packet_size: 64
                  max_packet_size: 1400
                hop_interval_max: 12s
                initial_packet_size: 1320
                realm:
                  server_url: https://realm.example.com
                  realm_id: test
                  stun_servers: [stun1.example.com, stun2.example.com]
              - name: tuic
                type: tuic
                server: tuic.example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000000
                password: secret
                handshake_timeout: 5s
                max_concurrent_streams: 32
              - name: snell
                type: snell
                server: snell.example.com
                port: 443
                psk: server-key
                version: 6
                userkey: user-key
                mode: unshaped
              - name: ssh
                type: ssh
                server: ssh.example.com
                port: 22
                user: root
                password: secret
                cipher: [aes128-gcm@openssh.com]
                mac: [hmac-sha2-256]
                kex_algorithm: [curve25519-sha256]
            """.trimIndent(),
        )!!

        assertEquals(4, profiles.size)
        assertEquals("gecko", (profiles[0] as HysteriaBean).obfsType)
        assertEquals(1320, profiles[0].quicInitialPacketSize)
        assertEquals(32, profiles[1].quicMaxConcurrentStreams)
        assertEquals("user-key", (profiles[2] as SnellBean).userKey)
        assertEquals("curve25519-sha256", (profiles[3] as SSHBean).kexAlgorithm)
    }

    private fun assertSharedQuic(expected: AbstractBean, actual: AbstractBean) {
        assertEquals(expected.tlsHandshakeTimeout, actual.tlsHandshakeTimeout)
        assertEquals(expected.quicIdleTimeout, actual.quicIdleTimeout)
        assertEquals(expected.quicKeepAlivePeriod, actual.quicKeepAlivePeriod)
        assertEquals(expected.quicStreamReceiveWindow, actual.quicStreamReceiveWindow)
        assertEquals(expected.quicConnectionReceiveWindow, actual.quicConnectionReceiveWindow)
        assertEquals(expected.quicMaxConcurrentStreams, actual.quicMaxConcurrentStreams)
        assertEquals(expected.quicInitialPacketSize, actual.quicInitialPacketSize)
        assertEquals(expected.quicDisablePathMtuDiscovery, actual.quicDisablePathMtuDiscovery)
    }
}
