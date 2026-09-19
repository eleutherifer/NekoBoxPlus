package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.applySharedTLSOptions
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.internal.decodeEmbeddedProfiles
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.SingBoxOptions.OutboundECHOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundTLSOptions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class XrayParserTest {
    @Test
    fun normalizesLegacyVlessVisionUdp443Flow() {
        val proxies =
            XrayParser.parse(
                """
                {"outbounds":[
                  {"protocol":"vless","settings":{"address":"legacy.example","port":443,"id":"11111111-1111-1111-1111-111111111111","flow":"xtls-rprx-vision-udp443"}},
                  {"protocol":"vless","settings":{"address":"current.example","port":443,"id":"22222222-2222-2222-2222-222222222222","flow":"xtls-rprx-vision"}}
                ]}
                """.trimIndent(),
            )!!

        assertEquals("xtls-rprx-vision", (proxies[0] as VMessBean).encryption)
        assertEquals("xtls-rprx-vision", (proxies[1] as VMessBean).encryption)
    }

    @Test
    fun convertsBalancerMembersIntoEmbeddedUrlTest() {
        val proxies = XrayParser.parse(
            """
            {
              "remarks":"Fleet",
              "outbounds":[
                {"protocol":"socks","tag":"proxy-b","settings":{"address":"b.example","port":1080}},
                {"protocol":"socks","tag":"other","settings":{"address":"other.example","port":1080}},
                {"protocol":"socks","tag":"proxy-a","settings":{"address":"a.example","port":1080}}
              ],
              "routing":{"balancers":[{
                "tag":"fastest","selector":["proxy-"],"strategy":{"type":"leastPing"}
              }]},
              "observatory":{"subjectSelector":["proxy-"],"probeURL":"https://probe.example/generate_204","probeInterval":"30s"}
            }
            """.trimIndent(),
        )!!

        assertEquals(2, proxies.size)
        val urlTest = proxies.first() as ProxySetBean
        assertEquals("Fleet", urlTest.name)
        assertEquals(ProxySetBean.MODE_URL_TEST, urlTest.mode)
        assertEquals("https://probe.example/generate_204", urlTest.testURL)
        assertEquals("30s", urlTest.testInterval)
        val members = urlTest.decodeEmbeddedProfiles()
        assertEquals(listOf("³ Fleet", "¹ Fleet"), members.map { it.displayName() })
        assertEquals(listOf("a.example", "b.example"), members.map { it.requireBean().serverAddress })
        assertEquals("² Fleet", proxies.last().name)
    }

    @Test
    fun convertsAllSupportedBalancerStrategiesIncludingDefaultRandom() {
        listOf(null, "random", "round-robin", "least-load", "leastPing").forEach { strategy ->
            val strategyJson = strategy?.let { "\"strategy\":{\"type\":\"$it\"}," }.orEmpty()
            val parsed = XrayParser.parse(
                """
                {"outbounds":[
                  {"protocol":"socks","tag":"proxy-a","settings":{"address":"a.example","port":1080}},
                  {"protocol":"socks","tag":"proxy-b","settings":{"address":"b.example","port":1080}}
                ],"routing":{"balancers":[{$strategyJson"tag":"set","selector":["proxy-"]}]}}
                """.trimIndent(),
            )!!
            assertEquals(strategy ?: "default random", 1, parsed.size)
            assertTrue(strategy ?: "default random", parsed.single() is ProxySetBean)
        }
    }

    @Test
    fun convertsLeastLoadBalancerWithSelectedFallbackAndProbeUrl() {
        val parsed = XrayParser.parse(
            """
            [{
              "remarks":"Auto fastest",
              "outbounds":[
                {"protocol":"socks","tag":"cand-fallback","settings":{"address":"fallback.example","port":1080}},
                {"protocol":"socks","tag":"cand-01","settings":{"address":"a.example","port":1080}},
                {"protocol":"socks","tag":"cand-02","settings":{"address":"b.example","port":1080}},
                {"protocol":"freedom","tag":"direct"}
              ],
              "observatory":{
                "probeInterval":"10s",
                "probeUrl":"https://www.google.com/generate_204",
                "subjectSelector":["cand-fallback","cand-01","cand-02"]
              },
              "routing":{"balancers":[{
                "tag":"bal_price",
                "selector":["cand-01","cand-02"],
                "fallbackTag":"cand-fallback",
                "strategy":{"type":"leastLoad","settings":{"expected":1,"maxRTT":"2s"}}
              }]}
            }]
            """.trimIndent(),
        )!!

        assertTrue(parsed.single() is ProxySetBean)
        val urlTest = parsed.single() as ProxySetBean
        assertEquals("Auto fastest", urlTest.name)
        assertEquals("https://www.google.com/generate_204", urlTest.testURL)
        assertEquals("10s", urlTest.testInterval)
        assertEquals(
            listOf("a.example", "b.example", "fallback.example"),
            urlTest.decodeEmbeddedProfiles().map { it.requireBean().serverAddress },
        )
    }

    @Test
    fun keepsFlatProfilesForFallbackAndMixedUnsafeBalancerMembers() {
        val parsed = XrayParser.parse(
            """
            {"outbounds":[
              {"protocol":"socks","tag":"a","settings":{"address":"a.example","port":1080}},
              {"protocol":"socks","tag":"b","settings":{"address":"b.example","port":1080}},
              {"protocol":"socks","tag":"c","settings":{"address":"c.example","port":1080}}
            ],"routing":{"balancers":[
              {"tag":"safe","selector":["a","b"],"strategy":{"type":"random"}},
              {"tag":"unsafe","selector":["b"],"strategy":{"type":"unknown"}},
              {"tag":"fallback","selector":["c"],"strategy":{"type":"leastPing"},"fallbackTag":"direct"}
            ]}}
            """.trimIndent(),
        )!!

        assertEquals(3, parsed.size)
        assertTrue(parsed.first() is ProxySetBean)
        assertEquals(listOf("b.example", "c.example"), parsed.drop(1).map { it.serverAddress })
    }

    @Test
    fun conflictingObservatoriesLeaveUrlTestDefaults() {
        val parsed = XrayParser.parse(
            """
            {"outbounds":[
              {"protocol":"socks","tag":"proxy-a","settings":{"address":"a.example","port":1080}}
            ],"routing":{"balancers":[{"tag":"set","selector":["proxy-"]}]},
              "observatory":{"subjectSelector":["proxy-"],"probeURL":"https://one.example/","probeInterval":"10s"},
              "burstObservatory":{"subjectSelector":["proxy-"],"pingConfig":{"destination":"https://two.example/","interval":"20s"}}
            }
            """.trimIndent(),
        )!!.single() as ProxySetBean

        assertEquals("https://www.gstatic.com/generate_204", parsed.testURL)
        assertEquals("3m", parsed.testInterval)
    }

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
        assertTrue(vmess.tcpFastOpen)
        assertTrue(vmess.tcpMultiPath)
        val custom = JSONObject(vmess.customOutboundJson)
        assertFalse(custom.has("tcp_fast_open"))
        assertFalse(custom.has("tcp_multi_path"))
        assertEquals("wlan0", custom.getString("bind_interface"))
        assertEquals(12, custom.getInt("routing_mark"))

        val vless = proxies[4] as VMessBean
        assertEquals("xhttp", vless.type)
        assertEquals("reality", vless.security)
        assertEquals("public-key", vless.realityPubKey)
        assertEquals("01234567", vless.realityShortId)

        val firstPeer = proxies[7] as WireGuardBean
        assertEquals("¹ All Xray", proxies.first().name)
        assertEquals("⁸ All Xray", firstPeer.name)
        assertEquals("wg1.example", firstPeer.serverAddress)
        assertEquals(51820, firstPeer.serverPort)
        assertEquals("10.0.0.2/32\nfd00::2/128", firstPeer.localAddress)
        assertEquals("1\n2\n3", firstPeer.reserved)

        val secondPeer = proxies[8] as WireGuardBean
        assertEquals("⁹ All Xray", secondPeer.name)
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
        assertEquals("Xray Socks", proxies.single().name)
    }

    @Test
    fun numbersEveryProducedProfileWithoutUsingTags() {
        val outbounds = JSONArray()
        repeat(10) { index ->
            outbounds.put(
                JSONObject()
                    .put("protocol", "socks")
                    .put("tag", "technical-$index")
                    .put("settings", JSONObject().put("address", "server-$index.example").put("port", 1080)),
            )
        }
        val proxies = XrayParser.parse(JSONObject().put("remarks", "Fleet").put("outbounds", outbounds).toString())!!

        assertEquals(10, proxies.size)
        assertEquals("¹ Fleet", proxies.first().name)
        assertEquals("¹⁰ Fleet", proxies.last().name)
        assertFalse(proxies.any { it.name.contains("technical") })
    }

    @Test
    fun convertsXrayCertificatePinsAndNormalizesTlsOptions() {
        val firstPin = ByteArray(32) { it.toByte() }
        val firstHex = firstPin.joinToString(":") { "%02X".format(it) }
        val secondPin = ByteArray(32) { 0xFF.toByte() }
        val bean = XrayParser.parse(
            """
            {
              "remarks": "Pinned",
              "outbounds": [{
                "protocol": "vless",
                "settings": {"address":"example.com","port":443,"id":"22222222-2222-2222-2222-222222222222"},
                "streamSettings": {
                  "security": "tls",
                  "tlsSettings": {
                    "serverName": "example.com",
                    "curvePreferences": ["CurveP256", "X25519", "SecP384r1MLKEM1024"],
                    "pinnedPeerCertSha256": "$firstHex,${"ff".repeat(32)}",
                    "echConfigList": "AQID"
                  }
                }
              }]
            }
            """.trimIndent(),
        )!!.single() as VMessBean

        assertEquals("P256\nX25519", bean.tlsCurvePreferences)
        assertEquals(
            listOf(firstPin, secondPin).joinToString("\n") { Base64.getEncoder().encodeToString(it) },
            bean.tlsXrayCertificateSha256,
        )
        assertEquals(
            "-----BEGIN ECH CONFIGS-----\nAQID\n-----END ECH CONFIGS-----",
            bean.echConfig,
        )
        val tls = OutboundTLSOptions().apply { applySharedTLSOptions(bean) }
        assertEquals(bean.tlsXrayCertificateSha256.lines(), tls.xray_certificate_sha256)
        assertNull(tls.certificate_public_key_sha256)
        assertEquals(listOf("P256", "X25519"), tls.curve_preferences)
    }

    @Test
    fun usesGlobalDnsForXrayEchResolverUrls() {
        fun parse(echConfigList: String) = XrayParser.parse(
            """
            {"outbounds":[{
              "protocol":"vless",
              "settings":{"address":"example.com","port":443,"id":"22222222-2222-2222-2222-222222222222"},
              "streamSettings":{"security":"tls","tlsSettings":{"serverName":"example.com","echConfigList":"$echConfigList"}}
            }]}
            """.trimIndent(),
        )!!.single() as VMessBean

        val named = parse("ech.example+https://dns.example/dns-query")
        assertTrue(named.enableECH)
        assertEquals("", named.echConfig)
        assertEquals("ech.example", named.echQueryServerName)
        val namedTls = OutboundTLSOptions().apply {
            ech = OutboundECHOptions().apply { enabled = true }
            applySharedTLSOptions(named)
        }.ech!!
        assertTrue(namedTls.enabled == true)
        assertEquals("ech.example", namedTls.query_server_name)

        val defaultName = parse("https://dns.example/dns-query")
        assertTrue(defaultName.enableECH)
        assertEquals("", defaultName.echConfig)
        assertEquals("", defaultName.echQueryServerName)
    }

    @Test
    fun readsHttp2TransportSettings() {
        val bean = XrayParser.parse(
            """
            {"outbounds":[{
              "protocol":"vless",
              "settings":{"address":"example.com","port":443,"id":"22222222-2222-2222-2222-222222222222"},
              "streamSettings":{"network":"h2","httpSettings":{"host":["one.example","two.example"],"path":"/h2"}}
            }]}
            """.trimIndent(),
        )!!.single() as VMessBean

        assertEquals("http", bean.type)
        assertEquals("one.example,two.example", bean.host)
        assertEquals("/h2", bean.path)
        assertEquals("none", bean.headerType)
    }

    @Test
    fun treatsXrayUnsafeFingerprintAsStandardTls() {
        val bean = XrayParser.parse(
            """
            {"outbounds":[{
              "protocol":"vless",
              "settings":{"address":"example.com","port":443,"id":"22222222-2222-2222-2222-222222222222"},
              "streamSettings":{"security":"tls","tlsSettings":{"fingerprint":"unsafe"}}
            }]}
            """.trimIndent(),
        )!!.single() as VMessBean

        assertEquals("", bean.utlsFingerprint)
    }

    @Test
    fun skipsOutboundWithMalformedCertificatePin() {
        val proxies = XrayParser.parse(
            """
            {"outbounds":[
              {"protocol":"vless","settings":{"address":"bad.example","port":443,"id":"22222222-2222-2222-2222-222222222222"},"streamSettings":{"security":"tls","tlsSettings":{"pinnedPeerCertSha256":"00:11"}}},
              {"protocol":"socks","settings":{"address":"good.example","port":1080}}
            ]}
            """.trimIndent(),
        )!!

        assertEquals(1, proxies.size)
        assertEquals("Xray Socks", proxies.single().name)
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
