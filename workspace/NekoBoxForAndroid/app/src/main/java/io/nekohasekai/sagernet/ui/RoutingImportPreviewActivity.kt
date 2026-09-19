package io.nekohasekai.sagernet.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RuleType
import io.nekohasekai.sagernet.databinding.LayoutRoutingImportPreviewBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
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
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RoutingImportPreviewActivity : ThemedActivity() {
    companion object {
        const val EXTRA_PAYLOAD_TOKEN = "routing_payload_token"
    }

    private lateinit var binding: LayoutRoutingImportPreviewBinding
    private lateinit var token: String
    private lateinit var candidate: RoutingImportCandidate
    private val settingChecks = linkedMapOf<RoutingSettingKind, MaterialCheckBox>()
    private val ruleChecks = linkedMapOf<Int, MaterialCheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutRoutingImportPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.routing_import_preview)
            setDisplayHomeAsUpEnabled(true)
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.previewActions, ListListener)

        token = intent.getStringExtra(EXTRA_PAYLOAD_TOKEN).orEmpty()
        candidate = RoutingPreviewPayloadStore.get(this, token) ?: run {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.error_title)
                .setMessage(R.string.routing_import_payload_missing)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .show()
            return
        }
        renderCandidate()
        binding.cancel.setOnClickListener { finish() }
        binding.importRouting.setOnClickListener { confirmImport() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        if (isFinishing && ::token.isInitialized) RoutingPreviewPayloadStore.remove(this, token)
        super.onDestroy()
    }

    private fun renderCandidate() {
        if (candidate.isNekoBoxPlus && candidate.rules.any { it.fullRule?.packages?.isNotEmpty() == true }) {
            PackageCache.awaitLoadSync()
        }
        addHeading(candidate.name.ifBlank { getString(R.string.routing_import_unnamed) })
        addCaption(getString(R.string.routing_import_source, candidate.format.label()))
        if (candidate.settings.isNotEmpty()) addSection(R.string.routing_import_settings)
        candidate.settings.forEach { setting ->
            addCheck(setting.title(), setting.summary(), candidate.isNekoBoxPlus)
                .also { settingChecks[setting.kind] = it }
        }
        candidate.warnings.forEach { warning ->
            addWarning(getString(when (warning) {
                RoutingImportWarning.UNSUPPORTED_XRAY_VALUES -> R.string.routing_import_warning_xray_values
            }))
        }
        if (candidate.rules.isNotEmpty()) addSection(R.string.routing_import_rules)
        candidate.rules.forEachIndexed { index, rule ->
            addCheck(rule.title(candidate.format), rule.summary(), candidate.isNekoBoxPlus)
                .also { ruleChecks[index] = it }
        }
    }

    private fun confirmImport() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm)
            .setMessage(R.string.routing_import_overwrite_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_import_routing) { _, _ -> performImport() }
            .show()
    }

    private fun performImport() {
        val selectedSettings = settingChecks.filterValues { it.isChecked }.keys
        val selectedRules = ruleChecks.filterValues { it.isChecked }.keys
        val changedAssets = RoutingImportManager.pendingAssetChanges(candidate, selectedSettings)
        val progressDialog = if (changedAssets.isNotEmpty()) {
            val progress = LayoutProgressBinding.inflate(layoutInflater)
            progress.content.setText(R.string.routing_import_downloading_resources)
            MaterialAlertDialogBuilder(this)
                .setView(progress.root)
                .setCancelable(false)
                .show()
        } else {
            null
        }
        binding.importRouting.isEnabled = false
        binding.cancel.isEnabled = false
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
            progressDialog?.dismiss()
            result.onSuccess {
                RoutingPreviewPayloadStore.remove(this@RoutingImportPreviewActivity, token)
                if (DataStore.serviceState.started) showReconnectPrompt() else finish()
            }.onFailure {
                binding.importRouting.isEnabled = true
                binding.cancel.isEnabled = true
                MaterialAlertDialogBuilder(this@RoutingImportPreviewActivity)
                    .setTitle(R.string.routing_import_applied_download_failed)
                    .setMessage(it.readableMessage)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showReconnectPrompt() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.routing_import_complete)
            .setMessage(R.string.routing_import_reconnect)
            .setNegativeButton(R.string.no) { _, _ -> finish() }
            .setPositiveButton(R.string.yes) { _, _ ->
                SagerNet.reloadService()
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun addHeading(text: String) = addText(text, 22f, View.TEXT_ALIGNMENT_VIEW_START)
    private fun addCaption(text: String) = addText(text, 14f, View.TEXT_ALIGNMENT_VIEW_START)
    private fun addSection(title: Int) = addText(getString(title), 18f, View.TEXT_ALIGNMENT_VIEW_START).apply {
        setPadding(0, resources.getDimensionPixelSize(R.dimen.mtrl_card_spacing), 0, 0)
    }

    private fun addText(text: String, size: Float, alignment: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        textAlignment = alignment
        binding.previewContent.addView(this)
    }

    private fun addWarning(text: String) = addText(text, 14f, View.TEXT_ALIGNMENT_VIEW_START).apply {
        setTextColor(MaterialColors.getColor(this, R.attr.colorError))
    }

    private fun addCheck(
        title: String,
        summary: String,
        mutedSummary: Boolean,
    ): MaterialCheckBox = MaterialCheckBox(this).apply {
        text = SpannableStringBuilder(title).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (summary.isNotBlank()) {
                append('\n')
                val summaryStart = length
                append(summary)
                if (mutedSummary) {
                    setSpan(
                        ForegroundColorSpan(
                            MaterialColors.getColor(
                                this@RoutingImportPreviewActivity,
                                com.google.android.material.R.attr.colorOnSurfaceVariant,
                                0,
                            ),
                        ),
                        summaryStart,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
        isChecked = true
        setPadding(0, 8, 0, 8)
        binding.previewContent.addView(this)
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
        addValue(
            R.string.domain_strategy,
            arrayEntry(R.array.dns_network_entry, R.array.dns_network_select, dnsStrategy),
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
