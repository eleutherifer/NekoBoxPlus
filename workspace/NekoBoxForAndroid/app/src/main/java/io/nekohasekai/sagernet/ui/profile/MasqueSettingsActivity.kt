package io.nekohasekai.sagernet.ui.profile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class MasqueSettingsActivity : ProfileSettingsActivity<MasqueBean>() {

    companion object {
        private const val KEY_PROFILE_DETOUR = "profileDetour"
    }

    override fun createEntity() = MasqueBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager().apply {
        add(PreferenceBinding(Type.Text, "name"))
        add(PreferenceBinding(Type.Bool, "useHTTP2"))
        add(PreferenceBinding(Type.Bool, "useIPv6"))
        add(PreferenceBinding(Type.Text, "profileId").apply {
            cacheName = "masqueProfileId"
        })
        add(PreferenceBinding(Type.Text, "profileAuthToken"))
        add(PreferenceBinding(Type.Text, "profilePrivateKey"))
        add(PreferenceBinding(Type.Bool, "profileRecreate"))
        add(PreferenceBinding(Type.Text, "configPrivateKey"))
        add(PreferenceBinding(Type.Text, "configEndpointV4"))
        add(PreferenceBinding(Type.Text, "configEndpointV6"))
        add(PreferenceBinding(Type.Text, "configEndpointH2V4"))
        add(PreferenceBinding(Type.Text, "configEndpointH2V6"))
        add(PreferenceBinding(Type.Text, "configEndpointPubKey"))
        add(PreferenceBinding(Type.Text, "configLicense"))
        add(PreferenceBinding(Type.Text, "configId"))
        add(PreferenceBinding(Type.Text, "configAccessToken"))
        add(PreferenceBinding(Type.Text, "configIPv4"))
        add(PreferenceBinding(Type.Text, "configIPv6"))
        add(PreferenceBinding(Type.Text, "udpTimeout"))
        add(PreferenceBinding(Type.Text, "udpKeepalivePeriod"))
        add(PreferenceBinding(Type.TextToInt, "udpInitialPacketSize"))
        add(PreferenceBinding(Type.Text, "reconnectDelay"))
        add(PreferenceBinding(Type.Text, "tlsSNI"))
        add(PreferenceBinding(Type.Bool, "tlsInsecure"))
        add(PreferenceBinding(Type.Text, "tlsCipherSuites"))
        add(PreferenceBinding(Type.Text, "tlsCurvePreferences"))
        add(PreferenceBinding(Type.Bool, "tlsFragment"))
        add(PreferenceBinding(Type.Text, "tlsFragmentFallbackDelay"))
        add(PreferenceBinding(Type.Bool, "tlsRecordFragment"))
        add(PreferenceBinding(Type.Bool, "tlsKernelTx"))
        add(PreferenceBinding(Type.Bool, "tlsKernelRx"))
    }

    private var detourPreference: Preference? = null

    override fun MasqueBean.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putLong(KEY_PROFILE_DETOUR, profileDetour ?: 0L)
    }

    override fun MasqueBean.serialize() {
        pbm.fromCacheAll(this)
        profileDetour = DataStore.profileCacheStore.getLong(KEY_PROFILE_DETOUR) ?: 0L
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.masque_preferences)
        pbm.setPreferenceFragment(this)

        findPreference<EditTextPreference>("udpInitialPacketSize")!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>("profileAuthToken")!!.summaryProvider = PasswordSummaryProvider
        findPreference<EditTextPreference>("profilePrivateKey")!!.summaryProvider = PasswordSummaryProvider
        findPreference<EditTextPreference>("configPrivateKey")!!.summaryProvider = PasswordSummaryProvider
        findPreference<EditTextPreference>("configAccessToken")!!.summaryProvider = PasswordSummaryProvider

        detourPreference = findPreference(KEY_PROFILE_DETOUR)
        detourPreference!!.setOnPreferenceClickListener {
            showDetourDialog()
            true
        }
        updateDetourSummary()
    }

    private fun currentDetourId(): Long {
        return DataStore.profileCacheStore.getLong(KEY_PROFILE_DETOUR) ?: 0L
    }

    private fun updateDetourSummary() {
        detourPreference?.summary = currentDetourId().takeIf { it > 0 }
            ?.let { ProfileManager.getProfile(it)?.displayName() }
            ?: getString(R.string.masque_profile_detour_direct)
    }

    private fun showDetourDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.masque_profile_detour)
            .setItems(arrayOf(getString(R.string.masque_profile_detour_direct), getString(R.string.route_profile))) { _, which ->
                if (which == 0) {
                    DataStore.profileCacheStore.putLong(KEY_PROFILE_DETOUR, 0L)
                    updateDetourSummary()
                } else {
                    selectDetour.launch(Intent(this, ProfileSelectActivity::class.java))
                }
            }
            .show()
    }

    private val selectDetour = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profileId = it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L)
            if (profileId == DataStore.editingId && profileId > 0L) {
                onMainDispatcher {
                    MaterialAlertDialogBuilder(this@MasqueSettingsActivity)
                        .setTitle(R.string.invalid_profile)
                        .setMessage(R.string.masque_profile_detour_self_error)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                return@runOnDefaultDispatcher
            }
            DataStore.profileCacheStore.putLong(KEY_PROFILE_DETOUR, profileId)
            onMainDispatcher { updateDetourSummary() }
        }
    }
}
