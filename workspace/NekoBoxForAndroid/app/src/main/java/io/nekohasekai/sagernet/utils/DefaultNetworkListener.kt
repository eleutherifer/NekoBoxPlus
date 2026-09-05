package io.nekohasekai.sagernet.utils

import android.net.ConnectivityManager
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
    private sealed class NetworkMessage {
        class Start(
            val key: Any,
            val listener: (Network?) -> Unit,
        ) : NetworkMessage()

        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Stop(
            val key: Any,
        ) : NetworkMessage()

        class Available(
            val network: Network,
        ) : NetworkMessage()

        class CapabilitiesChanged(
            val network: Network,
        ) : NetworkMessage()

        class Lost(
            val network: Network,
        ) : NetworkMessage()
    }

    private val networkActor =
        AppGraph.applicationScope.actor<NetworkMessage>(AppGraph.dispatchers.default) {
            val listeners = mutableMapOf<Any, (Network?) -> Unit>()
            val selector = PhysicalNetworkSelector<Network>()
            val pendingRequests = arrayListOf<NetworkMessage.Get>()

            fun currentNetwork(): Network? = selector.selected?.key

            // Publish the (possibly null) selected physical network to every
            // listener, completing pending get() requests first when non-null.
            fun publish(network: Network?) {
                if (network != null) {
                    pendingRequests.forEach { it.response.complete(network) }
                    pendingRequests.clear()
                }
                listeners.values.forEach { it(network) }
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
                            if (registered) unregister()
                        }
                    }

                    is NetworkMessage.Available -> {
                        val candidate = candidateFor(message.network)
                        if (candidate != null && selector.available(candidate)) {
                            publish(selector.selected?.key)
                        }
                    }

                    is NetworkMessage.CapabilitiesChanged -> {
                        val candidate = candidateFor(message.network)
                        if (candidate != null) {
                            val changed = selector.capabilitiesChanged(candidate)
                            val selected = selector.selected
                            if (changed || selected?.key == message.network) {
                                publish(selected?.key)
                            }
                        }
                    }

                    is NetworkMessage.Lost -> {
                        val changed = selector.remove(message.network)
                        // Publish null only once the last usable physical candidate is gone.
                        if (changed) publish(selector.selected?.key)
                    }
                }
            }
        }

    suspend fun start(
        key: Any,
        listener: (Network?) -> Unit,
    ) = networkActor.send(NetworkMessage.Start(key, listener))

    suspend fun get(): Network =
        if (fallback) {
            activeNetworkOrThrow()
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
        ) { // it's a good idea to refresh capabilities
            runBlocking { networkActor.send(NetworkMessage.CapabilitiesChanged(network)) }
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
        val interfaceName = linkProperties?.interfaceName
        return PhysicalNetworkSelector.Candidate(
            key = network,
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            notRestricted = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
            notVpn = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            isVpnTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            isTunInterface = interfaceName != null && interfaceName.startsWith("tun"),
        )
    }
}
