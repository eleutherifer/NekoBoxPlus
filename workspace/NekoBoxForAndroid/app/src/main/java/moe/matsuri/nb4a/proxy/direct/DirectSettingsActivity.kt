package moe.matsuri.nb4a.proxy.direct

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.compose.DirectProfileSettingsScreen

class DirectSettingsActivity :
    ProfileSettingsActivity<DirectBean>() {
    override val usesComposePreferences = true

    override fun createEntity() = DirectBean()

    override fun DirectBean.init() {
        DataStore.profileName = name
    }

    override fun DirectBean.serialize() {
        name = DataStore.profileName
    }

    @Composable
    override fun ComposePreferences() = DirectProfileSettingsScreen()
}
