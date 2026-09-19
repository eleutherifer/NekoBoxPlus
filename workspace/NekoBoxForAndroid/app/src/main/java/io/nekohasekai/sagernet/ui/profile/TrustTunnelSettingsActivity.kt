package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type
import moe.matsuri.nb4a.ui.MaterialSwitchPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class TrustTunnelSettingsActivity : ProfileSettingsActivity<TrustTunnelBean>() {

    companion object {
        private const val KEY_PROTOCOL = "trustTunnelProtocol"
        private const val KEY_CRONET_STACK = "trustTunnelCronetStack"
        private const val PROTOCOL_HTTPS = "https"
        private const val PROTOCOL_PREFER_QUIC = "prefer_quic"
        private const val PROTOCOL_FORCE_QUIC = "force_quic"
        private const val CRONET_NO = "no"
        private const val CRONET_FOR_HTTPS = "https"
        private const val CRONET_FOR_QUIC = "quic"
        private const val CRONET_FOR_HTTPS_AND_QUIC = "https_quic"
    }

    override fun createEntity() = TrustTunnelBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val username = pbm.add(PreferenceBinding(Type.Text, "username"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val healthCheck = pbm.add(PreferenceBinding(Type.Bool, "healthCheck"))
    private val quicBinding = pbm.add(PreferenceBinding(Type.Bool, "quic"))
    private val forceQuicBinding = pbm.add(PreferenceBinding(Type.Bool, "forceQuic"))
    private val useCronetQuicBinding = pbm.add(PreferenceBinding(Type.Bool, "useCronetQuic"))
    private val useCronetHttpsBinding = pbm.add(PreferenceBinding(Type.Bool, "useCronetHttps"))
    private val quicCongestionControl = pbm.add(PreferenceBinding(Type.Text, "quicCongestionControl"))
    private val clientRandomPrefix = pbm.add(PreferenceBinding(Type.Text, "clientRandomPrefix"))
    private val serverName = pbm.add(PreferenceBinding(Type.Text, "serverName"))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, "alpn"))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, "certificates"))
    private val certPublicKeySha256 = pbm.add(PreferenceBinding(Type.Text, "certPublicKeySha256"))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, "utlsFingerprint"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val tlsFragment = pbm.add(PreferenceBinding(Type.Bool, "tlsFragment"))
    private val tlsFragmentFallbackDelay = pbm.add(PreferenceBinding(Type.Text, "tlsFragmentFallbackDelay"))
    private val tlsRecordFragment = pbm.add(PreferenceBinding(Type.Bool, "tlsRecordFragment"))
    private val ech = pbm.add(PreferenceBinding(Type.Bool, "ech"))
    private val echConfig = pbm.add(PreferenceBinding(Type.Text, "echConfig"))
    private val echQueryServerName = pbm.add(PreferenceBinding(Type.Text, "echQueryServerName"))
    private val clientCert = pbm.add(PreferenceBinding(Type.Text, "clientCert"))
    private val clientKey = pbm.add(PreferenceBinding(Type.Text, "clientKey"))

    override fun TrustTunnelBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.trusttunnel_preferences)
        pbm.setPreferenceFragment(this)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>("password")!!.summaryProvider = PasswordSummaryProvider

        val protocolPreference = findPreference<SimpleMenuPreference>(KEY_PROTOCOL)!!
        val cronetStackPreference = findPreference<SimpleMenuPreference>(KEY_CRONET_STACK)!!
        val congestionControl = findPreference<SimpleMenuPreference>("quicCongestionControl")!!
        val clientRandom = findPreference<EditTextPreference>("clientRandomPrefix")!!
        val alpnPreference = findPreference<EditTextPreference>("alpn")!!
        val utls = findPreference<SimpleMenuPreference>("utlsFingerprint")!!
        val allowInsecurePreference = findPreference<MaterialSwitchPreference>("allowInsecure")!!
        val fragment = findPreference<MaterialSwitchPreference>("tlsFragment")!!
        val fragmentDelay = findPreference<EditTextPreference>("tlsFragmentFallbackDelay")!!
        val recordFragment = findPreference<MaterialSwitchPreference>("tlsRecordFragment")!!
        val echPreference = findPreference<MaterialSwitchPreference>("ech")!!
        val echConfigPreference = findPreference<EditTextPreference>("echConfig")!!
        val echQueryServerNamePreference = findPreference<EditTextPreference>("echQueryServerName")!!

        fun protocolValue(quicEnabled: Boolean, forceQuicEnabled: Boolean) = when {
            !quicEnabled -> PROTOCOL_HTTPS
            forceQuicEnabled -> PROTOCOL_FORCE_QUIC
            else -> PROTOCOL_PREFER_QUIC
        }

        fun cronetStackValue(useCronetHttpsEnabled: Boolean, useCronetQuicEnabled: Boolean) = when {
            useCronetHttpsEnabled && useCronetQuicEnabled -> CRONET_FOR_HTTPS_AND_QUIC
            useCronetHttpsEnabled -> CRONET_FOR_HTTPS
            useCronetQuicEnabled -> CRONET_FOR_QUIC
            else -> CRONET_NO
        }

        fun updateTransportOptions(
            protocol: String = protocolPreference.value,
            cronetStack: String = cronetStackPreference.value,
        ) {
            val quicEnabled = protocol != PROTOCOL_HTTPS
            val forceQuicActive = protocol == PROTOCOL_FORCE_QUIC
            val cronetSelected = cronetStack != CRONET_NO
            congestionControl.isEnabled = quicEnabled
            alpnPreference.isEnabled = !forceQuicActive
            utls.isEnabled = !forceQuicActive && !cronetSelected
            allowInsecurePreference.isEnabled = !forceQuicActive && !cronetSelected
            clientRandom.isEnabled = !cronetSelected
            fragment.isEnabled = !forceQuicActive && !cronetSelected && !recordFragment.isChecked
            fragmentDelay.isEnabled = !forceQuicActive && !cronetSelected && fragment.isChecked
            recordFragment.isEnabled = !forceQuicActive && !cronetSelected && !fragment.isChecked
        }

        fun updateEchOptions(enabled: Boolean) {
            echConfigPreference.isEnabled = enabled
            echQueryServerNamePreference.isEnabled = enabled
        }

        protocolPreference.value = protocolValue(
            quicBinding.readBoolFromCache(),
            forceQuicBinding.readBoolFromCache(),
        )
        cronetStackPreference.value = cronetStackValue(
            useCronetHttpsBinding.readBoolFromCache(),
            useCronetQuicBinding.readBoolFromCache(),
        )
        updateTransportOptions()
        updateEchOptions(echPreference.isChecked)

        protocolPreference.setOnPreferenceChangeListener { _, newValue ->
            updateTransportOptions(protocol = newValue as String, cronetStack = cronetStackPreference.value)
            true
        }
        cronetStackPreference.setOnPreferenceChangeListener { _, newValue ->
            updateTransportOptions(protocol = protocolPreference.value, cronetStack = newValue as String)
            true
        }
        utls.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == "cronet") {
                cronetStackPreference.value = CRONET_FOR_HTTPS
                updateTransportOptions(protocol = protocolPreference.value, cronetStack = cronetStackPreference.value)
            } else {
                updateTransportOptions(protocol = protocolPreference.value, cronetStack = cronetStackPreference.value)
            }
            true
        }
        fragment.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            val forceQuicActive = protocolPreference.value == PROTOCOL_FORCE_QUIC
            val cronetSelected = cronetStackPreference.value != CRONET_NO
            fragmentDelay.isEnabled = enabled && !forceQuicActive && !cronetSelected
            recordFragment.isEnabled = !enabled && !forceQuicActive && !cronetSelected
            true
        }
        recordFragment.setOnPreferenceChangeListener { _, newValue ->
            val forceQuicActive = protocolPreference.value == PROTOCOL_FORCE_QUIC
            val cronetSelected = cronetStackPreference.value != CRONET_NO
            fragment.isEnabled = !(newValue as Boolean) && !forceQuicActive && !cronetSelected
            true
        }
        echPreference.setOnPreferenceChangeListener { _, newValue ->
            updateEchOptions(newValue as Boolean)
            true
        }
    }

    override fun TrustTunnelBean.serialize() {
        val protocol = DataStore.profileCacheStore.getString(KEY_PROTOCOL) ?: PROTOCOL_HTTPS
        val cronetStack = DataStore.profileCacheStore.getString(KEY_CRONET_STACK) ?: CRONET_NO
        DataStore.profileCacheStore.putBoolean("quic", protocol != PROTOCOL_HTTPS)
        DataStore.profileCacheStore.putBoolean("forceQuic", protocol == PROTOCOL_FORCE_QUIC)
        DataStore.profileCacheStore.putBoolean(
            "useCronetQuic",
            cronetStack == CRONET_FOR_QUIC || cronetStack == CRONET_FOR_HTTPS_AND_QUIC,
        )
        DataStore.profileCacheStore.putBoolean(
            "useCronetHttps",
            cronetStack == CRONET_FOR_HTTPS || cronetStack == CRONET_FOR_HTTPS_AND_QUIC,
        )
        if (DataStore.profileCacheStore.getString("utlsFingerprint") == "cronet") {
            DataStore.profileCacheStore.putBoolean("useCronetHttps", true)
            DataStore.profileCacheStore.putString("utlsFingerprint", "")
        }
        pbm.fromCacheAll(this)
    }
}
