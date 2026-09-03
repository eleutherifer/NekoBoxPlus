package io.nekohasekai.sagernet.routing

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

enum class RoutingProfileFormat(val scheme: String) {
    HAPP("happ"),
    V2RAY_TUN("v2raytun"),
    INCY("incy"),
    NEKOBOX_PLUS("sn"),
}

data class ExternalRoutingProfile(
    @SerializedName("Name") var name: String? = null,
    @SerializedName("GlobalProxy") var globalProxy: String? = null,
    @SerializedName("RemoteDNSType") var remoteDnsType: String? = null,
    @SerializedName("RemoteDNSDomain") var remoteDnsDomain: String? = null,
    @SerializedName("RemoteDNSIP") var remoteDnsIp: String? = null,
    @SerializedName("RemoteDns") var remoteDns: String? = null,
    @SerializedName("DomesticDNSType") var domesticDnsType: String? = null,
    @SerializedName("DomesticDNSDomain") var domesticDnsDomain: String? = null,
    @SerializedName("DomesticDNSIP") var domesticDnsIp: String? = null,
    @SerializedName("DomesticDns") var domesticDns: String? = null,
    @SerializedName("Geoipurl") var geoipUrl: String? = null,
    @SerializedName("Geositeurl") var geositeUrl: String? = null,
    @SerializedName("DnsHosts") var dnsHosts: Map<String, String>? = null,
    @SerializedName("DirectSites") var directSites: List<String>? = null,
    @SerializedName("DirectIp") var directIp: List<String>? = null,
    @SerializedName("ProxySites") var proxySites: List<String>? = null,
    @SerializedName("ProxyIp") var proxyIp: List<String>? = null,
    @SerializedName("BlockSites") var blockSites: List<String>? = null,
    @SerializedName("BlockIp") var blockIp: List<String>? = null,
    @SerializedName("DomainStrategy") var domainStrategy: String? = null,
    @SerializedName("FakeDNS") var fakeDns: String? = null,
    @SerializedName("RouteOrder") var routeOrder: String? = null,
    @SerializedName("Rules") var rules: List<StableRoutingRule>? = null,
    @SerializedName("CustomDNSServers") var customDnsServers: List<StableCustomDnsServer>? = null,
    @SerializedName("LastUpdated") var lastUpdated: Long? = null,
)

/** Stable wire representation used exclusively by NekoBox+ routing links. */
data class StableCustomDnsServer(
    @SerializedName("Id") var id: Long = 0L,
    @SerializedName("Tag") var tag: String = "",
    @SerializedName("Type") var type: String = "udp",
    @SerializedName("Enabled") var enabled: Boolean = true,
    @SerializedName("Server") var server: String = "",
    @SerializedName("ServerPort") var serverPort: Int = 0,
    @SerializedName("Path") var path: String = "",
    @SerializedName("Method") var method: String = "",
    @SerializedName("Headers") var headers: String = "",
    @SerializedName("DomainResolver") var domainResolver: String = "",
    @SerializedName("DomainStrategy") var domainStrategy: String = "",
    @SerializedName("DisableCache") var disableCache: Boolean = false,
    @SerializedName("RewriteTtl") var rewriteTtl: Int = 0,
    @SerializedName("ClientSubnet") var clientSubnet: String = "",
    @SerializedName("Detour") var detour: String = "",
    @SerializedName("BindInterface") var bindInterface: String = "",
    @SerializedName("Inet4BindAddress") var inet4BindAddress: String = "",
    @SerializedName("Inet6BindAddress") var inet6BindAddress: String = "",
    @SerializedName("ConnectTimeout") var connectTimeout: Long = 0L,
    @SerializedName("TcpFastOpen") var tcpFastOpen: Boolean = false,
    @SerializedName("TcpMultiPath") var tcpMultiPath: Boolean = false,
    @SerializedName("UdpFragment") var udpFragment: String = "",
    @SerializedName("TlsServerName") var tlsServerName: String = "",
    @SerializedName("TlsInsecure") var tlsInsecure: Boolean = false,
    @SerializedName("TlsAlpn") var tlsAlpn: String = "",
    @SerializedName("TlsCertificates") var tlsCertificates: String = "",
    @SerializedName("LocalPreferGo") var localPreferGo: Boolean = false,
) {
    fun displaySummary(): String = buildString {
        append(tag)
        append('\n')
        append(type)
        if (server.isNotBlank()) append("://").append(server)
        if (serverPort > 0) append(":").append(serverPort)
        if (path.isNotBlank()) append(path)
    }
}

/** A custom server ID is encoded as a JSON number; built-in and legacy tags stay strings. */
@JsonAdapter(value = StableDnsServerReferenceAdapter::class, nullSafe = false)
data class StableDnsServerReference(
    val id: Long? = null,
    val tag: String? = null,
) {
    companion object {
        fun id(value: Long) = StableDnsServerReference(id = value)
        fun tag(value: String) = StableDnsServerReference(tag = value)
    }

    fun displayValue(): String = tag ?: id?.toString().orEmpty()
}

class StableDnsServerReferenceAdapter : TypeAdapter<StableDnsServerReference>() {
    override fun write(out: JsonWriter, value: StableDnsServerReference?) {
        when {
            value == null -> out.nullValue()
            value.id != null -> out.value(value.id)
            else -> out.value(value.tag.orEmpty())
        }
    }

    override fun read(input: JsonReader): StableDnsServerReference = when (input.peek()) {
        JsonToken.NULL -> input.nextNull().let { StableDnsServerReference.tag("") }
        JsonToken.NUMBER -> StableDnsServerReference.id(input.nextLong())
        JsonToken.STRING -> StableDnsServerReference.tag(input.nextString())
        else -> throw IllegalStateException("DnsServer must be a string or number")
    }
}

/** Stable wire representation used exclusively by NekoBox+ routing links. */
data class StableRoutingRule(
    @SerializedName("Type") var type: String = "normal",
    @SerializedName("Name") var name: String = "",
    @SerializedName("Config") var config: String = "",
    @SerializedName("Enabled") var enabled: Boolean = false,
    @SerializedName("Domains") var domains: String = "",
    @SerializedName("Ip") var ip: String = "",
    @SerializedName("Port") var port: String = "",
    @SerializedName("SourcePort") var sourcePort: String = "",
    @SerializedName("NetworkType") var networkType: Set<String> = emptySet(),
    @SerializedName("WifiSsid") var wifiSsid: String = "",
    @SerializedName("WifiBssid") var wifiBssid: String = "",
    @SerializedName("Network") var network: String = "",
    @SerializedName("Source") var source: String = "",
    @SerializedName("Protocol") var protocol: String = "",
    @SerializedName("Ruleset") var ruleset: String = "",
    @SerializedName("ClashMode") var clashMode: String = "",
    @SerializedName("Outbound") var outbound: String = StableRoutingOutbound.PROXY,
    @SerializedName("OutboundHash") var outboundHash: String? = null,
    @SerializedName("Packages") var packages: Set<String> = emptySet(),
    @SerializedName("CreateDnsRule") var createDnsRule: Boolean = true,
    @SerializedName("DnsAction") var dnsAction: String = "route",
    @SerializedName("DnsServer") var dnsServer: StableDnsServerReference = StableDnsServerReference.tag(""),
    @SerializedName("DnsStrategy") var dnsStrategy: String = "",
    @SerializedName("DnsDisableCache") var dnsDisableCache: Boolean = false,
    @SerializedName("DnsRewriteTtl") var dnsRewriteTtl: Int = 0,
    @SerializedName("DnsClientSubnet") var dnsClientSubnet: String = "",
    @SerializedName("DnsRcode") var dnsRcode: String = "NOERROR",
    @SerializedName("DnsRejectMethod") var dnsRejectMethod: String = "",
    @SerializedName("DnsPredefinedAnswer") var dnsPredefinedAnswer: String = "",
    @SerializedName("DnsPredefinedNs") var dnsPredefinedNs: String = "",
    @SerializedName("DnsPredefinedExtra") var dnsPredefinedExtra: String = "",
)

object StableRoutingOutbound {
    const val PROXY = "proxy"
    const val DIRECT = "direct"
    const val BLOCK = "block"
    const val CUSTOM = "custom"
}

enum class RoutingSettingKind {
    REMOTE_DNS,
    DIRECT_DNS,
    GEO_ASSETS,
    DNS_HOSTS,
    FAKE_DNS,
    DOMAIN_STRATEGY,
    CUSTOM_DNS_SERVERS,
}

data class RoutingImportSetting(
    val kind: RoutingSettingKind,
    val value: String,
    val secondaryValue: String? = null,
    val provider: Int? = null,
)

enum class RoutingRuleKind(val category: String, val outbound: Long) {
    DIRECT_SITES("direct", -1L),
    DIRECT_IP("direct", -1L),
    PROXY_SITES("proxy", 0L),
    PROXY_IP("proxy", 0L),
    BLOCK_SITES("block", -2L),
    BLOCK_IP("block", -2L),
    EVERYTHING_DIRECT("direct", -1L),
}

data class RoutingImportRule(
    val kind: RoutingRuleKind? = null,
    val values: List<String> = emptyList(),
    val fullRule: StableRoutingRule? = null,
    val resolvedOutbound: Long? = null,
    val resolvedOutboundName: String? = null,
    val outboundFallback: Boolean = false,
    val resolvedDnsServer: String? = null,
)

enum class RoutingImportWarning {
    UNSUPPORTED_XRAY_VALUES,
}

data class RoutingImportCandidate(
    val format: RoutingProfileFormat,
    val name: String,
    val settings: List<RoutingImportSetting>,
    val rules: List<RoutingImportRule>,
    val customDnsServers: List<StableCustomDnsServer>? = null,
    val warnings: Set<RoutingImportWarning> = emptySet(),
)

data class RoutingExportResult(
    val link: String,
    val warnings: Set<RoutingExportWarning>,
)

enum class RoutingExportWarning {
    UNSUPPORTED_RULES,
    SIMPLIFIED_ORDER,
    DNS_VALUES_OMITTED,
    DNS_HOST_VALUES_OMITTED,
    CUSTOM_OUTBOUND_FALLBACK,
}
