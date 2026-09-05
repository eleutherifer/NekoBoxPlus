package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.fmt.AbstractBean

internal data class SubscriptionDeduplicationResult(
    val profiles: List<AbstractBean>,
    val duplicateNames: List<String>,
)

internal object SubscriptionProfilePolicy {
    fun assignUniqueNames(profiles: List<AbstractBean>): List<AbstractBean> {
        val byName = LinkedHashMap<String, AbstractBean>(profiles.size)
        for (profile in profiles) {
            val originalName = profile.displayName()
            var candidate = originalName
            var suffix = 0
            while (candidate in byName) {
                suffix++
                candidate = "$originalName ($suffix)"
            }
            if (candidate != originalName) profile.name = candidate
            byName[candidate] = profile
        }
        return byName.values.toList()
    }

    fun filter(
        profiles: List<AbstractBean>,
        mode: Int,
        pattern: String,
    ): List<AbstractBean> {
        if (mode == SubscriptionFilterMode.DISABLED || pattern.isBlank()) return profiles
        val regex = pattern.toRegex()
        return when (mode) {
            SubscriptionFilterMode.INCLUDE -> profiles.filter { regex.containsMatchIn(it.displayName()) }
            SubscriptionFilterMode.EXCLUDE -> profiles.filterNot { regex.containsMatchIn(it.displayName()) }
            else -> profiles
        }
    }

    fun deduplicate(profiles: List<AbstractBean>): SubscriptionDeduplicationResult {
        data class FirstProfile(val index: Int, var name: String)

        val firstByHash = HashMap<String, FirstProfile>(profiles.size)
        val unique = LinkedHashMap<String, AbstractBean>(profiles.size)
        val duplicateNames = ArrayList<String>()
        for (profile in profiles) {
            val hash = profile.hash
            val first = firstByHash[hash]
            if (first == null) {
                firstByHash[hash] = FirstProfile(unique.size, profile.displayName())
                unique[hash] = profile
                continue
            }
            if (first.name.isNotBlank()) {
                val originalName = first.name.replace(" (${first.index})", "")
                if (originalName.isNotBlank()) duplicateNames += "$originalName (${first.index})"
                first.name = ""
            }
            duplicateNames += "${profile.displayName()} (${first.index})"
        }
        return SubscriptionDeduplicationResult(unique.values.toList(), duplicateNames)
    }
}
