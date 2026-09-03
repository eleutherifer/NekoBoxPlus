package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceJobCancellationTest {
    @Test
    fun cooperativeJobCancelsWithinDeadline() = runBlocking {
        val job = launch(Dispatchers.Default) {
            awaitCancellation()
        }

        assertTrue(job.cancelAndJoinWithin(1_000L))
        assertTrue(job.isCompleted)
    }

    @Test
    fun nonCooperativeJobReachesDeadline() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    release.await()
                }
            }
        }
        started.await()

        assertFalse(job.cancelAndJoinWithin(50L))

        release.complete(Unit)
        job.join()
    }
}
