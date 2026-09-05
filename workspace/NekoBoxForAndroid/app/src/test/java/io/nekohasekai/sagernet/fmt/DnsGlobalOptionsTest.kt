package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.DNSOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DnsGlobalOptionsTest {

    @Test
    fun enabledGlobalOptionsAreSerialized() {
        val options = DNSOptions().apply {
            disable_cache = true
            disable_expire = true
            cache_capacity = 4096
            reverse_mapping = true
        }

        assertEquals(true, options.asMap()["disable_cache"])
        assertEquals(true, options.asMap()["disable_expire"])
        assertEquals(4096L, options.asMap()["cache_capacity"])
        assertEquals(true, options.asMap()["reverse_mapping"])
    }

    @Test
    fun unsetGlobalOptionsAreOmitted() {
        val serialized = DNSOptions().asMap()

        assertFalse(serialized.containsKey("disable_cache"))
        assertFalse(serialized.containsKey("disable_expire"))
        assertFalse(serialized.containsKey("cache_capacity"))
        assertFalse(serialized.containsKey("reverse_mapping"))
    }
}
