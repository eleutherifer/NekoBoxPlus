package io.nekohasekai.sagernet.utils

import android.content.Context
import io.nekohasekai.sagernet.SagerNet
import libcore.Libcore
import java.io.File
import java.util.Locale

data class PrefixedSuggestionCatalog(
    val allSuggestions: List<String>,
)

object GeoAssetSuggestionRepository {
    private enum class GeoAssetType(
        val fileName: String,
        val prefix: String,
    ) {
        GEOIP("geoip.db", "geoip:"),
        GEOSITE("geosite.db", "geosite:"),
    }

    @Volatile
    private var cachedGeoIpSignature: String? = null

    @Volatile
    private var cachedGeoIpCatalog: PrefixedSuggestionCatalog? = null

    @Volatile
    private var cachedGeositeSignature: String? = null

    @Volatile
    private var cachedGeositeCatalog: PrefixedSuggestionCatalog? = null

    fun loadGeoIp(context: Context = SagerNet.application): PrefixedSuggestionCatalog {
        return load(context, GeoAssetType.GEOIP)
    }

    fun loadGeosite(context: Context = SagerNet.application): PrefixedSuggestionCatalog {
        return load(context, GeoAssetType.GEOSITE)
    }

    fun resolveExternalFile(type: String, context: Context = SagerNet.application): File {
        val fileName = when (type) {
            "geoip" -> GeoAssetType.GEOIP.fileName
            "geosite" -> GeoAssetType.GEOSITE.fileName
            else -> throw IllegalArgumentException("unknown geo asset type: $type")
        }
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, fileName)
    }

    private fun load(context: Context, type: GeoAssetType): PrefixedSuggestionCatalog {
        val sourceFile = resolveExternalFile(
            type = if (type == GeoAssetType.GEOIP) "geoip" else "geosite",
            context = context
        )
        val signature = "external:${sourceFile.length()}:${sourceFile.lastModified()}"
        getCached(type, signature)?.let { return it }

        return synchronized(this) {
            getCached(type, signature)?.let { return@synchronized it }

            val rawCodes = when (type) {
                GeoAssetType.GEOIP -> Libcore.listGeoipCodes(sourceFile.absolutePath)
                GeoAssetType.GEOSITE -> Libcore.listGeositeCodes(sourceFile.absolutePath)
            }
            parse(type, rawCodes).also { putCached(type, signature, it) }
        }
    }

    private fun getCached(type: GeoAssetType, signature: String): PrefixedSuggestionCatalog? {
        return when (type) {
            GeoAssetType.GEOIP -> cachedGeoIpCatalog?.takeIf { cachedGeoIpSignature == signature }
            GeoAssetType.GEOSITE -> cachedGeositeCatalog?.takeIf { cachedGeositeSignature == signature }
        }
    }

    private fun putCached(type: GeoAssetType, signature: String, catalog: PrefixedSuggestionCatalog) {
        when (type) {
            GeoAssetType.GEOIP -> {
                cachedGeoIpSignature = signature
                cachedGeoIpCatalog = catalog
            }
            GeoAssetType.GEOSITE -> {
                cachedGeositeSignature = signature
                cachedGeositeCatalog = catalog
            }
        }
    }

    private fun parse(type: GeoAssetType, rawCodes: String): PrefixedSuggestionCatalog {
        val comparator = compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it }
        val suggestions = rawCodes
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { "${type.prefix}$it" }
            .distinct()
            .sortedWith(comparator)
            .toList()

        return PrefixedSuggestionCatalog(
            allSuggestions = suggestions,
        )
    }
}
