package io.nekohasekai.sagernet.ui

internal enum class ProfileSelectionOperation {
    All,
    None,
    Invert,
}

internal fun updateProfileSelection(
    selectedIds: Set<Long>,
    currentGroupIds: Collection<Long>,
    operation: ProfileSelectionOperation,
): Set<Long> = selectedIds.toMutableSet().apply {
    when (operation) {
        ProfileSelectionOperation.All -> addAll(currentGroupIds)
        ProfileSelectionOperation.None -> removeAll(currentGroupIds.toSet())
        ProfileSelectionOperation.Invert -> currentGroupIds.forEach { id ->
            if (!add(id)) remove(id)
        }
    }
}

internal fun addMatchingProfileSelection(
    selectedIds: Set<Long>,
    matchingIds: Collection<Long>,
): Set<Long> = selectedIds.toMutableSet().apply {
    addAll(matchingIds)
}
