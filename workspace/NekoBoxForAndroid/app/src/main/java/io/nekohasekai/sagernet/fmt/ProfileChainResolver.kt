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

    fun resolve(profile: ProxyEntity): MutableList<ProxyEntity> = resolveInternal(profile).apply {
        frontProxy?.let { addAll(it.resolveInternal()) }
        landingProxy?.let { addAll(0, it.resolveInternal()) }
    }

    fun ProxyEntity.resolveInternal(): MutableList<ProxyEntity> = resolveInternal(this)

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

    private fun resolveInternal(
        profile: ProxyEntity,
        visiting: MutableSet<Long> = linkedSetOf(),
    ): MutableList<ProxyEntity> {
        val bean = profile.requireBean()
        if (bean is ChainBean) {
            check(visiting.add(profile.id)) { "Profile chain cycle detected at ${profile.displayName()}" }
            try {
                val byId = profiles.getEntities(bean.proxies).associateBy(ProxyEntity::id)
                val result = ArrayList<ProxyEntity>()
                for (profileId in bean.proxies) {
                    val child = byId[profileId] ?: continue
                    require(child.type != ProxyEntity.TYPE_MASTERDNSVPN) {
                        "MasterDnsVPN is not allowed in proxy chains"
                    }
                    result += resolveInternal(child, visiting)
                }
                return result.asReversed()
            } finally {
                visiting.remove(profile.id)
            }
        }
        if (bean is ProxySetBean) {
            val candidates = resolveProxySetCandidates(profile, bean)
            val byId = candidates.associateBy(ProxyEntity::id)
            val regex = bean.groupFilterNotRegex.takeIf(String::isNotBlank)?.toRegex()
            val ids = when {
                bean.hasEmbeddedProfiles() -> candidates.map(ProxyEntity::id)
                bean.type == ProxySetBean.TYPE_LIST -> bean.proxies
                else -> candidates.map(ProxyEntity::id)
            }
            val result = ArrayList<ProxyEntity>()
            val filtered = ids.mapNotNull(byId::get).let {
                bean.filterInsecureProfiles(it, allowInsecure)
            }
            for (candidate in filtered) {
                if (candidate.id == profile.id || candidate.type == ProxyEntity.TYPE_MASTERDNSVPN) continue
                if (regex != null && !regex.containsMatchIn(candidate.displayName())) continue
                if (containsByeDpi(candidate)) continue
                when (candidate.type) {
                    ProxyEntity.TYPE_PROXY_SET -> error("Nested proxy set are not supported")
                    ProxyEntity.TYPE_CHAIN -> if (bean.type == ProxySetBean.TYPE_GROUP) {
                        error("Chain is incompatible with group bean")
                    }
                }
                result += candidate
            }
            result += profile
            return result
        }
        return mutableListOf(profile)
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

    private fun containsByeDpi(profile: ProxyEntity, visiting: MutableSet<Long>): Boolean {
        if (profile.isByeDPI()) return true
        check(visiting.add(profile.id)) { "Profile chain cycle detected at ${profile.displayName()}" }
        return try {
            when (val bean = profile.requireBean()) {
                is ChainBean -> {
                    val byId = profiles.getEntities(bean.proxies).associateBy(ProxyEntity::id)
                    bean.proxies.any { byId[it]?.let { child -> containsByeDpi(child, visiting) } == true }
                }

                is ProxySetBean -> resolveProxySetCandidates(profile, bean)
                    .any { it.id != profile.id && containsByeDpi(it, visiting) }

                else -> false
            }
        } finally {
            visiting.remove(profile.id)
        }
    }

    private fun startsWithByeDpi(profile: ProxyEntity, visiting: MutableSet<Long>): Boolean {
        if (profile.isByeDPI()) return true
        val bean = profile.requireBean() as? ChainBean ?: return false
        check(visiting.add(profile.id)) { "Profile chain cycle detected at ${profile.displayName()}" }
        return try {
            val first = bean.proxies.firstOrNull()?.let(profiles::getById) ?: return false
            startsWithByeDpi(first, visiting)
        } finally {
            visiting.remove(profile.id)
        }
    }
}
