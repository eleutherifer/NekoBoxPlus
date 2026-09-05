package io.nekohasekai.sagernet.ui.profile

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.compose.HysteriaProfileSettingsScreen

class HysteriaSettingsActivity : ProfileSettingsActivity<HysteriaBean>() {

    override val usesComposePreferences = true

    override fun createEntity() = HysteriaBean().applyDefaultValues()

    override fun HysteriaBean.init() {
        DataStore.profileName = name
        DataStore.protocolVersion = protocolVersion
        DataStore.serverAddress = serverAddress
        DataStore.serverPorts = serverPorts
        DataStore.serverObfs = obfuscation
        DataStore.serverAuthType = authPayloadType
        DataStore.serverProtocolInt = protocol
        DataStore.serverPassword = authPayload
        DataStore.serverSNI = sni
        DataStore.serverALPN = alpn
        DataStore.serverCertificates = caText
        DataStore.serverAllowInsecure = allowInsecure
        DataStore.serverUploadSpeed = uploadMbps
        DataStore.serverDownloadSpeed = downloadMbps
        DataStore.serverStreamReceiveWindow = streamReceiveWindow
        DataStore.serverConnectionReceiveWindow = connectionReceiveWindow
        DataStore.serverDisableMtuDiscovery = disableMtuDiscovery
        DataStore.serverHopInterval = hopInterval
        DataStore.profileCacheStore.putString("hysteria2HopIntervalMax", hopIntervalMax)
        DataStore.profileCacheStore.putString("hysteria2BbrProfile", bbrProfile)
        DataStore.profileCacheStore.putBoolean("hysteria2BrutalDebug", brutalDebug)
        DataStore.profileCacheStore.putString("hysteria2ObfsType", obfsType)
        DataStore.profileCacheStore.putString("hysteria2GeckoMinPacketSize", geckoMinPacketSize?.takeIf { it > 0 }?.toString().orEmpty())
        DataStore.profileCacheStore.putString("hysteria2GeckoMaxPacketSize", geckoMaxPacketSize?.takeIf { it > 0 }?.toString().orEmpty())
        DataStore.profileCacheStore.putString("hysteria2RealmServerUrl", realmServerUrl)
        DataStore.profileCacheStore.putString("hysteria2RealmToken", realmToken)
        DataStore.profileCacheStore.putString("hysteria2RealmId", realmId)
        DataStore.profileCacheStore.putString("hysteria2RealmStunServers", realmStunServers)
        DataStore.profileCacheStore.putString("hysteria2RealmIpVersion", realmIpVersion.toString())
        DataStore.profileCacheStore.putBoolean("hysteria2RealmPortMapping", realmPortMapping)
        DataStore.profileCacheStore.putString("hysteria2RealmPortMappingTimeout", realmPortMappingTimeout)
        DataStore.profileCacheStore.putString("hysteria2RealmPortMappingLifetime", realmPortMappingLifetime)
    }

    override fun HysteriaBean.serialize() {
        name = DataStore.profileName
        protocolVersion = DataStore.protocolVersion
        serverAddress = DataStore.serverAddress
        serverPorts = DataStore.serverPorts
        obfuscation = DataStore.serverObfs
        authPayloadType = DataStore.serverAuthType
        authPayload = DataStore.serverPassword
        protocol = DataStore.serverProtocolInt
        sni = DataStore.serverSNI
        alpn = DataStore.serverALPN
        caText = DataStore.serverCertificates
        allowInsecure = DataStore.serverAllowInsecure
        uploadMbps = DataStore.serverUploadSpeed
        downloadMbps = DataStore.serverDownloadSpeed
        streamReceiveWindow = DataStore.serverStreamReceiveWindow
        connectionReceiveWindow = DataStore.serverConnectionReceiveWindow
        disableMtuDiscovery = DataStore.serverDisableMtuDiscovery
        hopInterval = DataStore.serverHopInterval
        hopIntervalMax = DataStore.profileCacheStore.getString("hysteria2HopIntervalMax").orEmpty()
        bbrProfile = DataStore.profileCacheStore.getString("hysteria2BbrProfile").orEmpty()
        brutalDebug = DataStore.profileCacheStore.getBoolean("hysteria2BrutalDebug", false)
        obfsType = DataStore.profileCacheStore.getString("hysteria2ObfsType") ?: "salamander"
        geckoMinPacketSize = DataStore.profileCacheStore.getString("hysteria2GeckoMinPacketSize")?.toIntOrNull() ?: 0
        geckoMaxPacketSize = DataStore.profileCacheStore.getString("hysteria2GeckoMaxPacketSize")?.toIntOrNull() ?: 0
        realmServerUrl = DataStore.profileCacheStore.getString("hysteria2RealmServerUrl").orEmpty()
        realmToken = DataStore.profileCacheStore.getString("hysteria2RealmToken").orEmpty()
        realmId = DataStore.profileCacheStore.getString("hysteria2RealmId").orEmpty()
        realmStunServers = DataStore.profileCacheStore.getString("hysteria2RealmStunServers").orEmpty()
        realmIpVersion = DataStore.profileCacheStore.getString("hysteria2RealmIpVersion")?.toIntOrNull() ?: 0
        realmPortMapping = DataStore.profileCacheStore.getBoolean("hysteria2RealmPortMapping", false)
        realmPortMappingTimeout = DataStore.profileCacheStore.getString("hysteria2RealmPortMappingTimeout").orEmpty()
        realmPortMappingLifetime = DataStore.profileCacheStore.getString("hysteria2RealmPortMappingLifetime").orEmpty()
    }

    @Composable
    override fun ComposePreferences() = HysteriaProfileSettingsScreen()

}
