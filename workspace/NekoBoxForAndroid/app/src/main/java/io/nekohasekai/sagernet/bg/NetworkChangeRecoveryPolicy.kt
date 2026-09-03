package io.nekohasekai.sagernet.bg

internal class NetworkChangeRecoveryPolicy {
    data class Decision(
        val oldInterfaceName: String?,
        val newInterfaceName: String?,
        val oldNetworkHandle: Long?,
        val newNetworkHandle: Long?,
        val reconnect: Boolean = false,
        val reset: Boolean = false,
        val ignoredReconnectForVpn: Boolean = false,
    ) {
        val changed: Boolean
            get() = oldInterfaceName != newInterfaceName || oldNetworkHandle != newNetworkHandle
    }

    private var observedInitialState = false
    private var currentInterfaceName: String? = null
    private var currentNetworkHandle: Long? = null
    private var pendingReconnectAfterLoss = false

    fun onNetworkChanged(
        interfaceName: String?,
        networkHandle: Long? = null,
        isVpnNetwork: Boolean,
        reconnectEnabled: Boolean,
        resetEnabled: Boolean,
    ): Decision {
        val oldInterfaceName = currentInterfaceName
        val oldNetworkHandle = currentNetworkHandle

        if (!observedInitialState) {
            observedInitialState = true
            currentInterfaceName = interfaceName
            currentNetworkHandle = networkHandle
            return Decision(oldInterfaceName, interfaceName, oldNetworkHandle, networkHandle)
        }

        if (oldInterfaceName == interfaceName && oldNetworkHandle == networkHandle) {
            return Decision(oldInterfaceName, interfaceName, oldNetworkHandle, networkHandle)
        }

        val lostKnownInterface = oldInterfaceName != null && interfaceName == null
        val recoveredAfterLoss = oldInterfaceName == null &&
            interfaceName != null &&
            pendingReconnectAfterLoss
        val switchedKnownInterface = oldInterfaceName != null && interfaceName != null
        val reconnectCandidate = recoveredAfterLoss || switchedKnownInterface
        val reconnect = reconnectEnabled && reconnectCandidate && !isVpnNetwork
        val ignoredReconnectForVpn = reconnectEnabled && reconnectCandidate && isVpnNetwork
        val reset = resetEnabled && (lostKnownInterface || recoveredAfterLoss || switchedKnownInterface)

        currentInterfaceName = interfaceName
        currentNetworkHandle = networkHandle
        if (lostKnownInterface) {
            pendingReconnectAfterLoss = reconnectEnabled
        } else if (interfaceName != null && !isVpnNetwork) {
            pendingReconnectAfterLoss = false
        }

        return Decision(
            oldInterfaceName = oldInterfaceName,
            newInterfaceName = interfaceName,
            oldNetworkHandle = oldNetworkHandle,
            newNetworkHandle = networkHandle,
            reconnect = reconnect,
            reset = reset,
            ignoredReconnectForVpn = ignoredReconnectForVpn,
        )
    }
}
