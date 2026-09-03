package io.nekohasekai.sagernet.fmt.v2ray

import org.json.JSONObject

object XhttpExtraConverter {

    private val xhttpFieldMap = linkedMapOf(
        "headers" to "headers",
        "xPaddingBytes" to "x_padding_bytes",
        "scMaxEachPostBytes" to "sc_max_each_post_bytes",
        "scMinPostsIntervalMs" to "sc_min_posts_interval_ms",
        "noGRPCHeader" to "no_grpc_header",
        "noSSEHeader" to "no_sse_header",
        "scMaxBufferedPosts" to "sc_max_buffered_posts",
        "scStreamUpServerSecs" to "sc_stream_up_server_secs",
        "uplinkDataPlacement" to "uplink_data_placement",
        "uplinkDataKey" to "uplink_data_key",
        "uplinkChunkSize" to "uplink_chunk_size",
        "uplinkHTTPMethod" to "uplink_http_method",
        "seqPlacement" to "seq_placement",
        "seqKey" to "seq_key",
        "xPaddingObfsMode" to "x_padding_obfs_mode",
        "xPaddingKey" to "x_padding_key",
        "xPaddingHeader" to "x_padding_header",
        "xPaddingPlacement" to "x_padding_placement",
        "xPaddingMethod" to "x_padding_method",
        "serverMaxHeaderBytes" to "server_max_header_bytes",
    )

    private val xraySessionIdFieldAliases = linkedMapOf(
        "sessionIDPlacement" to "session_id_placement",
        "sessionIDKey" to "session_id_key",
        "sessionIDTable" to "session_id_table",
        "sessionIDLength" to "session_id_length",
        "sessionIdPlacement" to "session_id_placement",
        "sessionIdKey" to "session_id_key",
        "sessionIdTable" to "session_id_table",
        "sessionIdLength" to "session_id_length",
    )

    private val legacyXraySessionFieldMap = linkedMapOf(
        "sessionPlacement" to "session_placement",
        "sessionKey" to "session_key",
    )

    private val legacySingBoxSessionFieldMap = linkedMapOf(
        "session_placement" to "session_placement",
        "session_key" to "session_key",
    )

    private val xmuxFieldMap = linkedMapOf(
        "maxConcurrency" to "max_concurrency",
        "maxConnections" to "max_connections",
        "cMaxReuseTimes" to "c_max_reuse_times",
        "hMaxRequestTimes" to "h_max_request_times",
        "hMaxReusableSecs" to "h_max_reusable_secs",
        "hKeepAlivePeriod" to "h_keep_alive_period",
    )

    private val singBoxOnlyFieldNames = setOf(
        "domain_strategy",
        "trusted_x_forwarded_for",
        "congestion_controller",
        "cwnd",
    )

    fun extractSupportedToGui(bean: StandardV2RayBean, rawExtra: String?): String {
        val normalizedExtra = rawExtra.takeUnlessNullJsonLiteral() ?: return ""
        return try {
            val singBoxExtra = xrayToSingBox(normalizedExtra)
            if (singBoxExtra.isBlank() || singBoxExtra.isJsonNullLiteral()) return ""

            val extra = JSONObject(singBoxExtra)

            extra.optJSONObject("headers")?.let { headers ->
                bean.xhttpHeaders = jsonObjectToXhttpHeaders(headers)
                extra.remove("headers")
            }
            extractStringField(extra, "uplink_data_placement") { bean.xhttpUplinkDataPlacement = it }
            extractStringField(extra, "session_id_placement") { bean.xhttpSessionPlacement = it }
            extractStringField(extra, "session_placement") { bean.xhttpSessionPlacementOld = it }
            extractStringField(extra, "x_padding_method") { bean.xhttpPaddingMethod = it }
            extractBooleanField(extra, "x_padding_obfs_mode") { bean.xhttpPaddingObfsMode = it }
            extractBooleanField(extra, "no_grpc_header") { bean.xhttpNoGrpcHeader = it }
            extractBooleanField(extra, "no_sse_header") { bean.xhttpNoSseHeader = it }
            extractXmuxField(extra, "x_padding_bytes") { bean.xhttpXPaddingBytes = it }
            extractXmuxField(extra, "sc_max_each_post_bytes") { bean.xhttpScMaxEachPostBytes = it }
            extractXmuxField(extra, "sc_min_posts_interval_ms") { bean.xhttpScMinPostsIntervalMs = it }
            extractStringField(extra, "sc_max_buffered_posts") { bean.xhttpScMaxBufferedPosts = it }
            extractXmuxField(extra, "sc_stream_up_server_secs") { bean.xhttpScStreamUpServerSecs = it }
            extractXmuxField(extra, "uplink_chunk_size") { bean.xhttpUplinkChunkSize = it }
            extractStringField(extra, "server_max_header_bytes") { bean.xhttpServerMaxHeaderBytes = it }
            extractStringField(extra, "congestion_controller") { bean.xhttpCongestionController = it }
            extractStringField(extra, "cwnd") { bean.xhttpCwnd = it }
            extractStringField(extra, "x_padding_key") { bean.xhttpXPaddingKey = it }
            extractStringField(extra, "x_padding_header") { bean.xhttpXPaddingHeader = it }
            extractStringField(extra, "x_padding_placement") { bean.xhttpXPaddingPlacement = it }
            extractStringField(extra, "uplink_http_method") { bean.xhttpUplinkHttpMethod = it }
            extractStringField(extra, "uplink_data_key") { bean.xhttpUplinkDataKey = it }
            extractStringField(extra, "session_id_key") { bean.xhttpSessionKey = it }
            extractStringField(extra, "session_key") { bean.xhttpSessionKeyOld = it }
            extractStringField(extra, "session_id_table") { bean.xhttpSessionIdTable = it }
            extractXmuxField(extra, "session_id_length") { bean.xhttpSessionIdLength = it }
            extractStringField(extra, "seq_placement") { bean.xhttpSeqPlacement = it }
            extractStringField(extra, "seq_key") { bean.xhttpSeqKey = it }

            extra.optJSONObject("xmux")?.let { xmux ->
                extractXmuxField(xmux, "max_concurrency") { bean.xhttpXmuxMaxConcurrency = it }
                extractXmuxField(xmux, "max_connections") { bean.xhttpXmuxMaxConnections = it }
                extractXmuxField(xmux, "c_max_reuse_times") { bean.xhttpXmuxCMaxReuseTimes = it }
                extractXmuxField(xmux, "h_max_request_times") { bean.xhttpXmuxHMaxRequestTimes = it }
                extractXmuxField(xmux, "h_max_reusable_secs") { bean.xhttpXmuxHMaxReusableSecs = it }
                extractXmuxField(xmux, "h_keep_alive_period") { bean.xhttpXmuxHKeepAlivePeriod = it }
                if (xmux.length() == 0) extra.remove("xmux")
            }

            if (extra.length() == 0) "" else extra.toString(2).replace("\\/", "/")
        } catch (e: Exception) {
            e.printStackTrace()
            xrayToSingBox(normalizedExtra)
        }
    }

    fun xrayToSingBox(xrayExtra: String?): String {
        val normalizedExtra = xrayExtra.takeUnlessNullJsonLiteral() ?: return ""
        return try {
            val xray = JSONObject(normalizedExtra)
            if (isSingBoxFormat(xray)) return normalizedExtra
            val singBox = JSONObject()

            convertXhttpExtraFields(xray, singBox, toSingBox = true)

            xray.optNonNullJSONObject("xmux")?.let { xrayXmux ->
                val singBoxXmux = JSONObject()
                convertXmuxFields(xrayXmux, singBoxXmux, toSingBox = true)
                copyUnknownFields(xrayXmux, singBoxXmux, xmuxFieldMap.keys)
                if (singBoxXmux.length() > 0) singBox.put("xmux", singBoxXmux)
            }

            xray.optNonNullJSONObject("downloadSettings")?.let { xrayDown ->
                val singBoxDown = JSONObject()

                xrayDown.optJSONObject("xhttpSettings")?.let { xhttpSettings ->
                    convertField(xhttpSettings, singBoxDown, "mode", "mode")
                    convertField(xhttpSettings, singBoxDown, "host", "host")
                    convertField(xhttpSettings, singBoxDown, "path", "path")
                }
                convertField(xrayDown, singBoxDown, "address", "server")
                convertField(xrayDown, singBoxDown, "port", "server_port")

                if (xrayDown.has("security")) {
                    val tls = JSONObject().apply { put("enabled", true) }

                    when (xrayDown.getString("security")) {
                        "tls" -> {
                            xrayDown.optJSONObject("tlsSettings")?.let { tlsSettings ->
                                convertField(tlsSettings, tls, "serverName", "server_name")
                                convertField(tlsSettings, tls, "alpn", "alpn")
                                convertField(tlsSettings, tls, "allowInsecure", "insecure")
                                tlsSettings.optString("fingerprint")?.let { fp ->
                                    if (fp.isNotBlank()) {
                                        val utls = JSONObject().apply {
                                            put("enabled", true)
                                            put("fingerprint", fp)
                                        }
                                        tls.put("utls", utls)
                                    }
                                }
                            }
                        }
                        "reality" -> {
                            xrayDown.optJSONObject("realitySettings")?.let { realitySettings ->
                                convertField(realitySettings, tls, "serverName", "server_name")
                                val reality = JSONObject().apply {
                                    put("enabled", true)
                                    convertField(realitySettings, this, "publicKey", "public_key")
                                    convertField(realitySettings, this, "shortId", "short_id")
                                }
                                tls.put("reality", reality)
                                realitySettings.optString("fingerprint")?.let { fp ->
                                    if (fp.isNotBlank()) {
                                        val utls = JSONObject().apply {
                                            put("enabled", true)
                                            put("fingerprint", fp)
                                        }
                                        tls.put("utls", utls)
                                    }
                                }
                            }
                        }
                    }
                    singBoxDown.put("tls", tls)
                }

                xrayDown.optJSONObject("xhttpSettings")?.optJSONObject("extra")?.let { extra ->
                    extra.optNonNullJSONObject("xmux")?.let { xrayXmux ->
                        val downXmux = JSONObject()
                        convertXmuxFields(xrayXmux, downXmux, toSingBox = true)
                        copyUnknownFields(xrayXmux, downXmux, xmuxFieldMap.keys)
                        if (downXmux.length() > 0) singBoxDown.put("xmux", downXmux)
                    }

                    convertXhttpExtraFields(extra, singBoxDown, toSingBox = true)
                    copyUnknownFields(extra, singBoxDown, xrayHandledXhttpKeys() + "xmux")
                }

                if (singBoxDown.length() > 0) singBox.put("download", singBoxDown)
            }

            copyUnknownFields(xray, singBox, xrayHandledXhttpKeys() + "xmux" + "downloadSettings")
            singBox.toString(2).replace("\\/", "/")
        } catch (e: Exception) {
            e.printStackTrace()
            normalizedExtra
        }
    }

    fun singBoxToXray(singBoxExtra: String?): String {
        val normalizedExtra = singBoxExtra.takeUnlessNullJsonLiteral() ?: return ""
        return try {
            val singBox = JSONObject(normalizedExtra)
            if (isXrayFormat(singBox)) return normalizedExtra
            val xray = JSONObject()

            convertXhttpExtraFields(singBox, xray, toSingBox = false)

            singBox.optNonNullJSONObject("xmux")?.let { singBoxXmux ->
                val xrayXmux = JSONObject()
                convertXmuxFields(singBoxXmux, xrayXmux, toSingBox = false)
                copyUnknownFields(singBoxXmux, xrayXmux, xmuxFieldMap.values)
                if (xrayXmux.length() > 0) xray.put("xmux", xrayXmux)
            }

            singBox.optNonNullJSONObject("download")?.let { singBoxDown ->
                val xrayDown = JSONObject()

                convertField(singBoxDown, xrayDown, "server", "address")
                convertField(singBoxDown, xrayDown, "server_port", "port")
                xrayDown.put("network", "xhttp")

                singBoxDown.optNonNullJSONObject("tls")?.let { tls ->

                    val reality = tls.optNonNullJSONObject("reality")
                    if (reality?.optBoolean("enabled", false) == true) {
                        xrayDown.put("security", "reality")
                        val realitySettings = JSONObject()
                        convertField(tls, realitySettings, "server_name", "serverName")
                        convertField(reality, realitySettings, "public_key", "publicKey")
                        convertField(reality, realitySettings, "short_id", "shortId")
                        tls.optNonNullJSONObject("utls")?.let { utls ->
                            convertField(utls, realitySettings, "fingerprint", "fingerprint")
                        }
                        xrayDown.put("realitySettings", realitySettings)
                    } else {
                        xrayDown.put("security", "tls")
                        val tlsSettings = JSONObject()
                        convertField(tls, tlsSettings, "server_name", "serverName")
                        convertField(tls, tlsSettings, "alpn", "alpn")
                        convertField(tls, tlsSettings, "insecure", "allowInsecure")
                        tls.optNonNullJSONObject("utls")?.let { utls ->
                            convertField(utls, tlsSettings, "fingerprint", "fingerprint")
                        }
                        xrayDown.put("tlsSettings", tlsSettings)
                    }
                }

                val xhttpSettings = JSONObject()
                convertField(singBoxDown, xhttpSettings, "mode", "mode")
                convertField(singBoxDown, xhttpSettings, "host", "host")
                convertField(singBoxDown, xhttpSettings, "path", "path")

                val xhttpExtra = JSONObject()
                convertXhttpExtraFields(singBoxDown, xhttpExtra, toSingBox = false)

                singBoxDown.optNonNullJSONObject("xmux")?.let { singBoxDownXmux ->
                    val xrayDownXmux = JSONObject()
                    convertXmuxFields(singBoxDownXmux, xrayDownXmux, toSingBox = false)
                    copyUnknownFields(singBoxDownXmux, xrayDownXmux, xmuxFieldMap.values)
                    if (xrayDownXmux.length() > 0) xhttpExtra.put("xmux", xrayDownXmux)
                }

                if (xhttpExtra.length() > 0) xhttpSettings.put("extra", xhttpExtra)
                xrayDown.put("xhttpSettings", xhttpSettings)

                if (xrayDown.length() > 0) xray.put("downloadSettings", xrayDown)
            }

            copyUnknownFields(singBox, xray, singBoxHandledXhttpKeys() + "xmux" + "download")
            xray.toString(2).replace("\\/", "/")
        } catch (e: Exception) {
            e.printStackTrace()
            normalizedExtra
        }
    }

    private fun isSingBoxFormat(json: JSONObject): Boolean {
        return json.hasNonNull("download") || singBoxHandledXhttpKeys()
            .filter { it != "headers" }
            .any { json.hasNonNull(it) } || singBoxOnlyFieldNames.any { json.hasNonNull(it) }
    }

    private fun isXrayFormat(json: JSONObject): Boolean {
        return json.hasNonNull("downloadSettings") || xrayFormatXhttpKeys()
            .filter { it != "headers" }
            .any { json.hasNonNull(it) }
    }

    private fun convertField(from: JSONObject, to: JSONObject, fromKey: String, toKey: String) {
        if (from.has(fromKey) && !from.isNull(fromKey)) {
            to.put(toKey, from.get(fromKey))
        }
    }

    private fun convertXhttpExtraFields(from: JSONObject, to: JSONObject, toSingBox: Boolean) {
        if (toSingBox) {
            xhttpFieldMap.forEach { (xrayKey, singBoxKey) ->
                convertField(from, to, xrayKey, singBoxKey)
            }
            xraySessionIdFieldAliases.forEach { (xrayKey, singBoxKey) ->
                convertField(from, to, xrayKey, singBoxKey)
            }
            legacyXraySessionFieldMap.forEach { (xrayKey, singBoxKey) ->
                convertField(from, to, xrayKey, singBoxKey)
            }
            legacySingBoxSessionFieldMap.forEach { (legacyKey, singBoxKey) ->
                convertField(from, to, legacyKey, singBoxKey)
            }
        } else {
            xhttpFieldMap.forEach { (xrayKey, singBoxKey) ->
                if (!singBoxKey.startsWith("session_id_")) {
                    convertField(from, to, singBoxKey, xrayKey)
                }
            }
            convertSingBoxSessionFieldsToXray(from, to)
        }
    }

    private fun convertSingBoxSessionFieldsToXray(from: JSONObject, to: JSONObject) {
        convertField(from, to, "session_placement", "sessionPlacement")
        convertField(from, to, "session_key", "sessionKey")
        convertField(from, to, "session_id_placement", "sessionIDPlacement")
        convertField(from, to, "session_id_key", "sessionIDKey")
        convertField(from, to, "session_id_table", "sessionIDTable")
        convertField(from, to, "session_id_length", "sessionIDLength")
    }

    private fun xrayFormatXhttpKeys(): Set<String> {
        return xhttpFieldMap.keys + legacyXraySessionFieldMap.keys + xraySessionIdFieldAliases.keys
    }

    private fun xrayHandledXhttpKeys(): Set<String> {
        return xrayFormatXhttpKeys() + legacySingBoxSessionFieldMap.keys
    }

    private fun singBoxHandledXhttpKeys(): Set<String> {
        return xhttpFieldMap.values.toSet() + xraySessionIdFieldAliases.values + legacySingBoxSessionFieldMap.keys
    }

    private fun convertXmuxFields(from: JSONObject, to: JSONObject, toSingBox: Boolean) {
        xmuxFieldMap.forEach { (xrayKey, singBoxKey) ->
            if (toSingBox) {
                convertField(from, to, xrayKey, singBoxKey)
            } else {
                convertField(from, to, singBoxKey, xrayKey)
            }
        }
    }

    private fun copyUnknownFields(from: JSONObject, to: JSONObject, handledKeys: Collection<String>) {
        from.keys().forEach { key ->
            if (key !in handledKeys && !to.has(key) && !from.isNull(key)) {
                to.put(key, from.get(key))
            }
        }
    }

    private fun extractStringField(json: JSONObject, key: String, assign: (String) -> Unit) {
        if (!json.has(key)) return
        assign(json.optString(key, ""))
        json.remove(key)
    }

    private fun extractBooleanField(json: JSONObject, key: String, assign: (Boolean) -> Unit) {
        if (!json.has(key)) return
        parseBoolean(json.get(key))?.let {
            assign(it)
            json.remove(key)
        }
    }

    private fun extractXmuxField(json: JSONObject, key: String, assign: (String) -> Unit) {
        if (!json.has(key)) return
        formatXmuxValue(json.get(key))?.let {
            assign(it)
            json.remove(key)
        }
    }

    private fun parseBoolean(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when {
                value.equals("true", ignoreCase = true) -> true
                value.equals("false", ignoreCase = true) -> false
                value == "1" -> true
                value == "0" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun formatXmuxValue(value: Any?): String? {
        return when (value) {
            JSONObject.NULL -> null
            is JSONObject -> {
                val from = value.optLongOrNull("from")
                val to = value.optLongOrNull("to")
                when {
                    from == null || to == null -> null
                    from == to -> from.toString()
                    else -> "$from-$to"
                }
            }
            null -> null
            else -> value.toString()
        }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key)) return null
        val value = get(key)
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.optNonNullJSONObject(key: String): JSONObject? {
        if (isNull(key)) return null
        return optJSONObject(key)
    }

    private fun JSONObject.hasNonNull(key: String): Boolean {
        return has(key) && !isNull(key)
    }

    private fun String?.takeUnlessNullJsonLiteral(): String? {
        if (isNullOrBlank()) return null
        return takeUnless { it.isJsonNullLiteral() }
    }

    private fun String.isJsonNullLiteral(): Boolean {
        return trim().equals("null", ignoreCase = true)
    }
}
