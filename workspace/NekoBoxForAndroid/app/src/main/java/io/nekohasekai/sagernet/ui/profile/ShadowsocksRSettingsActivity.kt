package io.nekohasekai.sagernet.ui.profile

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.ui.compose.ShadowsocksRProfileSettingsScreen

class ShadowsocksRSettingsActivity : ProfileSettingsActivity<ShadowsocksRBean>() {
    override val usesComposePreferences = true

    override fun createEntity() = ShadowsocksRBean()

    override fun ShadowsocksRBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverMethod = method
        DataStore.serverPassword = password
        DataStore.serverProtocol = protocol
        DataStore.serverObfs = obfs
        DataStore.serverProtocolParam = protocolParam
        DataStore.serverObfsParam = obfsParam
    }

    override fun ShadowsocksRBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        method = DataStore.serverMethod
        password = DataStore.serverPassword
        protocol = DataStore.serverProtocol
        obfs = DataStore.serverObfs
        protocolParam = DataStore.serverProtocolParam
        obfsParam = DataStore.serverObfsParam
    }

    @Composable
    override fun ComposePreferences() = ShadowsocksRProfileSettingsScreen()
}
