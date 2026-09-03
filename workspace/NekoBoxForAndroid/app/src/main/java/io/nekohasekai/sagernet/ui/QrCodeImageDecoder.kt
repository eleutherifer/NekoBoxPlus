package io.nekohasekai.sagernet.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader
import java.util.EnumSet

internal object QrCodeImageDecoder {
    fun decode(context: Context, uri: Uri): List<String> {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.contentResolver, uri),
            ) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
        return decode(bitmap)
    }

    fun decode(bitmap: Bitmap): List<String> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return decode(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
    }

    internal fun decode(source: LuminanceSource): List<String> {
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to EnumSet.of(BarcodeFormat.QR_CODE),
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val results = mutableListOf<Result>()
        runCatching {
            results.addAll(QRCodeMultiReader().decodeMultiple(bitmap, hints))
        }
        runCatching {
            results.addAll(GenericMultipleBarcodeReader(QRCodeReader()).decodeMultiple(bitmap, hints))
        }
        runCatching {
            results.add(QRCodeReader().decode(bitmap, hints))
        }
        return results.mapNotNull { it.text }.distinct()
    }
}
