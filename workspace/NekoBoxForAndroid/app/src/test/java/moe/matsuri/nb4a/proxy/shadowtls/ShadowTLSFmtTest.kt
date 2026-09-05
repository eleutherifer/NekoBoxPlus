package moe.matsuri.nb4a.proxy.shadowtls

import io.nekohasekai.sagernet.fmt.KryoConverters
import org.junit.Assert.assertEquals
import org.junit.Test

class ShadowTLSFmtTest {

    @Test
    fun proxyProtocolVersionSurvivesProfileSerialization() {
        val bean = ShadowTLSBean().apply {
            initializeDefaultValues()
            proxyProtocol = 2
        }
        val restored = KryoConverters.deserialize(
            ShadowTLSBean(),
            KryoConverters.serialize(bean),
        )

        assertEquals(2, restored.proxyProtocol)
    }
}
