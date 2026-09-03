package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Test

class RawUpdaterMuxSettingsTest {

    @Test
    fun `preserves v2ray mux settings`() {
        val existing = VMessBean().apply { setMuxSettings() }
        val updated = VMessBean().apply { initializeDefaultValues() }

        preserveMuxSettings(existing, updated)

        assertMuxSettings(updated)
    }

    @Test
    fun `preserves shadowsocks mux settings`() {
        val existing = ShadowsocksBean().apply { setMuxSettings() }
        val updated = ShadowsocksBean().apply { initializeDefaultValues() }

        preserveMuxSettings(existing, updated)

        assertMuxSettings(updated)
    }

    private fun VMessBean.setMuxSettings() {
        enableMux = true
        muxPadding = true
        muxType = 3
        muxConcurrency = 24
        muxMode = 1
        muxMaxConnections = 6
        muxMinStreams = 12
        muxBrutal = true
        muxBrutalUpMbps = 200
        muxBrutalDownMbps = 300
    }

    private fun ShadowsocksBean.setMuxSettings() {
        enableMux = true
        muxPadding = true
        muxType = 3
        muxConcurrency = 24
        muxMode = 1
        muxMaxConnections = 6
        muxMinStreams = 12
        muxBrutal = true
        muxBrutalUpMbps = 200
        muxBrutalDownMbps = 300
    }

    private fun assertMuxSettings(bean: VMessBean) {
        assertEquals(true, bean.enableMux)
        assertEquals(true, bean.muxPadding)
        assertEquals(3, bean.muxType)
        assertEquals(24, bean.muxConcurrency)
        assertEquals(1, bean.muxMode)
        assertEquals(6, bean.muxMaxConnections)
        assertEquals(12, bean.muxMinStreams)
        assertEquals(true, bean.muxBrutal)
        assertEquals(200, bean.muxBrutalUpMbps)
        assertEquals(300, bean.muxBrutalDownMbps)
    }

    private fun assertMuxSettings(bean: ShadowsocksBean) {
        assertEquals(true, bean.enableMux)
        assertEquals(true, bean.muxPadding)
        assertEquals(3, bean.muxType)
        assertEquals(24, bean.muxConcurrency)
        assertEquals(1, bean.muxMode)
        assertEquals(6, bean.muxMaxConnections)
        assertEquals(12, bean.muxMinStreams)
        assertEquals(true, bean.muxBrutal)
        assertEquals(200, bean.muxBrutalUpMbps)
        assertEquals(300, bean.muxBrutalDownMbps)
    }
}
