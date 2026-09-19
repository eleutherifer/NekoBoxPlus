package io.nekohasekai.sagernet.bg

internal class NetworkChangeRecoveryPolicy {
    data class Decision(
        val oldInterfaceName: String?,
        val newInterfaceName: String?,
        val reconnect: Boolean = false,
        val reset: Boolean = false,
        val ignoredReconnectForVpn: Boolean = false,
    ) {
        val changed: Boolean
            get() = oldInterfaceName != newInterfaceName
    }

    private var observedInitialState = false
    private var currentInterfaceName: String? = null
    private var pendingReconnectAfterLoss = false

    fun onNetworkChanged(
        interfaceName: String?,
        isVpnNetwork: Boolean,
        reconnectEnabled: Boolean,
        resetEnabled: Boolean,
    ): Decision {
        val oldInterfaceName = currentInterfaceName

        if (!observedInitialState) {
            observedInitialState = true
            currentInterfaceName = interfaceName
            return Decision(oldInterfaceName, interfaceName)
        }

        if (oldInterfaceName == interfaceName) {
            return Decision(oldInterfaceName, interfaceName)
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
        if (lostKnownInterface) {
            pendingReconnectAfterLoss = reconnectEnabled
        } else if (interfaceName != null && !isVpnNetwork) {
            pendingReconnectAfterLoss = false
        }

        return Decision(
            oldInterfaceName = oldInterfaceName,
            newInterfaceName = interfaceName,
            reconnect = reconnect,
            reset = reset,
            ignoredReconnectForVpn = ignoredReconnectForVpn,
        )
    }
}
