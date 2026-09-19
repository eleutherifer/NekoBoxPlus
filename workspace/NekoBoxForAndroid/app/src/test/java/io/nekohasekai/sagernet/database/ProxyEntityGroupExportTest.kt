package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.TypeMap
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import org.junit.Assert.assertFalse
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
    fun wireGuardUsesUniversalGroupExport() {
        val entity = ProxyEntity().putBean(WireGuardBean().apply {
            initializeDefaultValues()
        })

        assertFalse(entity.haveStandardLink())
        assertTrue(entity.usesUniversalLinkForGroupExport())
    }

    @Test
    fun amneziaWGUsesUniversalGroupExport() {
        val entity = ProxyEntity().putBean(AmneziaWGBean().apply {
            initializeDefaultValues()
        })

        assertFalse(entity.haveStandardLink())
        assertTrue(entity.usesUniversalLinkForGroupExport())
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
