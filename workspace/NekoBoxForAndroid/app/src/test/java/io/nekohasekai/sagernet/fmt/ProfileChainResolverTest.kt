package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProfileDataSource
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import java.lang.reflect.Proxy
import moe.matsuri.nb4a.proxy.direct.DirectBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileChainResolverTest {
    @Test
    fun groupProxiesWrapResolvedChainInLegacyOrder() {
        val first = direct(1L, "First")
        val second = direct(2L, "Second")
        val chain = chain(3L, "Chain", listOf(first.id, second.id))
        val landing = direct(4L, "Landing")
        val front = direct(5L, "Front")
        val resolver = ProfileChainResolver(
            profiles = source(first, second, chain, landing, front),
            allowInsecure = false,
            frontProxy = front,
            landingProxy = landing,
        )

        assertEquals(
            listOf("Landing", "Second", "First", "Front"),
            resolver.resolve(chain).map(ProxyEntity::displayName),
        )
    }

    @Test
    fun cyclicChainsFailWithActionableError() {
        val first = chain(1L, "First", listOf(2L))
        val second = chain(2L, "Second", listOf(1L))
        val resolver = ProfileChainResolver(source(first, second), false, null, null)

        val error = assertThrows(IllegalStateException::class.java) {
            resolver.run { first.resolveInternal() }
        }

        assertEquals("Profile chain cycle detected at First", error.message)
    }

    private fun direct(id: Long, name: String) = ProxyEntity(id = id).putBean(
        DirectBean().apply {
            initializeDefaultValues()
            this.name = name
        },
    )

    private fun chain(id: Long, name: String, children: List<Long>) = ProxyEntity(id = id).putBean(
        ChainBean().apply {
            initializeDefaultValues()
            this.name = name
            proxies = children
        },
    )

    private fun source(vararg entities: ProxyEntity): ProfileDataSource {
        val byId = entities.associateBy(ProxyEntity::id)
        return Proxy.newProxyInstance(
            ProfileDataSource::class.java.classLoader,
            arrayOf(ProfileDataSource::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getById" -> byId[args.single() as Long]
                "getEntities" -> (args.single() as List<*>).mapNotNull { byId[it as Long] }
                "getIdsByGroup" -> emptyList<Long>()
                else -> error("Unexpected ${method.name} call")
            }
        } as ProfileDataSource
    }
}
