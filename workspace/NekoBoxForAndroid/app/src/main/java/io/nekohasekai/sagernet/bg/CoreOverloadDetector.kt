package io.nekohasekai.sagernet.bg

internal class CoreOverloadDetector(
    private val windowMillis: Long = WINDOW_MILLIS,
    private val thresholdPercent: Double = THRESHOLD_PERCENT,
) {
    private data class Sample(val elapsedMillis: Long, val cpuMillis: Long)

    private val samples = ArrayDeque<Sample>()
    private var triggered = false

    fun addSample(elapsedMillis: Long, cpuMillis: Long): Double? {
        val previous = samples.lastOrNull()
        if (previous != null &&
            (elapsedMillis <= previous.elapsedMillis || cpuMillis < previous.cpuMillis)
        ) {
            reset()
        }

        samples.addLast(Sample(elapsedMillis, cpuMillis))
        while (samples.size > 2 &&
            elapsedMillis - samples.elementAt(1).elapsedMillis >= windowMillis
        ) {
            samples.removeFirst()
        }

        val first = samples.first()
        val elapsedDelta = elapsedMillis - first.elapsedMillis
        if (triggered || elapsedDelta < windowMillis) return null

        val cpuPercent = (cpuMillis - first.cpuMillis).toDouble() * 100.0 / elapsedDelta
        if (cpuPercent > thresholdPercent) {
            triggered = true
            return cpuPercent
        }
        return null
    }

    fun reset() {
        samples.clear()
        triggered = false
    }

    companion object {
        const val WINDOW_MILLIS = 60_000L
        const val THRESHOLD_PERCENT = 60.0
    }
}
