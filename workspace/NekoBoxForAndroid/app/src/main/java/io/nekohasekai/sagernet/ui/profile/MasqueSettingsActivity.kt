package io.nekohasekai.sagernet.ui.profile

import android.app.Activity
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.ui.compose.MasqueProfileSettingsScreen
import io.nekohasekai.sagernet.ui.compose.showComposeItemDialog
import io.nekohasekai.sagernet.ui.compose.showComposeMessageDialog
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class MasqueSettingsActivity : ProfileSettingsActivity<MasqueBean>() {

    override val usesComposePreferences = true

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

    private var detourRevision by mutableIntStateOf(0)

    override fun MasqueBean.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putLong(KEY_PROFILE_DETOUR, profileDetour ?: 0L)
    }

    override fun MasqueBean.serialize() {
        pbm.fromCacheAll(this)
        profileDetour = DataStore.profileCacheStore.getLong(KEY_PROFILE_DETOUR) ?: 0L
    }

    private fun currentDetourId(): Long {
        return DataStore.profileCacheStore.getLong(KEY_PROFILE_DETOUR) ?: 0L
    }

    @Composable
    override fun ComposePreferences() {
        detourRevision
        val detourId = currentDetourId()
        MasqueProfileSettingsScreen(
            detourName = detourId.takeIf { it > 0 }
                ?.let { ProfileManager.getProfile(it)?.displayName() }
                ?: getString(R.string.masque_profile_detour_direct),
            onSelectDetour = ::showDetourDialog,
        )
    }

    private fun showDetourDialog() {
        showComposeItemDialog(
            title = getText(R.string.masque_profile_detour),
            items = listOf(getString(R.string.masque_profile_detour_direct), getString(R.string.route_profile)),
            onItemSelected = { which ->
                if (which == 0) {
                    DataStore.profileCacheStore.putLong(KEY_PROFILE_DETOUR, 0L)
                    detourRevision++
                } else {
                    selectDetour.launch(Intent(this, ProfileSelectActivity::class.java))
                }
            },
        )
    }

    private val selectDetour = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profileId = it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L)
            if (profileId == DataStore.editingId && profileId > 0L) {
                onMainDispatcher {
                    showComposeMessageDialog(
                        title = getText(R.string.invalid_profile),
                        message = getText(R.string.masque_profile_detour_self_error),
                    )
                }
                return@runOnDefaultDispatcher
            }
            DataStore.profileCacheStore.putLong(KEY_PROFILE_DETOUR, profileId)
            onMainDispatcher { detourRevision++ }
        }
    }
}
