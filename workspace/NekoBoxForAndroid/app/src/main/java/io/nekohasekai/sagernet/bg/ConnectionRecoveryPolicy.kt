package io.nekohasekai.sagernet.bg

internal fun shouldResetConnections(resetRequested: Boolean, urlTestRunning: Boolean): Boolean =
    resetRequested && !urlTestRunning
