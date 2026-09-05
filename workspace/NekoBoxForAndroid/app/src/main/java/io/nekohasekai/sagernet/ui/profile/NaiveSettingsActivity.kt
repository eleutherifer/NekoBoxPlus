package io.nekohasekai.sagernet.ui.profile

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.ui.compose.NaiveProfileSettingsScreen

class NaiveSettingsActivity : ProfileSettingsActivity<NaiveBean>() {
    override val usesComposePreferences = true

    override fun createEntity() = NaiveBean()

    override fun NaiveBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUsername = username
        DataStore.serverPassword = password
        DataStore.serverProtocol = proto
        DataStore.serverSNI = sni
        DataStore.serverCertificates = certificates
        DataStore.serverHeaders = extraHeaders
        DataStore.serverInsecureConcurrency = insecureConcurrency
        DataStore.profileCacheStore.putBoolean("sUoT", sUoT)
        DataStore.profileCacheStore.putString("quicCongestionControl", quicCongestionControl)
        DataStore.profileCacheStore.putString("streamReceiveWindow", streamReceiveWindow)
        DataStore.profileCacheStore.putString("quicSessionReceiveWindow", quicSessionReceiveWindow)
    }

    override fun NaiveBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        username = DataStore.serverUsername
        password = DataStore.serverPassword
        proto = DataStore.serverProtocol
        sni = DataStore.serverSNI
        certificates = DataStore.serverCertificates
        extraHeaders = DataStore.serverHeaders.replace("\r\n", "\n")
        insecureConcurrency = DataStore.serverInsecureConcurrency
        sUoT = DataStore.profileCacheStore.getBoolean("sUoT")
        quicCongestionControl = DataStore.profileCacheStore.getString("quicCongestionControl").orEmpty()
        streamReceiveWindow = DataStore.profileCacheStore.getString("streamReceiveWindow").orEmpty()
        quicSessionReceiveWindow = DataStore.profileCacheStore.getString("quicSessionReceiveWindow").orEmpty()
    }

    @Composable
    override fun ComposePreferences() = NaiveProfileSettingsScreen()

    override fun finish() {
        if (DataStore.profileName == "喵要打开隐藏功能") {
            DataStore.isExpert = true
        } else if (DataStore.profileName == "喵要关闭隐藏功能") {
            DataStore.isExpert = false
        }
        super.finish()
    }

}
