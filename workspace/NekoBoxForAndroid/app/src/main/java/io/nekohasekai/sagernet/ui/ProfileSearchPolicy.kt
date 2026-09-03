package io.nekohasekai.sagernet.ui

internal object ProfileSearchPolicy {
    fun <T> candidates(
        previousQuery: String,
        query: String,
        visibleItems: List<T>,
        allItems: List<T>,
    ): List<T> = if (query.startsWith(previousQuery)) visibleItems else allItems
}
