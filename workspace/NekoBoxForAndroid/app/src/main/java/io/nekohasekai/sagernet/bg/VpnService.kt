package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.NGUtil.isPureIpAddress
import android.net.VpnService as BaseVpnService

internal data class VpnTunPlan(
    val core: AndroidTunPayload.Plan,
    val metered: Boolean,
    val bypassApplications: Boolean?,
    val applications: List<String>,
    val httpProxy: VpnHttpProxyPlan?,
)

internal data class VpnHttpProxyPlan(
    val host: String,
    val port: Int,
    val exclusions: List<String>,
)

class VpnService :
    BaseVpnService(),
    BaseService.Interface {
    companion object {
        private const val VPN_NETWORK_TEARDOWN_TIMEOUT_MS = 1_500L
        private const val VPN_NETWORK_TEARDOWN_POLL_MS = 100L
    }

    var conn: ParcelFileDescriptor? = null

    private var metered = false
    private var activeTunPlan: VpnTunPlan? = null
    private var reuseTunOnNextOpen = false

    override var upstreamInterfaceName: String? = null

    override suspend fun startProcesses() {
        DataStore.vpnService = this
        super.startProcesses() // launch proxy instance
    }

    override var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock =
            SagerNet.power
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:vpn")
                .apply { acquire() }
    }

    override fun finalizeProcessCleanup(retainTun: Boolean) {
        if (retainTun && conn != null) {
            reuseTunOnNextOpen = true
            Logs.d("Retaining Android VPN file descriptor for in-process restart")
        } else {
            closeTunConnection()
        }
    }

    override suspend fun beforeRestartAfterStop(tunRetained: Boolean) {
        if (tunRetained && conn != null) return
        val deadline = SystemClock.elapsedRealtime() + VPN_NETWORK_TEARDOWN_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline && isVpnNetwork(SagerNet.connectivity.activeNetwork)) {
            delay(VPN_NETWORK_TEARDOWN_POLL_MS)
        }
        if (isVpnNetwork(SagerNet.connectivity.activeNetwork)) {
            Logs.w("VPN network still active after teardown wait")
        }
    }

    override fun onBind(intent: Intent) =
        when (intent.action) {
            SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
            else -> super<BaseService.Interface>.onBind(intent)
        }

    override val data = BaseService.Data(this)
    override val tag = "SagerNetVpnService"

    override fun createNotification(profile: ProxyEntity?) = ServiceNotification(
        this,
        profile?.let {
            ServiceNotification.genNotificationTitle(it, DataStore.notificationCountryIndicator)
        }.orEmpty(),
        "service-vpn",
        profile = profile,
    )

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (DataStore.serviceMode == Key.MODE_VPN) {
            if (LocalNetworkPermission.isRequired(this, DataStore.tunImplementation)) {
                DataStore.tunImplementation = TunImplementation.GVISOR
            }
            if (prepare(this) != null) {
                startActivity(
                    Intent(
                        this,
                        VpnRequestActivity::class.java,
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
            }
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }

    inner class NullConnectionException :
        NullPointerException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    fun startVpn(
        tunPayloadJson: String,
        @Suppress("UNUSED_PARAMETER") tunPlatformOptionsJson: String,
    ): Int {
        // The address, DNS, MTU and route ranges come from the authoritative
        // payload produced by libcore (flattened from sing-box's effective TUN
        // options). Only per-app routing, HTTP proxy, metering, the session name
        // and the ParcelFileDescriptor lifecycle remain app-owned below.
        val plan = AndroidTunPayload.parse(tunPayloadJson)
        metered = DataStore.meteredNetwork
        val builder =
            Builder()
                .setConfigureIntent(SagerNet.configureIntent(this))
                .setSession(getString(R.string.app_name))
                .setMtu(plan.mtu)

        // interface addresses (IPv4 and/or IPv6, never an unrequested family)
        plan.inet4Address?.let { builder.addAddress(it.address, it.prefixLength) }
        plan.inet6Address?.let { builder.addAddress(it.address, it.prefixLength) }

        // Effective in-TUN DNS servers computed by sing-tun 1.14 from
        // dns_mode/dns_address and the configured address families.
        plan.dnsServers.forEach(builder::addDnsServer)

        // route ranges flattened by sing-box BuildAutoRouteRanges(true)
        plan.inet4Routes.forEach { builder.addRoute(it.address, it.prefixLength) }
        plan.inet6Routes.forEach { builder.addRoute(it.address, it.prefixLength) }

        updateUnderlyingNetwork(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)

        // app route
        val packageName = packageName
        val proxyApps = DataStore.proxyApps
        val adblockSystemWideFilter = DataStore.adblockSystemWideFilter
        var bypass = DataStore.bypass
        val workaroundSYSTEM = false // DataStore.tunImplementation == TunImplementation.SYSTEM
        val needBypassRootUid =
            workaroundSYSTEM ||
                data.proxy!!.config.trafficMap.values.any {
                    it[0].hysteriaBean?.protocol == HysteriaBean.PROTOCOL_FAKETCP
                }

        // List of all per-rule packages where outbound is not Bypass.
        // This adds those packages to the TUN package list and enables user-defined rules for them.
        // We don't mess with bypass since we can't guarantee that the rule will match because
        // runtime changes like network change may have an effect on that.
        val packagesFromRules: List<String> =
            SagerDatabase.rulesDao
                .enabledRules()
                .flatMap { rule ->
                    if (rule.packages.isNotEmpty() && rule.outbound != -1L) {
                        rule.packages
                    } else {
                        emptySet()
                    }
                }.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        val adblockPackages =
            DataStore.adblockIncludedPackages
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()

        var effectiveBypass: Boolean? = null
        var effectiveApplications = emptyList<String>()
        if (proxyApps || adblockSystemWideFilter || needBypassRootUid) {
            val individual = mutableSetOf<String>()
            val allApps by lazy {
                packageManager
                    .getInstalledApplications(PackageManager.GET_META_DATA)
                    .map { it.packageName }
                    .filter { it != packageName }
            }
            if (adblockSystemWideFilter) {
                individual.addAll(allApps)
                bypass = false
            } else if (proxyApps) {
                individual.addAll(
                    DataStore.individual
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() },
                )

                if (!bypass) {
                    individual.addAll(packagesFromRules)
                    individual.addAll(adblockPackages)
                } else {
                    individual.removeAll(packagesFromRules.toSet())
                    individual.removeAll(adblockPackages.toSet())
                }

                if (bypass && needBypassRootUid) {
                    val individualNew = allApps.toMutableList()
                    individualNew.removeAll(individual)
                    individual.clear()
                    individual.addAll(individualNew)
                    bypass = false
                }
            } else {
                individual.addAll(allApps)
                bypass = false
            }

            val added = mutableListOf<String>()

            individual
                .apply {
                    // Allow Matsuri itself using VPN.
                    remove(packageName)
                    if (!bypass) add(packageName)
                }.forEach {
                    try {
                        if (bypass) {
                            builder.addDisallowedApplication(it)
                        } else {
                            builder.addAllowedApplication(it)
                        }
                        added.add(it)
                    } catch (ex: PackageManager.NameNotFoundException) {
                        Logs.w(ex)
                    }
                }

            if (bypass) {
                Logs.d("Add bypass: ${added.joinToString(", ")}")
            } else {
                Logs.d("Add allow: ${added.joinToString(", ")}")
            }
            effectiveBypass = bypass
            effectiveApplications = added.sorted()
        }

        var httpProxyPlan: VpnHttpProxyPlan? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DataStore.appendHttpProxy && DataStore.requireProxyInVPN) {
            val proxyHost =
                when {
                    isPureIpAddress(DataStore.mixedListener) -> DataStore.mixedListener
                    else -> LOCALHOST
                }
            val exclusions = DataStore.httpProxyBypass.lines().mapNotNull { line ->
                line.trim().takeIf { it.isNotBlank() && !it.startsWith("#") }
            }
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    proxyHost,
                    DataStore.mixedPort,
                    exclusions,
                ),
            )
            httpProxyPlan = VpnHttpProxyPlan(proxyHost, DataStore.mixedPort, exclusions)
        }

        val requestedTunPlan = VpnTunPlan(
            core = plan,
            metered = metered,
            bypassApplications = effectiveBypass,
            applications = effectiveApplications,
            httpProxy = httpProxyPlan,
        )
        if (reuseTunOnNextOpen &&
            ServiceLifecyclePolicy.isTunReuseEligible(data.activeRestartCause) &&
            activeTunPlan == requestedTunPlan
        ) {
            val retained = conn
            if (retained != null) {
                reuseTunOnNextOpen = false
                Logs.d("Reusing retained Android VPN file descriptor")
                updateUnderlyingNetwork()
                return retained.fd
            }
        }

        reuseTunOnNextOpen = false
        val previousConnection = conn
        val newConnection = builder.establish() ?: throw NullConnectionException()
        conn = newConnection
        activeTunPlan = requestedTunPlan
        previousConnection?.let { previous ->
            runCatching { previous.close() }.onFailure { error -> Logs.w(error) }
        }

        return newConnection.fd
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            SagerNet.underlyingNetwork?.let {
                builder?.setUnderlyingNetworks(arrayOf(SagerNet.underlyingNetwork))
                    ?: setUnderlyingNetworks(arrayOf(SagerNet.underlyingNetwork))
            }
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        closeTunConnection()
        DataStore.vpnService = null
        super.onDestroy()
        data.binder.close()
    }

    private fun closeTunConnection() {
        val connection = conn
        conn = null
        activeTunPlan = null
        reuseTunOnNextOpen = false
        connection?.let {
            Logs.d("Closing Android VPN file descriptor")
            runCatching { it.close() }.onFailure { error -> Logs.w(error) }
            Logs.d("Android VPN file descriptor closed")
        }
    }
}
