package io.nekohasekai.sagernet.ui.profile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.ui.compose.ChainProfileSettingsScreen
import io.nekohasekai.sagernet.ui.compose.showComposeMessageDialog

class ChainSettingsActivity : ProfileSettingsActivity<ChainBean>() {
    override val usesComposePreferences = true

    override fun createEntity() = ChainBean()

    private val proxyList = mutableStateListOf<ProxyEntity>()
    private var profilesLoaded = false
    private var replacing = -1

    override fun ChainBean.init() {
        DataStore.profileName = name
        DataStore.serverProtocol = proxies.joinToString(",")
    }

    override fun ChainBean.serialize() {
        name = DataStore.profileName
        proxies = proxyList.map { it.id }
        initializeDefaultValues()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.chain_settings)
    }

    @Composable
    override fun ComposePreferences() = ChainProfileSettingsScreen(
        profiles = proxyList,
        onLoad = ::loadProfiles,
        onAdd = { selectProfile(-1, null) },
        onReplace = { index -> selectProfile(index, proxyList[index]) },
        onMove = ::moveProfile,
        onDelete = ::requestDelete,
    )

    private fun loadProfiles() {
        if (profilesLoaded) return
        profilesLoaded = true
        runOnDefaultDispatcher {
            val ids = DataStore.serverProtocol.split(",").mapNotNull {
                it.takeIf(String::isNotBlank)?.toLongOrNull()
            }
            val profiles = ProfileManager.getProfiles(ids).associateBy { it.id }
            onMainDispatcher {
                proxyList.clear()
                ids.mapNotNullTo(proxyList) { profiles[it] }
            }
        }
    }

    private fun selectProfile(index: Int, selected: ProxyEntity?) {
        replacing = index
        selectProfileForAdd.launch(Intent(this, ProfileSelectActivity::class.java).apply {
            selected?.let { putExtra(ProfileSelectActivity.EXTRA_SELECTED, it) }
        })
    }

    private fun moveProfile(from: Int, to: Int) {
        if (from !in proxyList.indices || to !in proxyList.indices) return
        val next = proxyList.toMutableList().apply { add(to, removeAt(from)) }
        if (!isValidByeDPIChain(next)) return
        proxyList.add(to, proxyList.removeAt(from))
        DataStore.dirty = true
    }

    private fun requestDelete(index: Int) {
        if (index !in proxyList.indices) return
        val remove = {
            if (index in proxyList.indices) proxyList.removeAt(index)
            DataStore.dirty = true
        }
        if (DataStore.confirmProfileDelete) {
            showComposeMessageDialog(
                title = getText(R.string.delete_confirm_prompt),
                positiveButton = getText(R.string.yes),
                negativeButton = getText(R.string.no),
                onPositive = remove,
            )
        } else remove()
    }

    private fun isValidByeDPIChain(list: List<ProxyEntity>): Boolean {
        var seenByeDPI = false
        list.forEachIndexed { index, profile ->
            if (!profile.containsByeDPI()) return@forEachIndexed
            if (seenByeDPI || index != 0 || !profile.startsWithByeDPI()) return false
            seenByeDPI = true
        }
        return true
    }

    private fun testProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id == DataStore.editingId || profile.containsMasterDnsVPN()) return false
        return proxyList.none { testProfileContains(it, profile) }
    }

    private fun testProfileContains(profile: ProxyEntity, another: ProxyEntity): Boolean {
        if (profile.type != 8 || another.type != 8) return false
        if (profile.id == another.id) return true
        val ids = profile.chainBean!!.proxies
        if (another.id in ids) return true
        return ids.isNotEmpty() && ProfileManager.getProfiles(ids).any {
            testProfileContains(it, another)
        }
    }

    private val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode != Activity.RESULT_OK || data == null) return@registerForActivityResult
            runOnDefaultDispatcher {
                val profile = ProfileManager.getProfile(
                    data.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0),
                ) ?: return@runOnDefaultDispatcher
                if (!testProfileAllowed(profile)) {
                    onMainDispatcher {
                        showComposeMessageDialog(
                            title = getText(R.string.invalid_profile),
                            message = getText(if (profile.containsMasterDnsVPN()) {
                                R.string.masterdnsvpn_chain_error
                            } else R.string.circular_reference_sum),
                        )
                    }
                    return@runOnDefaultDispatcher
                }
                val next = proxyList.toMutableList().apply {
                    if (replacing in indices) this[replacing] = profile else add(profile)
                }
                if (!isValidByeDPIChain(next)) {
                    onMainDispatcher {
                        showComposeMessageDialog(
                            title = getText(R.string.invalid_profile),
                            message = getText(R.string.byedpi_chain_position_error),
                        )
                    }
                    return@runOnDefaultDispatcher
                }
                onMainDispatcher {
                    if (replacing in proxyList.indices) proxyList[replacing] = profile
                    else proxyList.add(profile)
                    DataStore.dirty = true
                }
            }
        }
}
