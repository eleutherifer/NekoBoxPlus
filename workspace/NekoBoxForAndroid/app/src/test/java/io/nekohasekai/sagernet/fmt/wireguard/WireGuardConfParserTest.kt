package io.nekohasekai.sagernet.fmt.wireguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardConfParserTest {
    @Test
    fun `parses WireGuard syntax without INI transformations`() {
        val document =
            WireGuardConfParser.parse(
                "\uFEFF" +
                    """
                    # comment
                    [interface]
                    Address=10.0.0.2
                    ADDRESS = 2001:db8::2/128 # trailing comment
                    PrivateKey = ${'$'}{literal}\key==
                    Custom = value;still-value

                    [PEER]
                    PublicKey=peer-key==
                    Endpoint = [2001:db8::1]:51820
                    """.trimIndent(),
            )

        assertFalse(document.isAmneziaWG)
        assertEquals(listOf("10.0.0.2", "2001:db8::2/128"), document.interfaceOptions.getAll("address"))
        assertEquals("${'$'}{literal}\\key==", document.interfaceOptions["PRIVATEKEY"])
        assertEquals("value;still-value", document.interfaceOptions["custom"])
        assertEquals("peer-key==", document.peers.single()["publickey"])
        assertEquals("[2001:db8::1]:51820", document.peers.single()["endpoint"])
    }

    @Test
    fun `combines repeated interface sections and keeps scalar last value`() {
        val document =
            WireGuardConfParser.parse(
                """
                [Interface]
                Address = 10.0.0.2/32
                MTU = 1280

                [Peer]
                PublicKey = first

                [Interface]
                Address = 2001:db8::2/128
                MTU = 1420

                [Peer]
                PublicKey = second
                """.trimIndent(),
            )

        assertEquals(listOf("10.0.0.2/32", "2001:db8::2/128"), document.interfaceOptions.getAll("Address"))
        assertEquals("1420", document.interfaceOptions["MTU"])
        assertEquals(listOf("first", "second"), document.peers.map { it["PublicKey"] })
    }

    @Test
    fun `joins multiline AmneziaWG signature chains`() {
        val document =
            WireGuardConfParser.parse(
                """
                [Interface]
                PrivateKey = private
                I1 = <b 0x0123\
                  456789>
                  <r 10><t>
                I2 = <b 0xab
                  cd>

                [Peer]
                PublicKey = public
                Endpoint = example.com:51820
                """.trimIndent(),
            )

        assertTrue(document.isAmneziaWG)
        assertEquals("<b 0x0123456789><r 10><t>", document.interfaceOptions["I1"])
        assertEquals("<b 0xabcd>", document.interfaceOptions["I2"])
    }

    @Test
    fun `detects every supported AmneziaWG option`() {
        val values =
            mapOf(
                "Jc" to "1",
                "Jmin" to "2",
                "Jmax" to "3",
                "S1" to "4",
                "S2" to "5",
                "S3" to "6",
                "S4" to "7",
                "H1" to "8",
                "H2" to "9",
                "H3" to "10",
                "H4" to "11",
                "I1" to "<t>",
                "I2" to "<t>",
                "I3" to "<t>",
                "I4" to "<t>",
                "I5" to "<t>",
                "HeaderProtectionKey" to "key",
                "ContentPaddingAddition" to "10-20",
                "RekeyAfterTime" to "100-120",
                "RekeyTimeout" to "5",
                "RejectAfterTime" to "180",
                "KeepaliveTimeout" to "10-15",
                "MaxHandshakeAttempts" to "20",
                "RandomTrailers" to "on",
                "DisableCookies" to "on",
            )

        values.forEach { (key, value) ->
            val document =
                WireGuardConfParser.parse(
                    """
                    [Interface]
                    $key = $value
                    """.trimIndent(),
                )
            assertTrue("$key should identify AmneziaWG", document.isAmneziaWG)
        }
    }

    @Test
    fun `peer keepalive range identifies AmneziaWG 3`() {
        val document =
            WireGuardConfParser.parse(
                """
                [Interface]
                PrivateKey = private

                [Peer]
                PublicKey = public
                PersistentKeepalive = 22-30
                """.trimIndent(),
            )

        assertTrue(document.isAmneziaWG)
    }

    @Test
    fun `rejects INI colon assignments`() {
        assertThrows(IllegalStateException::class.java) {
            WireGuardConfParser.parse(
                """
                [Interface]
                PrivateKey: private
                """.trimIndent(),
            )
        }
    }
}
