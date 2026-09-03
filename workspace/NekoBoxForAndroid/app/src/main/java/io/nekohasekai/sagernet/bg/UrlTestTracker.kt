package io.nekohasekai.sagernet.bg

import java.util.concurrent.atomic.AtomicInteger

internal class UrlTestTracker {
    private val activeTests = AtomicInteger()

    val isRunning: Boolean
        get() = activeTests.get() > 0

    fun <T> track(block: () -> T): T {
        activeTests.incrementAndGet()
        return try {
            block()
        } finally {
            activeTests.decrementAndGet()
        }
    }
}
