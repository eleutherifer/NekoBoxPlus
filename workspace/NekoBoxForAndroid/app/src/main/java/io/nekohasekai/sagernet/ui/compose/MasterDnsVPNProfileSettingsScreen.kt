package io.nekohasekai.sagernet.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.nekohasekai.sagernet.R

@Composable
internal fun MasterDnsVPNProfileSettingsScreen(
    stateRevision: Int,
    onPreset: () -> Unit,
    onImportResolvers: () -> Unit,
) {
    val preferences = remember {
        fun c(title: Int) = CachePreferenceCategory(title)
        fun t(icon: Int, title: Int, key: String, summary: Int? = null) =
            CacheTextPreference(icon, title, key, fixedSummary = summary)
        fun n(icon: Int, title: Int, key: String) =
            CacheTextPreference(icon, title, key, number = true)
        fun d(icon: Int, title: Int, key: String) =
            CacheTextPreference(icon, title, key, decimal = true)
        fun s(icon: Int, title: Int, key: String) = CacheSwitchPreference(icon, title, key)
        listOf(
            t(R.drawable.ic_social_emoji_symbols, R.string.profile_name, "name"),
            c(R.string.masterdnsvpn),
            CacheActionPreference(R.drawable.ic_baseline_tune_24, R.string.masterdnsvpn_preset, "preset"),
            t(R.drawable.baseline_public_24, R.string.masterdnsvpn_domains, "domains"),
            t(R.drawable.ic_baseline_vpn_key_24, R.string.masterdnsvpn_encryption_key, "encryptionKey"),
            CacheListPreference(R.drawable.ic_notification_enhanced_encryption, R.string.masterdnsvpn_encryption_method,
                "dataEncryptionMethod", R.array.masterdnsvpn_encryption_method_entry, R.array.masterdnsvpn_encryption_method_value),
            t(R.drawable.ic_action_dns, R.string.masterdnsvpn_dns_resolvers, "resolvers", R.string.masterdnsvpn_dns_resolvers_summary),
            CacheActionPreference(R.drawable.ic_file_file_upload, R.string.masterdnsvpn_import_dns_resolvers, "importResolvers"),
            c(R.string.masterdnsvpn_dns_cache),
            n(R.drawable.ic_baseline_dns_24, R.string.masterdnsvpn_local_dns_cache_max_records, "localDNSCacheMaxRecords"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_local_dns_cache_ttl_seconds, "localDNSCacheTTLSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_local_dns_pending_timeout_seconds, "localDNSPendingTimeoutSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_dns_response_fragment_timeout_seconds, "dnsResponseFragmentTimeoutSeconds"),
            s(R.drawable.ic_baseline_save_24, R.string.masterdnsvpn_local_dns_cache_persist_to_file, "localDNSCachePersistToFile"),
            d(R.drawable.ic_baseline_update_24, R.string.masterdnsvpn_local_dns_cache_flush_interval_seconds, "localDNSCacheFlushIntervalSeconds"),
            c(R.string.masterdnsvpn_resolver_health),
            CacheListPreference(R.drawable.ic_baseline_shuffle_24, R.string.masterdnsvpn_resolver_balancing_strategy,
                "resolverBalancingStrategy", R.array.masterdnsvpn_resolver_balancing_entry, R.array.masterdnsvpn_resolver_balancing_value),
            n(R.drawable.ic_baseline_filter_list_24, R.string.masterdnsvpn_packet_duplication_count, "packetDuplicationCount"),
            n(R.drawable.ic_baseline_filter_list_24, R.string.masterdnsvpn_setup_packet_duplication_count, "setupPacketDuplicationCount"),
            n(R.drawable.ic_baseline_low_priority_24, R.string.masterdnsvpn_stream_resolver_failover_resend_threshold, "streamResolverFailoverResendThreshold"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_stream_resolver_failover_cooldown, "streamResolverFailoverCooldown"),
            s(R.drawable.ic_baseline_refresh_24, R.string.masterdnsvpn_recheck_inactive_servers_enabled, "recheckInactiveServersEnabled"),
            s(R.drawable.ic_baseline_running_with_errors_24, R.string.masterdnsvpn_auto_disable_timeout_servers, "autoDisableTimeoutServers"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_auto_disable_timeout_window_seconds, "autoDisableTimeoutWindowSeconds"),
            s(R.drawable.ic_baseline_security_24, R.string.masterdnsvpn_base_encode_data, "baseEncodeData"),
            c(R.string.masterdnsvpn_compression),
            CacheListPreference(R.drawable.ic_baseline_compress_24, R.string.masterdnsvpn_upload_compression_type,
                "uploadCompressionType", R.array.masterdnsvpn_compression_entry, R.array.masterdnsvpn_compression_value),
            CacheListPreference(R.drawable.ic_baseline_compress_24, R.string.masterdnsvpn_download_compression_type,
                "downloadCompressionType", R.array.masterdnsvpn_compression_entry, R.array.masterdnsvpn_compression_value),
            n(R.drawable.ic_baseline_format_align_left_24, R.string.masterdnsvpn_compression_min_size, "compressionMinSize"),
            c(R.string.masterdnsvpn_mtu_discovery),
            n(R.drawable.ic_baseline_upload_24, R.string.masterdnsvpn_min_upload_mtu, "minUploadMTU"),
            n(R.drawable.ic_baseline_download_24, R.string.masterdnsvpn_min_download_mtu, "minDownloadMTU"),
            n(R.drawable.ic_baseline_upload_24, R.string.masterdnsvpn_max_upload_mtu, "maxUploadMTU"),
            n(R.drawable.ic_baseline_download_24, R.string.masterdnsvpn_max_download_mtu, "maxDownloadMTU"),
            s(R.drawable.ic_baseline_filter_list_24, R.string.masterdnsvpn_auto_remove_low_mtu_servers, "autoRemoveLowMTUServers"),
            n(R.drawable.ic_baseline_refresh_24, R.string.masterdnsvpn_mtu_test_retries, "mtuTestRetries"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_mtu_test_timeout, "mtuTestTimeout"),
            n(R.drawable.ic_baseline_speed_24, R.string.masterdnsvpn_mtu_test_parallelism, "mtuTestParallelism"),
            c(R.string.masterdnsvpn_runtime),
            n(R.drawable.ic_baseline_speed_24, R.string.masterdnsvpn_rx_tx_workers, "rxTxWorkers"),
            n(R.drawable.ic_baseline_speed_24, R.string.masterdnsvpn_tunnel_process_workers, "tunnelProcessWorkers"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_tunnel_packet_timeout_seconds, "tunnelPacketTimeoutSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_dispatcher_idle_poll_interval_seconds, "dispatcherIdlePollIntervalSeconds"),
            n(R.drawable.ic_baseline_view_list_24, R.string.masterdnsvpn_rx_channel_size, "rxChannelSize"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_socks_udp_associate_read_timeout_seconds, "socksUDPAssociateReadTimeoutSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_client_terminal_stream_retention_seconds, "clientTerminalStreamRetentionSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_client_cancelled_setup_retention_seconds, "clientCancelledSetupRetentionSeconds"),
            c(R.string.masterdnsvpn_session_ping),
            d(R.drawable.ic_baseline_refresh_24, R.string.masterdnsvpn_session_init_retry_base_seconds, "sessionInitRetryBaseSeconds"),
            d(R.drawable.ic_baseline_refresh_24, R.string.masterdnsvpn_session_init_retry_step_seconds, "sessionInitRetryStepSeconds"),
            n(R.drawable.ic_baseline_refresh_24, R.string.masterdnsvpn_session_init_retry_linear_after, "sessionInitRetryLinearAfter"),
            d(R.drawable.ic_baseline_refresh_24, R.string.masterdnsvpn_session_init_retry_max_seconds, "sessionInitRetryMaxSeconds"),
            d(R.drawable.ic_baseline_refresh_24, R.string.masterdnsvpn_session_init_busy_retry_interval_seconds, "sessionInitBusyRetryIntervalSeconds"),
            n(R.drawable.ic_baseline_speed_24, R.string.masterdnsvpn_session_init_racing_count, "sessionInitRacingCount"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_ping_aggressive_interval_seconds, "pingAggressiveIntervalSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_ping_lazy_interval_seconds, "pingLazyIntervalSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_ping_cooldown_interval_seconds, "pingCooldownIntervalSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_ping_cold_interval_seconds, "pingColdIntervalSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_ping_warm_threshold_seconds, "pingWarmThresholdSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_ping_cool_threshold_seconds, "pingCoolThresholdSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_ping_cold_threshold_seconds, "pingColdThresholdSeconds"),
            c(R.string.masterdnsvpn_arq),
            n(R.drawable.ic_baseline_view_list_24, R.string.masterdnsvpn_max_packets_per_batch, "maxPacketsPerBatch"),
            n(R.drawable.ic_baseline_view_list_24, R.string.masterdnsvpn_arq_window_size, "arqWindowSize"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_arq_initial_rto_seconds, "arqInitialRTOSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_arq_max_rto_seconds, "arqMaxRTOSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_arq_control_initial_rto_seconds, "arqControlInitialRTOSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_arq_control_max_rto_seconds, "arqControlMaxRTOSeconds"),
            n(R.drawable.ic_baseline_refresh_24, R.string.masterdnsvpn_arq_max_control_retries, "arqMaxControlRetries"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_arq_inactivity_timeout_seconds, "arqInactivityTimeoutSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_arq_data_packet_ttl_seconds, "arqDataPacketTTLSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_arq_control_packet_ttl_seconds, "arqControlPacketTTLSeconds"),
            n(R.drawable.ic_baseline_refresh_24, R.string.masterdnsvpn_arq_max_data_retries, "arqMaxDataRetries"),
            n(R.drawable.ic_baseline_low_priority_24, R.string.masterdnsvpn_arq_data_nack_max_gap, "arqDataNackMaxGap"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_arq_data_nack_initial_delay_seconds, "arqDataNackInitialDelaySeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_arq_data_nack_repeat_seconds, "arqDataNackRepeatSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_arq_terminal_drain_timeout_seconds, "arqTerminalDrainTimeoutSeconds"),
            d(R.drawable.ic_baseline_timer_24, R.string.masterdnsvpn_arq_terminal_ack_wait_timeout_seconds, "arqTerminalAckWaitTimeoutSeconds"),
        )
    }
    CacheProfileSettingsScreen(
        preferences = preferences,
        stateRevision = stateRevision,
        onAction = {
            when (it) {
                "preset" -> onPreset()
                "importResolvers" -> onImportResolvers()
            }
        },
    )
}
