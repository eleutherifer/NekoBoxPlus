package io.nekohasekai.sagernet.ui.profile

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.compose.TrojanGoProfileSettingsScreen

class TrojanGoSettingsActivity : ProfileSettingsActivity<TrojanGoBean>() {
    override val usesComposePreferences = true

    override fun createEntity() = TrojanGoBean()

    override fun TrojanGoBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverPassword = password
        DataStore.serverSNI = sni
        DataStore.serverAllowInsecure = allowInsecure
        DataStore.serverNetwork = type.takeIf { it in trojanGoNetworks } ?: trojanGoNetworks[0]
        DataStore.serverHost = host
        DataStore.serverPath = path
        if (encryption.startsWith("ss;")) {
            DataStore.serverEncryption = "ss"
            DataStore.serverMethod = encryption.substringAfter(";").substringBefore(":")
                .takeIf { it in trojanGoMethods } ?: trojanGoMethods[0]
            DataStore.serverPassword1 = encryption.substringAfter(":")
        } else {
            DataStore.serverEncryption = encryption
        }
    }

    override fun TrojanGoBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        password = DataStore.serverPassword
        sni = DataStore.serverSNI
        allowInsecure = DataStore.serverAllowInsecure
        type = DataStore.serverNetwork
        host = DataStore.serverHost
        path = DataStore.serverPath
        encryption = when (val security = DataStore.serverEncryption) {
            "ss" -> {
                "ss;" + DataStore.serverMethod + ":" + DataStore.serverPassword1
            }
            else -> {
                security
            }
        }
    }

    val trojanGoMethods = app.resources.getStringArray(R.array.trojan_go_methods)
    val trojanGoNetworks = app.resources.getStringArray(R.array.trojan_go_networks_value)

    @Composable
    override fun ComposePreferences() = TrojanGoProfileSettingsScreen()

}
