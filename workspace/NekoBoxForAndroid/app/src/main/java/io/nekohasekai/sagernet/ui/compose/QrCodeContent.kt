package io.nekohasekai.sagernet.ui.compose

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign

@Composable
internal fun QrCodeContent(bitmap: Bitmap, displayName: String) {
    val image = bitmap.asImageBitmap()
    val size = with(LocalDensity.current) { bitmap.width.toDp() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = BitmapPainter(image, filterQuality = FilterQuality.None),
            contentDescription = null,
            modifier = Modifier.size(size),
        )
        Text(
            text = displayName,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
