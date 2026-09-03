package io.nekohasekai.sagernet.ui

internal class DpadDoublePressTracker(
    private val timeoutMillis: Long,
) {
    private var lastKeyCode = 0
    private var lastEventTime = Long.MIN_VALUE

    fun record(keyCode: Int, eventTime: Long): Boolean {
        val elapsed = eventTime - lastEventTime
        val doubled = keyCode == lastKeyCode && elapsed in 1..timeoutMillis
        lastKeyCode = if (doubled) 0 else keyCode
        lastEventTime = if (doubled) Long.MIN_VALUE else eventTime
        return doubled
    }

    fun reset() {
        lastKeyCode = 0
        lastEventTime = Long.MIN_VALUE
    }
}
