package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class MasterDnsVPNSettingsActivity : ProfileSettingsActivity<MasterDnsVPNBean>() {

    private val pbm = PreferenceBindingManager()
    private val additionalBindings = mutableListOf<PreferenceBinding>()

    private fun bind(type: Int, fieldName: String, additional: Boolean = false): PreferenceBinding {
        return pbm.add(PreferenceBinding(type, fieldName)).also {
            if (additional) additionalBindings.add(it)
        }
    }

    private val name = bind(Type.Text, "name")
    private val domains = bind(Type.Text, "domains")
    private val dataEncryptionMethod = bind(Type.TextToInt, "dataEncryptionMethod")
    private val encryptionKey = bind(Type.Text, "encryptionKey")
    private val resolvers = bind(Type.Text, "resolvers")

    private val localDNSCacheMaxRecords = bind(Type.TextToInt, "localDNSCacheMaxRecords", true)
    private val localDNSCacheTTLSeconds = bind(Type.TextToDouble, "localDNSCacheTTLSeconds", true)
    private val localDNSPendingTimeoutSeconds = bind(Type.TextToDouble, "localDNSPendingTimeoutSeconds", true)
    private val dnsResponseFragmentTimeoutSeconds = bind(Type.TextToDouble, "dnsResponseFragmentTimeoutSeconds", true)
    private val localDNSCachePersistToFile = bind(Type.Bool, "localDNSCachePersistToFile", true)
    private val localDNSCacheFlushIntervalSeconds = bind(Type.TextToDouble, "localDNSCacheFlushIntervalSeconds", true)
    private val resolverBalancingStrategy = bind(Type.TextToInt, "resolverBalancingStrategy", true)
    private val packetDuplicationCount = bind(Type.TextToInt, "packetDuplicationCount", true)
    private val setupPacketDuplicationCount = bind(Type.TextToInt, "setupPacketDuplicationCount", true)
    private val streamResolverFailoverResendThreshold = bind(Type.TextToInt, "streamResolverFailoverResendThreshold", true)
    private val streamResolverFailoverCooldown = bind(Type.TextToDouble, "streamResolverFailoverCooldown", true)
    private val recheckInactiveServersEnabled = bind(Type.Bool, "recheckInactiveServersEnabled", true)
    private val autoDisableTimeoutServers = bind(Type.Bool, "autoDisableTimeoutServers", true)
    private val autoDisableTimeoutWindowSeconds = bind(Type.TextToDouble, "autoDisableTimeoutWindowSeconds", true)
    private val baseEncodeData = bind(Type.Bool, "baseEncodeData", true)
    private val uploadCompressionType = bind(Type.TextToInt, "uploadCompressionType", true)
    private val downloadCompressionType = bind(Type.TextToInt, "downloadCompressionType", true)
    private val compressionMinSize = bind(Type.TextToInt, "compressionMinSize", true)
    private val minUploadMTU = bind(Type.TextToInt, "minUploadMTU", true)
    private val minDownloadMTU = bind(Type.TextToInt, "minDownloadMTU", true)
    private val maxUploadMTU = bind(Type.TextToInt, "maxUploadMTU", true)
    private val maxDownloadMTU = bind(Type.TextToInt, "maxDownloadMTU", true)
    private val autoRemoveLowMTUServers = bind(Type.Bool, "autoRemoveLowMTUServers", true)
    private val mtuTestRetries = bind(Type.TextToInt, "mtuTestRetries", true)
    private val mtuTestTimeout = bind(Type.TextToDouble, "mtuTestTimeout", true)
    private val mtuTestParallelism = bind(Type.TextToInt, "mtuTestParallelism", true)
    private val rxTxWorkers = bind(Type.TextToInt, "rxTxWorkers", true)
    private val tunnelProcessWorkers = bind(Type.TextToInt, "tunnelProcessWorkers", true)
    private val tunnelPacketTimeoutSeconds = bind(Type.TextToDouble, "tunnelPacketTimeoutSeconds", true)
    private val dispatcherIdlePollIntervalSeconds = bind(Type.TextToDouble, "dispatcherIdlePollIntervalSeconds", true)
    private val rxChannelSize = bind(Type.TextToInt, "rxChannelSize", true)
    private val socksUDPAssociateReadTimeoutSeconds = bind(Type.TextToDouble, "socksUDPAssociateReadTimeoutSeconds", true)
    private val clientTerminalStreamRetentionSeconds = bind(Type.TextToDouble, "clientTerminalStreamRetentionSeconds", true)
    private val clientCancelledSetupRetentionSeconds = bind(Type.TextToDouble, "clientCancelledSetupRetentionSeconds", true)
    private val sessionInitRetryBaseSeconds = bind(Type.TextToDouble, "sessionInitRetryBaseSeconds", true)
    private val sessionInitRetryStepSeconds = bind(Type.TextToDouble, "sessionInitRetryStepSeconds", true)
    private val sessionInitRetryLinearAfter = bind(Type.TextToInt, "sessionInitRetryLinearAfter", true)
    private val sessionInitRetryMaxSeconds = bind(Type.TextToDouble, "sessionInitRetryMaxSeconds", true)
    private val sessionInitBusyRetryIntervalSeconds = bind(Type.TextToDouble, "sessionInitBusyRetryIntervalSeconds", true)
    private val sessionInitRacingCount = bind(Type.TextToInt, "sessionInitRacingCount", true)
    private val pingAggressiveIntervalSeconds = bind(Type.TextToDouble, "pingAggressiveIntervalSeconds", true)
    private val pingLazyIntervalSeconds = bind(Type.TextToDouble, "pingLazyIntervalSeconds", true)
    private val pingCooldownIntervalSeconds = bind(Type.TextToDouble, "pingCooldownIntervalSeconds", true)
    private val pingColdIntervalSeconds = bind(Type.TextToDouble, "pingColdIntervalSeconds", true)
    private val pingWarmThresholdSeconds = bind(Type.TextToDouble, "pingWarmThresholdSeconds", true)
    private val pingCoolThresholdSeconds = bind(Type.TextToDouble, "pingCoolThresholdSeconds", true)
    private val pingColdThresholdSeconds = bind(Type.TextToDouble, "pingColdThresholdSeconds", true)
    private val maxPacketsPerBatch = bind(Type.TextToInt, "maxPacketsPerBatch", true)
    private val arqWindowSize = bind(Type.TextToInt, "arqWindowSize", true)
    private val arqInitialRTOSeconds = bind(Type.TextToDouble, "arqInitialRTOSeconds", true)
    private val arqMaxRTOSeconds = bind(Type.TextToDouble, "arqMaxRTOSeconds", true)
    private val arqControlInitialRTOSeconds = bind(Type.TextToDouble, "arqControlInitialRTOSeconds", true)
    private val arqControlMaxRTOSeconds = bind(Type.TextToDouble, "arqControlMaxRTOSeconds", true)
    private val arqMaxControlRetries = bind(Type.TextToInt, "arqMaxControlRetries", true)
    private val arqInactivityTimeoutSeconds = bind(Type.TextToDouble, "arqInactivityTimeoutSeconds", true)
    private val arqDataPacketTTLSeconds = bind(Type.TextToDouble, "arqDataPacketTTLSeconds", true)
    private val arqControlPacketTTLSeconds = bind(Type.TextToDouble, "arqControlPacketTTLSeconds", true)
    private val arqMaxDataRetries = bind(Type.TextToInt, "arqMaxDataRetries", true)
    private val arqDataNackMaxGap = bind(Type.TextToInt, "arqDataNackMaxGap", true)
    private val arqDataNackInitialDelaySeconds = bind(Type.TextToDouble, "arqDataNackInitialDelaySeconds", true)
    private val arqDataNackRepeatSeconds = bind(Type.TextToDouble, "arqDataNackRepeatSeconds", true)
    private val arqTerminalDrainTimeoutSeconds = bind(Type.TextToDouble, "arqTerminalDrainTimeoutSeconds", true)
    private val arqTerminalAckWaitTimeoutSeconds = bind(Type.TextToDouble, "arqTerminalAckWaitTimeoutSeconds", true)

    private val importResolvers = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@registerForActivityResult
            DataStore.profileCacheStore.putString("resolvers", text)
            (resolvers.preference as? EditTextPreference)?.text = text
        }
    }

    override fun createEntity() = MasterDnsVPNBean().applyDefaultValues()

    override fun MasterDnsVPNBean.init() {
        initializeDefaultValues()
        pbm.writeToCacheAll(this)
    }

    override fun MasterDnsVPNBean.serialize() {
        pbm.fromCacheAll(this)
        initializeDefaultValues()
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.masterdnsvpn_preferences)
        pbm.setPreferenceFragment(this)
        findPreference<Preference>("preset")!!.setOnPreferenceClickListener {
            showPresetDialog()
            true
        }
        findPreference<Preference>("importResolvers")!!.setOnPreferenceClickListener {
            importResolvers.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
            true
        }
        resolvers.preference.summary = getString(R.string.masterdnsvpn_dns_resolvers_summary)
        additionalBindings.forEach { binding ->
            (binding.preference as? EditTextPreference)?.setOnBindEditTextListener(
                if (binding.type == Type.TextToDouble) {
                    EditTextPreferenceModifiers.Decimal
                } else if (binding.type == Type.TextToInt) {
                    EditTextPreferenceModifiers.Number
                } else {
                    EditTextPreferenceModifiers.Multiline
                }
            )
        }
    }

    private fun PreferenceFragmentCompat.showPresetDialog() {
        val values = arrayOf("stable", "mobile", "censored", "throughput")
        val labels = arrayOf(
            getString(R.string.masterdnsvpn_preset_default),
            getString(R.string.masterdnsvpn_preset_mobile),
            getString(R.string.masterdnsvpn_preset_censored),
            getString(R.string.masterdnsvpn_preset_speed),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.masterdnsvpn_preset)
            .setItems(labels) { _, which ->
                val bean = MasterDnsVPNBean().applyDefaultValues()
                bean.applyPreset(values[which])
                additionalBindings.forEach {
                    it.bean = bean
                    it.writeToCache()
                    it.writeToPreference()
                }
            }
            .show()
    }
}
