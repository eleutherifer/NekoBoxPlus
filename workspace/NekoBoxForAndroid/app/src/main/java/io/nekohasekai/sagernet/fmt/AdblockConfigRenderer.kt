package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.Param
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.AdblockRepository
import libcore.Libcore
import java.io.File

internal data class AdblockFilterSource(
    val url: String,
    val format: String? = null,
    val trust: Boolean = false,
)

internal data class AdblockTlsConfig(
    val certificate: String,
    val key: String,
    val skipEv: Boolean,
    val cronet: Boolean,
    val fingerprint: String,
)

internal data class AdblockConfigInput(
    val sources: List<AdblockFilterSource>,
    val rules: List<String>,
    val includedPackages: List<String>,
    val dnsFiltering: Boolean,
    val httpFiltering: Boolean,
    val httpsFiltering: Boolean,
    val cnameUncloaking: Boolean,
    val systemWide: Boolean,
    val mixedLanFiltering: Boolean,
    val hasMixedInbound: Boolean,
    val databasePath: String,
    val resourcesPath: String,
    val tls: AdblockTlsConfig? = null,
)

internal object AdblockConfigRenderer {
    fun render(input: AdblockConfigInput): Map<String, Any>? {
        if ((input.sources.isEmpty() && input.rules.isEmpty()) || input.includedPackages.isEmpty()) return null

        val lists = input.sources.mapNotNull { source ->
            source.url.trim().takeIf(String::isNotEmpty)?.let { url ->
                linkedMapOf<String, Any>("url" to url).apply {
                    source.format?.trim()?.takeIf(String::isNotEmpty)?.let { put("format", it.lowercase()) }
                    if (source.trust) put("trust", true)
                }
            }
        }
        val adblock = linkedMapOf<String, Any>(
            "enabled" to true,
            "filtering" to linkedMapOf(
                "mode" to "default",
                "dns" to input.dnsFiltering,
                "http" to input.httpFiltering,
                "https" to input.httpsFiltering,
                "quic" to input.httpsFiltering,
                "cname_uncloaking" to (input.dnsFiltering && input.cnameUncloaking),
            ),
            "filters" to linkedMapOf<String, Any>().apply {
                if (lists.isNotEmpty()) put("lists", lists)
                if (input.rules.isNotEmpty()) put("rules", input.rules)
            },
            "database_path" to input.databasePath,
            "adblock_resources" to input.resourcesPath,
        )
        val constraints = buildList<Map<String, Any>> {
            if (!input.systemWide) add(linkedMapOf("package_name" to input.includedPackages))
            if (input.mixedLanFiltering && input.hasMixedInbound) {
                add(linkedMapOf("inbound" to listOf(TAG_MIXED), "source_ip_is_not_loopback" to true))
            }
        }
        if (constraints.isNotEmpty()) adblock["constraints"] = constraints
        input.tls?.let { tls ->
            adblock["tls"] = linkedMapOf<String, Any>(
                "enabled" to true,
                "certificate" to tls.certificate,
                "key" to tls.key,
                "skip_ev" to tls.skipEv,
            ).apply {
                if (tls.cronet) put("cronet", true) else tls.fingerprint.trim()
                    .takeIf(String::isNotEmpty)?.let { put("utls", it) }
            }
        }
        return adblock
    }
}

internal fun buildAdblockOptions(): Map<String, Any>? {
    if (!DataStore.adblockEnabled) return null

    val selectedBundledFilters = AdblockRepository.ensureBundledDefaults()
    val sources = buildList {
        AdblockRepository.catalog.filter { it.id in selectedBundledFilters }.forEach { entry ->
            entry.sources.forEach { add(AdblockFilterSource(it.url, it.format, trust = true)) }
        }
        AdblockRepository.customFilters()
            .filter(AdblockRepository::customFilterEnabled)
            .forEach { add(AdblockFilterSource(it.url, trust = it.trust)) }
    }
    val rules = DataStore.adblockCustomRules.lineSequence()
        .map(String::trim).filter(String::isNotBlank).toList()
    val packages = DataStore.adblockIncludedPackages.lineSequence()
        .map(String::trim).filter(String::isNotBlank).distinct().toList()
    if ((sources.none { it.url.isNotBlank() } && rules.isEmpty()) || packages.isEmpty()) return null

    val tls = if (DataStore.adblockHttpsFiltering) {
        val caDir = File(SagerNet.application.noBackupFilesDir, "adblock")
        val certFile = File(caDir, "ca.crt")
        val keyFile = File(caDir, "ca.key")
        Libcore.ensureAdblockCA(certFile.absolutePath, keyFile.absolutePath)
        DataStore.adblockCaCertificate = certFile.absolutePath
        DataStore.adblockCaKey = keyFile.absolutePath
        AdblockTlsConfig(
            certificate = certFile.absolutePath,
            key = keyFile.absolutePath,
            skipEv = DataStore.adblockSkipEvCerts,
            cronet = DataStore.adblockHttpsCronet,
            fingerprint = DataStore.adblockHttpsFingerprint,
        )
    } else {
        null
    }
    return AdblockConfigRenderer.render(
        AdblockConfigInput(
            sources = sources,
            rules = rules,
            includedPackages = packages,
            dnsFiltering = DataStore.adblockDnsFiltering,
            httpFiltering = DataStore.adblockHttpFiltering,
            httpsFiltering = DataStore.adblockHttpsFiltering,
            cnameUncloaking = DataStore.adblockCnameUncloaking,
            systemWide = DataStore.adblockSystemWideFilter,
            mixedLanFiltering = DataStore.adblockMixedLanFiltering,
            hasMixedInbound = DataStore.appendHttpProxy || DataStore.serviceMode == Key.MODE_PROXY,
            databasePath = Param.LIBCORE_ADBLOCK_DB_FILE_PATH,
            resourcesPath = Libcore.adblockBundledResourcesPath(),
            tls = tls,
        ),
    )
}
