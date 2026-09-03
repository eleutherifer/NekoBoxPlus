package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import moe.matsuri.nb4a.proxy.direct.DirectBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProfileTransferPolicyTest {

    @Test
    fun eligibleGroupsOnlyReturnsBasicGroupsInOriginalOrder() {
        val groups = listOf(
            ProxyGroup(id = 1L, type = GroupType.SUBSCRIPTION),
            ProxyGroup(id = 2L, type = GroupType.BASIC),
            ProxyGroup(id = 3L, type = GroupType.BASIC, ungrouped = true),
        )

        assertEquals(
            listOf(2L, 3L),
            ProfileTransferPolicy.eligibleGroups(groups).map { it.id },
        )
    }

    @Test
    fun copyChangesOnlyIdentityGroupAndOrder() {
        val bean = DirectBean()
        val source = ProxyEntity(
            id = 7L,
            groupId = 2L,
            type = ProxyEntity.TYPE_DIRECT,
            userOrder = 4L,
            tx = 100L,
            rx = 200L,
            status = 3,
            ping = 45,
            uuid = "stored-uuid",
            error = "stored error",
            directBean = bean,
        )

        val copied = ProfileTransferPolicy.copyForTarget(source, 9L, 12L)

        assertEquals(source.copy(id = 0L, groupId = 9L, userOrder = 12L), copied)
        assertSame(bean, copied.directBean)
    }

    @Test
    fun moveToSameGroupDoesNothing() {
        assertNull(
            ProfileTransferPolicy.moveForTarget(
                ProxyEntity(id = 7L, groupId = 2L),
                targetGroupId = 2L,
                targetOrder = 10L,
            )
        )
    }

    @Test
    fun moveKeepsIdentityAndPersistedState() {
        val source = ProxyEntity(
            id = 7L,
            groupId = 2L,
            userOrder = 4L,
            tx = 100L,
            rx = 200L,
            status = 3,
            ping = 45,
            uuid = "stored-uuid",
            error = "stored error",
        )

        assertEquals(
            source.copy(groupId = 9L, userOrder = 12L),
            ProfileTransferPolicy.moveForTarget(source, 9L, 12L),
        )
    }
}
