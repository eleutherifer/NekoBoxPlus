package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.byteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class AbstractBeanSerializationTest {

    @Test
    fun equalityAndHashIgnoreNameWithoutChangingPersistedSerialization() {
        val first = hysteriaBean("First name")
        val second = hysteriaBean("Second name")

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
