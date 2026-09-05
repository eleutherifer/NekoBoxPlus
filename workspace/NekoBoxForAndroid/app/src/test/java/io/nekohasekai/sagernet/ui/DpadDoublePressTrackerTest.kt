package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpadDoublePressTrackerTest {
    @Test
    fun recognizesSameKeyInsideTimeoutAndConsumesPair() {
        val tracker = DpadDoublePressTracker(300)

        assertFalse(tracker.record(21, 1_000))
        assertTrue(tracker.record(21, 1_250))
        assertFalse(tracker.record(21, 1_300))
    }

    @Test
    fun rejectsDifferentOrLateKeys() {
        val tracker = DpadDoublePressTracker(300)

        assertFalse(tracker.record(21, 1_000))
        assertFalse(tracker.record(19, 1_100))
        assertFalse(tracker.record(19, 1_500))
    }

    @Test
    fun resetDiscardsPendingPress() {
        val tracker = DpadDoublePressTracker(300)

        assertFalse(tracker.record(21, 1_000))
        tracker.reset()
        assertFalse(tracker.record(21, 1_100))
    }
}
