package io.nekohasekai.sagernet.fmt.v2ray

import moe.matsuri.nb4a.SingBoxOptions.Outbound_VLESSOptions
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VlessPacketEncodingTest {

    @Test
    fun vlessLinkWithoutPacketEncodingImportsAsNotSpecified() {
        val bean = parseVless(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?type=tcp&encryption=none",
        )

        assertEquals(StandardV2RayBean.PACKET_ENCODING_NOT_SPECIFIED, bean.packetEncoding)

        bean.initializeDefaultValues()
        val exportedUrl = bean.toUriVMessVLESSTrojan(false)
            .replaceFirst("vless://", "https://")
            .toHttpUrl()
        val outbound = buildSingBoxOutboundStandardV2RayBean(bean) as Outbound_VLESSOptions

        assertNull(exportedUrl.queryParameter("packetEncoding"))
        assertNull(outbound.packet_encoding)
    }

    @Test
    fun vlessLinkWithExplicitNoneDisablesPacketEncoding() {
        val bean = parseVless(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?type=tcp&encryption=none&packetEncoding=none",
        )

        assertEquals(StandardV2RayBean.PACKET_ENCODING_NONE, bean.packetEncoding)

        bean.initializeDefaultValues()
        val exportedUrl = bean.toUriVMessVLESSTrojan(false)
            .replaceFirst("vless://", "https://")
            .toHttpUrl()
        val outbound = buildSingBoxOutboundStandardV2RayBean(bean) as Outbound_VLESSOptions

        assertEquals("none", exportedUrl.queryParameter("packetEncoding"))
        assertEquals("", outbound.packet_encoding)
    }

    @Test
    fun vlessPacketEncodingParameterWinsOverXudpCompatibilityParameter() {
        val bean = parseVless(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?type=tcp&encryption=none&packetEncoding=xudp&xudp=0",
        )

        assertEquals(StandardV2RayBean.PACKET_ENCODING_XUDP, bean.packetEncoding)

        bean.initializeDefaultValues()
        val outbound = buildSingBoxOutboundStandardV2RayBean(bean) as Outbound_VLESSOptions

        assertEquals("xudp", outbound.packet_encoding)
    }

    @Test
    fun vlessXudpCompatibilityParameterImportsWhenPacketEncodingIsAbsent() {
        val enabled = parseVless(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?type=tcp&encryption=none&xudp=true",
        )
        val disabled = parseVless(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?type=tcp&encryption=none&xudp=false",
        )

        assertEquals(StandardV2RayBean.PACKET_ENCODING_XUDP, enabled.packetEncoding)
        assertEquals(StandardV2RayBean.PACKET_ENCODING_NONE, disabled.packetEncoding)
    }

    @Test
    fun vlessExplicitPacketEncodingValuesArePreserved() {
        val packetAddr = parseVless(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?type=tcp&encryption=none&packetEncoding=packetaddr",
        )
        val xudp = parseVless(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?type=tcp&encryption=none&packetEncoding=xudp",
        )

        assertEquals(StandardV2RayBean.PACKET_ENCODING_PACKETADDR, packetAddr.packetEncoding)
        assertEquals(StandardV2RayBean.PACKET_ENCODING_XUDP, xudp.packetEncoding)
    }

    private fun parseVless(link: String): VMessBean {
        return parseV2Ray(link) as VMessBean
    }
}
