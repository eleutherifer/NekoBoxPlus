package io.nekohasekai.sagernet.utils

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.app.AppGraph
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.runBlocking
import java.net.UnknownHostException

object DefaultNetworkListener {
    data class Snapshot(
        val network: Network,
        val interfaceName: String?,
        val validated: Boolean?,
        val isVpnNetwork: Boolean,
    )

    private sealed class NetworkMessage {
        class Start(
            val key: Any,
            val listener: (Snapshot?) -> Unit,
        ) : NetworkMessage()

        class Get : NetworkMessage() {
            val response = CompletableDeferred<Snapshot>()
        }

        class Stop(
            val key: Any,
        ) : NetworkMessage()

        class Available(
            val network: Network,
        ) : NetworkMessage()

        class CapabilitiesChanged(
            val network: Network,
            val capabilities: NetworkCapabilities,
        ) : NetworkMessage()

        class LinkChanged(
            val network: Network,
            val properties: LinkProperties,
        ) : NetworkMessage()

        class Lost(
            val network: Network,
        ) : NetworkMessage()
    }

    private val networkActor =
        AppGraph.applicationScope.actor<NetworkMessage>(AppGraph.dispatchers.default) {
            val listeners = mutableMapOf<Any, (Snapshot?) -> Unit>()
            val selector = PhysicalNetworkSelector<Network>()
            val pendingRequests = arrayListOf<NetworkMessage.Get>()

            val capabilities = mutableMapOf<Network, NetworkCapabilities>()
            val links = mutableMapOf<Network, LinkProperties>()
            val announced = mutableSetOf<Network>()

            fun currentNetwork(): Snapshot? = selector.selected?.key?.let { network ->
                snapshot(network, capabilities[network], links[network])
            }

            // Publish the (possibly null) selected physical network to every
            // listener, completing pending get() requests first when non-null.
            fun publish(network: Snapshot?) {
                if (network != null) {
                    pendingRequests.forEach { it.response.complete(network) }
                    pendingRequests.clear()
                }
                listeners.values.forEach { it(network) }
            }

            fun refresh(network: Network) {
                val caps = capabilities[network] ?: return
                val link = links[network] ?: return
                val changed = selector.capabilitiesChanged(candidateFor(network, caps, link))
                if (changed || selector.selected?.key == network) publish(currentNetwork())
            }

            for (message in channel) {
                when (message) {
                    is NetworkMessage.Start -> {
                        if (listeners.isEmpty()) register()
                        listeners[message.key] = message.listener
                        // Replay the current selection so a newly registered listener is
                        // never left unaware of an already-selected physical network.
                        currentNetwork()?.let(message.listener)
                    }

                    is NetworkMessage.Get -> {
                        check(listeners.isNotEmpty()) { "Getting network without any listeners is not supported" }
                        val current = currentNetwork()
                        if (current != null) {
                            message.response.complete(current)
                        } else {
                            pendingRequests += message
                        }
                    }

                    is NetworkMessage.Stop -> {
                        if (listeners.isNotEmpty() && // was not empty
                            listeners.remove(message.key) != null && listeners.isEmpty()
                        ) {
                            selector.clear()
                            capabilities.clear()
                            links.clear()
                            announced.clear()
                            if (registered) unregister()
                        }
                    }

                    is NetworkMessage.Available -> {
                        announced += message.network
                        selector.announce(message.network)
                        // Before O, initial capability/link callbacks are not guaranteed.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                            SagerNet.connectivity.getNetworkCapabilities(message.network)?.let {
                                capabilities[message.network] = it
                            }
                            SagerNet.connectivity.getLinkProperties(message.network)?.let {
                                links[message.network] = it
                            }
                        }
                        refresh(message.network)
                    }

                    is NetworkMessage.CapabilitiesChanged -> {
                        if (message.network in announced) {
                            capabilities[message.network] = message.capabilities
                            refresh(message.network)
                        }
                    }

                    is NetworkMessage.LinkChanged -> {
                        if (message.network in announced) {
                            links[message.network] = message.properties
                            refresh(message.network)
                        }
                    }

                    is NetworkMessage.Lost -> {
                        announced -= message.network
                        capabilities.remove(message.network)
                        links.remove(message.network)
                        if (selector.remove(message.network)) publish(currentNetwork())
                    }
                }
            }
        }

    suspend fun start(
        key: Any,
        listener: (Network?) -> Unit,
    ) = startSnapshots(key) { listener(it?.network) }

    suspend fun startSnapshots(key: Any, listener: (Snapshot?) -> Unit) =
        networkActor.send(NetworkMessage.Start(key, listener))

    suspend fun get(): Network = getSnapshot().network

    suspend fun getSnapshot(): Snapshot =
        if (fallback) {
            activeNetworkOrThrow().let { network ->
                snapshot(
                    network,
                    SagerNet.connectivity.getNetworkCapabilities(network),
                    SagerNet.connectivity.getLinkProperties(network),
                )
            }
        } else {
            NetworkMessage.Get().run {
                networkActor.send(this)
                response.await()
            }
        }

    // Failed to register a callback; fall back to polling the active network.
    @RequiresApi(Build.VERSION_CODES.M)
    private fun activeNetworkOrThrow(): Network =
        sequenceOf(SagerNet.connectivity.activeNetwork)
            .plus(SagerNet.connectivity.allNetworks.asSequence())
            .filterNotNull()
            .distinct()
            .firstOrNull { network -> candidateFor(network)?.isUsable == true }
            ?: throw UnknownHostException("no usable physical network")

    suspend fun stop(key: Any) = networkActor.send(NetworkMessage.Stop(key))

    // NB: this runs in ConnectivityThread, and this behavior cannot be changed until API 26
    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) =
            runBlocking { networkActor.send(NetworkMessage.Available(network)) }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            runBlocking {
                networkActor.send(NetworkMessage.CapabilitiesChanged(network, NetworkCapabilities(networkCapabilities)))
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            runBlocking { networkActor.send(NetworkMessage.LinkChanged(network, linkProperties)) }
        }

        override fun onLost(network: Network) = runBlocking { networkActor.send(NetworkMessage.Lost(network)) }
    }

    private var fallback = false
    private var registered = false

    // Only candidates with INTERNET, NOT_RESTRICTED and NOT_VPN are considered; the
    // platform can still surface our own VPN over registerDefaultNetworkCallback on
    // older APIs, so TRANSPORT_VPN and TUN interfaces are rejected again in
    // candidateFor()/isUsable() as defense in depth.
    private val request =
        NetworkRequest
            .Builder()
            .apply {
                addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                if (Build.VERSION.SDK_INT == 23) { // workarounds for OEM bugs
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                }
            }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register() {
        try {
            fallback = false
            registered = false
            when (Build.VERSION.SDK_INT) {
                in 31..Int.MAX_VALUE -> {
                    registerBestMatchingNetwork()
                }

                in 28 until 31 -> {
                    registerRequestNetwork()
                }

                // REQUEST instead of LISTEN
                in 26 until 28 -> {
                    registerDefaultWithHandler()
                }

                in 24 until 26 -> {
                    registerDefaultNetwork()
                }

                else -> {
                    SagerNet.connectivity.requestNetwork(request, Callback)
                    // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
            }
            registered = true
        } catch (e: Exception) {
            Logs.w(e)
            fallback = true
        }
    }

    // Annotated helpers keep NewApi lint satisfied without the fragile
    // annotated-lambda-as-when-branch pattern (which reformatters can break).

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerBestMatchingNetwork() {
        SagerNet.connectivity.registerBestMatchingNetworkCallback(request, Callback, mainHandler)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun registerRequestNetwork() {
        SagerNet.connectivity.requestNetwork(request, Callback, mainHandler)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun registerDefaultWithHandler() {
        SagerNet.connectivity.registerDefaultNetworkCallback(Callback, mainHandler)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun registerDefaultNetwork() {
        SagerNet.connectivity.registerDefaultNetworkCallback(Callback)
    }

    private fun unregister() {
        try {
            SagerNet.connectivity.unregisterNetworkCallback(Callback)
        } catch (e: IllegalArgumentException) {
            // Some OEM implementations can report a failed registration only
            // after partially accepting it. Do not let teardown kill the actor.
            Logs.w(e)
        } finally {
            registered = false
        }
    }

    /** Build a selector candidate from live capabilities/link properties. */
    private fun candidateFor(network: Network): PhysicalNetworkSelector.Candidate<Network>? {
        val capabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: return null
        val linkProperties = SagerNet.connectivity.getLinkProperties(network)
        return candidateFor(network, capabilities, linkProperties)
    }

    private fun snapshot(network: Network, capabilities: NetworkCapabilities?, link: LinkProperties?) =
        Snapshot(
            network,
            link?.interfaceName,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ||
                link?.interfaceName?.startsWith("tun") == true,
        )

    private fun candidateFor(network: Network, capabilities: NetworkCapabilities, link: LinkProperties?) =
        PhysicalNetworkSelector.Candidate(
            key = network,
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            notRestricted = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
            notVpn = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            isVpnTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            isTunInterface = link?.interfaceName?.startsWith("tun") == true,
        )
}
