package io.nekohasekai.sagernet.widget

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.DialogFragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.QrCodeContent
import io.nekohasekai.sagernet.utils.Theme
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

class QRCodeDialog() : DialogFragment() {

    companion object {
        private const val KEY_URL = "io.nekohasekai.sagernet.QRCodeDialog.KEY_URL"
        private const val KEY_NAME = "io.nekohasekai.sagernet.QRCodeDialog.KEY_NAME"
        private val iso88591 = StandardCharsets.ISO_8859_1.newEncoder()
    }

    constructor(url: String, displayName: String) : this() {
        arguments = Bundle().apply {
            putString(KEY_URL, url)
            putString(KEY_NAME, displayName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, Theme.getDialogTheme())
    }

    /**
     * Based on:
     * https://android.googlesource.com/platform/
    packages/apps/Settings/+/0d706f0/src/com/android/settings/wifi/qrcode/QrCodeGenerator.java
     * https://android.googlesource.com/platform/
    packages/apps/Settings/+/8a9ccfd/src/com/android/settings/wifi/dpp/WifiDppQrCodeGeneratorFragment.java#153
     */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ) = try {
        // get display size
        var pixelMin = 0

        try {
            val displayMetrics: DisplayMetrics = requireContext().resources.displayMetrics
            val height: Int = displayMetrics.heightPixels
            val width: Int = displayMetrics.widthPixels
            pixelMin = if (height > width) width else height
            pixelMin = (pixelMin * 0.8).roundToInt()
            if (width > height) {
                pixelMin -= (16 * displayMetrics.density).roundToInt()
            }
        } catch (e: Exception) {
        }

        val size = if (pixelMin > 0) pixelMin else resources.getDimensionPixelSize(R.dimen.qrcode_size)

        // draw QR Code
        val url = arguments?.getString(KEY_URL)!!
        val displayName = arguments?.getString(KEY_NAME)!!

        val hints = mutableMapOf<EncodeHintType, Any>()
        if (!iso88591.canEncode(url)) hints[EncodeHintType.CHARACTER_SET] = StandardCharsets.UTF_8.name()
        val qrBits = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val rowOffset = y * size
            for (x in 0 until size) {
                pixels[rowOffset + x] = if (qrBits.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }

        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NekoComposeTheme {
                    QrCodeContent(bitmap = bitmap, displayName = displayName)
                }
            }
        }
    } catch (e: WriterException) {
        Logs.w(e)
        (activity as? MainActivity)?.snackbar(R.string.qr_code_data_too_large)?.show()
            ?: Toast.makeText(requireContext(), R.string.qr_code_data_too_large, Toast.LENGTH_LONG)
                .show()
        dismiss()
        null
    }
}
