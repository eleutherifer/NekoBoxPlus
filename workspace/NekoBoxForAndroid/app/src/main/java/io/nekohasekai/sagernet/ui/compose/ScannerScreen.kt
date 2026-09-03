package io.nekohasekai.sagernet.ui.compose

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.nekohasekai.sagernet.R

@Composable
internal fun ScannerScreen(
    torchEnabled: Boolean,
    onPreviewReady: (PreviewView) -> Unit,
    onClose: () -> Unit,
    onImportFile: () -> Unit,
    onToggleTorch: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    onPreviewReady(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.2f)),
        )

        Icon(
            painter = painterResource(R.drawable.bg_scanner_frame),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier
                .size(288.dp)
                .align(Alignment.Center),
        )

        IconButton(
            onClick = onToggleTorch,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 164.dp)
                .size(56.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_flashlight_24),
                contentDescription = stringResource(
                    if (torchEnabled) R.string.scanner_turn_flashlight_off
                    else R.string.scanner_turn_flashlight_on,
                ),
                tint = Color.Unspecified,
                modifier = Modifier.size(32.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_navigation_close),
                    contentDescription = stringResource(R.string.mal_close),
                    tint = Color.White,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onImportFile) {
                Icon(
                    painter = painterResource(R.drawable.ic_image_photo),
                    contentDescription = stringResource(R.string.action_import_file),
                    tint = Color.White,
                )
            }
        }
    }
}
