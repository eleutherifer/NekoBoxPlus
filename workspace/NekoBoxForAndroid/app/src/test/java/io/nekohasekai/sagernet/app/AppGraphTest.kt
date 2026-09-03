package io.nekohasekai.sagernet.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppGraphTest {
    @Test
    fun installedDependenciesDriveClockAndApplicationScope() = runBlocking {
        val clock = object : AppClock {
            override fun currentTimeMillis() = 1234L
            override fun elapsedRealtimeNanos() = 5678L
        }
        val dispatcher = Dispatchers.Unconfined
        val dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher)

        AppGraph.installForTest(dispatchers, clock).use {
            assertSame(dispatchers, AppGraph.dispatchers)
            assertEquals(1234L, AppGraph.clock.currentTimeMillis())
            assertEquals(5678L, AppGraph.clock.elapsedRealtimeNanos())

            val completed = CompletableDeferred<Unit>()
            AppGraph.applicationScope.launch { completed.complete(Unit) }
            completed.await()
        }
    }
}
