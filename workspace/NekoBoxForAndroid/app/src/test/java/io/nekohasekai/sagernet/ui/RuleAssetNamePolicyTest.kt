package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleAssetNamePolicyTest {

    @Test
    fun customGeoipDatUrlDisplaysDatName() {
        assertEquals(
            "geoip.dat",
            RuleAssetNamePolicy.displayNameForCustom(
                RuleAssetNamePolicy.GEOIP_LOCAL,
                "https://example.com/releases/latest/download/geoip.dat"
            )
        )
    }

    @Test
    fun customGeositeDatUrlWithQueryDisplaysDatName() {
        assertEquals(
            "geosite.dat",
            RuleAssetNamePolicy.displayNameForCustom(
                RuleAssetNamePolicy.GEOSITE_LOCAL,
                "https://example.com/geosite.dat?download=1"
            )
        )
    }

    @Test
    fun customDbUrlKeepsLocalDbName() {
        assertEquals(
            RuleAssetNamePolicy.GEOIP_LOCAL,
            RuleAssetNamePolicy.displayNameForCustom(
                RuleAssetNamePolicy.GEOIP_LOCAL,
                "https://example.com/geoip.db"
            )
        )
    }
}
