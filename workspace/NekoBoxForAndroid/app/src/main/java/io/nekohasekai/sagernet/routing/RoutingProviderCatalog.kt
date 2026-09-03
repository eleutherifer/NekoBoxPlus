package io.nekohasekai.sagernet.routing

import io.nekohasekai.sagernet.database.DataStore

data class RoutingAssetProvider(
    val id: Int,
    val geoipRepo: String,
    val geositeRepo: String = geoipRepo,
    val geoipFile: String = "geoip.db",
    val geositeFile: String = "geosite.db",
) {
    val geoipUrl: String get() = releaseUrl(geoipRepo, geoipFile)
    val geositeUrl: String get() = releaseUrl(geositeRepo, geositeFile)

    private fun releaseUrl(repo: String, file: String) =
        "https://github.com/$repo/releases/latest/download/$file"
}

object RoutingProviderCatalog {
    val providers = listOf(
        RoutingAssetProvider(
            DataStore.RULES_PROVIDER_OFFICIAL,
            "SagerNet/sing-geoip",
            "SagerNet/sing-geosite",
        ),
        RoutingAssetProvider(
            DataStore.RULES_PROVIDER_LOYALSOLDIER,
            "soffchen/sing-geoip",
            "soffchen/sing-geosite",
        ),
        RoutingAssetProvider(DataStore.RULES_PROVIDER_IRAN, "Chocolate4U/Iran-sing-box-rules"),
        RoutingAssetProvider(DataStore.RULES_PROVIDER_ANTIZAPRET, "savely-krasovsky/antizapret-sing-box-geo"),
        RoutingAssetProvider(
            DataStore.RULES_PROVIDER_ITDOG,
            "itdoginfo/allow-domains",
            geoipFile = "geoip.dat",
            geositeFile = "geosite.dat",
        ),
        RoutingAssetProvider(
            DataStore.RULES_PROVIDER_V2RAY_DAT,
            "Loyalsoldier/v2ray-rules-dat",
            geoipFile = "geoip.dat",
            geositeFile = "geosite.dat",
        ),
        RoutingAssetProvider(
            DataStore.RULES_PROVIDER_RUNETFREEDOM_DAT,
            "runetfreedom/russia-v2ray-rules-dat",
            geoipFile = "geoip.dat",
            geositeFile = "geosite.dat",
        ),
    )

    fun match(geoipUrl: String, geositeUrl: String): RoutingAssetProvider? =
        providers.firstOrNull {
            normalize(it.geoipUrl) == normalize(geoipUrl) &&
                normalize(it.geositeUrl) == normalize(geositeUrl)
        }

    fun byId(id: Int): RoutingAssetProvider? = providers.firstOrNull { it.id == id }

    private fun normalize(url: String) = url.trim().trimEnd('/').lowercase()
}
