package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProfileDataSource
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.internal.decodeEmbeddedProfiles
import io.nekohasekai.sagernet.fmt.internal.filterInsecureProfiles
import io.nekohasekai.sagernet.fmt.internal.hasEmbeddedProfiles
import java.util.IdentityHashMap

internal class ProfileChainResolver(
    private val profiles: ProfileDataSource,
    private val allowInsecure: Boolean,
    private val frontProxy: ProxyEntity?,
    private val landingProxy: ProxyEntity?,
) {
    private var nextEmbeddedProfileId = -1L
    private val embeddedProxySetMembers = IdentityHashMap<ProxyEntity, List<ProxyEntity>>()

    fun resolveGraph(profile: ProxyEntity, includeGroupProxies: Boolean = true): List<ResolvedProfileHop> =
        resolveHops(profile).toMutableList().apply {
            if (includeGroupProxies) {
                frontProxy?.let { addAll(resolveHops(it)) }
                landingProxy?.let { addAll(0, resolveHops(it)) }
            }
        }

    fun resolve(profile: ProxyEntity): MutableList<ProxyEntity> = resolveGraph(profile).flattenProfiles()

    fun ProxyEntity.resolveInternal(): MutableList<ProxyEntity> = resolveHops(this).flattenProfiles()

    private fun List<ResolvedProfileHop>.flattenProfiles(): MutableList<ProxyEntity> =
        flatMap { hop -> hop.members.flatMap { it.hops.flattenProfiles() } + hop.profile }.toMutableList()

    fun selectedGroupProfileIds(group: ProxyGroup?): Set<Long> {
        if (group == null) return emptySet()
        return buildSet {
            addAll(profiles.getIdsByGroup(group.id))
            frontProxy?.resolveInternal()?.forEach { add(it.id) }
            landingProxy?.resolveInternal()?.forEach { add(it.id) }
        }
    }

    fun validateByeDpiPlacement(profile: ProxyEntity, context: String) {
        validateByeDpiPlacement(profile, context, linkedSetOf())
    }

    fun containsByeDpi(profile: ProxyEntity): Boolean = containsByeDpi(profile, linkedSetOf())

    fun startsWithByeDpi(profile: ProxyEntity): Boolean = startsWithByeDpi(profile, linkedSetOf())

    private fun resolveHops(
        profile: ProxyEntity,
        visiting: MutableList<ProxyEntity> = mutableListOf(),
    ): List<ResolvedProfileHop> {
        if (visiting.any { it.id == profile.id }) {
            throw ProfileReferenceCycleException((visiting + profile).map { it.displayName() })
        }
        visiting.add(profile)
        try {
            return when (val bean = profile.requireBean()) {
                is ChainBean -> {
                    val byId = profiles.getEntities(bean.proxies).associateBy(ProxyEntity::id)
                    bean.proxies.flatMap { id ->
                        val child = byId[id] ?: return@flatMap emptyList()
                        require(child.type != ProxyEntity.TYPE_MASTERDNSVPN) {
                            "MasterDnsVPN is not allowed in proxy chains"
                        }
                        resolveHops(child, visiting)
                    }.asReversed()
                }
                is ProxySetBean -> {
                    val members = eligibleProxySetCandidates(profile, bean).map { candidate ->
                        require(candidate.type != ProxyEntity.TYPE_CHAIN || bean.type != ProxySetBean.TYPE_GROUP) {
                            "Chain is incompatible with group bean"
                        }
                        ResolvedProfileMember(candidate.id, resolveHops(candidate, visiting))
                    }
                    require(members.isNotEmpty()) { "Proxy set has no eligible profiles: ${profile.displayName()}" }
                    listOf(ResolvedProfileHop(profile, members))
                }
                else -> listOf(ResolvedProfileHop(profile))
            }
        } finally {
            visiting.removeAt(visiting.lastIndex)
        }
    }

    fun eligibleProxySetCandidates(profile: ProxyEntity, bean: ProxySetBean): List<ProxyEntity> {
        val candidates = resolveProxySetCandidates(profile, bean)
        val byId = candidates.associateBy(ProxyEntity::id)
        val regex = bean.groupFilterNotRegex.takeIf(String::isNotBlank)?.toRegex()
        val ids = if (!bean.hasEmbeddedProfiles() && bean.type == ProxySetBean.TYPE_LIST) {
            bean.proxies
        } else {
            candidates.map(ProxyEntity::id)
        }
        return bean.filterInsecureProfiles(ids.distinct().mapNotNull(byId::get), allowInsecure).filter {
            // A group normally contains the set collecting it. Explicit self references are errors.
            !(bean.type == ProxySetBean.TYPE_GROUP && it.id == profile.id) &&
                it.type != ProxyEntity.TYPE_MASTERDNSVPN &&
                (regex == null || regex.containsMatchIn(it.displayName())) &&
                !containsByeDpi(it) && !containsMasterDnsVPN(it)
        }
    }

    fun containsMasterDnsVPN(profile: ProxyEntity): Boolean =
        references(profile) { it.type == ProxyEntity.TYPE_MASTERDNSVPN }

    fun referencesProfile(profile: ProxyEntity, id: Long): Boolean = references(profile) { it.id == id }

    private fun references(
        profile: ProxyEntity,
        visiting: MutableSet<Long> = mutableSetOf(),
        predicate: (ProxyEntity) -> Boolean,
    ): Boolean {
        if (predicate(profile)) return true
        if (!visiting.add(profile.id)) return false
        return when (val bean = profile.requireBean()) {
            is ChainBean -> profiles.getEntities(bean.proxies).any { references(it, visiting, predicate) }
            is ProxySetBean -> resolveProxySetCandidates(profile, bean)
                .any { it.id != profile.id && references(it, visiting, predicate) }
            else -> false
        }
    }

    private fun resolveProxySetCandidates(
        profile: ProxyEntity,
        bean: ProxySetBean,
    ): List<ProxyEntity> {
        if (bean.hasEmbeddedProfiles()) {
            return embeddedProxySetMembers.getOrPut(profile) {
                bean.decodeEmbeddedProfiles().onEach { it.id = nextEmbeddedProfileId-- }
            }
        }
        return when (bean.type) {
            ProxySetBean.TYPE_LIST -> profiles.getEntities(bean.proxies)
            ProxySetBean.TYPE_GROUP -> profiles.getByGroup(bean.groupId)
            else -> error("invalid proxy set type ${bean.type}")
        }
    }

    private fun validateByeDpiPlacement(
        profile: ProxyEntity,
        context: String,
        visiting: MutableSet<Long>,
    ) {
        if (profile.isByeDPI()) return
        when (val bean = profile.requireBean()) {
            is ChainBean -> {
                check(visiting.add(profile.id)) { "Profile chain cycle detected at ${profile.displayName()}" }
                try {
                    val byId = profiles.getEntities(bean.proxies).associateBy(ProxyEntity::id)
                    var seenByeDpi = false
                    bean.proxies.forEachIndexed { index, profileId ->
                        val child = byId[profileId] ?: return@forEachIndexed
                        if (containsByeDpi(child)) {
                            if (index != 0 || !startsWithByeDpi(child) || seenByeDpi) {
                                error("ByeDPI must be the first profile in $context")
                            }
                            seenByeDpi = true
                        }
                        validateByeDpiPlacement(child, context, visiting)
                    }
                } finally {
                    visiting.remove(profile.id)
                }
            }

            is ProxySetBean -> Unit
        }
    }

    private fun containsByeDpi(profile: ProxyEntity, visiting: MutableSet<Long>): Boolean =
        references(profile, visiting, ProxyEntity::isByeDPI)

    private fun startsWithByeDpi(profile: ProxyEntity, visiting: MutableSet<Long>): Boolean {
        if (profile.isByeDPI()) return true
        val bean = profile.requireBean() as? ChainBean ?: return false
        if (!visiting.add(profile.id)) return false
        return try {
            val first = bean.proxies.firstOrNull()?.let(profiles::getById) ?: return false
            startsWithByeDpi(first, visiting)
        } finally {
            visiting.remove(profile.id)
        }
    }
}
