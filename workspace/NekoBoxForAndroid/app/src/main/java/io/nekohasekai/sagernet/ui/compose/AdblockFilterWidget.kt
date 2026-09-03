package io.nekohasekai.sagernet.ui.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import io.nekohasekai.sagernet.R

@Composable
internal fun AdblockFilterWidget(
    checked: Boolean,
    updateEnabled: Boolean,
    running: Boolean,
    onUpdate: () -> Unit,
) {
    val animatedRotation = if (running) {
        val transition = rememberInfiniteTransition(label = "adblockFilterUpdate")
        val rotation by transition.animateFloat(
            initialValue = 0F,
            targetValue = 360F,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "adblockFilterUpdateRotation",
        )
        rotation
    } else {
        0F
    }
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .wrapContentWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.semantics {
                role = Role.Checkbox
                toggleableState = if (checked) {
                    ToggleableState.On
                } else {
                    ToggleableState.Off
                }
            },
        )
        IconButton(
            onClick = onUpdate,
            enabled = updateEnabled && !running,
            modifier = Modifier.alpha(if (updateEnabled) 1F else 0.38F),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_refresh_24),
                contentDescription = stringResource(R.string.adblock_update_filter),
                modifier = Modifier.rotate(animatedRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
