package io.nekohasekai.sagernet.fmt.internal

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.byteBuffer
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.proxy.direct.DirectBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class ProxySetFmtTest {

    @Test
    fun insecureProfilesAreFilteredOnlyWhenEnabled() {
        val insecure = profile(1L, SOCKSBean())
        val secure = profile(2L, DirectBean())
        val bean = ProxySetBean()

        assertEquals(listOf(insecure, secure), bean.filterInsecureProfiles(listOf(insecure, secure), false))

        bean.skipInsecureProfiles = true

        assertEquals(listOf(secure), bean.filterInsecureProfiles(listOf(insecure, secure), false))
    }

    @Test
    fun globalAllowInsecureParticipatesInFiltering() {
        val tls = profile(1L, HttpBean().apply { security = "tls" })
        val bean = ProxySetBean().apply { skipInsecureProfiles = true }

        assertEquals(listOf(tls), bean.filterInsecureProfiles(listOf(tls), false))
        assertTrue(bean.filterInsecureProfiles(listOf(tls), true).isEmpty())
    }

    @Test
    fun selectorOmitsFilteredDefaultAndRejectsEmptyMembers() {
        val bean = ProxySetBean().apply {
            mode = ProxySetBean.MODE_SELECTOR
            defaultOutbound = 1L
        }

        val outbound = buildSingBoxOutboundProxySetBean(
            bean,
            linkedMapOf(2L to "secure"),
        ) as SingBoxOptions.Outbound_SelectorOptions

        assertEquals(listOf("secure"), outbound.outbounds)
        assertNull(outbound.default_)
        assertThrows(IllegalArgumentException::class.java) {
            buildSingBoxOutboundProxySetBean(bean, emptyMap())
        }
    }

    @Test
    fun skipInsecureProfilesSurvivesSerialization() {
        val original = ProxySetBean().apply {
            initializeDefaultValues()
            skipInsecureProfiles = true
        }

        val restored = KryoConverters.deserialize(
            ProxySetBean(),
            KryoConverters.serialize(original),
        )

        assertTrue(restored.skipInsecureProfiles)
    }

    @Test
    fun embeddedProfilesSurviveSerializationAsUniversalLinks() {
        val original = ProxySetBean().apply {
            initializeDefaultValues()
            setEmbeddedProfiles(
                listOf(
                    SOCKSBean().apply {
                        serverAddress = "one.example"
                        serverPort = 1080
                        name = "One"
                        initializeDefaultValues()
                    },
                    HttpBean().apply {
                        serverAddress = "two.example"
                        serverPort = 8080
                        name = "Two"
                        initializeDefaultValues()
                    },
                ),
            )
        }

        val restored = KryoConverters.deserialize(ProxySetBean(), KryoConverters.serialize(original))

        assertTrue(restored.hasEmbeddedProfiles())
        assertEquals(listOf("One", "Two"), restored.decodeEmbeddedProfiles().map { it.displayName() })
        assertEquals(listOf("one.example", "two.example"), restored.decodeEmbeddedProfiles().map { it.requireBean().serverAddress })
    }

    @Test
    fun versionTwoProfilesDefaultToNotSkipping() {
        val output = ByteArrayOutputStream()
        val buffer: ByteBufferOutput = output.byteBuffer()
        buffer.writeInt(2)
        buffer.writeBoolean(false)
        buffer.writeString("https://www.gstatic.com/generate_204")
        buffer.writeString("3m")
        buffer.writeString("3m")
        buffer.writeInt(50)
        buffer.writeInt(ProxySetBean.TYPE_LIST)
        buffer.writeInt(0)
        buffer.writeInt(ProxySetBean.MODE_SELECTOR)
        buffer.writeLong(0L)
        buffer.writeInt(2)
        buffer.writeString("")
        buffer.writeString("")
        buffer.writeString("")
        buffer.writeBoolean(false)
        repeat(7) { buffer.writeString("") }
        buffer.close()

        val restored = KryoConverters.deserialize(ProxySetBean(), output.toByteArray())

        assertFalse(restored.skipInsecureProfiles)
        assertEquals("[]", restored.embeddedProfilesJson)
    }

    private fun profile(id: Long, bean: io.nekohasekai.sagernet.fmt.AbstractBean): ProxyEntity {
        bean.initializeDefaultValues()
        return ProxyEntity(id = id).putBean(bean)
    }
}
