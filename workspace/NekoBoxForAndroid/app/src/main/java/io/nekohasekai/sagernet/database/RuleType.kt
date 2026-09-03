package io.nekohasekai.sagernet.database

enum class RuleType(val value: String) {
    NORMAL("normal"),
    DNS("dns");

    companion object {
        fun fromValue(value: String?): RuleType {
            return entries.firstOrNull { it.value == value } ?: NORMAL
        }
    }
}
