package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlTestTrackerTest {
    @Test
    fun tracksNormalCompletion() {
        val tracker = UrlTestTracker()

        tracker.track {
            assertTrue(tracker.isRunning)
        }

        assertFalse(tracker.isRunning)
    }

    @Test
    fun clearsStateAfterFailure() {
        val tracker = UrlTestTracker()

        runCatching {
            tracker.track {
                assertTrue(tracker.isRunning)
                error("test failure")
            }
        }

        assertFalse(tracker.isRunning)
    }

    @Test
    fun remainsActiveUntilOverlappingTestsFinish() {
        val tracker = UrlTestTracker()

        tracker.track {
            tracker.track {
                assertTrue(tracker.isRunning)
            }
            assertTrue(tracker.isRunning)
        }

        assertFalse(tracker.isRunning)
    }
}
