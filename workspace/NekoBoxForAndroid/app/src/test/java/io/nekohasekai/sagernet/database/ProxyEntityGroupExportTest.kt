package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.TypeMap
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.openconnect.OpenConnectBean
import io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyEntityGroupExportTest {

    @Test
    fun standardLinkProfilesDoNotNeedUniversalGroupExport() {
        val entity = ProxyEntity().putBean(SOCKSBean().apply {
            initializeDefaultValues()
        })

        assertTrue(entity.haveStandardLink())
        assertFalse(entity.usesUniversalLinkForGroupExport())
    }

    @Test
    fun wireGuardUsesStandardGroupExport() {
        val entity = ProxyEntity().putBean(WireGuardBean().apply {
            initializeDefaultValues()
        })

        assertTrue(entity.haveStandardLink())
        assertFalse(entity.usesUniversalLinkForGroupExport())
    }

    @Test
    fun amneziaWGUsesStandardGroupExport() {
        val entity = ProxyEntity().putBean(AmneziaWGBean().apply {
            initializeDefaultValues()
        })

        assertTrue(entity.haveStandardLink())
        assertFalse(entity.usesUniversalLinkForGroupExport())
    }

    @Test
    fun openVPNAndOpenConnectUseUniversalGroupExport() {
        for (bean in listOf(OpenVPNBean(), OpenConnectBean())) {
            bean.initializeDefaultValues()
            val entity = ProxyEntity().putBean(bean)
            assertFalse(entity.haveStandardLink())
            assertTrue(entity.usesUniversalLinkForGroupExport())
        }
        assertEquals(ProxyEntity.TYPE_OPENVPN, TypeMap["openvpn"])
        assertEquals(ProxyEntity.TYPE_OPENCONNECT, TypeMap["openconnect"])
    }

    @Test
    fun groupExportLinkStillSkipsOtherNonStandardProfiles() {
        val entity = ProxyEntity().putBean(ProxySetBean().apply {
            initializeDefaultValues()
        })

        assertNull(entity.toGroupExportLink())
    }

    @Test
    fun groupExportSkipsStandardLinkProfileWhenSnLinkIsNotExportable() {
        val entity = ProxyEntity().putBean(MasqueBean().apply {
            initializeDefaultValues()
        })
        val snType = TypeMap.reversed.remove(ProxyEntity.TYPE_MASQUE)

        try {
            assertNull(entity.toGroupExportLink())
        } finally {
            if (snType != null) TypeMap.reversed[ProxyEntity.TYPE_MASQUE] = snType
        }
    }
}
