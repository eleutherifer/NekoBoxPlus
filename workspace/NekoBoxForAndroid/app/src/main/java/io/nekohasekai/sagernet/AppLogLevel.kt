package io.nekohasekai.sagernet

enum class AppLogLevel(
    val preferenceValue: Int,
    val singBoxName: String,
    val outputEnabled: Boolean = true,
    private val priority: Int,
) {
    NONE(0, "panic", false, 0),
    WARNING(1, "warn", priority = 3),
    INFO(2, "info", priority = 4),
    DEBUG(3, "debug", priority = 5),
    TRACE(4, "trace", priority = 6),
    PANIC(5, "panic", priority = 0),
    FATAL(6, "fatal", priority = 1),
    ERROR(7, "error", priority = 2),
    ;

    fun allows(messageLevel: AppLogLevel): Boolean {
        return outputEnabled && messageLevel.priority <= priority
    }

    companion object {
        fun fromPreferenceValue(value: Int): AppLogLevel {
            return entries.firstOrNull { it.preferenceValue == value } ?: INFO
        }
    }
}

object AppLogLevelController {
    @Volatile
    private var selected = AppLogLevel.NONE

    fun initialize(preferenceValue: Int): AppLogLevel {
        return AppLogLevel.fromPreferenceValue(preferenceValue).also(::set)
    }

    fun set(level: AppLogLevel) {
        selected = level
    }

    fun allows(messageLevel: AppLogLevel): Boolean = selected.allows(messageLevel)
}
