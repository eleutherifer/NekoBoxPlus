package moe.matsuri.nb4a.proxy.config

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.compose.ConfigProfileSettingsScreen
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity

class ConfigSettingActivity :
    ProfileSettingsActivity<ConfigBean>() {

    override val usesComposePreferences = true

    private val isOutboundOnlyKey = "isOutboundOnly"

    override fun createEntity() = ConfigBean()

    override fun ConfigBean.init() {
        // CustomBean to input
        DataStore.profileCacheStore.putBoolean(isOutboundOnlyKey, type == 1)
        DataStore.profileName = name
        DataStore.serverConfig = config
    }

    override fun ConfigBean.serialize() {
        // CustomBean from input
        type = if (DataStore.profileCacheStore.getBoolean(isOutboundOnlyKey, false)) 1 else 0
        name = DataStore.profileName
        config = DataStore.serverConfig
    }

    @Composable
    override fun ComposePreferences() = ConfigProfileSettingsScreen(isOutboundOnlyKey)

}
