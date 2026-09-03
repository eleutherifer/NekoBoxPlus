package moe.matsuri.nb4a.proxy.byedpi

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.compose.ByeDPIProfileSettingsScreen

class ByeDPISettingsActivity : ProfileSettingsActivity<ByeDPIBean>() {
    override val usesComposePreferences = true

    companion object {
        private const val KEY_CLI_STRATEGY = "byeDpiCliStrategy"
    }

    override fun createEntity() = ByeDPIBean()

    override fun ByeDPIBean.init() {
        DataStore.profileName = name
        DataStore.profileCacheStore.putString(KEY_CLI_STRATEGY, cliStrategy)
    }

    override fun ByeDPIBean.serialize() {
        name = DataStore.profileName
        cliStrategy = DataStore.profileCacheStore.getString(KEY_CLI_STRATEGY) ?: ""
    }

    @Composable
    override fun ComposePreferences() = ByeDPIProfileSettingsScreen()
}
