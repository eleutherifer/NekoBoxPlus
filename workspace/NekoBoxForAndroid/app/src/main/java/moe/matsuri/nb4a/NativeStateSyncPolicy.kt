package moe.matsuri.nb4a

internal class NativeStateSyncPolicy {
    private var lastDefaultInterface: String? = null
    private var lastInterfaces: String? = null

    fun shouldSyncNetworkState(defaultInterface: String, interfaces: String): Boolean {
        if (defaultInterface == lastDefaultInterface && interfaces == lastInterfaces) {
            return false
        }
        lastDefaultInterface = defaultInterface
        lastInterfaces = interfaces
        return true
    }

    fun resetNetworkState() {
        lastDefaultInterface = null
        lastInterfaces = null
    }
}
