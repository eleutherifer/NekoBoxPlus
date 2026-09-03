package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.SpoofApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SubscriptionRequestFingerprintTest {
    private val locale = Locale("ru", "RU")

    @Test
    fun `builds current Happ fingerprint without requiring HWID`() {
        val fingerprint =
            fingerprint(
                spoofApp = SpoofApp.HAPP,
                hwidEnabled = false,
                customUserAgent = "",
            )

        assertEquals("Happ/3.26.3/Android/17839452147361875676", fingerprint.userAgent)
        assertEquals("CPH2653", fingerprint.headers["X-Device-Model"])
        assertEquals("16", fingerprint.headers["X-Ver-Os"])
        assertEquals("Android", fingerprint.headers["X-Device-Os"])
        assertEquals("ru", fingerprint.headers["X-Device-Locale"])
        assertNull(fingerprint.headers["X-Hwid"])
        assertNull(fingerprint.headers["X-App-Version"])
    }

    @Test
    fun `builds current v2RayTun fingerprint with optional HWID`() {
        val fingerprint =
            fingerprint(
                spoofApp = SpoofApp.V2RAY_TUN,
                hwidEnabled = true,
                customUserAgent = "",
                hwid = "77B51D5A660C2616",
            )

        assertEquals("v2raytun/android", fingerprint.userAgent)
        assertEquals("5.25.80", fingerprint.headers["X-App-Version"])
        assertEquals("OnePlus CPH2653", fingerprint.headers["X-Device-Model"])
        assertEquals("Android 16", fingerprint.headers["X-Ver-Os"])
        assertEquals("Android", fingerprint.headers["X-Device-Os"])
        assertEquals("77B51D5A660C2616", fingerprint.headers["X-Hwid"])
    }

    @Test
    fun `builds current Incy fingerprint`() {
        val fingerprint =
            fingerprint(
                spoofApp = SpoofApp.INCY,
                hwidEnabled = true,
                customUserAgent = "",
                hwid = "3A8A4A04-5633-BA20-0962-2D0A915822B4",
            )

        assertEquals("INCY/3.4.3/android Dalvik/2.1.0", fingerprint.userAgent)
        assertEquals("*/*", fingerprint.headers["Accept"])
        assertEquals("ru-RU", fingerprint.headers["Accept-Language"])
        assertEquals("INCY", fingerprint.headers["X-Client"])
        assertEquals("ru_RU", fingerprint.headers["X-Device-Locale"])
        assertEquals("3.4.3", fingerprint.headers["X-App-Version"])
        assertEquals("OnePlus CPH2653", fingerprint.headers["X-Device-Model"])
        assertEquals("16", fingerprint.headers["X-Ver-Os"])
        assertEquals("Android", fingerprint.headers["X-Device-Os"])
        assertEquals(
            "3A8A4A04-5633-BA20-0962-2D0A915822B4",
            fingerprint.headers["X-Hwid"],
        )
    }

    @Test
    fun `keeps generic HWID behavior when spoofing is disabled`() {
        val fingerprint =
            fingerprint(
                spoofApp = SpoofApp.NONE,
                hwidEnabled = true,
                customUserAgent = "",
                hwid = "f7f41cbf74c2181e",
            )

        assertEquals("NekoBox", fingerprint.userAgent)
        assertEquals("f7f41cbf74c2181e", fingerprint.headers["X-Hwid"])
        assertEquals("OnePlus CPH2653", fingerprint.headers["X-Device-Model"])
        assertEquals("16", fingerprint.headers["X-Ver-Os"])
        assertEquals("Android", fingerprint.headers["X-Device-Os"])
    }

    @Test
    fun `updates known Happ default and preserves custom user agents`() {
        assertEquals(
            "Happ/3.26.3/Android/17839452147361875676",
            normalizeSpoofUserAgent(
                SpoofApp.HAPP,
                "Happ/3.17.0/Android/17756505247711753599",
            ),
        )
        assertEquals(
            "ProviderClient/1.0",
            normalizeSpoofUserAgent(SpoofApp.HAPP, "ProviderClient/1.0"),
        )
    }

    @Test
    fun `warns only when a spoofed app has no HWID`() {
        assertTrue(shouldWarnAboutMissingSpoofHwid(SpoofApp.HAPP, false))
        assertTrue(shouldWarnAboutMissingSpoofHwid(SpoofApp.V2RAY_TUN, false))
        assertTrue(shouldWarnAboutMissingSpoofHwid(SpoofApp.INCY, false))
        assertFalse(shouldWarnAboutMissingSpoofHwid(SpoofApp.NONE, false))
        assertFalse(shouldWarnAboutMissingSpoofHwid(SpoofApp.INCY, true))
    }

    private fun fingerprint(
        spoofApp: Int,
        hwidEnabled: Boolean,
        customUserAgent: String,
        hwid: String? = null,
    ) = buildSubscriptionRequestFingerprint(
        spoofApp = spoofApp,
        hwidEnabled = hwidEnabled,
        customUserAgent = customUserAgent,
        fallbackUserAgent = "NekoBox",
        manufacturer = "OnePlus",
        model = "CPH2653",
        sdkVersion = 16,
        locale = locale,
        hwid = hwid,
    )
}
