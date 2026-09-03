package io.nekohasekai.sagernet.bg

private const val BYTES_PER_MEBIBYTE = 1024L * 1024L
private const val TRIM_MEMORY_RUNNING_LOW_COMPAT = 10
private const val TRIM_MEMORY_UI_HIDDEN = 20

internal fun shouldSweepLibcoreMemory(level: Int): Boolean =
    level in TRIM_MEMORY_RUNNING_LOW_COMPAT until TRIM_MEMORY_UI_HIDDEN

internal fun calculateLibcoreMemoryLimit(configuredMebibytes: Long): Long =
    configuredMebibytes
        .coerceAtLeast(0L)
        .coerceAtMost(Long.MAX_VALUE / BYTES_PER_MEBIBYTE) * BYTES_PER_MEBIBYTE
