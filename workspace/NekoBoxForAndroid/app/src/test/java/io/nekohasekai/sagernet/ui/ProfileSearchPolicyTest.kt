package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSearchPolicyTest {
    private val allProfiles = listOf("alpha", "alpine", "beta")

    @Test
    fun appendedQueryReusesVisibleProfiles() {
        val visibleProfiles = listOf("alpha", "alpine")

        assertEquals(
            visibleProfiles,
            ProfileSearchPolicy.candidates("al", "alp", visibleProfiles, allProfiles),
        )
    }

    @Test
    fun shortenedQueryRechecksAllProfiles() {
        assertEquals(
            allProfiles,
            ProfileSearchPolicy.candidates("alp", "a", listOf("alpha"), allProfiles),
        )
    }

    @Test
    fun replacedQueryRechecksAllProfiles() {
        assertEquals(
            allProfiles,
            ProfileSearchPolicy.candidates("alpha", "beta", listOf("alpha"), allProfiles),
        )
    }

    @Test
    fun clearedQueryRechecksAllProfilesInOriginalOrder() {
        assertEquals(
            allProfiles,
            ProfileSearchPolicy.candidates("al", "", listOf("alpha", "alpine"), allProfiles),
        )
    }

    @Test
    fun caseChangeAndMiddleInsertionRecheckAllProfiles() {
        assertEquals(
            allProfiles,
            ProfileSearchPolicy.candidates("al", "AL", listOf("alpha", "alpine"), allProfiles),
        )
        assertEquals(
            allProfiles,
            ProfileSearchPolicy.candidates("ac", "abc", listOf("ac"), allProfiles),
        )
    }
}
