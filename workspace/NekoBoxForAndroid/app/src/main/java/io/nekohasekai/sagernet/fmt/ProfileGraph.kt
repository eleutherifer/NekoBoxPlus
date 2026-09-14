package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.internal.buildSingBoxOutboundProxySetBean

/** Hops are ordered from the application's outbound towards the physical network. */
internal class ResolvedProfileHop(
    val profile: ProxyEntity,
    val members: List<ResolvedProfileMember> = emptyList(),
)

internal class ResolvedProfileMember(val profileId: Long, val hops: List<ResolvedProfileHop>)

internal class PlannedProfileOutbound(
    val profile: ProxyEntity,
    val tag: String,
    val detour: String?,
    val memberTags: LinkedHashMap<Long, String> = linkedMapOf(),
) {
    fun buildProxySet() = buildSingBoxOutboundProxySetBean(
        profile.requireBean() as ProxySetBean,
        memberTags,
    ).apply { tag = this@PlannedProfileOutbound.tag }
}

internal class ProfileOutboundGraph(
    val entryTag: String,
    val outbounds: List<PlannedProfileOutbound>,
)

/** Each occurrence owns its bean and tag: a shared server can have different detours. */
internal fun planProfileOutbounds(
    hops: List<ResolvedProfileHop>,
    tags: OutboundTagPlanner,
): ProfileOutboundGraph {
    val outbounds = mutableListOf<PlannedProfileOutbound>()
    fun emitChain(chain: List<ResolvedProfileHop>, tail: String?): String {
        require(chain.isNotEmpty()) { "Profile chain has no eligible profiles" }
        val chainTags = chain.map { tags.readable(it.profile.displayName()) }
        chain.forEachIndexed { index, hop ->
            val next = chainTags.getOrNull(index + 1) ?: tail
            val isSet = hop.profile.requireBean() is ProxySetBean
            val planned = PlannedProfileOutbound(
                hop.profile.copy().putBean(hop.profile.requireBean().clone()),
                chainTags[index],
                if (isSet) null else next,
            )
            outbounds.add(planned)
            hop.members.forEach { member ->
                planned.memberTags[member.profileId] = emitChain(member.hops, next)
            }
        }
        return chainTags.first()
    }
    return ProfileOutboundGraph(emitChain(hops, null), outbounds)
}
