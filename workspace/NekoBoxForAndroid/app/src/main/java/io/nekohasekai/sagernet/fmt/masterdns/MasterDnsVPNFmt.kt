package io.nekohasekai.sagernet.fmt.masterdns

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.SingBoxOptions
import java.io.File

fun MasterDnsVPNBean.buildClientConfigText(): String {
    fun q(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    fun b(value: Boolean) = value.toString()
    val domainList = domains.split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(", ") { q(it) }
    val masterDnsLogLevel = when (DataStore.logLevel) {
        1 -> "WARN"
        2 -> "INFO"
        3, 4 -> "DEBUG"
        else -> "ERROR"
    }
    return """
DOMAINS = [$domainList]
DATA_ENCRYPTION_METHOD = $dataEncryptionMethod
ENCRYPTION_KEY = ${q(encryptionKey)}
PROTOCOL_TYPE = "SOCKS5"
LISTEN_IP = "127.0.0.1"
LISTEN_PORT = 0
SOCKS5_AUTH = false
LOCAL_DNS_ENABLED = false
LOCAL_DNS_CACHE_MAX_RECORDS = $localDNSCacheMaxRecords
LOCAL_DNS_CACHE_TTL_SECONDS = $localDNSCacheTTLSeconds
LOCAL_DNS_PENDING_TIMEOUT_SECONDS = $localDNSPendingTimeoutSeconds
DNS_RESPONSE_FRAGMENT_TIMEOUT_SECONDS = $dnsResponseFragmentTimeoutSeconds
LOCAL_DNS_CACHE_PERSIST_TO_FILE = ${b(localDNSCachePersistToFile)}
LOCAL_DNS_CACHE_FLUSH_INTERVAL_SECONDS = $localDNSCacheFlushIntervalSeconds
RESOLVER_BALANCING_STRATEGY = $resolverBalancingStrategy
PACKET_DUPLICATION_COUNT = $packetDuplicationCount
SETUP_PACKET_DUPLICATION_COUNT = $setupPacketDuplicationCount
STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD = $streamResolverFailoverResendThreshold
STREAM_RESOLVER_FAILOVER_COOLDOWN = $streamResolverFailoverCooldown
RECHECK_INACTIVE_SERVERS_ENABLED = ${b(recheckInactiveServersEnabled)}
AUTO_DISABLE_TIMEOUT_SERVERS = ${b(autoDisableTimeoutServers)}
AUTO_DISABLE_TIMEOUT_WINDOW_SECONDS = $autoDisableTimeoutWindowSeconds
BASE_ENCODE_DATA = ${b(baseEncodeData)}
UPLOAD_COMPRESSION_TYPE = $uploadCompressionType
DOWNLOAD_COMPRESSION_TYPE = $downloadCompressionType
COMPRESSION_MIN_SIZE = $compressionMinSize
MIN_UPLOAD_MTU = $minUploadMTU
MIN_DOWNLOAD_MTU = $minDownloadMTU
MAX_UPLOAD_MTU = $maxUploadMTU
MAX_DOWNLOAD_MTU = $maxDownloadMTU
AUTO_REMOVE_LOW_MTU_SERVERS = ${b(autoRemoveLowMTUServers)}
MTU_TEST_RETRIES = $mtuTestRetries
MTU_TEST_TIMEOUT = $mtuTestTimeout
MTU_TEST_PARALLELISM = $mtuTestParallelism
SAVE_MTU_SERVERS_TO_FILE = false
RX_TX_WORKERS = $rxTxWorkers
TUNNEL_PROCESS_WORKERS = $tunnelProcessWorkers
TUNNEL_PACKET_TIMEOUT_SECONDS = $tunnelPacketTimeoutSeconds
DISPATCHER_IDLE_POLL_INTERVAL_SECONDS = $dispatcherIdlePollIntervalSeconds
RX_CHANNEL_SIZE = $rxChannelSize
SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS = $socksUDPAssociateReadTimeoutSeconds
CLIENT_TERMINAL_STREAM_RETENTION_SECONDS = $clientTerminalStreamRetentionSeconds
CLIENT_CANCELLED_SETUP_RETENTION_SECONDS = $clientCancelledSetupRetentionSeconds
SESSION_INIT_RETRY_BASE_SECONDS = $sessionInitRetryBaseSeconds
SESSION_INIT_RETRY_STEP_SECONDS = $sessionInitRetryStepSeconds
SESSION_INIT_RETRY_LINEAR_AFTER = $sessionInitRetryLinearAfter
SESSION_INIT_RETRY_MAX_SECONDS = $sessionInitRetryMaxSeconds
SESSION_INIT_BUSY_RETRY_INTERVAL_SECONDS = $sessionInitBusyRetryIntervalSeconds
SESSION_INIT_RACING_COUNT = $sessionInitRacingCount
PING_AGGRESSIVE_INTERVAL_SECONDS = $pingAggressiveIntervalSeconds
PING_LAZY_INTERVAL_SECONDS = $pingLazyIntervalSeconds
PING_COOLDOWN_INTERVAL_SECONDS = $pingCooldownIntervalSeconds
PING_COLD_INTERVAL_SECONDS = $pingColdIntervalSeconds
PING_WARM_THRESHOLD_SECONDS = $pingWarmThresholdSeconds
PING_COOL_THRESHOLD_SECONDS = $pingCoolThresholdSeconds
PING_COLD_THRESHOLD_SECONDS = $pingColdThresholdSeconds
MAX_PACKETS_PER_BATCH = $maxPacketsPerBatch
ARQ_WINDOW_SIZE = $arqWindowSize
ARQ_INITIAL_RTO_SECONDS = $arqInitialRTOSeconds
ARQ_MAX_RTO_SECONDS = $arqMaxRTOSeconds
ARQ_CONTROL_INITIAL_RTO_SECONDS = $arqControlInitialRTOSeconds
ARQ_CONTROL_MAX_RTO_SECONDS = $arqControlMaxRTOSeconds
ARQ_MAX_CONTROL_RETRIES = $arqMaxControlRetries
ARQ_INACTIVITY_TIMEOUT_SECONDS = $arqInactivityTimeoutSeconds
ARQ_DATA_PACKET_TTL_SECONDS = $arqDataPacketTTLSeconds
ARQ_CONTROL_PACKET_TTL_SECONDS = $arqControlPacketTTLSeconds
ARQ_MAX_DATA_RETRIES = $arqMaxDataRetries
ARQ_DATA_NACK_MAX_GAP = $arqDataNackMaxGap
ARQ_DATA_NACK_INITIAL_DELAY_SECONDS = $arqDataNackInitialDelaySeconds
ARQ_DATA_NACK_REPEAT_SECONDS = $arqDataNackRepeatSeconds
ARQ_TERMINAL_DRAIN_TIMEOUT_SECONDS = $arqTerminalDrainTimeoutSeconds
ARQ_TERMINAL_ACK_WAIT_TIMEOUT_SECONDS = $arqTerminalAckWaitTimeoutSeconds
LOG_LEVEL = ${q(masterDnsLogLevel)}
""".trimIndent()
}

private fun MasterDnsVPNBean.resolverList(): List<String> {
    return resolvers.lineSequence()
        .flatMap { it.splitToSequence(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()
}

fun buildSingBoxOutboundMasterDnsVPNBean(bean: MasterDnsVPNBean, profileId: Long): SingBoxOptions.Outbound_MasterDnsVPNOptions {
    val profileDir = File(masterDnsVPNProfileCacheRoot(), profileId.toString()).also { it.mkdirs() }
    return SingBoxOptions.Outbound_MasterDnsVPNOptions().apply {
        type = "masterdnsvpn"
        config = bean.buildClientConfigText()
        resolvers = bean.resolverList()
        profile_dir = profileDir.absolutePath
    }
}

fun masterDnsVPNProfileCacheRoot(): File {
    return File(SagerNet.application.cacheDir, "masterdnsvpn_profiles")
}

fun deleteMasterDnsVPNProfileCache(profileId: Long) {
    File(masterDnsVPNProfileCacheRoot(), profileId.toString()).deleteRecursively()
}
