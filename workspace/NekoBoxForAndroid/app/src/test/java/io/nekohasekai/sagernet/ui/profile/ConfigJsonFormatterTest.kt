package io.nekohasekai.sagernet.ui.profile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigJsonFormatterTest {

    @Test
    fun `blank input stays blank`() {
        assertEquals("", ConfigJsonFormatter.format(" \n\t"))
    }

    @Test
    fun `valid object is pretty printed without changing values`() {
        val formatted = ConfigJsonFormatter.format("""{"name":"test","enabled":true}""")

        assertTrue(formatted.contains('\n'))
        assertEquals("test", JSONObject(formatted).getString("name"))
        assertTrue(JSONObject(formatted).getBoolean("enabled"))
    }

    @Test(expected = Exception::class)
    fun `invalid object is rejected`() {
        ConfigJsonFormatter.format("{")
    }
}
