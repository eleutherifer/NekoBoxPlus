package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashAwgMasqueImportTest {

    @Test
    fun clashImportsWireGuardAwgAndMasqueProfiles() = runBlocking {
        val proxies = RawUpdater.parseRaw(
            """
            warp-common: &warp-common
              type: wireguard
              ip: 172.16.0.2
              ipv6: 2606:4700:110:8ad9:73f9:1864:d4d1:72f9
              private-key: FzJldxydz4J7IGO9xVBmudushcQ+BQCKMGKCPD1+SKU=
              public-key: bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
              allowed-ips: ['0.0.0.0/0']
              udp: true
              mtu: 1280
            awg: &awg
              amnezia-wg-option:
                jc: 4
                jmin: 40
                jmax: 70
                s1: 0
                s2: 0
                h1: 1
                h2: 2
                h3: 3
                h4: 4
                i1: <b 0x494e56495445>
                header-protection-key: header-key
                content-padding-addition: 10-20
                rekey-after-time: 100-120
                rekey-timeout: 5
                reject-after-time: 180
                keepalive-timeout: 10-15
                max-handshake-attempts: 20
                random-trailers: true
                disable-cookies: true
            msq: &msq
              type: masque
              private-key: MHcCAQEEILEmhxxAAmIzUbDIJ6g7irEZIAruZPoMjdw9YaGrFFDnoAoGCCqGSM49AwEHoUQDQgAECQCgMdRjPl7Euqgv6LMDvMMiTTRRGyYrqTJoTpt8sTtrKMhgREDy7fyswFKPP3OZKaQZHv32dQs897/jZ4oNqA==
              public-key: MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==
              ip: 172.16.0.2
              ipv6: 2606:4700:110:8fec:9301:8a66:2586:53a1
            proxies:
              - name: plain-wg
                <<: *warp-common
                server: 162.159.192.1
                port: 4500
              - name: awg
                <<: [ *warp-common, *awg ]
                server: pl.example.com
                port: 500
                persistent-keepalive: 22-30
              - name: masque
                <<: *msq
                server: 162.159.198.2
                port: 443
                sni: example.com
              - name: masque-h2
                <<: *msq
                server: 162.159.198.3
                port: 443
                sni: h2.example.com
                network: h2
            """.trimIndent(),
        )!!

        assertEquals(4, proxies.size)

        val wireGuard = proxies[0] as WireGuardBean
        assertEquals("plain-wg", wireGuard.name)
        assertEquals("162.159.192.1", wireGuard.serverAddress)
        assertEquals(4500, wireGuard.serverPort)
        assertEquals(
            "172.16.0.2/32\n2606:4700:110:8ad9:73f9:1864:d4d1:72f9/128",
            wireGuard.localAddress,
        )

        val awg = proxies[1] as AmneziaWGBean
        assertEquals("awg", awg.name)
        assertEquals("pl.example.com", awg.serverAddress)
        assertEquals(500, awg.serverPort)
        assertEquals(4, awg.jc)
        assertEquals(40, awg.jmin)
        assertEquals(70, awg.jmax)
        assertEquals("1", awg.h1)
        assertEquals("4", awg.h4)
        assertEquals("<b 0x494e56495445>", awg.i1)
        assertEquals("header-key", awg.headerProtectionKey)
        assertEquals("10-20", awg.contentPaddingAddition)
        assertEquals("100-120", awg.rekeyAfterTime)
        assertEquals("5", awg.rekeyTimeout)
        assertEquals("180", awg.rejectAfterTime)
        assertEquals("10-15", awg.keepaliveTimeout)
        assertEquals("20", awg.maxHandshakeAttempts)
        assertTrue(awg.randomTrailers)
        assertTrue(awg.disableCookies)
        assertEquals("22-30", awg.peerPersistentKeepalive)

        val masque = proxies[2] as MasqueBean
        assertEquals("masque", masque.name)
        assertFalse(masque.useHTTP2)
        assertFalse(masque.useIPv6)
        assertEquals("162.159.198.2", masque.configEndpointV4)
        assertEquals("162.159.198.2", masque.configEndpointH2V4)
        assertEquals("172.16.0.2", masque.configIPv4)
        assertEquals("2606:4700:110:8fec:9301:8a66:2586:53a1", masque.configIPv6)
        assertEquals("example.com", masque.tlsSNI)
        assertTrue(masque.configEndpointPubKey.startsWith("-----BEGIN PUBLIC KEY-----\n"))
        assertTrue(masque.configEndpointPubKey.endsWith("\n-----END PUBLIC KEY-----"))

        val masqueH2 = proxies[3] as MasqueBean
        assertTrue(masqueH2.useHTTP2)
        assertFalse(masqueH2.useIPv6)
        assertEquals("162.159.198.3", masqueH2.configEndpointH2V4)
        assertEquals("h2.example.com", masqueH2.tlsSNI)
    }

    @Test
    fun clashImportsMasqueOnlyYamlAfterTabNormalizationRetry() = runBlocking {
        val proxies = RawUpdater.parseRaw(
            """
            msq: &msq
              type: masque
              private-key: MHcCAQEEIMImBO5bR0o6TYv9Rs1nvVMGpRLweHcUb/wvV2z//ISsoAoGCCqGSM49AwEHoUQDQgAEzRiW4wjgzVg1GESt3zp+piA1er7wGY2DOzNEMZRHjFKm930IgHJvK3qHsPaUjm3E+RirjItnMla9R36WnCemiw==
              public-key: MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==
              ip: 172.16.0.2
              ipv6: 2606:4700:110:8e1e:87f2:e3f7:ca5:c9be
              mtu: 1280
              udp: true
              remote-dns-resolve: true
              dns: [1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001]
            proxies:
              - name: "MASQUE"
                server: 162.159.198.2
                port: 443
                sni: 4pda.to
                <<: *msq
              - name: "MASQUE h2"
                server: 162.159.198.2
                port: 443
                sni: 4pda.to
                network: h2
                <<: *msq
            proxy-groups:
              - name: WARP
                type: select
                proxies:
                  - "MASQUE"
                  - "MASQUE h2"${'\t'}
            rules:
              - MATCH,WARP
            """.trimIndent(),
        )!!

        assertEquals(2, proxies.size)

        val masque = proxies[0] as MasqueBean
        assertEquals("MASQUE", masque.name)
        assertFalse(masque.useHTTP2)
        assertFalse(masque.useIPv6)
        assertEquals("162.159.198.2", masque.configEndpointV4)
        assertEquals("162.159.198.2", masque.configEndpointH2V4)
        assertEquals("172.16.0.2", masque.configIPv4)
        assertEquals("2606:4700:110:8e1e:87f2:e3f7:ca5:c9be", masque.configIPv6)
        assertEquals("4pda.to", masque.tlsSNI)
        assertTrue(masque.configEndpointPubKey.startsWith("-----BEGIN PUBLIC KEY-----\n"))
        assertTrue(masque.configEndpointPubKey.endsWith("\n-----END PUBLIC KEY-----"))

        val masqueH2 = proxies[1] as MasqueBean
        assertEquals("MASQUE h2", masqueH2.name)
        assertTrue(masqueH2.useHTTP2)
        assertFalse(masqueH2.useIPv6)
        assertEquals("162.159.198.2", masqueH2.configEndpointV4)
        assertEquals("162.159.198.2", masqueH2.configEndpointH2V4)
        assertEquals("172.16.0.2", masqueH2.configIPv4)
        assertEquals("2606:4700:110:8e1e:87f2:e3f7:ca5:c9be", masqueH2.configIPv6)
        assertEquals("4pda.to", masqueH2.tlsSNI)
        assertTrue(masqueH2.configEndpointPubKey.startsWith("-----BEGIN PUBLIC KEY-----\n"))
        assertTrue(masqueH2.configEndpointPubKey.endsWith("\n-----END PUBLIC KEY-----"))
    }
}
