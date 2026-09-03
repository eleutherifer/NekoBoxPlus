package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class CoreOverloadDetectorTest {
    @Test
    fun doesNotTriggerBeforeFullWindow() {
        val detector = CoreOverloadDetector()

        assertNull(detector.addSample(0L, 0L))
        assertNull(detector.addSample(59_999L, 36_000L))
    }

    @Test
    fun doesNotTriggerAtThreshold() {
        val detector = CoreOverloadDetector()

        detector.addSample(0L, 0L)
        assertNull(detector.addSample(60_000L, 36_000L))
    }

    @Test
    fun triggersAboveThresholdForFullWindow() {
        val detector = CoreOverloadDetector()

        detector.addSample(0L, 0L)
        val cpuPercent = detector.addSample(60_000L, 36_001L)

        assertNotNull(cpuPercent)
        assertEquals(60.0016, cpuPercent!!, 0.001)
    }

    @Test
    fun triggersOnlyOnce() {
        val detector = CoreOverloadDetector()

        detector.addSample(0L, 0L)
        assertNotNull(detector.addSample(60_000L, 36_001L))
        assertNull(detector.addSample(120_000L, 72_002L))
    }

    @Test
    fun usesRollingWindow() {
        val detector = CoreOverloadDetector()

        detector.addSample(0L, 0L)
        detector.addSample(30_000L, 0L)
        assertNull(detector.addSample(60_000L, 18_000L))
        assertNotNull(detector.addSample(90_000L, 36_001L))
    }

    @Test
    fun resetRequiresAnotherFullWindow() {
        val detector = CoreOverloadDetector()

        detector.addSample(0L, 0L)
        detector.reset()
        assertNull(detector.addSample(60_000L, 36_001L))
        assertNotNull(detector.addSample(120_000L, 72_002L))
    }
}
