package moe.matsuri.nb4a.proxy.anytls

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import io.nekohasekai.sagernet.fmt.applySharedTLSOptions
import io.nekohasekai.sagernet.ktx.blankAsNull
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import android.util.Base64

private const val StormDnsSchema = "stormdns"
private const val StormDnsProfileType = "whitedns.profile"
private const val StormDnsVersion = 1

fun buildSingBoxOutboundAnyTLSBean(bean: AnyTLSBean): SingBoxOptions.Outbound_AnyTLSOptions {
    return SingBoxOptions.Outbound_AnyTLSOptions().apply {
        type = "anytls"
        server = bean.serverAddress
        server_port = bean.serverPort
        password = bean.password
        client_metadata = bean.clientMetadata.blankAsNull()

        tls = SingBoxOptions.OutboundTLSOptions().apply {
            enabled = true
            server_name = bean.sni.blankAsNull()
            if (bean.allowInsecure || DataStore.globalAllowInsecure) insecure = true
            alpn = bean.alpn.blankAsNull()?.listByLineOrComma()
            bean.certificates.blankAsNull()?.let {
                certificate = it
            }
            var fingerprint = bean.utlsFingerprint.blankAsNull()
            if (!bean.realityPubKey.isNullOrBlank()) {
                reality = SingBoxOptions.OutboundRealityOptions().apply {
                    enabled = true
                    public_key = bean.realityPubKey
                    short_id = bean.realityShortId
                }
                if (fingerprint.isNullOrBlank()) {
                    fingerprint = "chrome"
                }
            }
            fingerprint?.let {
                utls = SingBoxOptions.OutboundUTLSOptions().apply {
                    enabled = true
                    fingerprint = it
                }
            }
            bean.echConfig.blankAsNull()?.let {
                // In new version, some complex options will be deprecated, so we just do this.
                ech = SingBoxOptions.OutboundECHOptions().apply {
                    enabled = true
                    config = if (it.contains("BEGIN ECH CONFIGS")) {
                        listOf(it)
                    } else {
                        listOf("-----BEGIN ECH CONFIGS-----", it.trim(), "-----END ECH CONFIGS-----")
                    }
                }
            }
            applySharedTLSOptions(bean)
        }
    }
}

fun AnyTLSBean.toUri(): String {
    val builder = linkBuilder()
        .host(serverAddress)
        .port(serverPort)
        .username(password)
    if (!name.isNullOrBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    if (allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    if (!sni.isNullOrBlank()) {
        builder.addQueryParameter("sni", sni)
    }
    if (!utlsFingerprint.isNullOrBlank()) {
        builder.addQueryParameter("fp", utlsFingerprint)
    }
    if (!realityPubKey.isNullOrBlank()) {
        builder.addQueryParameter("pbk", realityPubKey)
    }
    if (!realityShortId.isNullOrBlank()) {
        builder.addQueryParameter("sid", realityShortId)
    }
    return builder.toLink("anytls")
}

fun parseAnytls(url: String): AnyTLSBean {
    // https://github.com/anytls/anytls-go/blob/main/docs/uri_scheme.md
    val link = url.replace("anytls://", "https://").toHttpUrlOrNull() ?: error(
        "invalid anytls link $url"
    )
    return AnyTLSBean().apply {
        serverAddress = link.host
        serverPort = link.port
        name = link.fragment
        password = link.username
        sni = link.queryParameter("sni") ?: ""
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
        link.queryParameter("fp")?.let {
            utlsFingerprint = it
        }
        link.queryParameter("pbk")?.let {
            realityPubKey = it
        }
        link.queryParameter("sid")?.let {
            realityShortId = it
        }
    }
}

fun MasterDnsVPNBean.toUri(): String {
    val domain = domains.orEmpty().lineSequence()
        .flatMap { it.splitToSequence(',') }
        .map { it.trim().trimEnd('.') }
        .filter { it.isNotEmpty() }
        .joinToString(",")
    val key = encryptionKey.orEmpty().trim()

    if (domain.isBlank() || key.isBlank()) {
        throw IllegalArgumentException("Server domain and encryption key are required to export")
    }

    val profileJson = JSONObject()
        .put("name", name.orEmpty().takeIf { it.isNotBlank() } ?: domain)
        .put(
            "server",
            JSONObject()
                .put("domain", domain)
                .put("encryption_key", key)
                .put("encryption_method", (dataEncryptionMethod ?: 1).coerceIn(0, 5))
        )

    resolvers.orEmpty().trim().takeIf { it.isNotEmpty() }?.let {
        profileJson.put("resolvers", it)
    }

    profileJson.putOptional("resolver_balancing_strategy", resolverBalancingStrategy)
    profileJson.putOptional("packet_duplication_count", packetDuplicationCount)
    profileJson.putOptional("setup_packet_duplication_count", setupPacketDuplicationCount)
    profileJson.putOptional("base_encode_data", baseEncodeData)
    profileJson.putOptional("upload_compression_type", uploadCompressionType)
    profileJson.putOptional("download_compression_type", downloadCompressionType)
    profileJson.putOptional("min_upload_mtu", minUploadMTU)
    profileJson.putOptional("max_upload_mtu", maxUploadMTU)
    profileJson.putOptional("min_download_mtu", minDownloadMTU)
    profileJson.putOptional("max_download_mtu", maxDownloadMTU)
    profileJson.putOptional("rx_tx_workers", rxTxWorkers)
    profileJson.putOptional("tunnel_process_workers", tunnelProcessWorkers)
    profileJson.putOptional("max_packets_per_batch", maxPacketsPerBatch)
    profileJson.putOptional("arq_window_size", arqWindowSize)
    profileJson.putOptional("arq_initial_rto_seconds", arqInitialRTOSeconds)
    profileJson.putOptional("arq_max_rto_seconds", arqMaxRTOSeconds)
    profileJson.putOptional("ping_aggressive_interval_seconds", pingAggressiveIntervalSeconds)
    profileJson.putOptional("ping_lazy_interval_seconds", pingLazyIntervalSeconds)
    profileJson.putOptional("ping_cooldown_interval_seconds", pingCooldownIntervalSeconds)
    profileJson.putOptional("ping_cold_interval_seconds", pingColdIntervalSeconds)

    val root = JSONObject()
        .put("schema", StormDnsProfileType)
        .put("version", StormDnsVersion)
        .put("profile", profileJson)

    return "$StormDnsSchema://${encodeStormDnsPayload(root)}"
}

fun parseStormDns(url: String): MasterDnsVPNBean {
    val decoded = decodeStormDnsPayload(url)

    val profileJson = decoded.optJSONObject("profile")
        ?: throw IllegalArgumentException("profile is not defined")

    val serverJson = profileJson.optJSONObject("server")
        ?: throw IllegalArgumentException("server is not defined")

    val domain = serverJson.mustGetString("domain")
        .lineSequence()
        .flatMap { it.splitToSequence(',') }
        .map { it.trim().trimEnd('.') }
        .filter { it.isNotEmpty() }
        .joinToString(",")

    val encryptionKey = serverJson.mustGetString("encryption_key")
        .trim()

    if (domain.isBlank()) {
        throw IllegalArgumentException("server domain is not defined")
    }

    if (encryptionKey.isBlank()) {
        throw IllegalArgumentException("server encryption key is not defined")
    }

    val profileName = profileJson
        .getStringOrDefault("name", domain)
        .trim()

    val encryptionMethod = serverJson.mustGetInt("encryption_method")

    if (encryptionMethod !in 0..5) {
        throw IllegalArgumentException(
            "Server encryption method must be between 0 and 5"
        )
    }

    return MasterDnsVPNBean().apply {
        initializeDefaultValues()

        name = profileName

        domains = domain
        this.encryptionKey = encryptionKey
        dataEncryptionMethod = encryptionMethod

        profileJson.optionalString("resolvers")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                resolvers = it
            }

        profileJson.optionalString("preset")
            ?.takeIf { it.isNotBlank() }
            ?.let(::applyPreset)

        profileJson.optionalInt("resolver_balancing_strategy")?.let {
            resolverBalancingStrategy = it
        }

        profileJson.optionalInt("packet_duplication_count")?.let {
            packetDuplicationCount = it
        }

        profileJson.optionalInt("setup_packet_duplication_count")?.let {
            setupPacketDuplicationCount = it
        }

        profileJson.optionalBoolean("base_encode_data")?.let {
            baseEncodeData = it
        }

        profileJson.optionalInt("upload_compression_type")?.let {
            uploadCompressionType = it
        }

        profileJson.optionalInt("download_compression_type")?.let {
            downloadCompressionType = it
        }

        profileJson.optionalInt("min_upload_mtu")?.let {
            minUploadMTU = it
        }

        profileJson.optionalInt("max_upload_mtu")?.let {
            maxUploadMTU = it
        }

        profileJson.optionalInt("min_download_mtu")?.let {
            minDownloadMTU = it
        }

        profileJson.optionalInt("max_download_mtu")?.let {
            maxDownloadMTU = it
        }

        profileJson.optionalInt("rx_tx_workers")?.let {
            rxTxWorkers = it
        }

        profileJson.optionalInt("tunnel_process_workers")?.let {
            tunnelProcessWorkers = it
        }

        profileJson.optionalInt("max_packets_per_batch")?.let {
            maxPacketsPerBatch = it
        }

        profileJson.optionalInt("arq_window_size")?.let {
            arqWindowSize = it
        }

        profileJson.optionalDouble("arq_initial_rto_seconds")?.let {
            arqInitialRTOSeconds = it
        }

        profileJson.optionalDouble("arq_max_rto_seconds")?.let {
            arqMaxRTOSeconds = it
        }

        profileJson.optionalDouble("ping_aggressive_interval_seconds")?.let {
            pingAggressiveIntervalSeconds = it
        }

        profileJson.optionalDouble("ping_lazy_interval_seconds")?.let {
            pingLazyIntervalSeconds = it
        }

        profileJson.optionalDouble("ping_cooldown_interval_seconds")?.let {
            pingCooldownIntervalSeconds = it
        }

        profileJson.optionalDouble("ping_cold_interval_seconds")?.let {
            pingColdIntervalSeconds = it
        }
    }
}

private fun encodeStormDnsPayload(root: JSONObject): String {
    return Base64.encodeToString(
        root.toString().toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    )
}

private fun JSONObject.optionalBoolean(name: String): Boolean? {
    if (!has(name) || isNull(name)) {
        return null
    }

    return when (val value = opt(name)) {
        is Boolean -> value
        is String -> value.trim().toBooleanStrictOrNull()
        is Number -> value.toInt() != 0
        else -> null
    }
}

private fun JSONObject.optionalDouble(name: String): Double? {
    if (!has(name) || isNull(name)) {
        return null
    }

    return when (val value = opt(name)) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }
}

fun decodeStormDnsPayload(raw: String): JSONObject {
    val link = raw.trim()
    val prefix = "$StormDnsSchema://"
    if (!link.startsWith(prefix)) {
        throw IllegalArgumentException("Invalid stormdns link")
    }
    val payload = link.removePrefix(prefix).trim()
    if (payload.isBlank()) {
        throw IllegalArgumentException("Profile link is empty")
    }
    val decoded = decodeBase64Payload(payload.substringBefore('#').substringBefore('?'))
    return JSONObject(decoded)
}

private fun decodeBase64Payload(payload: String): String {
    val paddedPayload = payload.padEnd(
        payload.length + ((4 - payload.length % 4) % 4),
        '='
    )

    val bytes = runCatching {
        Base64.decode(
            paddedPayload,
            Base64.URL_SAFE or Base64.NO_WRAP
        )
    }.recoverCatching {
        Base64.decode(
            paddedPayload,
            Base64.NO_WRAP
        )
    }.getOrElse {
        throw IllegalArgumentException("Profile link payload is not valid base64")
    }

    return bytes.toString(Charsets.UTF_8)
}

private fun JSONObject.putOptional(name: String, value: Any?) {
    if (value != null) {
        put(name, value)
    }
}

inline fun <T> JSONObject.optional(
    key: String,
    getter: JSONObject.(String) -> T,
    block: (T) -> Unit
) {
    if (has(key) && !isNull(key)) {
        block(getter(key))
    }
}

private fun JSONObject.mustGetString(name: String): String {
    return optionalString(name)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("$name is not defined")
}

private fun JSONObject.getStringOrDefault(name: String, defaultValue: String): String {
    return optionalString(name)?.takeIf(String::isNotBlank)
        ?: defaultValue
}

private fun JSONObject.mustGetInt(name: String): Int {
    return optionalInt(name) ?: throw IllegalArgumentException("$name is not defined")
}

private fun JSONObject.optionalString(name: String): String? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return opt(name)?.toString()
}

private fun JSONObject.optionalInt(name: String): Int? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return when (val value = opt(name)) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}
