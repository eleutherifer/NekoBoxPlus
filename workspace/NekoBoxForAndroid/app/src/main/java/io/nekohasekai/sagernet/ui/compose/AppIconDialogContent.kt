package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sagernet.AppIcon
import io.nekohasekai.sagernet.R

@Composable
internal fun AppIconDialogContent(
    icons: List<AppIcon>,
    previews: Map<AppIcon, ImageBitmap>,
    selectedIcon: AppIcon,
    selectedColor: Color,
    onSelected: (AppIcon) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedDescription = stringResource(R.string.app_icon_selected)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        icons.forEach { appIcon ->
            val selected = appIcon == selectedIcon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .background(if (selected) selectedColor else Color.Transparent)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelected(appIcon) },
                    )
                    .semantics {
                        if (selected) stateDescription = selectedDescription
                    }
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                previews[appIcon]?.let { preview ->
                    Image(
                        painter = BitmapPainter(preview),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                    )
                } ?: Spacer(Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(appIcon.titleRes),
                    modifier = Modifier.weight(1f),
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontSize = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
