package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMuxResolutionTest {

    @Test
    fun `routing profile uses its owning group override`() {
        val selectedGroup = muxGroup(id = 1L, muxType = 0)
        val routingGroup = muxGroup(id = 2L, muxType = 3)
        val groups = mapOf(selectedGroup.id to selectedGroup, routingGroup.id to routingGroup)
        val routingProfile = vmessProfile(groupId = routingGroup.id)

        val application = resolveMuxApplication(routingProfile, profileMuxApplied = false, groups::get)

        assertEquals("mux.cool", application?.options?.protocol)
        assertFalse(application!!.consumesProfileMuxSlot)
    }

    @Test
    fun `chain members receive independent overrides from different groups`() {
        val h2Group = muxGroup(id = 1L, muxType = 0)
        val coolGroup = muxGroup(id = 2L, muxType = 3)
        val groups = mapOf(h2Group.id to h2Group, coolGroup.id to coolGroup)
        val firstProfile = vmessProfile(groupId = h2Group.id)
        val secondProfile = vmessProfile(groupId = coolGroup.id)

        val firstApplication = resolveMuxApplication(firstProfile, profileMuxApplied = false, groups::get)
        val secondApplication = resolveMuxApplication(secondProfile, profileMuxApplied = false, groups::get)

        assertEquals("h2mux", firstApplication?.options?.protocol)
        assertEquals("mux.cool", secondApplication?.options?.protocol)
        assertFalse(firstApplication!!.consumesProfileMuxSlot)
        assertFalse(secondApplication!!.consumesProfileMuxSlot)
    }

    @Test
    fun `urltest members resolve mux by each member group`() {
        val h2Group = muxGroup(id = 1L, muxType = 0)
        val coolGroup = muxGroup(id = 2L, muxType = 3)
        val groups = mapOf(h2Group.id to h2Group, coolGroup.id to coolGroup)
        val members = listOf(vmessProfile(h2Group.id), vmessProfile(coolGroup.id))

        val protocols =
            members.map { member ->
                resolveMuxApplication(member, profileMuxApplied = false, groups::get)
                    ?.options
                    ?.protocol
            }

        assertEquals(listOf("h2mux", "mux.cool"), protocols)
    }

    @Test
    fun `group override is ignored for mux incompatible profile`() {
        val group = muxGroup(id = 1L, muxType = 3)
        val profile =
            ProxyEntity(groupId = group.id).putBean(
                SOCKSBean().apply { initializeDefaultValues() },
            )

        assertNull(resolveMuxApplication(profile, profileMuxApplied = false) { group })
    }

    @Test
    fun `profile mux fallback keeps the single profile mux slot`() {
        val firstProfile = vmessProfile(groupId = 1L, profileMuxEnabled = true)
        val secondProfile = vmessProfile(groupId = 2L, profileMuxEnabled = true)

        val firstApplication = resolveMuxApplication(firstProfile, profileMuxApplied = false) { null }
        val secondApplication = resolveMuxApplication(secondProfile, profileMuxApplied = true) { null }

        assertTrue(firstApplication!!.consumesProfileMuxSlot)
        assertEquals("h2mux", firstApplication.options.protocol)
        assertNull(secondApplication)
    }

    private fun muxGroup(
        id: Long,
        muxType: Int,
    ) = ProxyGroup(
        id = id,
        enableMux = true,
        muxType = muxType,
    )

    private fun vmessProfile(
        groupId: Long,
        profileMuxEnabled: Boolean = false,
    ): ProxyEntity =
        ProxyEntity(groupId = groupId).putBean(
            VMessBean().apply {
                initializeDefaultValues()
                enableMux = profileMuxEnabled
            },
        )
}
