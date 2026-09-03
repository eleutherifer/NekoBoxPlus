package io.nekohasekai.sagernet.ui.profile

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.compose.JuicityProfileSettingsScreen

class JuicitySettingsActivity : ProfileSettingsActivity<JuicityBean>() {
    override val usesComposePreferences = true

    override fun createEntity() = JuicityBean().applyDefaultValues()

    override fun JuicityBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUserId = uuid
        DataStore.serverPassword = password
        DataStore.serverSNI = sni
        DataStore.serverPinnedCertChainSha256 = pinnedCertchainSha256
        DataStore.serverAllowInsecure = allowInsecure
    }

    override fun JuicityBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        uuid = DataStore.serverUserId
        password = DataStore.serverPassword
        sni = DataStore.serverSNI
        pinnedCertchainSha256 = DataStore.serverPinnedCertChainSha256
        allowInsecure = DataStore.serverAllowInsecure
    }

    @Composable
    override fun ComposePreferences() = JuicityProfileSettingsScreen()
}
