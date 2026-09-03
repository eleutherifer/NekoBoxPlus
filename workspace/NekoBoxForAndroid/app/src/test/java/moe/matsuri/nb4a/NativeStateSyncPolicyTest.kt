package moe.matsuri.nb4a

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStateSyncPolicyTest {

    @Test
    fun shouldSyncNetworkStateOnlyWhenEffectiveStateChanges() {
        val policy = NativeStateSyncPolicy()

        assertTrue(policy.shouldSyncNetworkState("rmnet0", "[1]"))
        assertFalse(policy.shouldSyncNetworkState("rmnet0", "[1]"))
        assertTrue(policy.shouldSyncNetworkState("wlan0", "[1]"))
        assertFalse(policy.shouldSyncNetworkState("wlan0", "[1]"))
        assertTrue(policy.shouldSyncNetworkState("wlan0", "[2]"))
    }

    @Test
    fun resetNetworkStateForcesNextSync() {
        val policy = NativeStateSyncPolicy()

        assertTrue(policy.shouldSyncNetworkState("rmnet0", "[1]"))
        assertFalse(policy.shouldSyncNetworkState("rmnet0", "[1]"))

        policy.resetNetworkState()

        assertTrue(policy.shouldSyncNetworkState("rmnet0", "[1]"))
    }
}
