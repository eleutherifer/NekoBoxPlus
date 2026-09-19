package io.nekohasekai.sagernet.ui

import android.net.NetworkCapabilities
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.ProfileStatusUpdater
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.withTimeoutOrNull
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.net.UnknownHostException

object ProfileTcpPingController {
    fun start(profile: ProxyEntity) {
        runOnDefaultDispatcher {
            if (profile.containsMasterDnsVPN() || !profile.requireBean().canTCPing()) {
                ProfileStatusUpdater.update(
                    profile.id,
                    status = 3,
                    error = app.getString(R.string.connection_test_tcp_ping_unavailable),
                    reloadDelayOrderedGroup = false,
                )
                return@runOnDefaultDispatcher
            }
            ProfileStatusUpdater.update(profile.id, status = 0, reloadDelayOrderedGroup = false)
            try {
                val network = withTimeoutOrNull(5000L) {
                    runCatching { DefaultNetworkListener.get() }.getOrNull()
                } ?: error(app.getString(R.string.connection_test_unreachable))
                val capabilities = SagerNet.connectivity.getNetworkCapabilities(network)
                    ?: error(app.getString(R.string.connection_test_unreachable))
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    error(app.getString(R.string.connection_test_unreachable))
                }
                val bean = profile.requireBean()
                if (DataStore.connectionTestHardened) {
                    val ping = Libcore.tcpPing(
                        bean.serverAddress,
                        bean.serverPort.toString(),
                        3000,
                        true,
                        LocalResolverImpl,
                    )
                    ProfileStatusUpdater.update(
                        profile.id,
                        status = 1,
                        ping = ping,
                        reloadDelayOrderedGroup = false,
                    )
                    return@runOnDefaultDispatcher
                }
                val addresses = if (bean.serverAddress.isIpAddress()) {
                    listOf(bean.serverAddress)
                } else {
                    try {
                        network.getAllByName(bean.serverAddress).mapNotNull { it.hostAddress }
                    } catch (_: UnknownHostException) {
                        emptyList()
                    }
                }
                if (addresses.isEmpty()) error(app.getString(R.string.connection_test_domain_not_found))
                var ping: Int? = null
                var lastError: Exception? = null
                for (address in addresses) {
                    try {
                        ping = Libcore.tcpPing(
                            address,
                            bean.serverPort.toString(),
                            3000,
                            false,
                            LocalResolverImpl,
                        )
                        break
                    } catch (e: Exception) {
                        lastError = e
                        if (!isAddressFamilyFailure(e)) throw e
                    }
                }
                ProfileStatusUpdater.update(
                    profile.id,
                    status = 1,
                    ping = ping ?: throw lastError ?: error("TCP ping failed"),
                    reloadDelayOrderedGroup = false,
                )
            } catch (e: Exception) {
                Logs.w(e)
                val message = e.readableMessage
                val friendly = when {
                    message.contains("resolve TCP ping host", ignoreCase = true) ->
                        app.getString(R.string.connection_test_domain_not_found)
                    message.contains("ECONNREFUSED") ->
                        app.getString(R.string.connection_test_refused)
                    isAddressFamilyFailure(e) ->
                        app.getString(R.string.connection_test_unreachable)
                    message.contains("deadline exceeded", ignoreCase = true) ||
                        message.contains("timed out", ignoreCase = true) ->
                        app.getString(R.string.connection_test_timeout_error)
                    else -> message
                }
                ProfileStatusUpdater.update(
                    profile.id,
                    status = if (friendly == message) 3 else 2,
                    error = friendly,
                    reloadDelayOrderedGroup = false,
                )
            }
        }
    }

    private fun isAddressFamilyFailure(error: Throwable): Boolean {
        val message = error.readableMessage
        return message.contains("ENETUNREACH") ||
            message.contains("EHOSTUNREACH") ||
            message.contains("EAFNOSUPPORT")
    }
}
