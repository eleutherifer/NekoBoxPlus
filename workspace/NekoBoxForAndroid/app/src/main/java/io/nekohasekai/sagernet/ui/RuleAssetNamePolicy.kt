package io.nekohasekai.sagernet.ui

internal object RuleAssetNamePolicy {
    const val GEOIP_LOCAL = "geoip.db"
    const val GEOSITE_LOCAL = "geosite.db"
    const val GEOIP_DAT = "geoip.dat"
    const val GEOSITE_DAT = "geosite.dat"

    fun displayNameForCustom(localFileName: String, url: String): String {
        val remoteName = url.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .takeIf { it.equals(GEOIP_DAT, ignoreCase = true) || it.equals(GEOSITE_DAT, ignoreCase = true) }
            ?.lowercase()
        return remoteName ?: localFileName
    }
}
