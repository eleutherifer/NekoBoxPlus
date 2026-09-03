package io.nekohasekai.sagernet.bg

internal class ConnectionTestSessionState {
    data class Presentation(val status: String, val ipInfo: String?)

    private var claimed = false
    private var presentation: Presentation? = null

    @Synchronized
    fun claim(connected: Boolean): Boolean {
        if (!connected || claimed) return false
        claimed = true
        return true
    }

    @Synchronized
    fun presentation(): Presentation? = presentation

    @Synchronized
    fun setPresentation(connected: Boolean, status: String?, ipInfo: String?) {
        if (!connected || status == null) return
        presentation = Presentation(status, ipInfo)
    }

    @Synchronized
    fun reset() {
        claimed = false
        presentation = null
    }
}
