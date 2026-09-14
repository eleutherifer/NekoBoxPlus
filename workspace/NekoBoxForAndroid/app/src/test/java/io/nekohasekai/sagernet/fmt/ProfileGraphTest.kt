package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProfileDataSource
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.internal.setEmbeddedProfiles
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.proxy.byedpi.ByeDPIBean
import moe.matsuri.nb4a.proxy.direct.DirectBean
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ProfileGraphTest {
    @Test
    fun urlTestChainsApplyDetoursToEveryMember() {
        for (length in 2..3) {
            val leaves = (1L..6L).map { socks(it, "Server $it") }
            val sets = leaves.chunked(2).mapIndexed { index, members ->
                urlTest(10L + index, ('A' + index).toString(), *members.toTypedArray())
            }.take(length)
            // The editor stores network-facing hops first; detours run in reverse order.
            val chain = chain(20L, *sets.reversed().toTypedArray())
            val graph = plan(chain, *(leaves + sets).toTypedArray())
            assertEquals("A", graph.entryTag)
            sets.forEachIndexed { index, set ->
                val group = graph.outbounds.single { it.profile.id == set.id }
                assertNull(group.detour)
                assertEquals(2, group.memberTags.size)
                group.memberTags.values.forEach { member ->
                    assertEquals(sets.getOrNull(index + 1)?.displayName(), graph.byTag(member).detour)
                }
            }
            assertValidReferences(graph)
        }
    }

    @Test
    fun routingRuleTargetRetainsGroupBasedNestedUrlTests() {
        val selected = socks(1L, "Selected")
        val leaf = socks(2L, "Inner server")
        val inner = urlTest(3L, "Inner", leaf).apply { groupId = 100L }
        val ordinary = socks(4L, "Ordinary").apply { groupId = 100L }
        val outer = groupSet(5L, "Outer", 100L)
        val rule = RuleEntity(domains = "example.org", outbound = outer.id, enabled = true)
        val source = source(selected, leaf, inner, ordinary, outer)
        val resolver = ProfileChainResolver(source, false, null, null)
        val tags = OutboundTagPlanner(emptySet())
        val main = planProfileOutbounds(resolver.resolveGraph(selected), tags)
        val target = planProfileOutbounds(resolver.resolveGraph(source.getById(rule.outbound)!!, false), tags)
        val outerOutbound = target.byTag(target.entryTag).buildProxySet() as SingBoxOptions.Outbound_URLTestOptions
        val innerOutbound = target.outbounds.single { it.profile.id == inner.id }.buildProxySet()
            as SingBoxOptions.Outbound_URLTestOptions

        assertEquals(listOf("Inner", "Ordinary"), outerOutbound.outbounds)
        assertEquals(listOf("Inner server"), innerOutbound.outbounds)
        assertEquals("Outer", target.entryTag)
        assertEquals("Selected", main.entryTag)
        assertEquals(1, main.outbounds.size)
        assertTrue(target.outbounds.all { it.detour == null })
        assertValidReferences(target)
    }

    @Test
    fun nestedSetMembersInheritTheContainingChainHopDetour() {
        val leaf = socks(1L, "Leaf")
        val other = socks(2L, "Other")
        val nextLeaf = socks(3L, "Next leaf")
        val inner = urlTest(4L, "Inner", leaf)
        val outer = urlTest(5L, "Outer", inner, other)
        val next = urlTest(6L, "Next", nextLeaf)
        val graph = plan(chain(7L, next, outer), leaf, other, nextLeaf, inner, outer, next)

        assertEquals("Outer", graph.entryTag)
        assertEquals("Next", graph.byTag("Leaf").detour)
        assertEquals("Next", graph.byTag("Other").detour)
        assertNull(graph.byTag("Next leaf").detour)
        assertNull(graph.byTag("Inner").detour)
        assertEquals(listOf("Inner", "Other"), graph.byTag("Outer").memberTags.values.toList())
        assertValidReferences(graph)
    }

    @Test
    fun sharedProfilesGetIndependentTagsDetoursAndMutableBeans() {
        val shared = socks(1L, "Shared")
        val a = urlTest(2L, "A", shared)
        val b = urlTest(3L, "B", shared)
        val graph = plan(chain(4L, b, a), shared, a, b)
        val instances = graph.outbounds.filter { it.profile.id == shared.id }

        assertEquals(2, instances.size)
        assertNotEquals(instances[0].tag, instances[1].tag)
        assertEquals("B", instances[0].detour)
        assertNull(instances[1].detour)
        instances[0].profile.requireBean().finalPort = 9999
        assertNotEquals(9999, instances[1].profile.requireBean().finalPort)
        assertNotEquals(9999, shared.requireBean().finalPort)
        assertValidReferences(graph)
    }

    @Test
    fun repeatedNestedSetsRemainIndependentAcrossChainStages() {
        val leaf = socks(1L, "Leaf")
        val inner = urlTest(2L, "Inner", leaf)
        val a = urlTest(3L, "A", inner)
        val b = urlTest(4L, "B", inner)
        val graph = plan(chain(5L, b, a), leaf, inner, a, b)
        val inners = graph.outbounds.filter { it.profile.id == inner.id }
        assertEquals(2, inners.size)
        assertNotEquals(inners[0].tag, inners[1].tag)
        assertEquals("B", graph.byTag(inners[0].memberTags.values.single()).detour)
        assertNull(graph.byTag(inners[1].memberTags.values.single()).detour)
        assertValidReferences(graph)
    }

    @Test
    fun chainCandidatesHaveTheirOwnEntryAndTail() {
        val entry = socks(1L, "Entry")
        val tail = socks(2L, "Tail")
        val candidate = chain(3L, tail, entry)
        val outer = urlTest(4L, "Outer", candidate)
        val next = socks(5L, "Next")
        val graph = plan(chain(6L, next, outer), entry, tail, candidate, outer, next)
        assertEquals(mapOf(candidate.id to "Entry"), graph.byTag("Outer").memberTags)
        assertEquals("Tail", graph.byTag("Entry").detour)
        assertEquals("Next", graph.byTag("Tail").detour)
        assertValidReferences(graph)
    }

    @Test
    fun nestedUrlTestSettingsAndSelectorDefaultArePreserved() {
        val leaf = socks(1L, "Leaf")
        val inner = urlTest(2L, "Inner", leaf).apply {
            proxySetBean!!.apply {
                testURL = "https://example.org/check"
                testInterval = "5m"
                testIdleTimeout = "10m"
                testTolerance = 150
                interruptExistConnections = true
            }
        }
        val outer = urlTest(3L, "Selector", inner).apply {
            proxySetBean!!.mode = ProxySetBean.MODE_SELECTOR
            proxySetBean!!.defaultOutbound = inner.id
        }
        val graph = plan(outer, inner, leaf)
        val selector = graph.byTag("Selector").buildProxySet() as SingBoxOptions.Outbound_SelectorOptions
        val urltest = graph.byTag("Inner").buildProxySet() as SingBoxOptions.Outbound_URLTestOptions
        assertEquals("Inner", selector.default_)
        assertEquals(listOf("Inner"), selector.outbounds)
        assertEquals("https://example.org/check", urltest.url)
        assertEquals("5m", urltest.interval)
        assertEquals("10m", urltest.idle_timeout)
        assertEquals(150, urltest.tolerance)
        assertTrue(urltest.interrupt_exist_connections)
    }

    @Test
    fun groupCollectionExcludesItselfAndPreservesRegexAndSecurityFilters() {
        val secure = ProxyEntity(id = 1L, groupId = 100L).putBean(DirectBean().apply {
            initializeDefaultValues()
            name = "Keep secure"
        })
        val insecure = socks(2L, "Keep insecure").apply { groupId = 100L }
        val excluded = socks(3L, "Excluded").apply { groupId = 100L }
        val outer = groupSet(4L, "Keep outer", 100L).apply {
            groupId = 100L
            proxySetBean!!.groupFilterNotRegex = "Keep"
            proxySetBean!!.skipInsecureProfiles = true
            proxySetBean!!.mode = ProxySetBean.MODE_SELECTOR
            proxySetBean!!.defaultOutbound = insecure.id
        }
        val graph = plan(outer, secure, insecure, excluded)
        assertEquals(listOf("Keep secure"), graph.byTag("Keep outer").memberTags.values.toList())
        assertNull((graph.byTag("Keep outer").buildProxySet() as SingBoxOptions.Outbound_SelectorOptions).default_)
    }

    @Test
    fun missingMembersAreSkippedAndEmptySetsFail() {
        val leaf = socks(1L, "Leaf")
        val outer = urlTest(2L, "Outer", leaf).apply { proxySetBean!!.proxies = listOf(99L, leaf.id) }
        assertEquals(listOf("Leaf"), plan(outer, leaf).byTag("Outer").memberTags.values.toList())
        assertThrows(IllegalArgumentException::class.java) { plan(outer) }
    }

    @Test
    fun recursiveSetsAndMixedChainReferencesFailWithPaths() {
        val a = urlTest(1L, "A")
        val b = urlTest(2L, "B", a)
        a.proxySetBean!!.proxies = listOf(b.id)
        val error = assertThrows(ProfileReferenceCycleException::class.java) { plan(a, b) }
        assertEquals("Profile reference cycle: A → B → A", error.message)
        val chain = chain(3L, a)
        a.proxySetBean!!.proxies = listOf(chain.id)
        assertThrows(ProfileReferenceCycleException::class.java) { plan(a, chain) }
        a.proxySetBean!!.proxies = listOf(a.id)
        assertThrows(ProfileReferenceCycleException::class.java) { plan(a) }
    }

    @Test
    fun recursiveGroupCollectionsFailWithoutOverflowingEligibilityChecks() {
        val a = groupSet(1L, "A", 100L).apply { groupId = 100L }
        val b = groupSet(2L, "B", 100L).apply { groupId = 100L }
        val resolver = ProfileChainResolver(source(a, b), false, null, null)
        assertFalse(resolver.containsByeDpi(a))
        assertFalse(resolver.containsMasterDnsVPN(a))
        assertTrue(resolver.referencesProfile(a, b.id))
        assertThrows(ProfileReferenceCycleException::class.java) { resolver.resolveGraph(a) }
    }

    @Test
    fun eligibilityTraversalHandlesCyclesContainingRestrictedProfiles() {
        val a = chain(1L)
        val b = chain(2L, a)
        val byeDpi = ProxyEntity(id = 3L).putBean(ByeDPIBean().apply { initializeDefaultValues() })
        val vpn = ProxyEntity(id = 4L).putBean(MasterDnsVPNBean().apply { initializeDefaultValues() })
        a.chainBean!!.proxies = listOf(b.id, byeDpi.id, vpn.id)
        val resolver = ProfileChainResolver(source(a, b, byeDpi, vpn), false, null, null)
        assertTrue(resolver.containsByeDpi(a))
        assertTrue(resolver.containsMasterDnsVPN(a))
        assertFalse(resolver.startsWithByeDpi(a))
        assertFalse(resolver.referencesProfile(a, 999L))
    }

    @Test
    fun proxySetsStillExcludeRestrictedProfilesAndRejectGroupChains() {
        val leaf = socks(1L, "Leaf").apply { groupId = 100L }
        val byeDpi = ProxyEntity(id = 2L, groupId = 100L)
            .putBean(ByeDPIBean().apply { initializeDefaultValues() })
        val vpn = ProxyEntity(id = 3L, groupId = 100L)
            .putBean(MasterDnsVPNBean().apply { initializeDefaultValues() })
        val outer = groupSet(4L, "Outer", 100L)
        assertEquals(listOf("Leaf"), plan(outer, leaf, byeDpi, vpn).byTag("Outer").memberTags.values.toList())
        val incompatible = chain(5L, leaf).apply { groupId = 100L }
        assertThrows(IllegalArgumentException::class.java) { plan(outer, leaf, incompatible) }
    }

    @Test
    fun embeddedNestedSetsKeepDistinctMemberIds() {
        val first = urlTest(1L, "First").apply {
            proxySetBean!!.setEmbeddedProfiles(listOf(socks(10L, "One").requireBean()))
        }
        val second = urlTest(2L, "Second").apply {
            proxySetBean!!.setEmbeddedProfiles(listOf(socks(11L, "Two").requireBean()))
        }
        val outer = urlTest(3L, "Outer", first, second)
        val graph = plan(outer, first, second)
        assertEquals(listOf("One"), graph.byTag("First").memberTags.values.toList())
        assertEquals(listOf("Two"), graph.byTag("Second").memberTags.values.toList())
        assertNotEquals(graph.byTag("First").memberTags.keys.single(), graph.byTag("Second").memberTags.keys.single())
        assertValidReferences(graph)
    }

    @Test
    fun frontAndLandingSetsWrapTheSelectedGraphInLegacyOrder() {
        val leaf = socks(1L, "Leaf")
        val main = urlTest(2L, "Main", leaf)
        val front = urlTest(3L, "Front", leaf)
        val landing = urlTest(4L, "Landing", leaf)
        val resolver = ProfileChainResolver(source(leaf, main, front, landing), false, front, landing)
        val graph = planProfileOutbounds(resolver.resolveGraph(main), OutboundTagPlanner(emptySet()))
        assertEquals("Landing", graph.entryTag)
        assertEquals("Main", graph.byTag(graph.byTag("Landing").memberTags.values.single()).detour)
        assertEquals("Front", graph.byTag(graph.byTag("Main").memberTags.values.single()).detour)
        assertNull(graph.byTag(graph.byTag("Front").memberTags.values.single()).detour)
        assertValidReferences(graph)
    }

    private fun plan(root: ProxyEntity, vararg others: ProxyEntity): ProfileOutboundGraph =
        planProfileOutbounds(
            ProfileChainResolver(source(root, *others), false, null, null).resolveGraph(root),
            OutboundTagPlanner(emptySet()),
        )

    private fun ProfileOutboundGraph.byTag(tag: String) = outbounds.single { it.tag == tag }

    private fun assertValidReferences(graph: ProfileOutboundGraph) {
        val tags = graph.outbounds.map { it.tag }.toSet()
        assertEquals(graph.outbounds.size, tags.size)
        assertTrue(graph.entryTag in tags)
        graph.outbounds.forEach { outbound ->
            assertTrue(outbound.detour == null || outbound.detour in tags)
            assertTrue(tags.containsAll(outbound.memberTags.values))
            assertFalse(outbound.tag in outbound.memberTags.values)
            assertNotEquals(outbound.tag, outbound.detour)
        }
    }

    private fun socks(id: Long, name: String) = ProxyEntity(id = id).putBean(SOCKSBean().apply {
        initializeDefaultValues()
        this.name = name
        serverAddress = "127.0.0.1"
        serverPort = 1080
    })

    private fun urlTest(id: Long, name: String, vararg members: ProxyEntity) =
        ProxyEntity(id = id).putBean(ProxySetBean().apply {
            initializeDefaultValues()
            this.name = name
            mode = ProxySetBean.MODE_URL_TEST
            proxies = members.map { it.id }
        })

    private fun groupSet(id: Long, name: String, group: Long) = urlTest(id, name).apply {
        proxySetBean!!.type = ProxySetBean.TYPE_GROUP
        proxySetBean!!.groupId = group
    }

    private fun chain(id: Long, vararg members: ProxyEntity) = ProxyEntity(id = id).putBean(ChainBean().apply {
        initializeDefaultValues()
        name = "Chain"
        proxies = members.map { it.id }
    })

    private fun source(vararg entities: ProxyEntity): ProfileDataSource {
        val byId = entities.associateBy { it.id }
        return Proxy.newProxyInstance(ProfileDataSource::class.java.classLoader, arrayOf(ProfileDataSource::class.java)) { _, method, args ->
            when (method.name) {
                "getById" -> byId[args.single() as Long]
                "getEntities" -> (args.single() as List<*>).mapNotNull { byId[it as Long] }
                "getByGroup" -> byId.values.filter { it.groupId == args.single() as Long }
                "getIdsByGroup" -> byId.values.filter { it.groupId == args.single() as Long }.map { it.id }
                else -> error("Unexpected ${method.name} call")
            }
        } as ProfileDataSource
    }
}
