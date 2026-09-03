package io.nekohasekai.sagernet.ui

import android.net.NetworkCapabilities
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.PingFailureKind
import io.nekohasekai.sagernet.bg.proto.ProfileStatusUpdater
import io.nekohasekai.sagernet.bg.proto.TcpPingOutcome
import io.nekohasekai.sagernet.bg.proto.TcpPingRequest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import io.nekohasekai.sagernet.utils.ProfileCountryResolver
import kotlinx.coroutines.withTimeoutOrNull

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
                val outcome = libcoreTcpPingProbe { host ->
                    network.getAllByName(host).mapNotNull { it.hostAddress }
                }.execute(
                    TcpPingRequest(
                        host = bean.serverAddress,
                        port = bean.serverPort.toString(),
                        timeoutMillis = 3_000,
                        hardened = DataStore.connectionTestHardened,
                        hostIsIpAddress = bean.serverAddress.isIpAddress(),
                    ),
                )
                when (outcome) {
                    is TcpPingOutcome.Success -> {
                        ProfileStatusUpdater.update(
                            profile.id,
                            status = 1,
                            ping = outcome.latency,
                            reloadDelayOrderedGroup = false,
                        )
                        ProfileCountryResolver.updateFromAddress(
                            profile.id,
                            outcome.address,
                            ProfileCountryResolver.SOURCE_ENDPOINT,
                        )
                    }
                    is TcpPingOutcome.Failure -> ProfileStatusUpdater.update(
                        profile.id,
                        status = if (outcome.kind == PingFailureKind.Other) 3 else 2,
                        error = outcome.kind.localizedMessage(outcome.detail),
                        reloadDelayOrderedGroup = false,
                    )
                }
            } catch (e: Exception) {
                Logs.w(e)
                ProfileStatusUpdater.update(
                    profile.id,
                    status = 3,
                    error = e.readableMessage,
                    reloadDelayOrderedGroup = false,
                )
            }
        }
    }
}
