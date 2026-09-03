package io.nekohasekai.sagernet.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

@Composable
internal fun StandardV2RayProfileSettingsScreen(
    isHttp: Boolean,
    isVmess: Boolean,
    isVless: Boolean,
    isTrojan: Boolean,
) {
    var revision by remember { mutableIntStateOf(0) }
    val store = DataStore.profileCacheStore
    val network = store.getString("type").orEmpty()
    val security = store.getString("security").orEmpty()
    val muxMode = store.getString("muxMode")?.toIntOrNull() ?: 0
    val muxEnabled = store.getBoolean("enableMux", false)
    val muxBrutal = store.getBoolean("muxBrutal", false)
    val preferences = remember(revision, isHttp, isVmess, isVless, isTrojan) {
        fun c(title: Int) = CachePreferenceCategory(title)
        fun t(icon: Int, title: Int, key: String, secret: Boolean = false, number: Boolean = false) =
            CacheTextPreference(icon, title, key, secret = secret, number = number)
        fun s(icon: Int, title: Int, key: String, summary: Int? = null) =
            CacheSwitchPreference(icon, title, key, summary = summary)
        fun l(icon: Int, title: Int, key: String, entries: Int, values: Int) =
            CacheListPreference(icon, title, key, entries, values)
        buildList {
            add(t(R.drawable.ic_social_emoji_symbols, R.string.profile_name, "name"))
            add(c(R.string.proxy_cat))
            add(t(R.drawable.ic_hardware_router, R.string.server_address, "serverAddress"))
            add(CacheTextPreference(R.drawable.ic_maps_directions_boat, R.string.server_port,
                "serverPort", number = true, maxLength = 5))
            if (isHttp) {
                add(t(R.drawable.ic_baseline_person_24, R.string.username_opt, "username"))
                add(t(R.drawable.ic_settings_password, R.string.password_opt, "password", secret = true))
            } else {
                add(t(R.drawable.ic_baseline_person_24, if (isTrojan) R.string.password else R.string.uuid,
                    "uuid", secret = true))
            }
            if (isVmess) add(t(R.drawable.ic_maps_360, R.string.alter_id, "alterId", number = true))
            if (isVmess || isVless) {
                if (isVless) add(l(R.drawable.ic_baseline_stream_24, R.string.xtls_flow, "encryption",
                    R.array.xtls_flow_value, R.array.xtls_flow_value))
                else add(l(R.drawable.ic_notification_enhanced_encryption, R.string.encryption, "encryption",
                    R.array.vmess_encryption_entry, R.array.vmess_encryption_value))
            }
            if (isVless) add(t(R.drawable.ic_notification_enhanced_encryption, R.string.vless_encryption,
                "vlessEncryption"))
            if (isVmess || isVless) add(l(R.drawable.baseline_widgets_24, R.string.packet_encoding,
                "packetEncoding", if (isVless) R.array.vless_packet_encoding_entry else R.array.packet_encoding_entry,
                if (isVless) R.array.vless_packet_encoding_value else R.array.int_array_3))
            if (!isHttp) add(l(R.drawable.ic_baseline_compare_arrows_24, R.string.network, "type",
                R.array.networks_entry, R.array.networks_value))

            val hostTitle = when (network) {
                "ws" -> R.string.ws_host
                "httpupgrade" -> R.string.http_upgrade_host
                "xhttp" -> R.string.xhttp_host
                else -> R.string.http_host
            }
            val pathTitle = when (network) {
                "ws" -> R.string.ws_path
                "grpc" -> R.string.grpc_service_name
                "httpupgrade" -> R.string.http_upgrade_path
                "xhttp" -> R.string.xhttp_path
                else -> R.string.http_path
            }
            if (network in setOf("http", "ws", "httpupgrade", "xhttp"))
                add(t(R.drawable.ic_baseline_airplanemode_active_24, hostTitle, "host"))
            if (network in setOf("http", "ws", "grpc", "httpupgrade", "xhttp"))
                add(t(R.drawable.ic_baseline_format_align_left_24, pathTitle, "path"))
            if (network == "kcp") {
                add(l(R.drawable.ic_baseline_texture_24, R.string.kcp_header_type, "headerType",
                    R.array.kcp_headers_entry, R.array.kcp_headers_value))
                add(l(R.drawable.ic_baseline_speed_24, R.string.kcp_cwnd_multiplier, "kcpCwndMultiplier",
                    R.array.kcp_cwnd_multiplier_entry, R.array.kcp_cwnd_multiplier_value))
                add(t(R.drawable.ic_baseline_format_align_left_24, R.string.kcp_seed, "mKcpSeed", secret = true))
                add(t(R.drawable.ic_baseline_tune_24, R.string.kcp_mtu, "kcpMtu", number = true))
                add(t(R.drawable.ic_baseline_speed_24, R.string.kcp_tti, "kcpTti", number = true))
                add(t(R.drawable.ic_baseline_compare_arrows_24, R.string.kcp_max_sending_window,
                    "kcpMaxSendingWindow", number = true))
            }
            add(l(R.drawable.ic_baseline_layers_24, R.string.security, "security",
                R.array.transport_layer_encryption_entry, R.array.transport_layer_encryption_value))

            if (network == "ws") {
                add(c(R.string.cag_ws))
                add(t(R.drawable.ic_baseline_compare_arrows_24, R.string.ws_max_early_data,
                    "wsMaxEarlyData", number = true))
                add(t(R.drawable.ic_baseline_stream_24, R.string.early_data_header_name, "earlyDataHeaderName"))
            }
            if (network == "xhttp") addAll(xhttpPreferences())

            if (security == "tls" || security == "reality") {
                add(c(R.string.security_settings))
                add(t(R.drawable.ic_action_copyright, R.string.sni, "sni"))
                add(t(R.drawable.ic_baseline_legend_toggle_24, R.string.alpn, "alpn"))
                add(t(R.drawable.ic_baseline_vpn_key_24, R.string.certificates, "certificates"))
                add(s(R.drawable.ic_notification_enhanced_encryption, R.string.allow_insecure,
                    "allowInsecure", R.string.allow_insecure_sum))
                add(c(R.string.tls_camouflage_settings))
                add(l(R.drawable.ic_baseline_fingerprint_24, R.string.utls_fingerprint, "utlsFingerprint",
                    R.array.utls_fingerprint_entry, R.array.utls_fingerprint_value))
                add(t(R.drawable.ic_baseline_vpn_key_24, R.string.reality_public_key, "realityPubKey"))
                add(t(R.drawable.ic_baseline_texture_24, R.string.reality_short_id, "realityShortId"))
            }

            add(c(R.string.mux_preference))
            add(s(R.drawable.ic_baseline_compare_arrows_24, R.string.enable_mux, "enableMux", R.string.mux_sum))
            if (muxEnabled) {
                add(l(R.drawable.ic_baseline_stream_24, R.string.mux_type, "muxType", R.array.mux_type, R.array.int_array_4))
                add(l(R.drawable.ic_baseline_tune_24, R.string.mux_mode, "muxMode", R.array.mux_mode, R.array.int_array_2))
                if (muxMode == 0) add(t(R.drawable.ic_baseline_low_priority_24, R.string.mux_concurrency,
                    "muxConcurrency", number = true))
                else {
                    add(t(R.drawable.ic_baseline_low_priority_24, R.string.mux_max_connections,
                        "muxMaxConnections", number = true))
                    add(t(R.drawable.ic_baseline_low_priority_24, R.string.mux_min_streams,
                        "muxMinStreams", number = true))
                }
                add(s(R.drawable.baseline_developer_board_24, R.string.padding, "muxPadding"))
                add(s(R.drawable.ic_baseline_speed_24, R.string.mux_brutal, "muxBrutal"))
                if (muxBrutal) {
                    add(t(R.drawable.ic_baseline_upload_24, R.string.mux_brutal_up_mbps,
                        "muxBrutalUpMbps", number = true))
                    add(t(R.drawable.ic_baseline_download_24, R.string.mux_brutal_down_mbps,
                        "muxBrutalDownMbps", number = true))
                }
            }
            if (security == "tls" || security == "reality") {
                add(c(R.string.ech))
                add(s(R.drawable.ic_baseline_security_24, R.string.enable, "enableECH"))
                add(t(R.drawable.ic_baseline_nfc_24, R.string.ech_config, "echConfig"))
            }
        }
    }
    CacheProfileSettingsScreen(
        preferences = preferences,
        includeTlsOptions = true,
        stateRevision = revision,
        onValueChanged = { key, _ ->
            if (key in setOf("type", "security", "enableMux", "muxMode", "muxBrutal")) revision++
        },
    )
}

private fun xhttpPreferences(): List<CachePreferenceItem> = listOf(
    CachePreferenceCategory(R.string.xhttp_settings),
    CacheListPreference(R.drawable.ic_baseline_stream_24, R.string.xhttp_mode, "xhttpMode",
        R.array.xhttp_mode_entry, R.array.xhttp_mode_value),
    CacheListPreference(R.drawable.ic_baseline_speed_24, R.string.xhttp_congestion_controller, "xhttpCongestionController",
        R.array.xhttp_congestion_controller_entry, R.array.xhttp_congestion_controller_value),
    CacheTextPreference(R.drawable.ic_device_data_usage, R.string.xhttp_cwnd, "xhttpCwnd", number = true),
    CacheTextPreference(R.drawable.ic_baseline_texture_24, R.string.xhttp_headers, "xhttpHeaders"),
    CacheTextPreference(R.drawable.ic_baseline_compress_24, R.string.xhttp_x_padding_bytes, "xhttpXPaddingBytes"),
    CacheSwitchPreference(R.drawable.ic_baseline_stream_24, R.string.xhttp_no_grpc_header, "xhttpNoGrpcHeader"),
    CacheSwitchPreference(R.drawable.ic_baseline_stream_24, R.string.xhttp_no_sse_header, "xhttpNoSseHeader"),
    CacheTextPreference(R.drawable.ic_device_data_usage, R.string.xhttp_sc_max_each_post_bytes, "xhttpScMaxEachPostBytes"),
    CacheTextPreference(R.drawable.ic_baseline_timer_24, R.string.xhttp_sc_min_posts_interval_ms, "xhttpScMinPostsIntervalMs"),
    CacheTextPreference(R.drawable.ic_baseline_low_priority_24, R.string.xhttp_sc_max_buffered_posts, "xhttpScMaxBufferedPosts"),
    CacheTextPreference(R.drawable.ic_baseline_timelapse_24, R.string.xhttp_sc_stream_up_server_secs, "xhttpScStreamUpServerSecs"),
    CacheTextPreference(R.drawable.ic_baseline_compare_arrows_24, R.string.xhttp_xmux_max_concurrency, "xhttpXmuxMaxConcurrency"),
    CacheTextPreference(R.drawable.ic_baseline_compare_arrows_24, R.string.xhttp_xmux_max_connections, "xhttpXmuxMaxConnections"),
    CacheTextPreference(R.drawable.ic_baseline_low_priority_24, R.string.xhttp_xmux_c_max_reuse_times, "xhttpXmuxCMaxReuseTimes"),
    CacheTextPreference(R.drawable.ic_baseline_low_priority_24, R.string.xhttp_xmux_h_max_request_times, "xhttpXmuxHMaxRequestTimes"),
    CacheTextPreference(R.drawable.ic_baseline_low_priority_24, R.string.xhttp_xmux_h_max_reusable_secs, "xhttpXmuxHMaxReusableSecs"),
    CacheTextPreference(R.drawable.ic_baseline_low_priority_24, R.string.xhttp_xmux_h_keep_alive_period, "xhttpXmuxHKeepAlivePeriod"),
    CacheSwitchPreference(R.drawable.ic_baseline_texture_24, R.string.xhttp_padding_obfs_mode, "xhttpPaddingObfsMode"),
    CacheTextPreference(R.drawable.ic_baseline_format_align_left_24, R.string.xhttp_x_padding_key, "xhttpXPaddingKey"),
    CacheTextPreference(R.drawable.ic_baseline_format_align_left_24, R.string.xhttp_x_padding_header, "xhttpXPaddingHeader"),
    CacheListPreference(R.drawable.ic_baseline_push_pin_24, R.string.xhttp_x_padding_placement, "xhttpXPaddingPlacement",
        R.array.xhttp_x_padding_placement_entry, R.array.xhttp_x_padding_placement_value),
    CacheListPreference(R.drawable.ic_baseline_texture_24, R.string.xhttp_padding_method, "xhttpPaddingMethod",
        R.array.xhttp_padding_method_entry, R.array.xhttp_padding_method_value),
    CacheListPreference(R.drawable.ic_baseline_http_24, R.string.xhttp_uplink_http_method, "xhttpUplinkHttpMethod",
        R.array.xhttp_uplink_http_method_entry, R.array.xhttp_uplink_http_method_value),
    CacheListPreference(R.drawable.ic_baseline_upload_24, R.string.xhttp_uplink_data_placement, "xhttpUplinkDataPlacement",
        R.array.xhttp_uplink_data_placement_entry, R.array.xhttp_uplink_data_placement_value),
    CacheTextPreference(R.drawable.ic_baseline_format_align_left_24, R.string.xhttp_uplink_data_key, "xhttpUplinkDataKey"),
    CacheTextPreference(R.drawable.ic_device_data_usage, R.string.xhttp_uplink_chunk_size, "xhttpUplinkChunkSize"),
    CacheListPreference(R.drawable.ic_baseline_push_pin_24, R.string.xhttp_session_placement, "xhttpSessionPlacement",
        R.array.xhttp_session_placement_entry, R.array.xhttp_session_placement_value),
    CacheListPreference(R.drawable.ic_baseline_push_pin_24, R.string.xhttp_session_placement_old, "xhttpSessionPlacementOld",
        R.array.xhttp_session_placement_entry, R.array.xhttp_session_placement_value),
    CacheTextPreference(R.drawable.ic_baseline_format_align_left_24, R.string.xhttp_session_key, "xhttpSessionKey"),
    CacheTextPreference(R.drawable.ic_baseline_format_align_left_24, R.string.xhttp_session_key_old, "xhttpSessionKeyOld"),
    CacheTextPreference(R.drawable.ic_baseline_grid_3x3_24, R.string.xhttp_session_id_table, "xhttpSessionIdTable"),
    CacheTextPreference(R.drawable.ic_baseline_format_align_left_24, R.string.xhttp_session_id_length, "xhttpSessionIdLength"),
    CacheListPreference(R.drawable.ic_baseline_low_priority_24, R.string.xhttp_seq_placement, "xhttpSeqPlacement",
        R.array.xhttp_seq_placement_entry, R.array.xhttp_seq_placement_value),
    CacheTextPreference(R.drawable.ic_baseline_format_align_left_24, R.string.xhttp_seq_key, "xhttpSeqKey"),
    CacheTextPreference(R.drawable.ic_baseline_dns_24, R.string.xhttp_server_max_header_bytes, "xhttpServerMaxHeaderBytes"),
    CacheTextPreference(R.drawable.ic_action_settings, R.string.xhttp_extra, "xhttpExtra"),
)
