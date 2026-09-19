package io.nekohasekai.sagernet.fmt.wireguard

import io.nekohasekai.sagernet.group.RawUpdater
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class WireGuardLinkFormatTest {
    @Test
    fun throneWireGuardLinkRoundTrips() {
        val bean = WireGuardBean().apply {
            initializeDefaultValues()
            name = "WG Europe"
            serverAddress = "2001:db8::1"
            serverPort = 51820
            privateKey = "private+/="
            peerPublicKey = "public+/="
            peerPreSharedKey = "psk+/="
            localAddress = "10.0.0.2/32\nfd00::2/128"
            mtu = 1380
            reserved = "0, 2, 255"
            peerPersistentKeepalive = 25
        }

        val link = bean.toWireGuardUri()
        val parsed = parseThroneWireGuardUri(link) as WireGuardBean

        assertTrue(link.startsWith("wg://[2001:db8::1]:51820?"))
        assertEquals(bean.name, parsed.name)
        assertEquals(bean.serverAddress, parsed.serverAddress)
        assertEquals(bean.serverPort, parsed.serverPort)
        assertEquals(bean.privateKey, parsed.privateKey)
        assertEquals(bean.peerPublicKey, parsed.peerPublicKey)
        assertEquals(bean.peerPreSharedKey, parsed.peerPreSharedKey)
        assertEquals(bean.localAddress, parsed.localAddress)
        assertEquals(bean.mtu, parsed.mtu)
        assertEquals("0,2,255", parsed.reserved)
        assertEquals(bean.peerPersistentKeepalive, parsed.peerPersistentKeepalive)
    }

    @Test
    fun throneAmneziaParametersCreateAmneziaProfile() {
        val parsed = parseThroneWireGuardUri(
            "wireguard://example.com:51820" +
                "?private_key=private&peer_public_key=public&local_address=10.0.0.2%2F32" +
                "&enable_amnezia=true&jc=4&i1=%3Cb%200x01%3E&rekey_after_time=10-20",
        ) as AmneziaWGBean

        assertEquals(4, parsed.jc)
        assertEquals("<b 0x01>", parsed.i1)
        assertEquals("10-20", parsed.rekeyAfterTime)
    }

    @Test
    fun throneQueryKeepsLiteralPlusSigns() {
        val parsed = parseThroneWireGuardUri(
            "wg://example.com:51820" +
                "?private_key=private+key=&public_key=public+key=" +
                "&pre_shared_key=shared+key=&local_address=10.0.0.2%2F32",
        ) as WireGuardBean

        assertEquals("private+key=", parsed.privateKey)
        assertEquals("public+key=", parsed.peerPublicKey)
        assertEquals("shared+key=", parsed.peerPreSharedKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun throneLinkRequiresExplicitPort() {
        parseThroneWireGuardUri(
            "wg://example.com?private_key=private&public_key=public",
        )
    }

    @Test
    fun amneziaLineAliasesImportAndCanonicalExport() = runBlocking {
        val config = amneziaConfig("server.example.com")
        val encoded = encode(config)
        val profiles = RawUpdater.parseRaw(
            "awg://$encoded#Netherlands\namneziawg://$encoded#Duplicate",
        ).orEmpty()

        assertEquals(1, profiles.size)
        val bean = profiles.single() as AmneziaWGBean
        assertEquals("Netherlands", bean.name)
        assertTrue(bean.toAmneziaWGUri().startsWith("amneziawg://"))
        assertTrue(bean.toAmneziaWGUri().endsWith("#Netherlands"))
    }

    @Test
    fun jsonContainerSkipsBadEntriesAndUsesNameFallbacks() {
        val namedConfig = "# Name=Comment Name\n${amneziaConfig("one.example")}"
        val json = JSONObject(
            """
            {
              "type": "amneziawg",
              "version": 99,
              "servers": [
                {"name": "Explicit", "config": "${encode(amneziaConfig("explicit.example"))}"},
                {"config": "${encode(namedConfig)}"},
                {"config": "%%%"}
              ]
            }
            """.trimIndent(),
        )

        val profiles = parseAmneziaWGJsonContainer(json)

        assertEquals(listOf("Explicit", "Comment Name"), profiles.map { it.name })
        val exported = JSONObject(buildAmneziaWGJsonContainer(profiles))
        assertEquals("amneziawg", exported.getString("type"))
        assertEquals(1, exported.getInt("version"))
        assertEquals(2, exported.getJSONArray("servers").length())
    }

    private fun amneziaConfig(host: String) =
        """
        [Interface]
        PrivateKey = private
        Address = 10.0.0.2/32
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 11
        S2 = 12
        H1 = 101
        H2 = 102

        [Peer]
        PublicKey = public
        Endpoint = $host:51820
        """.trimIndent()

    @OptIn(ExperimentalEncodingApi::class)
    private fun encode(value: String): String =
        Base64.UrlSafe.encode(value.toByteArray()).trimEnd('=')
}
