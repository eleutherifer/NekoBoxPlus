package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AppCacheTest {
    @Test
    fun clearRemovesFilesRecursivelyAndTruncatesLog() {
        val cacheDir = Files.createTempDirectory("app-cache-test").toFile()
        try {
            val nestedDir = cacheDir.resolve("nested").apply { mkdirs() }
            val cachedFile = cacheDir.resolve("cached.bin").apply { writeText("cached") }
            val nestedFile = nestedDir.resolve("nested.bin").apply { writeText("cached") }
            val logFile = cacheDir.resolve("neko.log").apply { writeText("log") }

            AppCache.clear(cacheDir)

            assertFalse(cachedFile.exists())
            assertFalse(nestedFile.exists())
            assertTrue(nestedDir.isDirectory)
            assertTrue(logFile.exists())
            assertEquals("", logFile.readText())
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
