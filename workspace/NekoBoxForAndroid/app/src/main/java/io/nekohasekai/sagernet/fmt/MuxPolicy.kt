package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import moe.matsuri.nb4a.SingBoxOptions.MultiplexOptions

internal data class MuxApplication(
    val options: MultiplexOptions,
    val consumesProfileMuxSlot: Boolean,
)

internal fun resolveMuxApplication(
    profile: ProxyEntity,
    profileMuxApplied: Boolean,
    groupProvider: (Long) -> ProxyGroup?,
): MuxApplication? {
    val profileMux = profile.singMux() ?: return null
    val groupMux = groupProvider(profile.groupId)?.singMux()
    if (groupMux != null) {
        return MuxApplication(groupMux, consumesProfileMuxSlot = false)
    }
    if (profileMuxApplied || profileMux.enabled != true) return null
    return MuxApplication(profileMux, consumesProfileMuxSlot = true)
}
