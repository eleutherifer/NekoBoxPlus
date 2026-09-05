package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.byteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class AbstractBeanSerializationTest {

    @Test
    fun equalityAndHashIgnoreNameWithoutChangingPersistedSerialization() {
        val first = hysteriaBean("First name")
        first.tcpFastOpen = true
        first.tcpMultiPath = true
        first.udpFragment = false
        first.tlsHandshakeTimeout = "3s"
        first.quicIdleTimeout = "30s"
        first.quicKeepAlivePeriod = "10s"
        first.quicStreamReceiveWindow = 1_024L
        first.quicConnectionReceiveWindow = 2_048L
        first.quicMaxConcurrentStreams = 16
        first.quicInitialPacketSize = 1_350
        first.quicDisablePathMtuDiscovery = true
        first.tlsXrayCertificateSha256 = "pin"
        val second = hysteriaBean("Second name")
        second.tcpFastOpen = true
        second.tcpMultiPath = true
        second.udpFragment = false
        second.tlsHandshakeTimeout = "3s"
        second.quicIdleTimeout = "30s"
        second.quicKeepAlivePeriod = "10s"
        second.quicStreamReceiveWindow = 1_024L
        second.quicConnectionReceiveWindow = 2_048L
        second.quicMaxConcurrentStreams = 16
        second.quicInitialPacketSize = 1_350
        second.quicDisablePathMtuDiscovery = true
        second.tlsXrayCertificateSha256 = "pin"

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(
            KryoConverters.serialize(first).toList(),
            KryoConverters.serialize(second).toList(),
        )

        val restored = KryoConverters.deserialize(
            HysteriaBean(),
            KryoConverters.serialize(first),
        )
        assertEquals("First name", restored.name)
        assertTrue(restored.tcpFastOpen)
        assertTrue(restored.tcpMultiPath)
        assertEquals(false, restored.udpFragment)
        assertEquals("3s", restored.tlsHandshakeTimeout)
        assertEquals("30s", restored.quicIdleTimeout)
        assertEquals("10s", restored.quicKeepAlivePeriod)
        assertEquals(1_024L, restored.quicStreamReceiveWindow)
        assertEquals(2_048L, restored.quicConnectionReceiveWindow)
        assertEquals(16, restored.quicMaxConcurrentStreams)
        assertEquals(1_350, restored.quicInitialPacketSize)
        assertTrue(restored.quicDisablePathMtuDiscovery)
        assertEquals("pin", restored.tlsXrayCertificateSha256)
    }

    @Test
    fun restoresPlusVersion3Tail() {
        val bean = hysteriaBean("Plus profile")
        val bytes = legacyVersion3(bean) { buffer ->
            buffer.writeBoolean(true)
            buffer.writeBoolean(false)
            buffer.writeString("true")
        }

        val restored = KryoConverters.deserialize(HysteriaBean(), bytes)

        assertTrue(restored.tcpFastOpen)
        assertFalse(restored.tcpMultiPath)
        assertEquals(true, restored.udpFragment)
        assertEquals("", restored.tlsHandshakeTimeout)
    }

    @Test
    fun restoresPlus114Version3Tail() {
        val bean = hysteriaBean("Plus 1.14 profile")
        val bytes = legacyVersion3(bean) { buffer ->
            buffer.writeString("4s")
            buffer.writeString("40s")
            buffer.writeString("12s")
            buffer.writeLong(4_096L)
            buffer.writeLong(8_192L)
            buffer.writeInt(24)
            buffer.writeInt(1_400)
            buffer.writeBoolean(true)
        }

        val restored = KryoConverters.deserialize(HysteriaBean(), bytes)

        assertEquals("4s", restored.tlsHandshakeTimeout)
        assertEquals("40s", restored.quicIdleTimeout)
        assertEquals("12s", restored.quicKeepAlivePeriod)
        assertEquals(4_096L, restored.quicStreamReceiveWindow)
        assertEquals(8_192L, restored.quicConnectionReceiveWindow)
        assertEquals(24, restored.quicMaxConcurrentStreams)
        assertEquals(1_400, restored.quicInitialPacketSize)
        assertTrue(restored.quicDisablePathMtuDiscovery)
        assertFalse(restored.tcpFastOpen)
        assertFalse(restored.tcpMultiPath)
        assertNull(restored.udpFragment)
    }

    @Test
    fun malformedLegacyTailDoesNotCrashDeserialization() {
        val bean = hysteriaBean("Profile name")
        val output = ByteArrayOutputStream()
        val buffer: ByteBufferOutput = output.byteBuffer()
        bean.serialize(buffer)
        buffer.writeInt(2)
        // Older concurrent equality/hash calculation could omit the name here.
        buffer.writeString(bean.customOutboundJson)
        buffer.writeString(bean.customConfigJson)
        buffer.writeBoolean(bean.disableTcpKeepAlive)
        buffer.writeString(bean.tcpKeepAlive)
        buffer.writeString(bean.tcpKeepAliveInterval)
        buffer.writeString(bean.tlsCurvePreferences)
        buffer.writeString(bean.tlsCertificatePublicKeySha256)
        buffer.writeString(bean.tlsClientCertificate)
        buffer.writeString(bean.tlsClientKey)
        buffer.writeString(bean.echQueryServerName)
        buffer.close()

        val restored = KryoConverters.deserialize(HysteriaBean(), output.toByteArray())

        assertEquals("example.com", restored.serverAddress)
        assertEquals("443", restored.serverPorts)
        assertFalse(restored.tcpFastOpen)
        assertFalse(restored.tcpMultiPath)
        assertNull(restored.udpFragment)
    }

    @Test
    fun versionThreeProfileDefaultsXrayCertificatePins() {
        val bean = hysteriaBean("Profile name")
        val output = ByteArrayOutputStream()
        val buffer: ByteBufferOutput = output.byteBuffer()
        bean.serialize(buffer)
        buffer.writeInt(3)
        buffer.writeString(bean.name)
        buffer.writeString(bean.customOutboundJson)
        buffer.writeString(bean.customConfigJson)
        buffer.writeBoolean(bean.disableTcpKeepAlive)
        buffer.writeString(bean.tcpKeepAlive)
        buffer.writeString(bean.tcpKeepAliveInterval)
        buffer.writeString(bean.tlsCurvePreferences)
        buffer.writeString(bean.tlsCertificatePublicKeySha256)
        buffer.writeString(bean.tlsClientCertificate)
        buffer.writeString(bean.tlsClientKey)
        buffer.writeString(bean.echQueryServerName)
        buffer.writeBoolean(bean.tcpFastOpen)
        buffer.writeBoolean(bean.tcpMultiPath)
        buffer.writeString("")
        buffer.close()

        val restored = KryoConverters.deserialize(HysteriaBean(), output.toByteArray())

        assertEquals("", restored.tlsXrayCertificateSha256)
    }

    private fun hysteriaBean(profileName: String) = HysteriaBean().apply {
        initializeDefaultValues()
        serverAddress = "example.com"
        serverPort = 443
        serverPorts = "443"
        name = profileName
        authPayload = "secret"
    }

    private fun legacyVersion3(
        bean: HysteriaBean,
        writeVersion3Fields: (ByteBufferOutput) -> Unit,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer: ByteBufferOutput = output.byteBuffer()
        bean.serialize(buffer)
        buffer.writeInt(3)
        buffer.writeString(bean.name)
        buffer.writeString(bean.customOutboundJson)
        buffer.writeString(bean.customConfigJson)
        buffer.writeBoolean(bean.disableTcpKeepAlive)
        buffer.writeString(bean.tcpKeepAlive)
        buffer.writeString(bean.tcpKeepAliveInterval)
        buffer.writeString(bean.tlsCurvePreferences)
        buffer.writeString(bean.tlsCertificatePublicKeySha256)
        buffer.writeString(bean.tlsClientCertificate)
        buffer.writeString(bean.tlsClientKey)
        buffer.writeString(bean.echQueryServerName)
        writeVersion3Fields(buffer)
        buffer.close()
        return output.toByteArray()
    }
}
