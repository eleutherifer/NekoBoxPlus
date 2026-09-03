package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.Param
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.DataStore
import com.google.gson.reflect.TypeToken
import libcore.Libcore
import moe.matsuri.nb4a.utils.JavaUtil

object AdblockRepository {
    private const val CATALOG_ASSET = "list_catalog.json"
    private val customFiltersLock = Any()

    data class CatalogEntry(
        val id: String = "",
        val title: String = "",
        val desc: String = "",
        val category: String = "",
        val default_enabled: Boolean = false,
        val sources: List<CatalogSource> = emptyList(),
    )

    data class CatalogSource(
        val url: String = "",
        val title: String = "",
        val format: String = "",
    )

    data class CustomFilter(
        val url: String = "",
        val trust: Boolean = false,
        val enabled: Boolean? = null,
        val title: String = "",
        val description: String = "",
        val metadataFetched: Boolean? = null,
    )

    data class FilterMetadata(
        val title: String = "",
        val description: String = "",
    )

    data class FilterUpdateResult(
        val url: String = "",
        val lastUpdated: String = "",
        val lastModified: String = "",
        val error: String? = null,
    )

    val catalog: List<CatalogEntry> by lazy {
        SagerNet.application.assets.open(CATALOG_ASSET).bufferedReader().use { reader ->
            JavaUtil.gson.fromJson(reader, Array<CatalogEntry>::class.java)?.toList()
                ?: emptyList()
        }
    }

    fun groupedCatalog(): LinkedHashMap<String, List<CatalogEntry>> {
        val grouped = LinkedHashMap<String, MutableList<CatalogEntry>>()
        for (entry in catalog) {
            grouped.getOrPut(entry.category) { mutableListOf() }.add(entry)
        }
        return LinkedHashMap<String, List<CatalogEntry>>().apply {
            grouped.forEach { (category, entries) -> put(category, entries) }
        }
    }

    fun ensureBundledDefaults(): Set<String> {
        val storedSelection = DataStore.configurationStore.getStringSet(Key.ADBLOCK_BUNDLED_FILTERS)
        if (!DataStore.adblockBundledFiltersInitialized || storedSelection == null) {
            val defaults = catalog
                .filter { it.default_enabled }
                .map { it.id }
                .toSet()
            DataStore.adblockBundledFilters = defaults
            DataStore.adblockBundledFiltersInitialized = true
        }
        return DataStore.adblockBundledFilters
    }

    fun saveBundledFilters(ids: Set<String>) {
        DataStore.adblockBundledFilters = ids
        DataStore.adblockBundledFiltersInitialized = true
    }

    fun customFilters(): MutableList<CustomFilter> = synchronized(customFiltersLock) {
        readCustomFiltersLocked()
    }

    fun saveCustomFilters(filters: List<CustomFilter>) = synchronized(customFiltersLock) {
        DataStore.adblockCustomFilters = JavaUtil.gson.toJson(filters
            .filter { it.url.isNotBlank() }
            .map { if (it.enabled == null) it.copy(enabled = true) else it })
    }

    fun customFilterEnabled(filter: CustomFilter): Boolean {
        return filter.enabled != false
    }

    fun filterDisplayTitle(filter: CustomFilter): String {
        return filter.title.takeIf { it.isNotBlank() } ?: filter.url
    }

    fun filterDisplaySummary(filter: CustomFilter): String {
        return when {
            filter.title.isNotBlank() && filter.description.isNotBlank() -> filter.description
            filter.title.isNotBlank() -> filter.url
            else -> ""
        }
    }

    fun fetchFilterMetadata(url: String, service: ISagerNetService? = null): FilterMetadata? {
        if (url.isBlank()) return null
        return fetchFilterMetadataMap(listOf(url), service)[url.trim()]
    }

    fun fetchFilterMetadataMap(urls: List<String>, service: ISagerNetService? = null): Map<String, FilterMetadata> {
        val valid = urls.map { it.trim() }.filter { it.isNotBlank() }
        if (valid.isEmpty()) return emptyMap()
        return runCatching {
            val joined = valid.joinToString("\n")
            val json = service?.adblockFilterMetadataMap(joined)
                ?: Libcore.adblockFilterMetadataMap(joined, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            val type = object : TypeToken<Map<String, FilterMetadata>>() {}.type
            JavaUtil.gson.fromJson<Map<String, FilterMetadata>>(json, type) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    fun fetchStoredFilterVersion(url: String, service: ISagerNetService? = null): String {
        if (url.isBlank()) return ""
        return fetchStoredFilterVersions(listOf(url), service)[url.trim()].orEmpty()
    }

    fun fetchStoredFilterVersions(urls: List<String>, service: ISagerNetService? = null): Map<String, String> {
        val valid = urls.map { it.trim() }.filter { it.isNotBlank() }
        if (valid.isEmpty()) return emptyMap()
        return runCatching {
            val joined = valid.joinToString("\n")
            val json = service?.adblockStoredFilterVersions(joined)
                ?: Libcore.adblockStoredFilterVersions(joined, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            @Suppress("UNCHECKED_CAST")
            (JavaUtil.gson.fromJson(json, Map::class.java) as? Map<String, String>) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    fun preCacheFilter(url: String, service: ISagerNetService? = null): String {
        val result = preCacheFilters(listOf(url), service).firstOrNull()
        return result?.lastModified?.takeIf { it.isNotBlank() }
            ?: result?.lastUpdated.orEmpty()
    }

    fun preCacheFilters(urls: List<String>, service: ISagerNetService? = null): List<FilterUpdateResult> {
        if (urls.isEmpty()) return emptyList()
        return runCatching {
            val joined = urls.filter { it.isNotBlank() }.joinToString("\n")
            val json = service?.adblockPreCacheFilters(joined)
                ?: Libcore.adblockPreCacheFilters(joined, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            JavaUtil.gson.fromJson(json, Array<FilterUpdateResult>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun deleteCachedFilter(url: String, service: ISagerNetService? = null) {
        if (url.isBlank()) return
        deleteCachedFilters(listOf(url), service)
    }

    fun deleteCachedFilters(urls: List<String>, service: ISagerNetService? = null) {
        val joined = urls.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
        if (joined.isBlank()) return
        service?.adblockDeleteCachedFilters(joined)
            ?: Libcore.adblockDeleteCachedFilters(joined, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
    }

    /**
     * Trigger a throttled reload of the running adblock engine from the cached
     * filter database, avoiding a full proxy restart. No-op when the proxy is
     * not running (the binder is unavailable); the engine is rebuilt on next
     * start in that case. Safe to call after each filter update or batch update
     * because reloads are coalesced and rate-limited on the core side.
     */
    fun reloadEngine(service: ISagerNetService? = null) {
        runCatching { service?.adblockReloadEngine() }
    }

    fun saveCustomFilterMetadata(url: String, metadata: FilterMetadata?): Boolean = synchronized(customFiltersLock) {
        if (url.isBlank()) return@synchronized false
        val filters = readCustomFiltersLocked()
        var changed = false
        val updatedFilters = filters.map { filter ->
            if (filter.url != url || filter.metadataFetched == true) {
                filter
            } else {
                changed = true
                filter.copy(
                    title = metadata?.title?.trim().orEmpty(),
                    description = metadata?.description?.trim().orEmpty(),
                    metadataFetched = true,
                )
            }
        }
        if (changed) {
            DataStore.adblockCustomFilters = JavaUtil.gson.toJson(updatedFilters)
        }
        changed
    }

    fun saveCustomFilterMetadataMap(metadataByUrl: Map<String, FilterMetadata>): Boolean = synchronized(customFiltersLock) {
        if (metadataByUrl.isEmpty()) return@synchronized false
        val filters = readCustomFiltersLocked()
        var changed = false
        val updatedFilters = filters.map { filter ->
            val url = filter.url.trim()
            val metadata = metadataByUrl[url]
            if (url.isBlank() || metadata == null || filter.metadataFetched == true) {
                filter
            } else {
                changed = true
                filter.copy(
                    title = metadata.title.trim(),
                    description = metadata.description.trim(),
                    metadataFetched = true,
                )
            }
        }
        if (changed) {
            DataStore.adblockCustomFilters = JavaUtil.gson.toJson(updatedFilters)
        }
        changed
    }

    private fun readCustomFiltersLocked(): MutableList<CustomFilter> {
        val raw = DataStore.adblockCustomFilters.takeIf { it.isNotBlank() } ?: return mutableListOf()
        return runCatching {
            JavaUtil.gson.fromJson(raw, Array<CustomFilter>::class.java)
                ?.filter { it.url.isNotBlank() }
                ?.map { if (it.enabled == null) it.copy(enabled = true) else it }
                ?.toMutableList()
        }.getOrNull() ?: mutableListOf()
    }

    fun categoryTitle(category: String): String {
        return category.replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }
}
