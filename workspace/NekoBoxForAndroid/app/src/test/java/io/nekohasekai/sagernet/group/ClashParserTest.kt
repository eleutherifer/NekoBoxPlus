package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashParserTest {
    @Test
    fun normalizesLegacyVlessVisionUdp443Flow() {
        val proxies =
            ClashParser.parse(
                """
                proxies:
                  - {name: legacy, type: vless, server: legacy.example, port: 443, uuid: 11111111-1111-1111-1111-111111111111, flow: xtls-rprx-vision-udp443}
                  - {name: current, type: vless, server: current.example, port: 443, uuid: 22222222-2222-2222-2222-222222222222, flow: xtls-rprx-vision}
                """.trimIndent(),
            )!!

        assertEquals("xtls-rprx-vision", (proxies[0] as VMessBean).encryption)
        assertEquals("xtls-rprx-vision", (proxies[1] as VMessBean).encryption)
    }

    @Test
    fun importsMihomoCoreProtocolIntersection() {
        val proxies =
            ClashParser.parse(
                """
                global-client-fingerprint: chrome
                proxies:
                  - {name: socks, type: socks5, server: socks.example, port: 1080}
                  - {name: http, type: http, server: http.example, port: 8080}
                  - {name: ss, type: ss, server: ss.example, port: 8388, cipher: aes-256-gcm, password: secret}
                  - {name: ssr, type: ssr, server: ssr.example, port: 8389, cipher: aes-256-cfb, password: secret, protocol: auth_sha1_v4, obfs: tls1.2_ticket_auth}
                  - {name: vmess, type: vmess, server: vmess.example, port: 443, uuid: 11111111-1111-1111-1111-111111111111, tls: true}
                  - {name: vless, type: vless, server: vless.example, port: 443, uuid: 22222222-2222-2222-2222-222222222222, tls: true}
                  - {name: trojan, type: trojan, server: trojan.example, port: 443, password: secret}
                  - {name: snell, type: snell, server: snell.example, port: 443, psk: secret, version: 4}
                  - {name: hy1, type: hysteria, server: hy1.example, port: 443, auth-str: secret, up: 100 Mbps, down: 200 Mbps}
                  - {name: hy2, type: hysteria2, server: hy2.example, port: 443, password: secret}
                  - {name: wg, type: wireguard, server: wg.example, port: 51820, ip: 10.0.0.2, private-key: private, public-key: public}
                  - {name: tuic, type: tuic, server: tuic.example, port: 443, uuid: 33333333-3333-3333-3333-333333333333, password: secret}
                  - {name: ssh, type: ssh, server: ssh.example, port: 22, username: root, private-key: private}
                  - {name: mieru, type: mieru, server: mieru.example, port: 5000, transport: TCP, username: user, password: secret}
                  - {name: anytls, type: anytls, server: anytls.example, port: 443, password: secret}
                  - {name: masque, type: masque, server: 192.0.2.1, port: 443, private-key: private, public-key: public}
                  - {name: trust, type: trusttunnel, server: trust.example, port: 443, username: user, password: secret}
                  - {name: tailscale, type: tailscale, auth-key: tskey-auth-test, hostname: android}
                  - {name: openvpn, type: openvpn, server: vpn.example, port: 1194}
                  - {name: utility, type: direct}
                """.trimIndent(),
            )!!

        assertEquals(19, proxies.size)
        assertTrue(proxies[0] is SOCKSBean)
        assertTrue(proxies[2] is ShadowsocksBean)
        assertTrue(proxies[3] is ShadowsocksRBean)
        assertTrue(proxies[4] is VMessBean)
        assertTrue((proxies[5] as VMessBean).isVLESS)
        assertTrue(proxies[7] is SnellBean)
        assertTrue(proxies[8] is HysteriaBean)
        assertEquals(2, (proxies[9] as HysteriaBean).protocolVersion)
        assertTrue(proxies[10] is WireGuardBean)
        assertTrue(proxies[11] is TuicBean)
        assertTrue(proxies[12] is SSHBean)
        assertTrue(proxies[13] is MieruBean)
        assertTrue(proxies[14] is AnyTLSBean)
        assertTrue(proxies[15] is MasqueBean)
        assertTrue(proxies[16] is TrustTunnelBean)
        assertTrue(proxies[17] is TailscaleBean)
        assertTrue(proxies[18] is OpenVPNBean)
        assertFalse(proxies.any { it.name == "utility" })
    }

    @Test
    fun preservesCommonCoreAndMuxOptions() {
        val proxy =
            ClashParser.parse(
                """
                proxies:
                  - name: advanced
                    type: vless
                    server: example.com
                    port: 443
                    uuid: 11111111-1111-1111-1111-111111111111
                    network: ws
                    tls: true
                    sni: tls.example
                    tfo: true
                    mptcp: true
                    interface-name: wlan0
                    routing-mark: 42
                    ip-version: prefer-ipv6
                    smux:
                      enabled: true
                      protocol: h2mux
                      max-connections: 3
                      min-streams: 2
                      padding: true
                      brutal-opts:
                        enabled: true
                        up: 120 Mbps
                        down: 240 Mbps
                    ws-opts:
                      path: /ws
                      headers: {Host: cdn.example}
                """.trimIndent(),
            )!!.single() as VMessBean

        assertEquals("ws", proxy.type)
        assertEquals("/ws", proxy.path)
        assertEquals("cdn.example", proxy.host)
        assertTrue(proxy.enableMux)
        assertEquals(1, proxy.muxMode)
        assertEquals(3, proxy.muxMaxConnections)
        assertEquals(2, proxy.muxMinStreams)
        assertTrue(proxy.muxPadding)
        assertTrue(proxy.muxBrutal)
        assertEquals(120, proxy.muxBrutalUpMbps)
        assertEquals(240, proxy.muxBrutalDownMbps)
        assertTrue(proxy.tcpFastOpen)
        assertTrue(proxy.tcpMultiPath)

        val custom = JSONObject(proxy.customOutboundJson)
        assertFalse(custom.has("tcp_fast_open"))
        assertFalse(custom.has("tcp_multi_path"))
        assertEquals("wlan0", custom.getString("bind_interface"))
        assertEquals(42, custom.getInt("routing_mark"))
        assertEquals("prefer_ipv6", custom.getString("domain_strategy"))
    }

    @Test
    fun skipsMalformedEntryAndNormalizesTabs() {
        val proxies =
            ClashParser.parse(
                """
                proxies:
                  - {name: bad, type: trojan, server: bad.example, port: 443}
                  - name: good
                    type: socks5
                    server: good.example
                    port: 1080${'\t'}
                """.trimIndent(),
            )!!

        assertEquals(1, proxies.size)
        assertEquals("good", proxies.single().name)
    }

    @Test
    fun doesNotClaimNonClashYaml() {
        assertNull(ClashParser.parse("server: example.com\nport: 443"))
    }
}
