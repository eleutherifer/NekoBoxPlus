package io.nekohasekai.sagernet.backup.throne

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ThrBackupTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun containerMatchesQtLayoutForEverySelection() {
        val bytes = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val database = temp.newFile().apply { writeBytes(bytes) }
        for (flags in 1..7) {
            val selection = ThrSelection(flags and 1 != 0, flags and 2 != 0, flags and 4 != 0)
            val output = ByteArrayOutputStream()
            ThrBackup.write(output, database, selection, "test-version")
            val buffer = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("THRN", String(ByteArray(4).also(buffer::get), Charsets.US_ASCII))
            assertEquals(2, buffer.int)
            fun qstring(): String = String(ByteArray(buffer.int).also(buffer::get), Charsets.UTF_16LE)
            val metadata = JSONObject(qstring())
            assertEquals("android", metadata.getString("platform"))
            assertEquals("test-version", metadata.getString("app_version"))
            val parts = metadata.getJSONObject("parts")
            assertEquals(selection.profiles, parts.getBoolean("profiles"))
            assertEquals(selection.routes, parts.getBoolean("routes"))
            assertEquals(selection.settings, parts.getBoolean("settings"))
            assertFalse(parts.getBoolean("otp"))
            assertFalse(parts.getBoolean("icons"))
            assertEquals(1, buffer.int)
            assertEquals("database", qstring())
            assertEquals(bytes.size, buffer.int)
            assertArrayEquals(bytes, ByteArray(buffer.remaining()).also(buffer::get))
        }
    }
}
