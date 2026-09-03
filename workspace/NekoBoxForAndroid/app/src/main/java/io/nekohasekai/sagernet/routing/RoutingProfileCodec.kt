package io.nekohasekai.sagernet.routing

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.common.io.BaseEncoding
import java.nio.charset.StandardCharsets

object RoutingProfileCodec {
    private val gson = Gson()

    fun supports(link: String): Boolean = RoutingProfileFormat.values().any { format ->
        supportedPrefixes(format).any { link.trim().startsWith(it, ignoreCase = true) }
    }

    fun decode(link: String): Pair<RoutingProfileFormat, ExternalRoutingProfile> {
        val trimmed = link.trim()
        val match = RoutingProfileFormat.values().firstNotNullOfOrNull { format ->
            supportedPrefixes(format).firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
                ?.let { format to it }
        } ?: error("Unsupported routing link")
        val (format, matchedPrefix) = match
        val payload = trimmed.substring(matchedPrefix.length).trim()
        require(payload.isNotEmpty()) { "Routing payload is empty" }
        val json = decodeBase64(payload)
        val root = JsonParser.parseString(json)
        require(root.isJsonObject) { "Routing payload must be a JSON object" }
        return format to gson.fromJson(root, ExternalRoutingProfile::class.java)
    }

    fun encode(format: RoutingProfileFormat, profile: ExternalRoutingProfile): String {
        val root = gson.toJsonTree(profile).asJsonObject
        root.getAsJsonArray("Rules")?.forEach { element ->
            val rule = element.asJsonObject
            legacyRuleFields.forEach(rule::remove)
            val isDnsRule = rule.get("Type")?.asString == "dns"
            val fieldsToRemove = if (isDnsRule) normalOnlyRuleFields else dnsOnlyRuleFields
            fieldsToRemove.forEach(rule::remove)
        }
        val json = gson.toJson(root)
        val payload = BaseEncoding.base64().encode(json.toByteArray(StandardCharsets.UTF_8))
        return prefix(format) + payload
    }

    private val normalOnlyRuleFields = setOf("Outbound", "OutboundHash", "CreateDnsRule")
    private val legacyRuleFields = setOf("DnsStrategy")
    private val dnsOnlyRuleFields = setOf(
        "DnsAction",
        "DnsServer",
        "DnsDisableCache",
        "DnsRewriteTtl",
        "DnsClientSubnet",
        "DnsRcode",
        "DnsRejectMethod",
        "DnsPredefinedAnswer",
        "DnsPredefinedNs",
        "DnsPredefinedExtra",
    )

    private fun decodeBase64(value: String): String {
        val normalized = value.filterNot(Char::isWhitespace)
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val bytes = runCatching { BaseEncoding.base64().decode(padded) }
            .recoverCatching { BaseEncoding.base64Url().decode(padded) }
            .getOrElse { error("Invalid Base64 routing payload") }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun prefix(format: RoutingProfileFormat) =
        "${format.scheme}://routing/${if (format == RoutingProfileFormat.NEKOBOX_PLUS) "add" else "onadd"}/"

    private fun supportedPrefixes(format: RoutingProfileFormat) =
        if (format == RoutingProfileFormat.NEKOBOX_PLUS) {
            listOf("sn://routing/add/")
        } else {
            listOf(
                "${format.scheme}://routing/onadd/",
                "${format.scheme}://routing/add/",
            )
        }
}

interface RoutingLinkProcessor {
    val format: RoutingProfileFormat
    fun supports(link: String): Boolean = actions().any { action ->
        link.trim().startsWith("${format.scheme}://routing/$action/", ignoreCase = true)
    }

    fun process(link: String): RoutingImportCandidate {
        val (decodedFormat, profile) = RoutingProfileCodec.decode(link)
        require(decodedFormat == format)
        val candidate = RoutingProfileMapper.toImportCandidate(format, profile)
        if (format != RoutingProfileFormat.NEKOBOX_PLUS) return candidate
        val customDnsServers = profile.customDnsServers
        val customDnsServerTags = customDnsServers.orEmpty().associate { it.id to it.tag }
        return candidate.copy(
            settings = candidate.settings + listOfNotNull(customDnsServers?.let { servers ->
                RoutingImportSetting(
                    RoutingSettingKind.CUSTOM_DNS_SERVERS,
                    servers.joinToString("\n", transform = StableCustomDnsServer::displaySummary),
                )
            }),
            rules = profile.rules.orEmpty().map { rule ->
                RoutingImportRule(
                    fullRule = rule,
                    resolvedDnsServer = rule.dnsServer.tag
                        ?: rule.dnsServer.id?.let(customDnsServerTags::get),
                )
            },
            customDnsServers = customDnsServers,
        )
    }

    private fun actions() = if (format == RoutingProfileFormat.NEKOBOX_PLUS) listOf("add") else listOf("onadd", "add")
}

object HappRoutingLinkProcessor : RoutingLinkProcessor {
    override val format = RoutingProfileFormat.HAPP
}

object IncyRoutingLinkProcessor : RoutingLinkProcessor {
    override val format = RoutingProfileFormat.INCY
}

object V2RayTunRoutingLinkProcessor : RoutingLinkProcessor {
    override val format = RoutingProfileFormat.V2RAY_TUN
}

object NekoBoxPlusRoutingLinkProcessor : RoutingLinkProcessor {
    override val format = RoutingProfileFormat.NEKOBOX_PLUS
}

object RoutingLinkProcessors {
    private val processors = listOf(
        HappRoutingLinkProcessor,
        V2RayTunRoutingLinkProcessor,
        IncyRoutingLinkProcessor,
        NekoBoxPlusRoutingLinkProcessor,
    )
    fun forLink(link: String): RoutingLinkProcessor? = processors.firstOrNull { it.supports(link) }
}
