package io.nekohasekai.sagernet.ui

object ProfileImportPolicy {

    sealed class Confirmation {
        data class Single(val profileName: String) : Confirmation()
        data class Multiple(val count: Int) : Confirmation()
    }

    fun confirmation(profileNames: List<String>): Confirmation {
        require(profileNames.isNotEmpty()) { "At least one profile is required" }
        return if (profileNames.size == 1) {
            Confirmation.Single(profileNames.first())
        } else {
            Confirmation.Multiple(profileNames.size)
        }
    }
}
