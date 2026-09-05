package io.nekohasekai.sagernet.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeImageDecoderTest {
    @Test
    fun `decodes one QR code without duplicate results`() {
        val payload = "vless://00000000-0000-0000-0000-000000000000@example.com:443"
        val image = qrImage(payload)

        assertEquals(listOf(payload), QrCodeImageDecoder.decode(image.source()))
    }

    @Test
    fun `decodes multiple QR codes from one image`() {
        val first = "ss://first-profile"
        val second = "trojan://second-profile@example.com:443"
        val image = combineHorizontally(qrImage(first), qrImage(second))

        assertEquals(setOf(first, second), QrCodeImageDecoder.decode(image.source()).toSet())
    }

    @Test
    fun `returns no payload for image without QR code`() {
        val image = TestImage(240, 240, IntArray(240 * 240) { WHITE })

        assertTrue(QrCodeImageDecoder.decode(image.source()).isEmpty())
    }

    private fun qrImage(text: String, size: Int = 240): TestImage {
        val bits = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        return TestImage(
            width = size,
            height = size,
            pixels = IntArray(size * size) { index ->
                if (bits[index % size, index / size]) BLACK else WHITE
            },
        )
    }

    private fun combineHorizontally(first: TestImage, second: TestImage): TestImage {
        val gap = 48
        val width = first.width + gap + second.width
        val height = maxOf(first.height, second.height)
        val pixels = IntArray(width * height) { WHITE }

        fun copy(image: TestImage, left: Int) {
            for (y in 0 until image.height) {
                image.pixels.copyInto(
                    destination = pixels,
                    destinationOffset = y * width + left,
                    startIndex = y * image.width,
                    endIndex = (y + 1) * image.width,
                )
            }
        }

        copy(first, 0)
        copy(second, first.width + gap)
        return TestImage(width, height, pixels)
    }

    private data class TestImage(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    ) {
        fun source() = RGBLuminanceSource(width, height, pixels)
    }

    private companion object {
        const val BLACK = -0x1000000
        const val WHITE = -0x1
    }
}
