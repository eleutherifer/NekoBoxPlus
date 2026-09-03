package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderGlobalOutboundTest {

    @Test
    fun `chain detour reuses the tag of an already emitted global outbound`() {
        val globalOutbounds = mutableMapOf(1L to "proxy#1")
        val emittedTags = setOf("proxy#1", "proxy#2")

        val resolvedTag = resolveGlobalOutboundTag(globalOutbounds, 1L, "g-1")
        val chainDetour = resolvedTag.tag

        assertTrue(resolvedTag.reused)
        assertEquals("proxy#1", chainDetour)
        assertTrue(chainDetour in emittedTags)
        assertFalse("g-1" in emittedTags)
    }
}
