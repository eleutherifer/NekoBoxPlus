package io.nekohasekai.sagernet.fmt

internal data class GlobalOutboundTag(
    val tag: String,
    val reused: Boolean,
)

internal fun resolveGlobalOutboundTag(
    globalOutbounds: MutableMap<Long, String>,
    profileId: Long,
    proposedTag: String,
): GlobalOutboundTag {
    val existingTag = globalOutbounds[profileId]
    if (existingTag != null) return GlobalOutboundTag(existingTag, reused = true)
    globalOutbounds[profileId] = proposedTag
    return GlobalOutboundTag(proposedTag, reused = false)
}

internal class OutboundTagPlanner(initialReservedTags: Collection<String>) {
    private val reservedTags = initialReservedTags.toMutableSet()
    private val globalOutbounds = hashMapOf<Long, String>()

    fun readable(name: String): String {
        var candidate = name
        var suffix = 0
        while (!reservedTags.add(candidate)) {
            suffix++
            candidate = "$name-$suffix"
        }
        return candidate
    }

    fun resolveGlobal(profileId: Long, proposedTag: String): GlobalOutboundTag =
        resolveGlobalOutboundTag(globalOutbounds, profileId, proposedTag)
}
