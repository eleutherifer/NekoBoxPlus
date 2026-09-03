package io.nekohasekai.sagernet.ui.compose

import android.graphics.Color as AndroidColor
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.nekohasekai.sagernet.utils.CustomTheme
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
internal fun CustomThemeColorPickerDialog(
    spec: CustomTheme.ColorSpec,
    originalColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initialHsv = remember(spec.key, originalColor) {
        FloatArray(3).also { AndroidColor.colorToHSV(originalColor, it) }
    }
    var hue by remember(spec.key, originalColor) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(spec.key, originalColor) { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember(spec.key, originalColor) { mutableFloatStateOf(initialHsv[2]) }
    var hexValue by remember(spec.key, originalColor) {
        mutableStateOf(formatOpaqueHexColor(originalColor))
    }
    val selectedColor = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))

    fun applyPickerColor(newHue: Float, newSaturation: Float, newBrightness: Float) {
        hue = newHue.coerceIn(0F, 359.999F)
        saturation = newSaturation.coerceIn(0F, 1F)
        brightness = newBrightness.coerceIn(0F, 1F)
        hexValue = formatOpaqueHexColor(
            AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)),
        )
    }

    fun applyHex(value: String) {
        hexValue = value
        val parsed = parseOpaqueHexColor(value) ?: return
        val parsedHsv = FloatArray(3).also { AndroidColor.colorToHSV(parsed, it) }
        hue = parsedHsv[0]
        saturation = parsedHsv[1]
        brightness = parsedHsv[2]
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(spec.titleRes)) },
        text = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val windowSize = LocalWindowInfo.current.containerSize
                val screenHeight = with(LocalDensity.current) { windowSize.height.toDp() }
                val isLandscape = windowSize.width > windowSize.height
                val maxWheelByHeight = if (isLandscape) {
                    160.dp
                } else {
                    screenHeight * 0.42F
                }
                val wheelSize = minOf(260.dp, maxWidth - 68.dp, maxWheelByHeight)
                    .coerceAtLeast(160.dp)
                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ColorPickerControls(
                            wheelSize = wheelSize,
                            hue = hue,
                            saturation = saturation,
                            brightness = brightness,
                            onHueAndSaturationChanged = { newHue, newSaturation ->
                                applyPickerColor(newHue, newSaturation, brightness)
                            },
                            onBrightnessChanged = {
                                applyPickerColor(hue, saturation, it)
                            },
                        )
                        ColorPreviewAndHex(
                            color = selectedColor,
                            hexValue = hexValue,
                            onHexValueChanged = ::applyHex,
                            modifier = Modifier.padding(start = 24.dp),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ColorPickerControls(
                            wheelSize = wheelSize,
                            hue = hue,
                            saturation = saturation,
                            brightness = brightness,
                            onHueAndSaturationChanged = { newHue, newSaturation ->
                                applyPickerColor(newHue, newSaturation, brightness)
                            },
                            onBrightnessChanged = {
                                applyPickerColor(hue, saturation, it)
                            },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        ColorPreviewAndHex(
                            color = selectedColor,
                            hexValue = hexValue,
                            onHexValueChanged = ::applyHex,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedColor) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun ColorPickerControls(
    wheelSize: Dp,
    hue: Float,
    saturation: Float,
    brightness: Float,
    onHueAndSaturationChanged: (Float, Float) -> Unit,
    onBrightnessChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var brightnessAdjusting by remember { mutableStateOf(false) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HsvColorWheel(
            diameter = wheelSize,
            hue = hue,
            saturation = saturation,
            brightness = brightness,
            onColorPositionChanged = onHueAndSaturationChanged,
        )
        Box(
            modifier = Modifier
                .padding(start = 12.dp)
                .width(56.dp)
                .height(wheelSize)
                .tvFocusTarget()
                .onFocusChanged { if (!it.isFocused) brightnessAdjusting = false }
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (event.nativeKeyEvent.repeatCount == 0) {
                                brightnessAdjusting = !brightnessAdjusting
                            }
                            true
                        }
                        KeyEvent.KEYCODE_BACK -> if (brightnessAdjusting) {
                            brightnessAdjusting = false
                            true
                        } else false
                        KeyEvent.KEYCODE_DPAD_UP -> if (brightnessAdjusting) {
                            onBrightnessChanged((brightness + 0.02F).coerceAtMost(1F))
                            true
                        } else false
                        KeyEvent.KEYCODE_DPAD_DOWN -> if (brightnessAdjusting) {
                            onBrightnessChanged((brightness - 0.02F).coerceAtLeast(0F))
                            true
                        } else false
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            Slider(
                value = brightness,
                onValueChange = onBrightnessChanged,
                modifier = Modifier
                    .requiredWidth(wheelSize)
                    .rotate(-90F)
                    .focusProperties { canFocus = false },
            )
        }
    }
}

@Composable
private fun ColorPreviewAndHex(
    color: Int,
    hexValue: String,
    onHexValueChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier
                .padding(bottom = 8.dp)
                .size(44.dp),
            shape = CircleShape,
            color = Color(color),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant),
        ) {}
        OutlinedTextField(
            value = hexValue,
            onValueChange = { if (it.length <= 7) onHexValueChanged(it) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(132.dp).tvFocusTarget(),
        )
    }
}

@Composable
private fun HsvColorWheel(
    diameter: Dp,
    hue: Float,
    saturation: Float,
    brightness: Float,
    onColorPositionChanged: (Float, Float) -> Unit,
) {
    var adjusting by remember { mutableStateOf(false) }
    val hueColors = remember(brightness) {
        listOf(0F, 60F, 120F, 180F, 240F, 300F, 360F).map {
            Color.hsv(it % 360F, 1F, brightness)
        }
    }
    Canvas(
        modifier = Modifier
            .size(diameter)
            .tvFocusTarget(shape = CircleShape)
            .onFocusChanged { if (!it.isFocused) adjusting = false }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (event.nativeKeyEvent.repeatCount == 0) adjusting = !adjusting
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> if (adjusting) {
                        adjusting = false
                        true
                    } else false
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (adjusting) {
                        onColorPositionChanged((hue - 3F + 360F) % 360F, saturation)
                        true
                    } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (adjusting) {
                        onColorPositionChanged((hue + 3F) % 360F, saturation)
                        true
                    } else false
                    KeyEvent.KEYCODE_DPAD_UP -> if (adjusting) {
                        onColorPositionChanged(hue, (saturation + 0.02F).coerceAtMost(1F))
                        true
                    } else false
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (adjusting) {
                        onColorPositionChanged(hue, (saturation - 0.02F).coerceAtLeast(0F))
                        true
                    } else false
                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) {
                fun update(position: Offset) {
                    val centerX = this.size.width / 2F
                    val centerY = this.size.height / 2F
                    val radius = min(this.size.width, this.size.height) / 2F - 10.dp.toPx()
                    val dx = position.x - centerX
                    val dy = position.y - centerY
                    val newSaturation = (sqrt(dx * dx + dy * dy) / radius).coerceIn(0F, 1F)
                    val newHue = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360F) % 360F
                    onColorPositionChanged(newHue, newSaturation)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    update(down.position)
                    drag(down.id) { change ->
                        update(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        val radius = min(size.width, size.height) / 2F - 10.dp.toPx()
        drawCircle(
            brush = Brush.sweepGradient(hueColors),
            radius = radius,
        )
        val gray = Color.hsv(0F, 0F, brightness)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(gray, Color.Transparent),
                radius = radius,
            ),
            radius = radius,
        )
        val angle = Math.toRadians(hue.toDouble())
        val markerRadius = radius * saturation
        val markerCenter = Offset(
            center.x + cos(angle).toFloat() * markerRadius,
            center.y + sin(angle).toFloat() * markerRadius,
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.4F),
            radius = 8.dp.toPx(),
            center = markerCenter,
            style = Stroke(width = 5.dp.toPx()),
        )
        drawCircle(
            color = Color.White,
            radius = 8.dp.toPx(),
            center = markerCenter,
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

internal fun parseOpaqueHexColor(value: String): Int? {
    val digits = value.trim().removePrefix("#")
    if (!Regex("^[0-9A-Fa-f]{6}$").matches(digits)) return null
    return (0xFF000000L or digits.toLong(16)).toInt()
}

internal fun formatOpaqueHexColor(color: Int): String =
    String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
