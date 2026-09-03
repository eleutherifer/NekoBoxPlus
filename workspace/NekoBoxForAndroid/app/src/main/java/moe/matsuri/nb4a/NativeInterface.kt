package moe.matsuri.nb4a

import android.Manifest
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.preferSmallIcon
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import libcore.BoxPlatformInterface
import libcore.Libcore
import libcore.NB4AInterface
import java.net.InetSocketAddress
import java.net.NetworkInterface
import org.json.JSONArray
import org.json.JSONObject
import android.widget.Toast
import io.nekohasekai.sagernet.Key

class NativeInterface : BoxPlatformInterface, NB4AInterface {
    private val nativeStateSyncPolicy = NativeStateSyncPolicy()
    private var lastLoggedWifiState: String? = null
    private var wifiRuleMonitoringEnabled = false
    private var wifiStateReceiverRegistered = false

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            notifyWifiStateChanged(intent?.action ?: "broadcast")
        }
    }

    fun setWifiRuleMonitoringEnabled(enabled: Boolean) {
        wifiRuleMonitoringEnabled = enabled
    }

    fun registerWifiStateListener() {
        if (!wifiRuleMonitoringEnabled || wifiStateReceiverRegistered) {
            return
        }
        if (!canReadWifiIdentityInBackground()) {
            Logs.d(
                "Wi-Fi listener skipped in process ${SagerNet.application.process}: " +
                    "missing background location permission"
            )
            return
        }
        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        app.registerReceiver(wifiStateReceiver, filter)
        wifiStateReceiverRegistered = true
        lastLoggedWifiState = null
        Logs.d("Wi-Fi listener registered in process ${SagerNet.application.process}")
        notifyWifiStateChanged("initial")
    }

    fun unregisterWifiStateListener() {
        if (!wifiStateReceiverRegistered) {
            return
        }
        runCatching {
            app.unregisterReceiver(wifiStateReceiver)
        }
        wifiStateReceiverRegistered = false
        lastLoggedWifiState = null
        Logs.d("Wi-Fi listener unregistered in process ${SagerNet.application.process}")
    }

    fun notifyWifiStateChanged(reason: String = "manual") {
        if (!wifiRuleMonitoringEnabled || !canReadWifiIdentityInBackground()) {
            return
        }
        val wifiState = wifiState()
        if (wifiState == lastLoggedWifiState) {
            return
        }
        Logs.d("Wi-Fi changed ($reason) in process ${SagerNet.application.process}: $wifiState")
        lastLoggedWifiState = wifiState
        Libcore.updatePlatformWIFIState()
    }

    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        if (DataStore.serviceMode != Key.MODE_VPN) return
        val vpnService = DataStore.vpnService
            ?: throw Exception("no VpnService for socket protect")
        if (!vpnService.protect(fd)) {
            throw Exception("VpnService.protect failed")
        }
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        if (DataStore.vpnService == null) {
            throw Exception("no VpnService")
        }
        return DataStore.vpnService!!.startVpn(singTunOptionsJson, tunPlatformOptionsJson).toLong()
    }

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int
    ): Int {
        return SagerNet.connectivity.getConnectionOwnerUid(
            ipProto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(destIp, destPort)
        )
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) for (packageName in packageNames) {
            return packageName
        }

        error("unknown uid $uid")
    }

    override fun uidByPackageName(packageName: String): Int {
        PackageCache.awaitLoadSync()
        return PackageCache[packageName] ?: 0
    }

    // TODO: 'getter for connectionInfo: WifiInfo!' is deprecated
    override fun wifiState(): String {
        if (!canReadWifiIdentity()) {
            return ","
        }
        val wifiManager =
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = runCatching { wifiManager.connectionInfo }.getOrNull()
        val ssid = normalizeWifiSsid(connectionInfo?.ssid)
        val bssid = connectionInfo?.bssid.orEmpty()
        return "$ssid,$bssid"
    }

    override fun defaultInterface(): String {
        val network = SagerNet.underlyingNetwork ?: return ""
        return buildDefaultInterface(network)?.toString() ?: ""
    }

    override fun networkInterfaces(): String {
        val interfaces = JSONArray()
        val seen = linkedSetOf<String>()
        for (network in SagerNet.connectivity.allNetworks) {
            val networkJson = buildNetworkInterface(network) ?: continue
            val name = networkJson.optString("name")
            if (name.isEmpty() || !seen.add(name)) continue
            interfaces.put(networkJson)
        }
        return interfaces.toString()
    }

    override fun sendNotification(
        identifier: String,
        typeName: String,
        title: String,
        body: String,
        openURL: String,
    ) {
        val localizedTitle = if (identifier == "tailscale-authentication") {
            app.getString(R.string.tailscale_authentication_title)
        } else {
            title
        }
        val localizedBody = if (identifier == "tailscale-authentication") {
            app.getString(R.string.tailscale_authentication_message)
        } else {
            body
        }
        val contentIntent = openURL.takeIf { it.isNotBlank() }?.let { url ->
            PendingIntent.getActivity(
                app,
                identifier.hashCode(),
                Intent(Intent.ACTION_VIEW, url.toUri()),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(app, "sing-box-authentication")
            .setSmallIcon(R.drawable.ic_service_active)
            .preferSmallIcon()
            .setContentTitle(localizedTitle)
            .setContentText(localizedBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(localizedBody))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                NotificationManagerCompat.from(app).notify(identifier.hashCode(), notification)
            }.onFailure(Logs::w)
        }
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
        runCatching {
            NotificationManagerCompat.from(app).cancel(identifier.hashCode())
        }.onFailure(Logs::w)
    }

    fun syncNetworkState(network: Network?) {
        val defaultInterface = if (network == null) "" else buildDefaultInterface(network)?.toString() ?: ""
        val interfaces = networkInterfaces()
        if (!nativeStateSyncPolicy.shouldSyncNetworkState(defaultInterface, interfaces)) {
            return
        }
        Logs.d(
            "Sync network state in process ${SagerNet.application.process}: " +
                "default=$defaultInterface interfaces=$interfaces"
        )
        Libcore.updatePlatformNetworkState(defaultInterface, interfaces)
        if (wifiRuleMonitoringEnabled) {
            notifyWifiStateChanged("network-sync")
        }
    }

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        val service = DataStore.baseService
        if (service == null) {
            Libcore.resetAllConnections(true)
            return
        }
        service.apply {
            runOnDefaultDispatcher {
                val proxy = data.proxy ?: run {
                    resetCoreNetwork()
                    return@runOnDefaultDispatcher
                }
                val id = proxy.config.profileTagMap
                    .filterValues { it == tag }.keys.firstOrNull() ?: -1
                val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
                // traffic & title
                proxy.apply {
                    looper?.selectorChanged(id) {
                        resetCoreNetwork()
                    } ?: resetCoreNetwork()
                    displayProfileName = ServiceNotification.genTitle(ent)
                    data.notification?.postNotificationTitle(displayProfileName)
                }
                // post binder
                data.binder.broadcast { b ->
                    b.cbSelectorUpdate(id)
                }
            }
        }
    }

    override fun masterDnsVPNResolverProgress(found: Int, total: Int, ready: Boolean) {
        val service = DataStore.baseService ?: return
        service.data.binder.masterDnsVPNResolverProgress(found, total, ready)
    }

    override fun masterDnsVPNStartupFailed(noWorkingDNS: Boolean, message: String?) {
        Handler(Looper.getMainLooper()).post {
            if (noWorkingDNS) {
                Toast.makeText(app, R.string.masterdnsvpn_no_working_dns, Toast.LENGTH_SHORT).show()
            } else if (!message.isNullOrBlank()) {
                Logs.w(message)
            }
            SagerNet.stopService()
        }
    }

    override fun endpointAuthenticationRequired(protocol: String?, detail: String?) {
        Handler(Looper.getMainLooper()).post {
            val message = app.getString(R.string.endpoint_authentication_required, protocol ?: "VPN")
            Logs.w(listOfNotNull(message, detail?.takeIf { it.isNotBlank() }).joinToString(" "))
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            SagerNet.stopService()
        }
    }

    private fun buildDefaultInterface(network: Network): JSONObject? {
        val linkProperties = SagerNet.connectivity.getLinkProperties(network) ?: return null
        val capabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: return null
        val interfaceName = linkProperties.interfaceName ?: return null
        val networkInterface = runCatching { NetworkInterface.getByName(interfaceName) }.getOrNull()
        val index = networkInterface?.index ?: 0
        return JSONObject().apply {
            put("name", interfaceName)
            put("index", index)
            put("network_handle", network.networkHandle)
            put("expensive", isExpensive(capabilities))
            put("constrained", false)
        }
    }

    private fun buildNetworkInterface(network: Network): JSONObject? {
        val linkProperties = SagerNet.connectivity.getLinkProperties(network) ?: return null
        val capabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
        return buildNetworkInterface(linkProperties, capabilities)
    }

    private fun buildNetworkInterface(
        linkProperties: LinkProperties,
        capabilities: NetworkCapabilities
    ): JSONObject? {
        val interfaceName = linkProperties.interfaceName ?: return null
        val networkInterface = runCatching { NetworkInterface.getByName(interfaceName) }.getOrNull()
        val flags = buildFlags(networkInterface)
        return JSONObject().apply {
            put("index", networkInterface?.index ?: 0)
            put("mtu", networkInterface?.mtu ?: linkProperties.mtu)
            put("name", interfaceName)
            put("flags", flags)
            put("addresses", JSONArray().apply {
                for (linkAddress in linkProperties.linkAddresses) {
                    put("${linkAddress.address.hostAddress}/${linkAddress.prefixLength}")
                }
            })
            put("type", mapInterfaceType(capabilities))
            put("dns_servers", JSONArray().apply {
                for (dnsServer in linkProperties.dnsServers) {
                    put(dnsServer.hostAddress)
                }
            })
            put("expensive", isExpensive(capabilities))
            put("constrained", false)
        }
    }

    private fun buildFlags(networkInterface: NetworkInterface?): Int {
        if (networkInterface == null) return 1
        var flags = 0
        if (runCatching { networkInterface.isUp }.getOrDefault(true)) flags = flags or 1
        if (runCatching { networkInterface.isLoopback }.getOrDefault(false)) flags = flags or 4
        if (runCatching { networkInterface.isPointToPoint }.getOrDefault(false)) flags = flags or 8
        if (runCatching { networkInterface.supportsMulticast() }.getOrDefault(false)) flags = flags or 16
        return flags
    }

    private fun mapInterfaceType(capabilities: NetworkCapabilities): Int {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
            else -> 3
        }
    }

    private fun isExpensive(capabilities: NetworkCapabilities): Boolean {
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun normalizeWifiSsid(rawSsid: String?): String {
        val ssid = rawSsid?.trim().orEmpty()
        if (ssid.isEmpty() || ssid.equals("<unknown ssid>", ignoreCase = true)) {
            return ""
        }
        return ssid.removeSurrounding("\"")
    }

    fun hasForegroundWifiLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasBackgroundWifiLocationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun canReadWifiIdentityInBackground(): Boolean {
        return hasForegroundWifiLocationPermission() && hasBackgroundWifiLocationPermission()
    }

    private fun canReadWifiIdentity(): Boolean {
        if (!hasForegroundWifiLocationPermission()) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasBackgroundWifiLocationPermission()) {
            return true
        }
        return isAppVisible()
    }

    private fun isAppVisible(): Boolean {
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = activityManager.runningAppProcesses ?: return false
        return processes.any { process ->
            process.pkgList?.contains(app.packageName) == true &&
                process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    fun maybeShowRedactedWifiToastOnConnect() {
        if (!wifiRuleMonitoringEnabled) {
            return
        }
        if (!hasForegroundWifiLocationPermission() || !hasBackgroundWifiLocationPermission()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    app,
                    R.string.wifi_geolocation_permission_required_toast,
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        val wifiState = wifiState()
        val parts = wifiState.split(",", limit = 2)
        val ssid = parts.getOrElse(0) { "" }
        val bssid = parts.getOrElse(1) { "" }
        val isRedacted = ssid.isEmpty() && bssid == "02:00:00:00:00:00"
        if (isRedacted) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    app,
                    R.string.wifi_geolocation_required_toast,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

}
