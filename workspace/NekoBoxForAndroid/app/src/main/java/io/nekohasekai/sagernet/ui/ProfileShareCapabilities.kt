package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyEntity

internal data class ProfileShareCapabilities(
    val links: Boolean,
    val standardLinks: Boolean,
    val configuration: Boolean,
) {
    companion object {
        fun from(entity: ProxyEntity) = ProfileShareCapabilities(
            links = entity.haveLink(),
            standardLinks = entity.haveStandardLink(),
            configuration = entity.nekoBean == null,
        )
    }
}
