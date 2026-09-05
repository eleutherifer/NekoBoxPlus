package io.nekohasekai.sagernet.utils

import java.util.Locale

object SubscriptionTrafficUnit {
    const val BINARY = 0
    const val DECIMAL = 1
}

object SubscriptionTrafficFormatter {

    fun format(
        bytes: Long,
        unit: Int,
        locale: Locale = Locale.getDefault(),
    ): String {
        val decimal = unit == SubscriptionTrafficUnit.DECIMAL
        val base = if (decimal) 1000L else 1024L
        val labels = if (decimal) {
            arrayOf("Bytes", "KB", "MB", "GB")
        } else {
            arrayOf("Bytes", "KiB", "MiB", "GiB")
        }
        if (bytes < base) return "$bytes ${labels[0]}"

        var divisor = base.toDouble()
        var unitIndex = 1
        while (unitIndex < labels.lastIndex && bytes >= divisor * base) {
            divisor *= base
            unitIndex++
        }
        return String.format(locale, "%.2f %s", bytes / divisor, labels[unitIndex])
    }
}
