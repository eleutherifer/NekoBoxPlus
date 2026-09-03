package io.nekohasekai.sagernet.widget

import io.nekohasekai.sagernet.aidl.ISagerNetService

internal class StatsBarConnectionCheckPolicy {
    data class ConnectedEvent(
        val session: Int,
        val shouldRunAutomaticCheck: Boolean,
    )

    private var connectedStateHandled = false
    private var connectionSession = 0
    private var retainedStatus: String? = null
    private var retainedIpInfo: String? = null
    private var service: ISagerNetService? = null

    val currentSession: Int
        get() = connectionSession

    fun bindService(service: ISagerNetService) {
        this.service = service
        retainedStatus = runCatching { service.connectionTestStatus() }.getOrNull()
        retainedIpInfo = runCatching { service.connectionTestIpInfo() }.getOrNull()
    }

    fun onConnected(): ConnectedEvent {
        val shouldRunAutomaticCheck = service?.let {
            runCatching { it.claimAutomaticConnectionCheck() }.getOrDefault(false)
        } ?: !connectedStateHandled
        connectedStateHandled = true
        return ConnectedEvent(connectionSession, shouldRunAutomaticCheck)
    }

    fun onDisconnected(): Int {
        connectedStateHandled = false
        retainedStatus = null
        retainedIpInfo = null
        return ++connectionSession
    }

    fun retainStatus(status: CharSequence) {
        if (!connectedStateHandled) return
        retainedStatus = status.toString()
        syncPresentation()
    }

    fun retainIpInfo(ipInfo: CharSequence?) {
        if (!connectedStateHandled) return
        retainedIpInfo = ipInfo?.toString()
        syncPresentation()
    }

    fun retainedPresentation(): Pair<String, String?>? {
        return retainedStatus?.let { it to retainedIpInfo }
    }

    fun isCurrent(session: Int): Boolean = session == connectionSession

    private fun syncPresentation() {
        service?.let {
            runCatching { it.setConnectionTestPresentation(retainedStatus, retainedIpInfo) }
        }
    }
}
