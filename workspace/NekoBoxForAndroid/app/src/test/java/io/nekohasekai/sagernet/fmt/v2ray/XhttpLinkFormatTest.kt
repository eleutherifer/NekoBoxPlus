package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.KryoConverters
import moe.matsuri.nb4a.SingBoxOptions.V2RayTransportOptions_XHTTPOptions
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XhttpLinkFormatTest {

    @Test
    fun congestionFieldsRoundTripThroughPersistenceAndLink() {
        val original = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            serverAddress = "example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000000"
            xhttpCongestionController = "cubic"
            xhttpCwnd = "64"
            name = "XHTTP congestion"
        }

        val restored = KryoConverters.deserialize(
            VMessBean(),
            KryoConverters.serialize(original),
        )
        assertEquals("cubic", restored.xhttpCongestionController)
        assertEquals("64", restored.xhttpCwnd)

        restored.name = ""
        val exportedExtra = JSONObject(exportedVlessXhttpExtra(restored))
        assertEquals("cubic", exportedExtra.getString("congestion_controller"))
        assertEquals(64, exportedExtra.getInt("cwnd"))

        val reparsed = parseVlessXhttpExtra(exportedExtra.toString())
        assertEquals("cubic", reparsed.xhttpCongestionController)
        assertEquals("64", reparsed.xhttpCwnd)
    }

    @Test
    fun singBoxXhttpTransportUsesCongestionFields() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            serverAddress = "example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000000"
            xhttpCongestionController = "reno"
            xhttpCwnd = "48"
        }

        val transport = buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_XHTTPOptions

        assertEquals("reno", transport.congestion_controller)
        assertEquals(48, transport.cwnd)
    }

    @Test
    fun vlessXhttpLinkRoundTripPreservesSingBoxOnlyExtraFields() {
        val extra = JSONObject()
            .put("xPaddingBytes", "100-1000")
            .put("domain_strategy", "prefer_ipv4")
            .put("trusted_x_forwarded_for", listOf("10.0.0.0/8", "192.168.0.0/16"))
            .put("unknown_passthrough", "kept")
            .toString()

        val link = HttpUrl.Builder()
            .scheme("https")
            .username("00000000-0000-0000-0000-000000000000")
            .host("example.com")
            .port(443)
            .addQueryParameter("type", "xhttp")
            .addQueryParameter("encryption", "none")
            .addQueryParameter("host", "cdn.example.com")
            .addQueryParameter("path", "/xhttp")
            .addQueryParameter("mode", "packet-up")
            .addQueryParameter("extra", extra)
            .build()
            .toString()
            .replaceFirst("https://", "vless://")

        val bean = VMessBean().apply {
            alterId = -1
            parseDuckSoft(link.replaceFirst("vless://", "https://").toHttpUrl(), "xhttp")
        }
        bean.initializeDefaultValues()
        bean.name = ""

        val exported = bean.toUriVMessVLESSTrojan(false)
        val exportedUrl = exported.replaceFirst("vless://", "https://").toHttpUrl()
        val exportedExtra = JSONObject(exportedUrl.queryParameter("extra")!!)

        assertEquals("xhttp", exportedUrl.queryParameter("type"))
        assertEquals("packet-up", exportedUrl.queryParameter("mode"))
        assertEquals("100-1000", exportedExtra.getString("xPaddingBytes"))
        assertEquals("prefer_ipv4", exportedExtra.getString("domain_strategy"))
        assertEquals("10.0.0.0/8", exportedExtra.getJSONArray("trusted_x_forwarded_for").getString(0))
        assertEquals("192.168.0.0/16", exportedExtra.getJSONArray("trusted_x_forwarded_for").getString(1))
        assertEquals("kept", exportedExtra.getString("unknown_passthrough"))
    }

    @Test
    fun singBoxXhttpTransportIncludesSingBoxOnlyExtraFields() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            serverAddress = "example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000000"
            host = "cdn.example.com"
            path = "/xhttp"
            xhttpMode = "packet-up"
            xhttpExtra = JSONObject()
                .put("domain_strategy", "prefer_ipv4")
                .put("trusted_x_forwarded_for", listOf("10.0.0.0/8"))
                .toString()
        }

        val transport = buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_XHTTPOptions

        assertEquals("prefer_ipv4", transport.domain_strategy)
        assertTrue(transport.trusted_x_forwarded_for.isJsonArray)
        assertEquals("10.0.0.0/8", transport.trusted_x_forwarded_for.asJsonArray[0].asString)
    }

    @Test
    fun singBoxXhttpPacketUpTransportKeepsOmittedUploadLimitsUnset() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            serverAddress = "example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000000"
            path = "/xhttp"
            xhttpMode = "packet-up"
        }

        val transport = buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_XHTTPOptions

        assertNull(transport.sc_max_each_post_bytes)
        assertNull(transport.sc_max_buffered_posts)
    }

    @Test
    fun singBoxXhttpPacketUpTransportKeepsExplicitUploadLimits() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            serverAddress = "example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000000"
            path = "/xhttp"
            xhttpMode = "packet-up"
            xhttpExtra = JSONObject()
                .put("sc_max_each_post_bytes", "1048576-1048576")
                .put("sc_max_buffered_posts", 9)
                .toString()
        }

        val transport = buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_XHTTPOptions

        assertEquals("1048576-1048576", transport.sc_max_each_post_bytes.asString)
        assertEquals(9, transport.sc_max_buffered_posts.asLong)
    }

    @Test
    fun xrayToSingBoxIgnoresNullXmuxObject() {
        val xrayExtra = JSONObject()
            .put("xPaddingBytes", "100-1000")
            .put("xmux", JSONObject.NULL)
            .toString()

        val singBoxExtra = JSONObject(XhttpExtraConverter.xrayToSingBox(xrayExtra))

        assertEquals("100-1000", singBoxExtra.getString("x_padding_bytes"))
        assertEquals(false, singBoxExtra.has("xmux"))
    }

    @Test
    fun xrayToSingBoxIgnoresNullDownloadSettingsObject() {
        val xrayExtra = JSONObject()
            .put("downloadSettings", JSONObject.NULL)
            .toString()

        val singBoxExtra = JSONObject(XhttpExtraConverter.xrayToSingBox(xrayExtra))

        assertEquals(0, singBoxExtra.length())
    }

    @Test
    fun xrayToSingBoxIgnoresNullLiteralInput() {
        assertEquals("", XhttpExtraConverter.xrayToSingBox("null"))
    }

    @Test
    fun extractSupportedToGuiIgnoresNullLiteralInput() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
        }

        assertEquals("", XhttpExtraConverter.extractSupportedToGui(bean, "null"))
    }

    @Test
    fun vlessXhttpLinkIgnoresNullExtraQueryParameter() {
        val link = HttpUrl.Builder()
            .scheme("https")
            .username("00000000-0000-0000-0000-000000000000")
            .host("example.com")
            .port(443)
            .addQueryParameter("type", "xhttp")
            .addQueryParameter("encryption", "none")
            .addQueryParameter("extra", "null")
            .build()
            .toString()
            .replaceFirst("https://", "vless://")

        val bean = VMessBean().apply {
            alterId = -1
            parseDuckSoft(link.replaceFirst("vless://", "https://").toHttpUrl(), "xhttp")
        }

        assertTrue(bean.xhttpExtra.isNullOrBlank())
    }

    @Test
    fun singBoxXhttpTransportIgnoresNullLiteralExtra() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            serverAddress = "example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000000"
            path = "/xhttp"
            xhttpExtra = "null"
        }

        val transport = buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_XHTTPOptions

        assertEquals("xhttp", transport.type)
    }

    @Test
    fun vlessXhttpLinkKeepsLegacySessionKeysWhenTableAndLengthAreAbsent() {
        val bean = parseVlessXhttpExtra(
            JSONObject()
                .put("sessionPlacement", "header")
                .put("sessionKey", "X-Legacy-Session")
                .toString()
        )

        assertEquals("", bean.xhttpSessionPlacement)
        assertEquals("", bean.xhttpSessionKey)
        assertEquals("header", bean.xhttpSessionPlacementOld)
        assertEquals("X-Legacy-Session", bean.xhttpSessionKeyOld)

        val exportedExtra = JSONObject(exportedVlessXhttpExtra(bean))

        assertEquals("header", exportedExtra.getString("sessionPlacement"))
        assertEquals("X-Legacy-Session", exportedExtra.getString("sessionKey"))
        assertEquals(false, exportedExtra.has("sessionIDPlacement"))
        assertEquals(false, exportedExtra.has("sessionIDKey"))
    }

    @Test
    fun vlessXhttpLinkKeepsLegacyAndNewSessionKeysSeparately() {
        val bean = parseVlessXhttpExtra(
            JSONObject()
                .put("xmux", JSONObject().put("maxConcurrency", "8-32"))
                .put("seqKey", "part_index")
                .put("sessionKey", "stream_auth")
                .put("seqPlacement", "cookie")
                .put("sessionIDKey", "viewer_session")
                .put("sessionPlacement", "cookie")
                .put("sessionIDPlacement", "query")
                .toString()
        )

        assertEquals("query", bean.xhttpSessionPlacement)
        assertEquals("viewer_session", bean.xhttpSessionKey)
        assertEquals("cookie", bean.xhttpSessionPlacementOld)
        assertEquals("stream_auth", bean.xhttpSessionKeyOld)

        val exportedExtra = JSONObject(exportedVlessXhttpExtra(bean))

        assertEquals("query", exportedExtra.getString("sessionIDPlacement"))
        assertEquals("viewer_session", exportedExtra.getString("sessionIDKey"))
        assertEquals("cookie", exportedExtra.getString("sessionPlacement"))
        assertEquals("stream_auth", exportedExtra.getString("sessionKey"))
    }

    @Test
    fun vlessXhttpLinkImportsUppercaseSessionIDKeysAndExportsNewFormat() {
        val bean = parseVlessXhttpExtra(
            JSONObject()
                .put("sessionIDPlacement", "query")
                .put("sessionIDKey", "sid")
                .put("sessionIDTable", "Base62")
                .put("sessionIDLength", "6-8")
                .toString()
        )

        assertEquals("query", bean.xhttpSessionPlacement)
        assertEquals("sid", bean.xhttpSessionKey)
        assertEquals("Base62", bean.xhttpSessionIdTable)
        assertEquals("6-8", bean.xhttpSessionIdLength)

        val exportedExtra = JSONObject(exportedVlessXhttpExtra(bean))

        assertEquals("query", exportedExtra.getString("sessionIDPlacement"))
        assertEquals("sid", exportedExtra.getString("sessionIDKey"))
        assertEquals("Base62", exportedExtra.getString("sessionIDTable"))
        assertEquals("6-8", exportedExtra.getString("sessionIDLength"))
        assertEquals(false, exportedExtra.has("sessionPlacement"))
        assertEquals(false, exportedExtra.has("sessionKey"))
    }

    @Test
    fun vlessXhttpLinkImportsLowercaseSessionIdKeysAndExportsNewFormat() {
        val bean = parseVlessXhttpExtra(
            JSONObject()
                .put("sessionIdPlacement", "cookie")
                .put("sessionIdKey", "sid")
                .put("sessionIdTable", "number")
                .put("sessionIdLength", 12)
                .toString()
        )

        assertEquals("cookie", bean.xhttpSessionPlacement)
        assertEquals("sid", bean.xhttpSessionKey)
        assertEquals("number", bean.xhttpSessionIdTable)
        assertEquals("12", bean.xhttpSessionIdLength)

        val exportedExtra = JSONObject(exportedVlessXhttpExtra(bean))

        assertEquals("cookie", exportedExtra.getString("sessionIDPlacement"))
        assertEquals("sid", exportedExtra.getString("sessionIDKey"))
        assertEquals("number", exportedExtra.getString("sessionIDTable"))
        assertEquals("12", exportedExtra.getString("sessionIDLength"))
    }

    @Test
    fun singBoxXhttpTransportUsesNewSessionIDKeys() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            serverAddress = "example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000000"
            path = "/xhttp"
            xhttpSessionPlacement = "header"
            xhttpSessionKey = "X-Session-ID"
            xhttpSessionIdTable = "Base62"
            xhttpSessionIdLength = "6"
        }

        val transport = buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_XHTTPOptions

        assertEquals("header", transport.session_id_placement)
        assertEquals("X-Session-ID", transport.session_id_key)
        assertEquals("Base62", transport.session_id_table)
        assertEquals("6", transport.session_id_length.asString)
        assertEquals(null, transport.session_placement)
        assertEquals(null, transport.session_key)
    }

    @Test
    fun singBoxXhttpTransportKeepsLegacyAndNewSessionKeysSeparately() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            serverAddress = "example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000000"
            path = "/xhttp"
            xhttpSessionPlacement = "query"
            xhttpSessionKey = "viewer_session"
            xhttpSessionPlacementOld = "cookie"
            xhttpSessionKeyOld = "stream_auth"
        }

        val transport = buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_XHTTPOptions

        assertEquals("query", transport.session_id_placement)
        assertEquals("viewer_session", transport.session_id_key)
        assertEquals("cookie", transport.session_placement)
        assertEquals("stream_auth", transport.session_key)
    }

    private fun parseVlessXhttpExtra(extra: String): VMessBean {
        val link = HttpUrl.Builder()
            .scheme("https")
            .username("00000000-0000-0000-0000-000000000000")
            .host("example.com")
            .port(443)
            .addQueryParameter("type", "xhttp")
            .addQueryParameter("encryption", "none")
            .addQueryParameter("extra", extra)
            .build()
            .toString()
            .replaceFirst("https://", "vless://")

        return VMessBean().apply {
            alterId = -1
            parseDuckSoft(link.replaceFirst("vless://", "https://").toHttpUrl(), "xhttp")
            initializeDefaultValues()
            name = ""
        }
    }

    private fun exportedVlessXhttpExtra(bean: VMessBean): String {
        val exported = bean.toUriVMessVLESSTrojan(false)
        val exportedUrl = exported.replaceFirst("vless://", "https://").toHttpUrl()
        return exportedUrl.queryParameter("extra")!!
    }
}
