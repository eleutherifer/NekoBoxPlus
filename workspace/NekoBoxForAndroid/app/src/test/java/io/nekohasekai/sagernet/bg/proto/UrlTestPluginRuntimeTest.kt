package io.nekohasekai.sagernet.bg.proto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UrlTestPluginRuntimeTest {
    @Test
    fun `cleanup continues after process shutdown failure and rethrows it`() = runBlocking {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("shutdown failed")

        val thrown = runCatching {
            cleanupUrlTestRuntime(
                closeProcesses = { calls += "close"; throw failure },
                deleteTrackedFiles = { calls += "tracked" },
                deleteOrphanedFiles = { calls += "orphans" },
                clearPluginConfigs = { calls += "configs" },
            )
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(listOf("close", "tracked", "orphans", "configs"), calls)
    }
}
