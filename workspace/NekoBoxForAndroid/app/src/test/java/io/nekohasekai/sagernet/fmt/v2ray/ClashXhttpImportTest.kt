package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.group.RawUpdater
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashXhttpImportTest {

    @Test
    fun clashXhttpOptionsPopulateSameGuiFieldsAsVlessExtra() {
        val clashBean = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            applyClashXhttpOptions(this, clashXhttpOptions())
        }

        val vlessExtra = JSONObject()
            .put("headers", JSONObject().put("X-Test", "one"))
            .put("no_grpc_header", true)
            .put("no_sse_header", true)
            .put("x_padding_bytes", "100-1000")
            .put("sc_max_each_post_bytes", "65536-917504")
            .put("sc_min_posts_interval_ms", "30")
            .put("sc_max_buffered_posts", 7)
            .put("sc_stream_up_server_secs", "20-40")
            .put("server_max_header_bytes", 8192)
            .put("x_padding_obfs_mode", true)
            .put("x_padding_key", "xp")
            .put("x_padding_header", "Referer")
            .put("x_padding_placement", "queryInHeader")
            .put("x_padding_method", "tokenish")
            .put("uplink_http_method", "PATCH")
            .put("session_placement", "header")
            .put("session_key", "sid")
            .put("seq_placement", "query")
            .put("seq_key", "seq")
            .put("uplink_data_placement", "header")
            .put("uplink_data_key", "data")
            .put("uplink_chunk_size", 4096)
            .put(
                "xmux",
                JSONObject()
                    .put("max_connections", "2")
                    .put("max_concurrency", "16-32")
                    .put("c_max_reuse_times", "10")
                    .put("h_max_request_times", "600-900")
                    .put("h_max_reusable_secs", "1800-3000")
                    .put("h_keep_alive_period", 0)
            )

        val vlessLink = HttpUrl.Builder()
            .scheme("https")
            .username("00000000-0000-0000-0000-000000000000")
            .host("example.com")
            .port(443)
            .addQueryParameter("type", "xhttp")
            .addQueryParameter("encryption", "none")
            .addQueryParameter("host", "cdn.example.com")
            .addQueryParameter("path", "/xhttp")
            .addQueryParameter("mode", "packet-up")
            .addQueryParameter("extra", vlessExtra.toString())
            .build()
            .toString()
            .replaceFirst("https://", "vless://")

        val vlessBean = VMessBean().apply {
            alterId = -1
            parseDuckSoft(vlessLink.replaceFirst("vless://", "https://").toHttpUrl(), "xhttp")
            initializeDefaultValues()
        }

        assertEquals(vlessBean.host, clashBean.host)
        assertEquals(vlessBean.path, clashBean.path)
        assertEquals(vlessBean.xhttpMode, clashBean.xhttpMode)
        assertEquals(vlessBean.xhttpHeaders, clashBean.xhttpHeaders)
        assertEquals(vlessBean.xhttpNoGrpcHeader, clashBean.xhttpNoGrpcHeader)
        assertEquals(vlessBean.xhttpNoSseHeader, clashBean.xhttpNoSseHeader)
        assertEquals(vlessBean.xhttpXPaddingBytes, clashBean.xhttpXPaddingBytes)
        assertEquals(vlessBean.xhttpScMaxEachPostBytes, clashBean.xhttpScMaxEachPostBytes)
        assertEquals(vlessBean.xhttpScMinPostsIntervalMs, clashBean.xhttpScMinPostsIntervalMs)
        assertEquals(vlessBean.xhttpScMaxBufferedPosts, clashBean.xhttpScMaxBufferedPosts)
        assertEquals(vlessBean.xhttpScStreamUpServerSecs, clashBean.xhttpScStreamUpServerSecs)
        assertEquals(vlessBean.xhttpServerMaxHeaderBytes, clashBean.xhttpServerMaxHeaderBytes)
        assertEquals(vlessBean.xhttpPaddingObfsMode, clashBean.xhttpPaddingObfsMode)
        assertEquals(vlessBean.xhttpXPaddingKey, clashBean.xhttpXPaddingKey)
        assertEquals(vlessBean.xhttpXPaddingHeader, clashBean.xhttpXPaddingHeader)
        assertEquals(vlessBean.xhttpXPaddingPlacement, clashBean.xhttpXPaddingPlacement)
        assertEquals(vlessBean.xhttpPaddingMethod, clashBean.xhttpPaddingMethod)
        assertEquals(vlessBean.xhttpUplinkHttpMethod, clashBean.xhttpUplinkHttpMethod)
        assertEquals(vlessBean.xhttpSessionPlacement, clashBean.xhttpSessionPlacement)
        assertEquals(vlessBean.xhttpSessionKey, clashBean.xhttpSessionKey)
        assertEquals(vlessBean.xhttpSessionPlacementOld, clashBean.xhttpSessionPlacementOld)
        assertEquals(vlessBean.xhttpSessionKeyOld, clashBean.xhttpSessionKeyOld)
        assertEquals(vlessBean.xhttpSeqPlacement, clashBean.xhttpSeqPlacement)
        assertEquals(vlessBean.xhttpSeqKey, clashBean.xhttpSeqKey)
        assertEquals(vlessBean.xhttpUplinkDataPlacement, clashBean.xhttpUplinkDataPlacement)
        assertEquals(vlessBean.xhttpUplinkDataKey, clashBean.xhttpUplinkDataKey)
        assertEquals(vlessBean.xhttpUplinkChunkSize, clashBean.xhttpUplinkChunkSize)
        assertEquals(vlessBean.xhttpXmuxMaxConnections, clashBean.xhttpXmuxMaxConnections)
        assertEquals(vlessBean.xhttpXmuxMaxConcurrency, clashBean.xhttpXmuxMaxConcurrency)
        assertEquals(vlessBean.xhttpXmuxCMaxReuseTimes, clashBean.xhttpXmuxCMaxReuseTimes)
        assertEquals(vlessBean.xhttpXmuxHMaxRequestTimes, clashBean.xhttpXmuxHMaxRequestTimes)
        assertEquals(vlessBean.xhttpXmuxHMaxReusableSecs, clashBean.xhttpXmuxHMaxReusableSecs)
        assertEquals(vlessBean.xhttpXmuxHKeepAlivePeriod, clashBean.xhttpXmuxHKeepAlivePeriod)
    }

    @Test
    fun clashRawImporterParsesMissingXhttpParityFields() = runBlocking {
        val proxies = RawUpdater.parseRaw(
            """
            proxies:
              - name: clash-xhttp
                type: vless
                server: example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000000
                network: xhttp
                tls: true
                xhttp-opts:
                  path: /xhttp
                  host: cdn.example.com
                  mode: packet-up
                  headers:
                    X-Test: one
                  no-sse-header: true
                  sc-max-buffered-posts: 7
                  sc-stream-up-server-secs: 20-40
                  server-max-header-bytes: 8192
            """.trimIndent()
        )

        val bean = proxies!!.single() as VMessBean

        assertEquals("xhttp", bean.type)
        assertEquals("cdn.example.com", bean.host)
        assertEquals("/xhttp", bean.path)
        assertEquals("packet-up", bean.xhttpMode)
        assertEquals("X-Test: one", bean.xhttpHeaders)
        assertEquals(true, bean.xhttpNoSseHeader)
        assertEquals("7", bean.xhttpScMaxBufferedPosts)
        assertEquals("20-40", bean.xhttpScStreamUpServerSecs)
        assertEquals("8192", bean.xhttpServerMaxHeaderBytes)
        assertTrue(bean.xhttpExtra.isNullOrBlank())
    }

    @Test
    fun clashXhttpDownloadSettingsStayInSingBoxExtra() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            type = "xhttp"
            applyClashXhttpOptions(
                this,
                mapOf(
                    "download-settings" to mapOf(
                        "path" to "/down",
                        "host" to "down.example.com",
                        "headers" to mapOf("X-Down" to "yes"),
                        "reuse-settings" to mapOf("max-concurrency" to "8-16"),
                        "server" to "download-server.example.com",
                        "port" to 8443,
                        "tls" to true,
                        "servername" to "sni.example.com",
                        "alpn" to listOf("h2"),
                        "skip-cert-verify" to true,
                        "client-fingerprint" to "chrome",
                        "reality-opts" to mapOf(
                            "public-key" to "pub",
                            "short-id" to "sid",
                        ),
                    )
                )
            )
        }

        val download = JSONObject(bean.xhttpExtra).getJSONObject("download")
        val tls = download.getJSONObject("tls")

        assertEquals("/down", download.getString("path"))
        assertEquals("down.example.com", download.getString("host"))
        assertEquals("yes", download.getJSONObject("headers").getString("X-Down"))
        assertEquals("8-16", download.getJSONObject("xmux").getString("max_concurrency"))
        assertEquals("download-server.example.com", download.getString("server"))
        assertEquals(8443, download.getInt("server_port"))
        assertTrue(tls.getBoolean("enabled"))
        assertEquals("sni.example.com", tls.getString("server_name"))
        assertEquals("h2", tls.getJSONArray("alpn").getString(0))
        assertTrue(tls.getBoolean("insecure"))
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
        assertEquals("pub", tls.getJSONObject("reality").getString("public_key"))
        assertEquals("sid", tls.getJSONObject("reality").getString("short_id"))
        assertFalse(bean.xhttpExtra.contains("downloadSettings"))
    }

    private fun clashXhttpOptions(): Map<String, Any?> {
        return mapOf(
            "host" to "cdn.example.com",
            "path" to "/xhttp",
            "mode" to "packet-up",
            "headers" to mapOf("X-Test" to "one"),
            "no-grpc-header" to true,
            "no-sse-header" to true,
            "x-padding-bytes" to "100-1000",
            "sc-max-each-post-bytes" to "65536-917504",
            "sc-min-posts-interval-ms" to "30",
            "sc-max-buffered-posts" to 7,
            "sc-stream-up-server-secs" to "20-40",
            "server-max-header-bytes" to 8192,
            "x-padding-obfs-mode" to true,
            "x-padding-key" to "xp",
            "x-padding-header" to "Referer",
            "x-padding-placement" to "queryInHeader",
            "x-padding-method" to "tokenish",
            "uplink-http-method" to "PATCH",
            "session-placement" to "header",
            "session-key" to "sid",
            "seq-placement" to "query",
            "seq-key" to "seq",
            "uplink-data-placement" to "header",
            "uplink-data-key" to "data",
            "uplink-chunk-size" to 4096,
            "reuse-settings" to mapOf(
                "max-connections" to "2",
                "max-concurrency" to "16-32",
                "c-max-reuse-times" to "10",
                "h-max-request-times" to "600-900",
                "h-max-reusable-secs" to "1800-3000",
                "h-keep-alive-period" to 0,
            ),
        )
    }
}
