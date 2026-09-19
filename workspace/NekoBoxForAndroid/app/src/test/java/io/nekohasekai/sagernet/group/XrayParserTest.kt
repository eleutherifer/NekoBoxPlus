package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayParserTest {
    @Test
    fun importsEverySupportedXrayRemoteProtocol() {
        val proxies =
            XrayParser.parse(
                """
                {
                  "remarks": "All Xray",
                  "outbounds": [
                    {
                      "protocol": "http",
                      "tag": "http",
                      "settings": {
                        "servers": [{
                          "address": "http.example",
                          "port": 8080,
                          "users": [{"user": "alice", "pass": "secret"}]
                        }]
                      }
                    },
                    {
                      "protocol": "socks",
                      "tag": "socks",
                      "settings": {
                        "address": "socks.example",
                        "port": 1080,
                        "user": "bob",
                        "pass": "password"
                      }
                    },
                    {
                      "protocol": "shadowsocks",
                      "tag": "ss",
                      "settings": {
                        "servers": [{
                          "address": "ss.example",
                          "port": 8388,
                          "method": "2022-blake3-aes-128-gcm",
                          "password": "key",
                          "uot": true
                        }]
                      }
                    },
                    {
                      "protocol": "vmess",
                      "tag": "vmess",
                      "settings": {
                        "vnext": [{
                          "address": "vmess.example",
                          "port": 443,
                          "users": [{
                            "id": "11111111-1111-1111-1111-111111111111",
                            "alterId": 0,
                            "security": "auto"
                          }]
                        }]
                      },
                      "streamSettings": {
                        "network": "ws",
                        "security": "tls",
                        "wsSettings": {
                          "path": "/ws",
                          "headers": {"Host": "cdn.example"},
                          "maxEarlyData": 2048,
                          "earlyDataHeaderName": "Sec-WebSocket-Protocol"
                        },
                        "tlsSettings": {
                          "serverName": "tls.example",
                          "alpn": ["h2", "http/1.1"],
                          "fingerprint": "chrome",
                          "curvePreferences": ["X25519"]
                        },
                        "sockopt": {
                          "tcpFastOpen": true,
                          "tcpMptcp": true,
                          "interface": "wlan0",
                          "mark": 12
                        }
                      },
                      "mux": {"enabled": true, "concurrency": 16}
                    },
                    {
                      "protocol": "vless",
                      "tag": "vless",
                      "settings": {
                        "address": "vless.example",
                        "port": 443,
                        "id": "22222222-2222-2222-2222-222222222222",
                        "flow": "xtls-rprx-vision",
                        "encryption": "none"
                      },
                      "streamSettings": {
                        "network": "xhttp",
                        "security": "reality",
                        "xhttpSettings": {
                          "mode": "auto",
                          "host": "xhttp.example",
                          "path": "/xhttp",
                          "extra": {"noSSEHeader": true}
                        },
                        "realitySettings": {
                          "serverName": "reality.example",
                          "fingerprint": "firefox",
                          "publicKey": "public-key",
                          "shortId": "01234567"
                        }
                      }
                    },
                    {
                      "protocol": "trojan",
                      "tag": "trojan",
                      "settings": {
                        "servers": [{
                          "address": "trojan.example",
                          "port": 443,
                          "password": "trojan-password"
                        }]
                      },
                      "streamSettings": {"security": "tls"}
                    },
                    {
                      "protocol": "hysteria",
                      "tag": "hysteria",
                      "settings": {
                        "version": 2,
                        "address": "hy.example",
                        "port": 443
                      },
                      "streamSettings": {
                        "security": "tls",
                        "tlsSettings": {"serverName": "hy-sni.example"},
                        "hysteriaSettings": {"auth": "hy-password"}
                      }
                    },
                    {
                      "protocol": "wireguard",
                      "tag": "wg",
                      "settings": {
                        "secretKey": "private-key",
                        "address": ["10.0.0.2/32", "fd00::2/128"],
                        "mtu": 1380,
                        "reserved": [1, 2, 3],
                        "peers": [
                          {
                            "endpoint": "wg1.example:51820",
                            "publicKey": "public-key-1",
                            "preSharedKey": "psk-1",
                            "keepAlive": 25
                          },
                          {
                            "endpoint": "[2001:db8::1]:51821",
                            "publicKey": "public-key-2"
                          }
                        ]
                      }
                    },
                    {"protocol": "freedom", "tag": "direct", "settings": {}}
                  ]
                }
                """.trimIndent(),
            )!!

        assertEquals(9, proxies.size)
        assertTrue(proxies[0] is HttpBean)
        assertTrue(proxies[1] is SOCKSBean)
        assertTrue(proxies[2] is ShadowsocksBean)
        assertTrue(proxies[3] is VMessBean)
        assertTrue((proxies[4] as VMessBean).isVLESS)
        assertTrue(proxies[5] is TrojanBean)
        assertTrue(proxies[6] is HysteriaBean)
        assertTrue(proxies[7] is WireGuardBean)
        assertTrue(proxies[8] is WireGuardBean)

        val vmess = proxies[3] as VMessBean
        assertEquals("ws", vmess.type)
        assertEquals("/ws", vmess.path)
        assertEquals("cdn.example", vmess.host)
        assertEquals("tls.example", vmess.sni)
        assertEquals("h2\nhttp/1.1", vmess.alpn)
        assertEquals(16, vmess.muxConcurrency)
        assertEquals("X25519", vmess.tlsCurvePreferences)
        val custom = JSONObject(vmess.customOutboundJson)
        assertTrue(custom.getBoolean("tcp_fast_open"))
        assertTrue(custom.getBoolean("tcp_multi_path"))
        assertEquals("wlan0", custom.getString("bind_interface"))
        assertEquals(12, custom.getInt("routing_mark"))

        val vless = proxies[4] as VMessBean
        assertEquals("xhttp", vless.type)
        assertEquals("reality", vless.security)
        assertEquals("public-key", vless.realityPubKey)
        assertEquals("01234567", vless.realityShortId)

        val firstPeer = proxies[7] as WireGuardBean
        assertEquals("All Xray [wg] #1", firstPeer.name)
        assertEquals("wg1.example", firstPeer.serverAddress)
        assertEquals(51820, firstPeer.serverPort)
        assertEquals("10.0.0.2/32\nfd00::2/128", firstPeer.localAddress)
        assertEquals("1\n2\n3", firstPeer.reserved)

        val secondPeer = proxies[8] as WireGuardBean
        assertEquals("2001:db8::1", secondPeer.serverAddress)
        assertEquals(51821, secondPeer.serverPort)
        assertFalse(proxies.any { it.name == "direct" })
    }

    @Test
    fun skipsMalformedSupportedOutboundButKeepsValidOnes() {
        val proxies =
            XrayParser.parse(
                """
                {
                  "outbounds": [
                    {
                      "protocol": "trojan",
                      "tag": "bad",
                      "settings": {"servers": [{"address": "bad.example", "port": 443}]}
                    },
                    {
                      "protocol": "socks",
                      "tag": "good",
                      "settings": {"address": "good.example", "port": 1080}
                    }
                  ]
                }
                """.trimIndent(),
            )!!

        assertEquals(1, proxies.size)
        assertEquals("good", proxies.single().name)
    }

    @Test
    fun doesNotClaimSingBoxJson() {
        assertNull(
            XrayParser.parse(
                """{"outbounds":[{"type":"socks","tag":"sing-box","server":"example.com","server_port":1080}]}""",
            ),
        )
    }
}
