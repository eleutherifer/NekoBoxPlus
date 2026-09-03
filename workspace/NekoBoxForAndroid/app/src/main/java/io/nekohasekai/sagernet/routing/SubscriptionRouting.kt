package io.nekohasekai.sagernet.routing

import android.system.Os
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SpoofApp
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.group.buildSubscriptionRequestFingerprint
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.app
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import java.io.File
import java.io.IOException

object SubscriptionRoutingIntervals {
    val allowed = setOf(43_200, 86_400, 259_200, 604_800)
    const val DEFAULT = 86_400

    fun normalize(value: Int?) = value?.takeIf { it in allowed } ?: DEFAULT
}

sealed interface ProviderRoutingSource {
    data object Missing : ProviderRoutingSource
    data object Off : ProviderRoutingSource
    data class Value(val value: String, val auto: Boolean) : ProviderRoutingSource
}

internal fun ProviderRoutingSource.disablesStoredRouting() =
    this is ProviderRoutingSource.Missing || this is ProviderRoutingSource.Off

internal object SubscriptionRoutingExtractor {
    fun extract(autoroutingHeader: String, routingHeader: String, body: String): ProviderRoutingSource {
        autoroutingHeader.trim().takeIf(String::isNotEmpty)?.let {
            return ProviderRoutingSource.Value(it, auto = true)
        }

        val lines = body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        lines.firstOrNull {
            it.contains("://autorouting/", ignoreCase = true) &&
                routingPayload(it).let { payload ->
                    payload.startsWith("http://", true) || payload.startsWith("https://", true)
                }
        }?.let {
            return ProviderRoutingSource.Value(it, auto = true)
        }

        routingHeader.trim().takeIf(String::isNotEmpty)?.let {
            if (it.equals("off", ignoreCase = true) || it.endsWith("://routing/off", ignoreCase = true)) {
                return ProviderRoutingSource.Off
            }
            return ProviderRoutingSource.Value(it, auto = false)
        }

        lines.firstOrNull {
            it.contains("://autorouting/", ignoreCase = true) ||
            it.contains("://routing/", ignoreCase = true) ||
                it.startsWith("://onadd/", ignoreCase = true)
        }?.let {
            if (it.endsWith("://routing/off", ignoreCase = true)) return ProviderRoutingSource.Off
            return ProviderRoutingSource.Value(it, auto = false)
        }
        return ProviderRoutingSource.Missing
    }

    private fun routingPayload(value: String): String =
        value.substringAfter("/onadd/", value.substringAfter("/add/", ""))
}

data class ResolvedSubscriptionRouting(
    val format: RoutingProfileFormat,
    val profile: ExternalRoutingProfile,
    val sourceUrl: String?,
) {
    fun candidate(): RoutingImportCandidate = RoutingProfileMapper.toImportCandidate(format, profile)
}

data class SubscriptionRoutingPolicy(
    val candidate: RoutingImportCandidate,
) {
    fun setting(kind: RoutingSettingKind): RoutingImportSetting? =
        candidate.settings.firstOrNull { it.kind == kind }

    fun rules(): List<RuleEntity> = candidate.rules.mapNotNull { draft ->
        val kind = draft.kind ?: return@mapNotNull null
        RuleEntity(
            name = "${candidate.name}: ${kind.name.lowercase().replace('_', ' ')}",
            enabled = true,
            domains = draft.values.takeIf { kind.name.endsWith("SITES") }?.joinToString("\n").orEmpty(),
            ip = draft.values.takeIf { kind.name.endsWith("IP") }?.joinToString("\n").orEmpty(),
            port = if (kind == RoutingRuleKind.EVERYTHING_DIRECT) "0:65535" else "",
            outbound = kind.outbound,
        )
    }
}

object SubscriptionRoutingRepository {
    private val gson = Gson()
    private val supportedFormats = setOf(
        RoutingProfileFormat.HAPP,
        RoutingProfileFormat.V2RAY_TUN,
        RoutingProfileFormat.INCY,
    )

    fun stored(subscription: SubscriptionBean): ResolvedSubscriptionRouting? {
        if (subscription.routingOff == true || subscription.routingPayload.isNullOrBlank()) return null
        val format = runCatching { RoutingProfileFormat.valueOf(subscription.routingFormat) }.getOrNull()
            ?.takeIf { it in supportedFormats } ?: RoutingProfileFormat.INCY
        val profile = runCatching {
            gson.fromJson(subscription.routingPayload, ExternalRoutingProfile::class.java)
        }.getOrNull() ?: return null
        return ResolvedSubscriptionRouting(format, profile, subscription.autoRoutingUrl.takeIf(String::isNotBlank))
    }

    suspend fun resolve(source: ProviderRoutingSource): ResolvedSubscriptionRouting? = when (source) {
        ProviderRoutingSource.Missing, ProviderRoutingSource.Off -> null
        is ProviderRoutingSource.Value -> resolveValue(source.value, source.auto)
    }

    suspend fun updateStored(
        subscription: SubscriptionBean,
        source: ProviderRoutingSource,
        groupId: Long,
    ): ResolvedSubscriptionRouting? {
        if (subscription.routingEnabled != true) return null
        if (source.disablesStoredRouting()) {
            clearStored(subscription)
            deleteFiles(groupId)
            return null
        }
        val resolved = resolve(source) ?: return stored(subscription)
        prepareAssets(groupId, resolved)
        store(subscription, resolved)
        return resolved
    }

    fun clearStored(subscription: SubscriptionBean) {
        subscription.routingEnabled = false
        subscription.routingPayload = ""
        subscription.routingFormat = ""
        subscription.autoRoutingUrl = ""
        subscription.routingLastUpdated = 0L
        subscription.routingOff = false
    }

    fun store(
        subscription: SubscriptionBean,
        routing: ResolvedSubscriptionRouting,
    ) {
        subscription.routingEnabled = true
        subscription.routingPayload = gson.toJson(routing.profile)
        subscription.routingFormat = routing.format.name
        subscription.autoRoutingUrl = routing.sourceUrl.orEmpty()
        subscription.routingLastUpdated = System.currentTimeMillis() / 1000L
        subscription.routingOff = false
        subscription.routingUpdateInterval =
            SubscriptionRoutingIntervals.normalize(subscription.routingUpdateInterval)
    }

    suspend fun refreshAutoRouting(group: ProxyGroup): Boolean {
        val subscription = group.subscription ?: return false
        val url = subscription.autoRoutingUrl.takeIf(String::isNotBlank) ?: return false
        val resolved = resolveValue(url, auto = true) ?: return false
        prepareAssets(group.id, resolved)
        subscription.routingPayload = gson.toJson(resolved.profile)
        subscription.routingFormat = resolved.format.name
        subscription.autoRoutingUrl = url
        subscription.routingLastUpdated = System.currentTimeMillis() / 1000L
        subscription.routingOff = false
        return true
    }

    suspend fun prepareAssets(groupId: Long, routing: ResolvedSubscriptionRouting) {
        val root = assetsDirectory(groupId)
        val parent = requireNotNull(root.parentFile).apply { mkdirs() }
        val staging = File(parent, "assets.pending").apply {
            deleteRecursively()
            mkdirs()
        }
        val geoip = routing.profile.geoipUrl?.trim().orEmpty()
        val geosite = routing.profile.geositeUrl?.trim().orEmpty()
        try {
            prepareAsset(staging, "geoip.db", geoip)
            prepareAsset(staging, "geosite.db", geosite)
            val previous = File(parent, "assets.previous")
            previous.deleteRecursively()
            if (root.exists() && !root.renameTo(previous)) {
                throw IOException("Unable to replace subscription routing assets")
            }
            if (!staging.renameTo(root)) {
                previous.renameTo(root)
                throw IOException("Unable to install subscription routing assets")
            }
            previous.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
    }

    fun assetsReady(groupId: Long): Boolean {
        val root = assetsDirectory(groupId)
        return File(root, "geoip.db").isFile && File(root, "geosite.db").isFile
    }

    fun assetsDirectory(groupId: Long) =
        File(app.filesDir, "subscription-routing/$groupId/assets")

    fun cacheDirectory(groupId: Long) =
        File(app.cacheDir, "subscription-routing/$groupId").apply { mkdirs() }

    fun singBoxCacheFile(groupId: Long) = File(cacheDirectory(groupId), "cache.db")
    fun routingRulesCacheFile(groupId: Long) = File(cacheDirectory(groupId), "routing-rules-cache.db")

    fun deleteFiles(groupId: Long) {
        File(app.filesDir, "subscription-routing/$groupId").deleteRecursively()
        File(app.cacheDir, "subscription-routing/$groupId").deleteRecursively()
    }

    suspend fun fetchFromSubscription(group: ProxyGroup): Pair<ProviderRoutingSource, ResolvedSubscriptionRouting?> {
        val subscription = requireNotNull(group.subscription)
        if (subscription.link.startsWith("content://")) {
            val body = app.contentResolver.openInputStream(subscription.link.toUri())
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: throw IOException("Unable to read subscription content")
            val source = SubscriptionRoutingExtractor.extract("", "", body)
            return source to resolve(source)
        }
        val client = Libcore.newHttpClient().apply {
            withUTLS(DataStore.appUTLSFingerprint)
            setTimeoutMillis(120_000)
            tryH3Direct()
            if (DataStore.appTLSVersion == "1.3") restrictedTLS()
        }
        return try {
            val response = client.newRequest().apply {
                if (DataStore.allowInsecureOnRequest) allowInsecure()
                setURL(subscription.link)
                val fingerprint =
                    buildSubscriptionRequestFingerprint(
                        spoofApp = subscription.spoofApp ?: SpoofApp.NONE,
                        hwidEnabled = subscription.hwidEnabled == true,
                        customUserAgent = subscription.customUserAgent,
                        fallbackUserAgent = USER_AGENT,
                    )
                setUserAgent(fingerprint.userAgent)
                for ((name, value) in fingerprint.headers) {
                    setHeader(name, value)
                }
            }.execute()
            val body = Util.getStringBox(response.contentString)
            val source = SubscriptionRoutingExtractor.extract(
                Util.getStringBox(response.getHeader("autorouting")),
                Util.getStringBox(response.getHeader("routing")),
                body,
            )
            source to resolve(source)
        } finally {
            client.close()
        }
    }

    private suspend fun resolveValue(rawValue: String, auto: Boolean): ResolvedSubscriptionRouting? {
        val value = rawValue.trim()
        parseWrappedSource(value)?.let { wrapped ->
            if (wrapped.payload.startsWith("http://", true) || wrapped.payload.startsWith("https://", true)) {
                val url = normalizeGitHubUrl(wrapped.payload)
                val content = downloadText(url)
                val resolved = resolveRemoteContent(content)
                    ?: error(app.getString(R.string.subscription_routing_not_found))
                return resolved.copy(
                    format = wrapped.format ?: resolved.format,
                    sourceUrl = url.takeIf { auto || wrapped.auto },
                )
            }
            val scheme = (wrapped.format ?: RoutingProfileFormat.INCY).scheme
            val link = "$scheme://routing/onadd/${wrapped.payload}"
            val (format, profile) = RoutingProfileCodec.decode(link)
            return ResolvedSubscriptionRouting(format, profile, null)
        }
        RoutingProfileCodec.supports(value).takeIf { it }?.let {
            val (format, profile) = RoutingProfileCodec.decode(value)
            if (format !in supportedFormats) return null
            return ResolvedSubscriptionRouting(format, profile, sourceUrl = null)
        }

        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            val url = normalizeGitHubUrl(value)
            val content = downloadText(url)
            val resolved = resolveRemoteContent(content) ?: error(app.getString(R.string.subscription_routing_not_found))
            return resolved.copy(sourceUrl = url.takeIf { auto })
        }

        decodeProfile(value)?.let {
            return ResolvedSubscriptionRouting(RoutingProfileFormat.INCY, it, null)
        }
        return null
    }

    private suspend fun resolveRemoteContent(content: String): ResolvedSubscriptionRouting? {
        val trimmed = content.trim()
        if (parseWrappedSource(trimmed) != null) {
            return resolveValue(trimmed, auto = false)
        }
        if (RoutingProfileCodec.supports(trimmed)) {
            val (format, profile) = RoutingProfileCodec.decode(trimmed)
            if (format in supportedFormats) return ResolvedSubscriptionRouting(format, profile, null)
        }
        return decodeProfile(trimmed)?.let {
            ResolvedSubscriptionRouting(RoutingProfileFormat.INCY, it, null)
        }
    }

    private fun decodeProfile(value: String): ExternalRoutingProfile? {
        fun decodeJson(json: String): ExternalRoutingProfile? = runCatching {
            val root = JsonParser.parseString(json)
            require(root.isJsonObject)
            gson.fromJson(root, ExternalRoutingProfile::class.java)
        }.getOrNull()
        decodeJson(value)?.let { return it }
        val normalized = value.filterNot(Char::isWhitespace)
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val decoded = runCatching { android.util.Base64.decode(padded, android.util.Base64.DEFAULT) }
            .recoverCatching { android.util.Base64.decode(padded, android.util.Base64.URL_SAFE) }
            .getOrNull() ?: return null
        return decodeJson(decoded.toString(Charsets.UTF_8))
    }

    private data class WrappedSource(
        val format: RoutingProfileFormat?,
        val auto: Boolean,
        val payload: String,
    )

    private fun parseWrappedSource(value: String): WrappedSource? {
        val match = Regex(
            """^(?:([a-zA-Z][a-zA-Z0-9+.-]*))?://(?:(auto)?routing(?:/(?:onadd|add))?|onadd)/(.+)$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).matchEntire(value.trim()) ?: return null
        val format = match.groupValues[1].takeIf(String::isNotBlank)?.let { scheme ->
            supportedFormats.firstOrNull { it.scheme.equals(scheme, ignoreCase = true) }
        }
        return WrappedSource(
            format = format,
            auto = match.groupValues[2].isNotBlank(),
            payload = match.groupValues[3].trim(),
        )
    }

    private fun normalizeGitHubUrl(url: String): String {
        val match = Regex("""^https://github\.com/([^/]+)/([^/]+)/blob/(.+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(url)
        return match?.let { "https://raw.githubusercontent.com/${it.groupValues[1]}/${it.groupValues[2]}/${it.groupValues[3]}" }
            ?: url
    }

    private fun downloadText(url: String): String {
        val client = Libcore.newHttpClient().apply {
            withUTLS(DataStore.appUTLSFingerprint)
            modernTLS()
            keepAlive()
        }
        return try {
            Util.getStringBox(client.newRequest().apply { setURL(url) }.execute().contentString)
        } finally {
            client.close()
        }
    }

    private fun prepareAsset(root: File, name: String, url: String) {
        val target = File(root, name)
        if (url.isBlank()) {
            val defaults = app.getExternalFilesDir(null) ?: app.filesDir
            val source = File(defaults, name)
            if (!source.isFile) throw IOException("Default $name is unavailable")
            Os.symlink(source.absolutePath, target.absolutePath)
            val versionName = "${target.nameWithoutExtension}.version.txt"
            Os.symlink(
                File(defaults, versionName).absolutePath,
                File(root, versionName).absolutePath,
            )
            return
        }
        val temporary = File(root, "$name.tmp")
        val client = Libcore.newHttpClient().apply {
            withUTLS(DataStore.appUTLSFingerprint)
            modernTLS()
            keepAlive()
        }
        try {
            client.newRequest().apply { setURL(url) }.execute().writeTo(temporary.canonicalPath)
            if (!temporary.isFile || temporary.length() == 0L) throw IOException("Downloaded $name is empty")
            temporary.copyTo(target, overwrite = true)
            writeAssetVersion(root, target)
        } finally {
            temporary.delete()
            client.close()
        }
    }

    private fun writeAssetVersion(root: File, target: File) {
        File(root, "${target.nameWithoutExtension}.version.txt")
            .writeText("${target.length()}:${target.lastModified()}")
    }
}
