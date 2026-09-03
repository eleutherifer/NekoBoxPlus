package io.nekohasekai.sagernet.routing

import android.content.Context
import com.google.gson.Gson
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.CustomDnsServerStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleType
import io.nekohasekai.sagernet.database.SagerDatabase
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class RoutingImportResult(val changedAssetUrls: List<Pair<String, String>>)

object RoutingImportManager {
    fun prepareNekoBoxPlus(candidate: RoutingImportCandidate): RoutingImportCandidate {
        require(candidate.format == RoutingProfileFormat.NEKOBOX_PLUS)
        val hashes = candidate.rules.mapNotNull { draft ->
            draft.fullRule?.takeIf {
                RuleType.fromValue(it.type) == RuleType.NORMAL &&
                    it.outbound == StableRoutingOutbound.CUSTOM
            }
                ?.outboundHash?.takeIf(String::isNotBlank)
        }.toSet()
        val matches = RoutingOutboundHashResolver.resolve(hashes, SagerDatabase.proxyDao.getAll())
        return candidate.copy(rules = candidate.rules.map { draft ->
            val rule = draft.fullRule ?: return@map draft
            if (RuleType.fromValue(rule.type) == RuleType.DNS) return@map draft
            val builtin = StableRoutingRuleMapper.builtinOutbound(rule)
            if (builtin != null) return@map draft.copy(resolvedOutbound = builtin)
            val match = rule.outboundHash?.let(matches::get)
            if (match == null) {
                draft.copy(resolvedOutbound = 0L, outboundFallback = true)
            } else {
                draft.copy(resolvedOutbound = match.id, resolvedOutboundName = match.name)
            }
        })
    }

    suspend fun apply(
        context: Context,
        candidate: RoutingImportCandidate,
        selectedSettings: Set<RoutingSettingKind>,
        selectedRuleIndexes: Set<Int>,
        changedAssetUrls: List<Pair<String, String>> = pendingAssetChanges(candidate, selectedSettings),
    ): RoutingImportResult {
        candidate.settings.filter { it.kind in selectedSettings }.forEach { setting ->
            when (setting.kind) {
                RoutingSettingKind.REMOTE_DNS -> DataStore.remoteDns = setting.value
                RoutingSettingKind.DIRECT_DNS -> DataStore.directDns = setting.value
                RoutingSettingKind.DNS_HOSTS -> DataStore.dnsDomainOverrides = setting.value
                RoutingSettingKind.FAKE_DNS -> DataStore.enableFakeDns = setting.value.toBooleanStrict()
                RoutingSettingKind.DOMAIN_STRATEGY -> DataStore.resolveDestination = setting.value.toBooleanStrict()
                RoutingSettingKind.CUSTOM_DNS_SERVERS -> Unit
                RoutingSettingKind.GEO_ASSETS -> {
                    DataStore.rulesProvider = setting.provider ?: DataStore.RULES_PROVIDER_CUSTOM
                    DataStore.rulesGeoipUrl = setting.value
                    DataStore.rulesGeositeUrl = requireNotNull(setting.secondaryValue)
                }
            }
        }

        if (RoutingSettingKind.CUSTOM_DNS_SERVERS in selectedSettings) {
            importedCustomDnsServers(candidate)?.let(CustomDnsServerStore::replaceAll)
        }

        val rules = selectedImportRules(candidate, selectedSettings, selectedRuleIndexes).map { draft ->
            draft.toRuleEntity(context, candidate.format)
        }
        ProfileManager.replaceRules(rules)

        return RoutingImportResult(changedAssetUrls)
    }

    fun pendingAssetChanges(
        candidate: RoutingImportCandidate,
        selectedSettings: Set<RoutingSettingKind>,
    ): List<Pair<String, String>> {
        if (RoutingSettingKind.GEO_ASSETS !in selectedSettings) return emptyList()
        val setting = candidate.settings.firstOrNull { it.kind == RoutingSettingKind.GEO_ASSETS }
            ?: return emptyList()
        val provider = setting.provider?.let(RoutingProviderCatalog::byId)
        val newGeoip = provider?.geoipUrl ?: setting.value
        val newGeosite = provider?.geositeUrl ?: setting.secondaryValue.orEmpty()
        return buildList {
            if (newGeoip != effectiveGeoipUrl()) add("geoip.db" to newGeoip)
            if (newGeosite != effectiveGeositeUrl()) add("geosite.db" to newGeosite)
        }
    }

    internal fun importedCustomDnsServers(candidate: RoutingImportCandidate) =
        candidate.customDnsServers?.mapIndexed { index, server ->
            StableCustomDnsServerMapper.import(server, index + 1L)
        }

    internal fun selectedImportRules(
        candidate: RoutingImportCandidate,
        selectedSettings: Set<RoutingSettingKind>,
        selectedRuleIndexes: Set<Int>,
    ): List<RoutingImportRule> {
        val importCustomDnsServers = RoutingSettingKind.CUSTOM_DNS_SERVERS in selectedSettings
        return candidate.rules.mapIndexedNotNull { index, rule ->
            rule.takeIf {
                index in selectedRuleIndexes &&
                    (importCustomDnsServers || it.fullRule?.dnsServer?.id == null)
            }
        }
    }

    suspend fun refreshAssets(context: Context, assets: List<Pair<String, String>>) {
        if (assets.isEmpty()) return
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        assets.forEach { (fileName, url) -> downloadAsset(directory, fileName, url) }
    }

    private fun RoutingImportRule.toRuleEntity(
        context: Context,
        format: RoutingProfileFormat,
    ): RuleEntity {
        fullRule?.let {
            return StableRoutingRuleMapper.import(
                it,
                resolvedOutbound ?: 0L,
                resolvedDnsServer,
            )
        }
        val legacyKind = requireNotNull(kind)
        val name = when (legacyKind) {
            RoutingRuleKind.DIRECT_SITES -> context.getString(R.string.routing_import_direct_sites, format.label(context))
            RoutingRuleKind.DIRECT_IP -> context.getString(R.string.routing_import_direct_ips, format.label(context))
            RoutingRuleKind.PROXY_SITES -> context.getString(R.string.routing_import_proxy_sites, format.label(context))
            RoutingRuleKind.PROXY_IP -> context.getString(R.string.routing_import_proxy_ips, format.label(context))
            RoutingRuleKind.BLOCK_SITES -> context.getString(R.string.routing_import_block_sites, format.label(context))
            RoutingRuleKind.BLOCK_IP -> context.getString(R.string.routing_import_block_ips, format.label(context))
            RoutingRuleKind.EVERYTHING_DIRECT -> context.getString(R.string.routing_import_everything_direct)
        }
        return RuleEntity(
            name = name,
            enabled = true,
            domains = values.takeIf { legacyKind.name.endsWith("SITES") }?.joinToString("\n").orEmpty(),
            ip = values.takeIf { legacyKind.name.endsWith("IP") }?.joinToString("\n").orEmpty(),
            port = if (legacyKind == RoutingRuleKind.EVERYTHING_DIRECT) "0:65535" else "",
            outbound = legacyKind.outbound,
        )
    }

    private fun RoutingProfileFormat.label(context: Context) = context.getString(
        when (this) {
            RoutingProfileFormat.HAPP -> R.string.routing_format_happ
            RoutingProfileFormat.V2RAY_TUN -> R.string.routing_format_v2ray_tun
            RoutingProfileFormat.INCY -> R.string.routing_format_incy
            RoutingProfileFormat.NEKOBOX_PLUS -> R.string.routing_format_nekobox_plus
        },
    )

    private suspend fun downloadAsset(directory: File, fileName: String, url: String) {
        directory.mkdirs()
        val target = File(directory, fileName)
        val temporary = File(directory, "$fileName.routing-import.tmp")
        val client = Libcore.newHttpClient().apply {
            withUTLS(DataStore.appUTLSFingerprint)
            modernTLS()
            keepAlive()
            trySocks5(DataStore.mixedListener, DataStore.mixedPort, DataStore.mixedUsername, DataStore.mixedPassword)
        }
        try {
            val version = resolveAssetVersion(client, fileName, url)
            client.newRequest().apply { setURL(url) }.execute().writeTo(temporary.canonicalPath)
            if (!temporary.isFile || temporary.length() == 0L) throw IOException("Downloaded $fileName is empty")
            if (target.exists() && !target.delete()) throw IOException("Unable to replace $fileName")
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                if (!temporary.delete()) throw IOException("Unable to finalize $fileName")
            }
            File(directory, "${target.nameWithoutExtension}.version.txt")
                .writeText(version)
        } finally {
            temporary.delete()
            client.close()
        }
    }

    private fun resolveAssetVersion(client: libcore.HTTPClient, fileName: String, url: String): String {
        val provider = RoutingProviderCatalog.providers.firstOrNull {
            val providerUrl = if (fileName == "geoip.db") it.geoipUrl else it.geositeUrl
            providerUrl.equals(url.trim(), ignoreCase = true)
        }
        if (provider != null) {
            val repo = if (fileName == "geoip.db") provider.geoipRepo else provider.geositeRepo
            runCatching {
                val response = client.newRequest().apply {
                    setURL("https://api.github.com/repos/$repo/releases/latest")
                }.execute()
                JSONObject(Util.getStringBox(response.contentString)).optString("tag_name")
                    .takeIf(String::isNotBlank)
            }.getOrNull()?.let { return it }
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        return "custom:$timestamp"
    }

    private fun effectiveGeoipUrl() =
        RoutingProviderCatalog.byId(DataStore.rulesProvider)?.geoipUrl ?: DataStore.rulesGeoipUrl

    private fun effectiveGeositeUrl() =
        RoutingProviderCatalog.byId(DataStore.rulesProvider)?.geositeUrl ?: DataStore.rulesGeositeUrl
}

object RoutingPreviewPayloadStore {
    private val gson = Gson()

    fun put(context: Context, candidate: RoutingImportCandidate): String {
        val token = UUID.randomUUID().toString()
        file(context, token).writeText(gson.toJson(candidate))
        return token
    }

    fun get(context: Context, token: String): RoutingImportCandidate? = runCatching {
        gson.fromJson(file(context, token).readText(), RoutingImportCandidate::class.java)
    }.getOrNull()

    fun remove(context: Context, token: String) {
        file(context, token).delete()
    }

    private fun file(context: Context, token: String) =
        File(context.cacheDir, "routing-preview-${token.filter(Char::isLetterOrDigit)}.json")
}
