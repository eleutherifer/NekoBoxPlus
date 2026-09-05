package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RuleType
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.routing.RoutingImportCandidate
import io.nekohasekai.sagernet.routing.RoutingImportManager
import io.nekohasekai.sagernet.routing.RoutingImportRule
import io.nekohasekai.sagernet.routing.RoutingImportSetting
import io.nekohasekai.sagernet.routing.RoutingImportWarning
import io.nekohasekai.sagernet.routing.RoutingPreviewPayloadStore
import io.nekohasekai.sagernet.routing.RoutingProfileFormat
import io.nekohasekai.sagernet.routing.RoutingRuleKind
import io.nekohasekai.sagernet.routing.RoutingSettingKind
import io.nekohasekai.sagernet.routing.StableRoutingOutbound
import io.nekohasekai.sagernet.routing.StableRoutingRule
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.RoutingImportPreviewData
import io.nekohasekai.sagernet.ui.compose.RoutingImportPreviewScreen
import io.nekohasekai.sagernet.ui.compose.RoutingImportPreviewSetting
import io.nekohasekai.sagernet.ui.compose.RoutingImportPreviewRule
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RoutingImportPreviewActivity : ThemedActivity() {
    companion object {
        const val EXTRA_PAYLOAD_TOKEN = "routing_payload_token"
    }

    private lateinit var token: String
    private lateinit var candidate: RoutingImportCandidate
    private var previewData by mutableStateOf<RoutingImportPreviewData?>(null)
    private var selectedSettings by mutableStateOf<Set<RoutingSettingKind>>(emptySet())
    private var selectedRules by mutableStateOf<Set<Int>>(emptySet())
    private var importing by mutableStateOf(false)
    private var downloadingAssets by mutableStateOf(false)
    private var showOverwriteConfirmation by mutableStateOf(false)
    private var fatalErrorMessage by mutableStateOf<String?>(null)
    private var importErrorMessage by mutableStateOf<String?>(null)
    private var showReconnectConfirmation by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra(EXTRA_PAYLOAD_TOKEN).orEmpty()
        setContent {
            NekoComposeTheme {
                RoutingImportPreviewScreen(
                    data = previewData,
                    selectedSettings = selectedSettings,
                    selectedRules = selectedRules,
                    importing = importing,
                    downloadingAssets = downloadingAssets,
                    showOverwriteConfirmation = showOverwriteConfirmation,
                    fatalErrorMessage = fatalErrorMessage,
                    importErrorMessage = importErrorMessage,
                    showReconnectConfirmation = showReconnectConfirmation,
                    onClose = ::finish,
                    onSettingChecked = ::setSettingChecked,
                    onRuleChecked = ::setRuleChecked,
                    onImport = { showOverwriteConfirmation = true },
                    onDismissConfirmation = { showOverwriteConfirmation = false },
                    onConfirmImport = {
                        showOverwriteConfirmation = false
                        performImport()
                    },
                    onDismissFatalError = ::finish,
                    onDismissImportError = { importErrorMessage = null },
                    onReconnect = { reconnect ->
                        showReconnectConfirmation = false
                        if (reconnect) SagerNet.reloadService()
                        finish()
                    },
                )
            }
        }
        loadCandidate()
    }

    override fun onDestroy() {
        if (isFinishing && ::token.isInitialized) RoutingPreviewPayloadStore.remove(this, token)
        super.onDestroy()
    }

    private fun loadCandidate() {
        lifecycleScope.launch {
            val loadedCandidate = withContext(Dispatchers.IO) {
                RoutingPreviewPayloadStore.get(this@RoutingImportPreviewActivity, token)
            }
            if (loadedCandidate == null) {
                fatalErrorMessage = getString(R.string.routing_import_payload_missing)
                return@launch
            }
            candidate = loadedCandidate
            previewData = withContext(Dispatchers.Default) { candidate.previewData() }
            selectedSettings = candidate.settings.mapTo(linkedSetOf()) { it.kind }
            selectedRules = candidate.rules.indices.toSet()
        }
    }

    private fun setSettingChecked(kind: RoutingSettingKind, checked: Boolean) {
        selectedSettings = if (checked) selectedSettings + kind else selectedSettings - kind
    }

    private fun setRuleChecked(index: Int, checked: Boolean) {
        selectedRules = if (checked) selectedRules + index else selectedRules - index
    }

    private fun performImport() {
        val changedAssets = RoutingImportManager.pendingAssetChanges(candidate, selectedSettings)
        importing = true
        downloadingAssets = changedAssets.isNotEmpty()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val applied = RoutingImportManager.apply(
                        this@RoutingImportPreviewActivity,
                        candidate,
                        selectedSettings,
                        selectedRules,
                        changedAssets,
                    )
                    RoutingImportManager.refreshAssets(this@RoutingImportPreviewActivity, applied.changedAssetUrls)
                }
            }
            downloadingAssets = false
            result.onSuccess {
                RoutingPreviewPayloadStore.remove(this@RoutingImportPreviewActivity, token)
                if (DataStore.serviceState.started) {
                    showReconnectConfirmation = true
                } else {
                    finish()
                }
            }.onFailure {
                importing = false
                importErrorMessage = it.readableMessage
            }
        }
    }

    private fun RoutingImportCandidate.previewData(): RoutingImportPreviewData {
        if (isNekoBoxPlus && rules.any { it.fullRule?.packages?.isNotEmpty() == true }) {
            PackageCache.awaitLoadSync()
        }
        return RoutingImportPreviewData(
            name = name.ifBlank { getString(R.string.routing_import_unnamed) },
            source = getString(R.string.routing_import_source, format.label()),
            settings = settings.map {
                RoutingImportPreviewSetting(it.kind, it.title(), it.summary(), isNekoBoxPlus)
            },
            warnings = warnings.map { warning ->
                getString(when (warning) {
                    RoutingImportWarning.UNSUPPORTED_XRAY_VALUES ->
                        R.string.routing_import_warning_xray_values
                })
            },
            rules = rules.mapIndexed { index, rule ->
                RoutingImportPreviewRule(
                    index,
                    rule.title(format),
                    rule.summary(),
                    isNekoBoxPlus,
                )
            },
        )
    }

    private fun RoutingImportSetting.title(): String = getString(when (kind) {
        RoutingSettingKind.REMOTE_DNS -> R.string.remote_dns
        RoutingSettingKind.DIRECT_DNS -> R.string.direct_dns
        RoutingSettingKind.GEO_ASSETS -> R.string.route_rules_provider
        RoutingSettingKind.DNS_HOSTS -> R.string.dns_domain_overrides
        RoutingSettingKind.FAKE_DNS -> R.string.enable_fakedns
        RoutingSettingKind.DOMAIN_STRATEGY -> R.string.resolve_destination
        RoutingSettingKind.CUSTOM_DNS_SERVERS -> R.string.custom_dns_servers
    })

    private fun RoutingImportSetting.summary(): String = when (kind) {
        RoutingSettingKind.GEO_ASSETS -> "$value\n${secondaryValue.orEmpty()}"
        RoutingSettingKind.FAKE_DNS, RoutingSettingKind.DOMAIN_STRATEGY ->
            getString(if (value.toBoolean()) R.string.enable else R.string.disable)
        RoutingSettingKind.CUSTOM_DNS_SERVERS -> value.ifBlank {
            getString(R.string.custom_dns_servers_empty)
        }
        else -> value
    }

    private fun RoutingImportRule.title(format: RoutingProfileFormat): String = when (kind) {
        RoutingRuleKind.DIRECT_SITES -> getString(R.string.routing_import_direct_sites, format.label())
        RoutingRuleKind.DIRECT_IP -> getString(R.string.routing_import_direct_ips, format.label())
        RoutingRuleKind.PROXY_SITES -> getString(R.string.routing_import_proxy_sites, format.label())
        RoutingRuleKind.PROXY_IP -> getString(R.string.routing_import_proxy_ips, format.label())
        RoutingRuleKind.BLOCK_SITES -> getString(R.string.routing_import_block_sites, format.label())
        RoutingRuleKind.BLOCK_IP -> getString(R.string.routing_import_block_ips, format.label())
        RoutingRuleKind.EVERYTHING_DIRECT -> getString(R.string.routing_import_everything_direct)
        null -> fullRule?.name?.takeIf(String::isNotBlank)
            ?: getString(R.string.routing_import_unnamed_rule)
    }

    private fun RoutingImportRule.summary(): String {
        fullRule?.let { rule ->
            return buildList {
                if (RuleType.fromValue(rule.type) == RuleType.NORMAL) {
                    val outboundName = when (rule.outbound) {
                        StableRoutingOutbound.DIRECT -> getString(R.string.route_bypass)
                        StableRoutingOutbound.BLOCK -> getString(R.string.route_block)
                        StableRoutingOutbound.CUSTOM -> resolvedOutboundName?.takeIf(String::isNotBlank)
                            ?: getString(R.string.route_proxy)
                        else -> getString(R.string.route_proxy)
                    }
                    add(getString(R.string.routing_import_rule_outbound, outboundName))
                }
                addAll(rule.definitionLines(resolvedDnsServer))
                if (outboundFallback) add(getString(R.string.routing_import_warning_outbound_fallback))
            }.joinToString("\n")
        }
        return if (kind == RoutingRuleKind.EVERYTHING_DIRECT) {
            getString(R.string.routing_import_all_ports)
        } else {
            values.joinToString("\n")
        }
    }

    private fun StableRoutingRule.definitionLines(resolvedDnsServer: String?): List<String> = buildList {
        val isDnsRule = RuleType.fromValue(type) == RuleType.DNS
        fun addValue(@StringRes label: Int, value: String?, default: String = "") {
            if (!value.isNullOrBlank() && value != default) {
                add(getString(R.string.routing_import_rule_definition, getString(label), value))
            }
        }
        addValue(R.string.dns_server_type, typeLabel())
        addValue(R.string.routing_import_rule_enabled, yesNo(enabled))
        addValue(R.string.custom_config, config)
        addValue(R.string.domain, domains)
        addValue(R.string.destination_ip, ip)
        addValue(R.string.destination_port, port)
        addValue(R.string.source_port, sourcePort)
        addValue(R.string.network_type, networkType.joinToString { networkTypeLabel(it) })
        addValue(R.string.wifi_ssid, wifiSsid)
        addValue(R.string.wifi_bssid, wifiBssid)
        addValue(R.string.network, network)
        addValue(R.string.source_ip, source)
        addValue(
            R.string.protocol,
            arrayEntry(R.array.route_sniff_protocol_entry, R.array.route_sniff_protocol_value, protocol),
        )
        addValue(R.string.routing_import_ruleset, ruleset)
        addValue(R.string.clash_mode, clashMode)
        addValue(R.string.apps, packages.joinToString { PackageCache.loadLabel(it) })
        if (!isDnsRule) addValue(R.string.create_dns_rule, yesNo(createDnsRule))
        if (!isDnsRule) return@buildList
        addValue(
            R.string.dns_rule_action,
            arrayEntry(R.array.dns_rule_action_entry, R.array.dns_rule_action_value, dnsAction),
        )
        addValue(
            R.string.dns_rule_server,
            arrayEntry(
                R.array.dns_rule_server_entry,
                R.array.dns_rule_server_value,
                resolvedDnsServer ?: dnsServer.displayValue(),
            ),
        )
        addValue(R.string.dns_disable_cache, yesNo(dnsDisableCache))
        addValue(R.string.dns_rewrite_ttl, dnsRewriteTtl.toString(), "0")
        addValue(R.string.dns_client_subnet, dnsClientSubnet)
        addValue(R.string.dns_rcode, dnsRcode)
        addValue(
            R.string.dns_reject_method,
            arrayEntry(R.array.dns_reject_method_entry, R.array.dns_reject_method_value, dnsRejectMethod),
        )
        addValue(R.string.dns_predefined_answer, dnsPredefinedAnswer)
        addValue(R.string.dns_predefined_ns, dnsPredefinedNs)
        addValue(R.string.dns_predefined_extra, dnsPredefinedExtra)
    }

    private fun StableRoutingRule.typeLabel() = when (type) {
        "dns" -> getString(R.string.dns_rule)
        "normal" -> getString(R.string.route_normal)
        else -> type
    }

    private fun yesNo(value: Boolean) = getString(if (value) R.string.yes else R.string.no)

    private fun networkTypeLabel(value: String) =
        arrayEntry(R.array.route_network_type_entry, R.array.route_network_type_value, value)

    private fun arrayEntry(@ArrayRes entriesRes: Int, @ArrayRes valuesRes: Int, value: String): String {
        val values = resources.getStringArray(valuesRes)
        val index = values.indexOf(value)
        return resources.getStringArray(entriesRes).getOrNull(index) ?: value
    }

    private val RoutingImportCandidate.isNekoBoxPlus
        get() = format == RoutingProfileFormat.NEKOBOX_PLUS

    private fun RoutingProfileFormat.label() = getString(
        when (this) {
            RoutingProfileFormat.HAPP -> R.string.routing_format_happ
            RoutingProfileFormat.V2RAY_TUN -> R.string.routing_format_v2ray_tun
            RoutingProfileFormat.INCY -> R.string.routing_format_incy
            RoutingProfileFormat.NEKOBOX_PLUS -> R.string.routing_format_nekobox_plus
        },
    )
}
