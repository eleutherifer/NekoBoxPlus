package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardConfImportTest {
    @Test
    fun `I1-only profile imports as AmneziaWG with multiline value`() = runBlocking {
        val profiles =
            RawUpdater.parseRaw(
                """
                # profile
                [Interface]
                PrivateKey = private-key==
                Address = 10.2.0.2/32, 2001:db8::2/128
                MTU = 1420
                I1 = <b 0xce000001
                  0897a297><r 10>

                [Peer]
                PublicKey = public-key==
                AllowedIPs = 0.0.0.0/0, ::/0
                Endpoint = 192.0.2.1:51820
                """.trimIndent(),
            ).orEmpty()

        assertEquals(1, profiles.size)
        val profile = profiles.single()
        assertTrue(profile is AmneziaWGBean)
        profile as AmneziaWGBean
        assertEquals("<b 0xce0000010897a297><r 10>", profile.i1)
        assertEquals("10.2.0.2/32\n2001:db8::2/128", profile.localAddress)
        assertEquals("192.0.2.1", profile.serverAddress)
        assertEquals(51820, profile.serverPort)
    }

    @Test
    fun `ordinary mixed-case WireGuard profile stays WireGuard`() = runBlocking {
        val profiles =
            RawUpdater.parseRaw(
                """
                [interface]
                privatekey=private-key==
                address=10.2.0.2
                DNS = 1.1.1.1
                PostUp = ignored-command

                [peer]
                publickey=public-key==
                endpoint=example.com:51820
                PersistentKeepAlive = 25
                """.trimIndent(),
            ).orEmpty()

        assertEquals(1, profiles.size)
        val profile = profiles.single()
        assertTrue(profile is WireGuardBean)
        profile as WireGuardBean
        assertEquals("10.2.0.2/32", profile.localAddress)
        assertEquals("example.com", profile.serverAddress)
        assertEquals(25, profile.peerPersistentKeepalive)
    }

    @Test
    fun `AWG 3-only options select AmneziaWG and preserve ranges`() = runBlocking {
        val profiles =
            RawUpdater.parseRaw(
                """
                [Interface]
                PrivateKey = private-key==
                Address = 10.2.0.2/32
                HeaderProtectionKey = header-key==
                ContentPaddingAddition = 10-20
                RekeyAfterTime = 100-120
                RekeyTimeout = 5
                RejectAfterTime = 180
                KeepaliveTimeout = 10-15
                MaxHandshakeAttempts = 20
                RandomTrailers = ON
                DisableCookies = enabled

                [Peer]
                PublicKey = public-key==
                Endpoint = example.com:51820
                PersistentKeepalive = 22-30
                """.trimIndent(),
            ).orEmpty()

        val profile = profiles.single() as AmneziaWGBean
        assertEquals("header-key==", profile.headerProtectionKey)
        assertEquals("10-20", profile.contentPaddingAddition)
        assertEquals("100-120", profile.rekeyAfterTime)
        assertEquals("5", profile.rekeyTimeout)
        assertEquals("180", profile.rejectAfterTime)
        assertEquals("10-15", profile.keepaliveTimeout)
        assertEquals("20", profile.maxHandshakeAttempts)
        assertTrue(profile.randomTrailers)
        assertTrue(profile.disableCookies)
        assertEquals("22-30", profile.peerPersistentKeepalive)
    }

    @Test
    fun `AWG 31-only toggles select AmneziaWG and accept disabled aliases`() = runBlocking {
        val profiles =
            RawUpdater.parseRaw(
                """
                [Interface]
                PrivateKey = private-key==
                Address = 10.2.0.2/32
                RandomTrailers = off
                DisableCookies = 0

                [Peer]
                PublicKey = public-key==
                Endpoint = example.com:51820
                """.trimIndent(),
            ).orEmpty()

        val profile = profiles.single() as AmneziaWGBean
        assertFalse(profile.randomTrailers)
        assertFalse(profile.disableCookies)
    }

    @Test
    fun `multiple peers produce profiles and unusable peers are skipped`() {
        val profiles =
            RawUpdater.parseWireGuard(
                """
                [Interface]
                PrivateKey = private-key==
                Address = 10.2.0.2/32

                [Peer]
                PublicKey = first==
                Endpoint = first.example:51820

                [Peer]
                PublicKey = missing-endpoint==

                [Peer]
                PublicKey = second==
                Endpoint = [2001:db8::1]:51821
                """.trimIndent(),
            )

        assertEquals(listOf("first.example", "2001:db8::1"), profiles.map { it.serverAddress })
        assertEquals(listOf(51820, 51821), profiles.map { it.serverPort })
    }
}
