package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChangeRecoveryPolicyTest {

    @Test
    fun initialNetworkDoesNotRecover() {
        val policy = NetworkChangeRecoveryPolicy()

        val decision = policy.onNetworkChanged(
            interfaceName = "rmnet0",
            isVpnNetwork = false,
            reconnectEnabled = true,
            resetEnabled = true,
        )

        assertFalse(decision.reconnect)
        assertFalse(decision.reset)
    }

    @Test
    fun initialNullThenNetworkDoesNotRecover() {
        val policy = NetworkChangeRecoveryPolicy()

        policy.onNetworkChanged(
            interfaceName = null,
            isVpnNetwork = false,
            reconnectEnabled = true,
            resetEnabled = true,
        )
        val decision = policy.onNetworkChanged(
            interfaceName = "rmnet0",
            isVpnNetwork = false,
            reconnectEnabled = true,
            resetEnabled = true,
        )

        assertFalse(decision.reconnect)
        assertFalse(decision.reset)
    }

    @Test
    fun knownInterfaceSwitchRecovers() {
        val policy = NetworkChangeRecoveryPolicy()

        policy.onNetworkChanged(
            interfaceName = "rmnet0",
            isVpnNetwork = false,
            reconnectEnabled = true,
            resetEnabled = true,
        )
        val decision = policy.onNetworkChanged(
            interfaceName = "wlan0",
            isVpnNetwork = false,
            reconnectEnabled = true,
            resetEnabled = true,
        )

        assertTrue(decision.reconnect)
        assertTrue(decision.reset)
    }

    @Test
    fun knownInterfaceLossResetsWithoutReconnect() {
        val policy = NetworkChangeRecoveryPolicy()

        policy.onNetworkChanged(
            interfaceName = "wlan0",
            isVpnNetwork = false,
            reconnectEnabled = true,
            resetEnabled = true,
        )
        val decision = policy.onNetworkChanged(
            interfaceName = null,
            isVpnNetwork = false,
            reconnectEnabled = true,
            resetEnabled = true,
        )

        assertFalse(decision.reconnect)
        assertTrue(decision.reset)
    }

    @Test
    fun knownInterfaceLossDefersReconnectUntilNextNetwork() {
        val policy = NetworkChangeRecoveryPolicy()

        policy.onNetworkChanged(
            interfaceName = "wlan0",
            isVpnNetwork = false,
            reconnectEnabled = true,
            resetEnabled = true,
        )
        policy.onNetworkChanged(
            interfaceName = null,
            isVpnNetwork = false,
            reconnectEnabled = true,
            resetEnabled = true,
        )
        val decision = policy.onNetworkChanged(
            interfaceName = "rmnet0",
            isVpnNetwork = false,
            reconnectEnabled = true,
            resetEnabled = true,
        )

        assertTrue(decision.reconnect)
        assertTrue(decision.reset)
    }

    @Test
    fun vpnNetworkDoesNotReconnect() {
        val policy = NetworkChangeRecoveryPolicy()

        policy.onNetworkChanged(
            interfaceName = "wlan0",
            isVpnNetwork = false,
            reconnectEnabled = true,
            resetEnabled = true,
        )
        val decision = policy.onNetworkChanged(
            interfaceName = "tun0",
            isVpnNetwork = true,
            reconnectEnabled = true,
            resetEnabled = true,
        )

        assertFalse(decision.reconnect)
        assertTrue(decision.reset)
        assertTrue(decision.ignoredReconnectForVpn)
    }

    @Test
    fun repeatedInterfaceDoesNothing() {
        val policy = NetworkChangeRecoveryPolicy()

        policy.onNetworkChanged(
            interfaceName = "wlan0",
            isVpnNetwork = false,
            reconnectEnabled = true,
            resetEnabled = true,
        )
        val decision = policy.onNetworkChanged(
            interfaceName = "wlan0",
            isVpnNetwork = false,
            reconnectEnabled = true,
            resetEnabled = true,
        )

        assertFalse(decision.reconnect)
        assertFalse(decision.reset)
    }
}
