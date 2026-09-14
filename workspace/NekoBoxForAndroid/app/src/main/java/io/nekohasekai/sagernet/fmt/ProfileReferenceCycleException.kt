package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app

internal class ProfileReferenceCycleException(names: List<String>) :
    IllegalStateException("Profile reference cycle: ${names.joinToString(" → ")}") {
    private val path = names.joinToString(" → ")

    override fun getLocalizedMessage(): String = app.getString(R.string.profile_reference_cycle, path)
}
