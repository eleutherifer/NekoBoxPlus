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
        first.tlsXrayCertificateSha256 = "pin"
        val second = hysteriaBean("Second name")
        second.tcpFastOpen = true
        second.tcpMultiPath = true
        second.udpFragment = false
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
        assertEquals("pin", restored.tlsXrayCertificateSha256)
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
}
