package io.nekohasekai.sagernet.bg

internal class NetworkChangeRecoveryPolicy {
    data class Decision(
        val oldInterfaceName: String?,
        val newInterfaceName: String?,
        val oldNetworkHandle: Long?,
        val newNetworkHandle: Long?,
        val oldValidated: Boolean?,
        val newValidated: Boolean?,
        val reconnect: Boolean = false,
        val reset: Boolean = false,
        val ignoredReconnectForVpn: Boolean = false,
    ) {
        val changed: Boolean
            get() = oldInterfaceName != newInterfaceName ||
                oldNetworkHandle != newNetworkHandle ||
                oldValidated != newValidated
    }

    private var observedInitialState = false
    private var currentInterfaceName: String? = null
    private var currentNetworkHandle: Long? = null
    private var currentValidated: Boolean? = null
    private var pendingRecoveryAfterLoss = false

    fun onNetworkChanged(
        interfaceName: String?,
        networkHandle: Long? = null,
        isVpnNetwork: Boolean,
        reconnectEnabled: Boolean,
        resetEnabled: Boolean,
        validated: Boolean? = null,
    ): Decision {
        val oldInterfaceName = currentInterfaceName
        val oldNetworkHandle = currentNetworkHandle
        val oldValidated = currentValidated

        if (!observedInitialState) {
            observedInitialState = true
            currentInterfaceName = interfaceName
            currentNetworkHandle = networkHandle
            currentValidated = validated
            return Decision(
                oldInterfaceName,
                interfaceName,
                oldNetworkHandle,
                networkHandle,
                oldValidated,
                validated,
            )
        }

        if (
            oldInterfaceName == interfaceName &&
            oldNetworkHandle == networkHandle &&
            oldValidated == validated
        ) {
            return Decision(
                oldInterfaceName,
                interfaceName,
                oldNetworkHandle,
                networkHandle,
                oldValidated,
                validated,
            )
        }

        val lostKnownInterface = oldInterfaceName != null && interfaceName == null
        val recoveredAfterLoss = oldInterfaceName == null &&
            interfaceName != null &&
            pendingRecoveryAfterLoss
        val networkIdentityChanged = oldInterfaceName != interfaceName || oldNetworkHandle != networkHandle
        val switchedKnownInterface = oldInterfaceName != null &&
            interfaceName != null &&
            networkIdentityChanged
        val reconnectCandidate = recoveredAfterLoss || switchedKnownInterface
        val reconnect = reconnectEnabled && reconnectCandidate && !isVpnNetwork
        val ignoredReconnectForVpn = reconnectEnabled && reconnectCandidate && isVpnNetwork
        val validationRecovered = oldInterfaceName == interfaceName &&
            oldNetworkHandle == networkHandle &&
            oldValidated == false &&
            validated == true
        val reset = resetEnabled &&
            (lostKnownInterface || recoveredAfterLoss || switchedKnownInterface || validationRecovered)

        currentInterfaceName = interfaceName
        currentNetworkHandle = networkHandle
        currentValidated = validated
        if (lostKnownInterface) {
            pendingRecoveryAfterLoss = true
        } else if (interfaceName != null && !isVpnNetwork) {
            pendingRecoveryAfterLoss = false
        }

        return Decision(
            oldInterfaceName = oldInterfaceName,
            newInterfaceName = interfaceName,
            oldNetworkHandle = oldNetworkHandle,
            newNetworkHandle = networkHandle,
            oldValidated = oldValidated,
            newValidated = validated,
            reconnect = reconnect,
            reset = reset,
            ignoredReconnectForVpn = ignoredReconnectForVpn,
        )
    }
}
