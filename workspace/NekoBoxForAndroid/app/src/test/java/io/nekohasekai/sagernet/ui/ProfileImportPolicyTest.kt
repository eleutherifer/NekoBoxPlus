package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileImportPolicyTest {

    @Test
    fun singleProfileUsesProfileName() {
        val confirmation = ProfileImportPolicy.confirmation(listOf("server-a"))

        assertEquals(ProfileImportPolicy.Confirmation.Single("server-a"), confirmation)
    }

    @Test
    fun multipleProfilesUseCount() {
        val confirmation = ProfileImportPolicy.confirmation(listOf("server-a", "server-b"))

        assertEquals(ProfileImportPolicy.Confirmation.Multiple(2), confirmation)
    }

    @Test
    fun emptyProfileListIsRejected() {
        val result = runCatching { ProfileImportPolicy.confirmation(emptyList()) }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
