package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.Param
import io.nekohasekai.sagernet.database.ProxyEntity

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
    val routingAssetsPath: String? = null,
    val routingCachePath: String? = null,
    val singBoxCachePath: String = Param.LIBCORE_CACHE_FILE_PATH,
) {
    data class IndexEntity(
        var chain: LinkedHashMap<Int, ProxyEntity>,
    )
}
