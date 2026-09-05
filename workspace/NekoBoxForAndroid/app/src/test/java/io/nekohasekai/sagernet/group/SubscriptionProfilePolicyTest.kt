package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SubscriptionProfilePolicyTest {
    @Test
    fun assignsStableSuffixesToDuplicateNames() {
        val profiles = listOf(profile("Node"), profile("Node"), profile("Node"))

        val result = SubscriptionProfilePolicy.assignUniqueNames(profiles)

        assertEquals(listOf("Node", "Node (1)", "Node (2)"), result.map { it.displayName() })
    }

    @Test
    fun deduplicationKeepsFirstProfileAndReportsEveryDuplicate() {
        val first = profile("Primary")
        val result = SubscriptionProfilePolicy.deduplicate(
            listOf(first, profile("Mirror 1"), profile("Mirror 2")),
        )

        assertEquals(1, result.profiles.size)
        assertSame(first, result.profiles.single())
        assertEquals(
            listOf("Primary (0)", "Mirror 1 (0)", "Mirror 2 (0)"),
            result.duplicateNames,
        )
    }

    @Test
    fun includeAndExcludeFiltersUseDisplayNames() {
        val profiles = listOf(profile("DE Berlin"), profile("NL Amsterdam"))

        assertEquals(
            listOf("DE Berlin"),
            SubscriptionProfilePolicy.filter(
                profiles,
                SubscriptionFilterMode.INCLUDE,
                "^DE",
            ).map { it.displayName() },
        )
        assertEquals(
            listOf("NL Amsterdam"),
            SubscriptionProfilePolicy.filter(
                profiles,
                SubscriptionFilterMode.EXCLUDE,
                "^DE",
            ).map { it.displayName() },
        )
    }

    private fun profile(name: String) = ShadowsocksBean().apply {
        initializeDefaultValues()
        this.name = name
        serverAddress = "proxy.example"
        serverPort = 443
        method = "aes-256-gcm"
        password = "secret"
    }
}
