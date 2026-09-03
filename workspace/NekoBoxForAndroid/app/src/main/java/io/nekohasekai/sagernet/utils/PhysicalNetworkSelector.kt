package io.nekohasekai.sagernet.utils

/**
 * Pure, framework-free selection of the physical default network from a set of
 * connectivity candidates.
 *
 * A candidate is *usable* as the VPN underlay only when it carries
 * [Candidate.hasInternet] and [Candidate.notRestricted] and [Candidate.notVpn]
 * and is neither a VPN transport ([Candidate.isVpnTransport]) nor a TUN-backed
 * interface ([Candidate.isTunInterface]). Candidates that fail any of those are
 * tracked but never selected, so the previously selected physical network is
 * retained when our own VPN (a self-VPN callback) appears, and null is published
 * only once the last usable physical candidate disappears.
 *
 * A usable candidate is promoted only when Android announces it through
 * `onAvailable`. Later capability refreshes update metadata without changing
 * the selected network. Older candidates remain available as fallbacks if the
 * selected network is later lost or becomes unusable.
 */
class PhysicalNetworkSelector<K : Any> {
    /** Metadata describing one connectivity candidate. */
    data class Candidate<K : Any>(
        val key: K,
        val hasInternet: Boolean,
        val notRestricted: Boolean,
        val notVpn: Boolean,
        val isVpnTransport: Boolean,
        val isTunInterface: Boolean,
    ) {
        val isUsable: Boolean
            get() =
                hasInternet &&
                    notRestricted &&
                    notVpn &&
                    !isVpnTransport &&
                    !isTunInterface
    }

    private val candidates = LinkedHashMap<K, Candidate<K>>()

    /** The currently selected usable physical candidate, or null if none. */
    var selected: Candidate<K>? = null
        private set

    /** Whether [candidate] may be used as the VPN underlay. */
    fun isUsable(candidate: Candidate<K>): Boolean = candidate.isUsable

    /**
     * Insert or replace [candidate]. Returns true when the selected physical
     * candidate changed (including to/from null).
     */
    fun available(candidate: Candidate<K>): Boolean {
        candidates[candidate.key] = candidate
        val previous = selected?.key
        selected =
            when {
                candidate.isUsable -> candidate
                selected?.key == candidate.key -> firstUsableCandidate()
                else -> selected
            }
        return previous != selected?.key
    }

    /**
     * Refresh [candidate] without treating the callback as a default-network
     * change. A usable candidate may be selected here only when no selection
     * exists, which handles platforms where capabilities become readable after
     * `onAvailable`.
     */
    fun capabilitiesChanged(candidate: Candidate<K>): Boolean {
        candidates[candidate.key] = candidate
        val previous = selected?.key
        selected =
            when {
                selected?.key == candidate.key && candidate.isUsable -> candidate
                selected?.key == candidate.key -> firstUsableCandidate()
                selected == null && candidate.isUsable -> candidate
                else -> selected
            }
        return previous != selected?.key
    }

    /**
     * Remove [key]. Returns true when the selected physical candidate changed.
     */
    fun remove(key: K): Boolean {
        if (candidates.remove(key) == null) return false
        return recompute()
    }

    fun clear() {
        candidates.clear()
        selected = null
    }

    fun all(): List<Candidate<K>> = candidates.values.toList()

    private fun recompute(): Boolean {
        val previous = selected?.key
        selected = firstUsableCandidate()
        return previous != selected?.key
    }

    private fun firstUsableCandidate(): Candidate<K>? = candidates.values.firstOrNull(::isUsable)
}
