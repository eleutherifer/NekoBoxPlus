package moe.matsuri.nb4a.proxy.direct

import io.nekohasekai.sagernet.fmt.KryoConverters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBeanTest {

    private fun roundTrip(bean: DirectBean): DirectBean {
        val copy = DirectBean()
        KryoConverters.deserialize(copy, KryoConverters.serialize(bean))
        return copy.apply { initializeDefaultValues() }
    }

    @Test
    fun displayNameFallsBackToDirect() {
        val bean = DirectBean().apply { initializeDefaultValues() }
        assertEquals("Direct", bean.displayName())

        bean.name = "My Direct"
        assertEquals("My Direct", bean.displayName())
    }

    @Test
    fun clonePreservesNameAndCustomJson() {
        val bean = DirectBean().apply {
            name = "AdBlock only"
            customOutboundJson = """{"tag":"x","type":"direct"}"""
            customConfigJson = """{"experimental":{"adblock":{}}}"""
            initializeDefaultValues()
        }

        val copy = bean.clone()
        assertEquals(bean.name, copy.name)
        assertEquals(bean.customOutboundJson, copy.customOutboundJson)
        assertEquals(bean.customConfigJson, copy.customConfigJson)
    }

    @Test
    fun roundTripPreservesFields() {
        val bean = DirectBean().apply {
            name = "Plain"
            customConfigJson = """{"a":1}"""
            initializeDefaultValues()
        }

        val restored = roundTrip(bean)
        assertEquals(bean.name, restored.name)
        assertEquals(bean.customConfigJson, restored.customConfigJson)
    }

    @Test
    fun hashIsStableAndPrefixed() {
        val bean = DirectBean().apply { initializeDefaultValues() }
        val hash = bean.hash

        assertTrue("hash must be prefixed with direct:", hash.startsWith("direct:"))
        assertEquals(hash, roundTrip(bean).hash)
    }

    @Test
    fun differentlyNamedDirectOutboundsDedupe() {
        val first = DirectBean().apply {
            name = "First"
            initializeDefaultValues()
        }
        val second = DirectBean().apply {
            name = "Second"
            initializeDefaultValues()
        }

        // Name is intentionally excluded from the deduplication hash, so two plain
        // Direct outbounds are treated as duplicates by the "Delete duplicates" action.
        assertEquals(first.hash, second.hash)
        assertNotEquals(first.name, second.name)
    }
}
