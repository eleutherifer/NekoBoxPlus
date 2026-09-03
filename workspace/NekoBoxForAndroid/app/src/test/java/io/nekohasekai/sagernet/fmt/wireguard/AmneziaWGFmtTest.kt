package io.nekohasekai.sagernet.fmt.wireguard

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.KryoConverters
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneziaWGFmtTest {

    @Test
    fun endpointBuilderMapsAllAmneziaOptions() {
        val bean = AmneziaWGBean().apply {
            initializeDefaultValues()
            serverAddress = "2001:db8::1"
            serverPort = 51820
            localAddress = "10.0.0.2, fd00::2"
            privateKey = "private"
            peerPublicKey = "public"
            peerPreSharedKey = "preshared"
            peerPersistentKeepalive = "22-33"
            mtu = 1280
            reserved = "1, 2, 3"
            jc = 4
            jmin = 40
            jmax = 70
            s1 = 11
            s2 = 12
            s3 = 13
            s4 = 14
            h1 = "101-102"
            h2 = "201-202"
            h3 = "301-302"
            h4 = "401-402"
            i1 = "<b 0x01>"
            i2 = "<b 0x02>"
            i3 = "<b 0x03>"
            i4 = "<b 0x04>"
            i5 = "<b 0x05>"
            headerProtectionKey = "header-key"
            contentPaddingAddition = "10-20"
            rekeyAfterTime = "100-120"
            rekeyTimeout = "5"
            rejectAfterTime = "180"
            keepaliveTimeout = "10-15"
            maxHandshakeAttempts = "20"
            randomTrailers = true
            disableCookies = true
        }

        val endpoint = buildSingBoxEndpointAwgBean(bean)

        assertEquals("awg", endpoint.type)
        assertEquals(listOf("10.0.0.2/32", "fd00::2/128"), endpoint.address)
        assertEquals("private", endpoint.private_key)
        assertEquals(1280, endpoint.mtu)
        assertEquals(4, endpoint.jc)
        assertEquals(40, endpoint.jmin)
        assertEquals(70, endpoint.jmax)
        assertEquals(11, endpoint.s1)
        assertEquals(12, endpoint.s2)
        assertEquals(13, endpoint.s3)
        assertEquals(14, endpoint.s4)
        assertEquals("101-102", endpoint.h1)
        assertEquals("201-202", endpoint.h2)
        assertEquals("301-302", endpoint.h3)
        assertEquals("401-402", endpoint.h4)
        assertEquals("<b 0x01>", endpoint.i1)
        assertEquals("<b 0x02>", endpoint.i2)
        assertEquals("<b 0x03>", endpoint.i3)
        assertEquals("<b 0x04>", endpoint.i4)
        assertEquals("<b 0x05>", endpoint.i5)
        assertEquals("header-key", endpoint.header_protection_key)
        assertEquals("10-20", endpoint.content_padding_addition)
        assertEquals("100-120", endpoint.rekey_after_time)
        assertEquals("5", endpoint.rekey_timeout)
        assertEquals("180", endpoint.reject_after_time)
        assertEquals("10-15", endpoint.keepalive_timeout)
        assertEquals("20", endpoint.max_handshake_attempts)
        assertEquals(true, endpoint.random_trailers)
        assertEquals(true, endpoint.disable_cookies)

        val peer = endpoint.peers.single()
        assertEquals("[2001:db8::1]", peer.address)
        assertEquals(51820, peer.port)
        assertEquals("public", peer.public_key)
        assertEquals("preshared", peer.preshared_key)
        assertEquals("22-33", peer.persistent_keepalive_interval)
        assertEquals(listOf("0.0.0.0/0", "::/0"), peer.allowed_ips)
        assertEquals(listOf(1, 2, 3), peer.reserved)
    }

    @Test
    fun awg31OptionsRoundTripThroughBeanSerializationAndConfExport() {
        val bean = AmneziaWGBean().apply {
            initializeDefaultValues()
            headerProtectionKey = "header-key"
            contentPaddingAddition = "10-20"
            rekeyAfterTime = "100-120"
            rekeyTimeout = "5"
            rejectAfterTime = "180"
            keepaliveTimeout = "10-15"
            maxHandshakeAttempts = "20"
            peerPersistentKeepalive = "22-30"
            randomTrailers = true
            disableCookies = true
        }

        val restored = KryoConverters.deserialize(
            AmneziaWGBean(),
            KryoConverters.serialize(bean),
        )

        assertEquals("header-key", restored.headerProtectionKey)
        assertEquals("10-20", restored.contentPaddingAddition)
        assertEquals("100-120", restored.rekeyAfterTime)
        assertEquals("5", restored.rekeyTimeout)
        assertEquals("180", restored.rejectAfterTime)
        assertEquals("10-15", restored.keepaliveTimeout)
        assertEquals("20", restored.maxHandshakeAttempts)
        assertEquals("22-30", restored.peerPersistentKeepalive)
        assertTrue(restored.randomTrailers)
        assertTrue(restored.disableCookies)
        assertTrue(restored.hasAmneziaWG3Options())
        assertTrue(restored.hasAmneziaWG31Options())

        val config = restored.buildAmneziaWGConfig()
        assertTrue(config.contains("HeaderProtectionKey = header-key"))
        assertTrue(config.contains("ContentPaddingAddition = 10-20"))
        assertTrue(config.contains("RekeyAfterTime = 100-120"))
        assertTrue(config.contains("PersistentKeepalive = 22-30"))
        assertTrue(config.contains("RandomTrailers = on"))
        assertTrue(config.contains("DisableCookies = on"))
    }

    @Test
    fun legacyVersion2IntegerKeepaliveIsRestoredAsString() {
        val restored = KryoConverters.deserialize(AmneziaWGBean(), legacyVersion2Bean())

        assertEquals("33", restored.peerPersistentKeepalive)
        assertFalse(restored.hasAmneziaWG3Options())
    }

    private fun legacyVersion2Bean(): ByteArray {
        val stream = ByteArrayOutputStream()
        val output = ByteBufferOutput(stream)
        output.writeInt(2)
        output.writeString("example.com")
        output.writeInt(51820)
        output.writeString("10.0.0.2/32")
        output.writeString("private")
        output.writeString("public")
        output.writeString("")
        output.writeInt(33)
        output.writeInt(1280)
        output.writeString("")
        output.writeInt(3)
        output.writeInt(50)
        output.writeInt(1000)
        output.writeInt(0)
        output.writeInt(0)
        output.writeString("1")
        output.writeString("2")
        output.writeInt(0)
        output.writeInt(0)
        output.writeString("3")
        output.writeString("4")
        repeat(5) { output.writeString("") }
        output.writeInt(2)
        repeat(3) { output.writeString("") }
        output.writeBoolean(false)
        repeat(7) { output.writeString("") }
        output.flush()
        output.close()
        return stream.toByteArray()
    }
}
