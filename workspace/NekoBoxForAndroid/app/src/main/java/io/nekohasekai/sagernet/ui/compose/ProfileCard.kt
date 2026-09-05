package io.nekohasekai.sagernet.ui.compose

import android.view.KeyEvent
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ui.ProfileShareCapabilities
import io.nekohasekai.sagernet.utils.ProfileCountryResolver
import io.nekohasekai.sagernet.widget.CountryFlagRenderer

enum class ProfileCardLayout {
    SINGLE,
    COMPACT,
    DOUBLE,
    ALTERNATE,
}

enum class ProfileShareAction {
    STANDARD_QR,
    UNIVERSAL_QR,
    STANDARD_CLIPBOARD,
    UNIVERSAL_CLIPBOARD,
    CONFIGURATION_CLIPBOARD,
    CONFIGURATION_FILE,
}

data class ProfileCardModel(
    val entity: ProxyEntity,
    val layout: ProfileCardLayout,
    val name: String,
    val type: String,
    val countryVisible: Boolean,
    val address: String,
    val traffic: String,
    val status: String,
    @ColorInt val typeColor: Int,
    @ColorInt val statusColor: Int,
    val selected: Boolean,
    val insecure: Boolean,
    val borders: Boolean,
    val middleRowVisible: Boolean,
    val middleRowReserved: Boolean,
    val statusVisible: Boolean,
    val batchSelection: Boolean,
    val batchSelected: Boolean,
    val showEdit: Boolean,
    val editEnabled: Boolean,
    val showUrlTest: Boolean,
    val urlTestEnabled: Boolean,
    val showShare: Boolean,
    val showDelete: Boolean,
    val showOverflow: Boolean,
    val minimumHeightDp: Int,
)

internal fun shouldWrapDoubleProfileFooter(
    availableWidth: Int,
    statusWidth: Int,
    trafficWidth: Int,
    spacing: Int,
): Boolean = statusWidth > 0 && trafficWidth > 0 &&
    statusWidth > availableWidth - trafficWidth - spacing

private class ProfileCardFocusHandles {
    val body = FocusRequester()
    val edit = FocusRequester()
    val urlTest = FocusRequester()
    val share = FocusRequester()
    val delete = FocusRequester()
    val overflow = FocusRequester()
}

@Composable
fun ProfileCard(
    model: ProfileCardModel,
    bodyFocusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onStatusClick: (() -> Unit)?,
    onEdit: () -> Unit,
    onUrlTest: () -> Unit,
    onShare: (ProfileShareAction) -> Unit,
    onDelete: () -> Unit,
    onSelectionChange: () -> Unit,
) {
    val televisionUi = isTelevisionUi()
    val focusHandles = remember(model.entity.id) { ProfileCardFocusHandles() }
    var bodyFocused by remember(model.entity.id) { mutableStateOf(false) }
    val firstAction = when {
        model.showEdit -> focusHandles.edit
        model.showUrlTest -> focusHandles.urlTest
        model.showShare -> focusHandles.share
        model.showDelete -> focusHandles.delete
        model.showOverflow -> focusHandles.overflow
        else -> null
    }
    val compact = model.layout == ProfileCardLayout.COMPACT
    val double = model.layout == ProfileCardLayout.DOUBLE ||
        model.layout == ProfileCardLayout.ALTERNATE
    val elevation = if (model.borders) 0.dp else if (compact) 2.dp else 1.dp
    val border = when {
        model.borders && model.selected -> BorderStroke(
            2.dp,
            MaterialTheme.colorScheme.primary,
        )
        model.insecure -> BorderStroke(
            if (compact) 1.dp else 2.dp,
            MaterialTheme.colorScheme.error,
        )
        model.borders -> BorderStroke(
            if (model.selected) 2.dp else 1.dp,
            if (model.selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        )
        else -> null
    }
    val containerColor = if (model.borders) {
        if (model.selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                .compositeOver(MaterialTheme.colorScheme.surface)
        } else {
            MaterialTheme.colorScheme.surface
        }
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(2.dp)
            .focusGroup()
            .tvFocusTarget()
            .focusRequester(bodyFocusRequester ?: focusHandles.body)
            .onFocusChanged { bodyFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (televisionUi && bodyFocused && firstAction != null &&
                    event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.repeatCount == 0 &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                    firstAction.requestFocus()
                    true
                } else false
            }
            .clickable(onClick = onClick),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
        ),
        border = border,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .heightIn(min = model.minimumHeightDp.dp),
        ) {
            Box(
                Modifier
                    .matchParentSize(),
            ) {
                Box(
                    Modifier
                        .width(8.dp)
                        .fillMaxHeight()
                        .background(
                            if (model.selected && !model.borders) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                        ),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp)
                    .height(IntrinsicSize.Min),
            ) {
                if (double) {
                    DoubleProfileContent(
                        model,
                        onStatusClick,
                        onEdit,
                        onUrlTest,
                        onShare,
                        onDelete,
                        onSelectionChange,
                        focusHandles,
                    )
                } else {
                    LinearProfileContent(
                        model,
                        compact,
                        onStatusClick,
                        onEdit,
                        onUrlTest,
                        onShare,
                        onDelete,
                        onSelectionChange,
                        focusHandles,
                    )
                }
            }
        }
    }
}

@Composable
private fun LinearProfileContent(
    model: ProfileCardModel,
    compact: Boolean,
    onStatusClick: (() -> Unit)?,
    onEdit: () -> Unit,
    onUrlTest: () -> Unit,
    onShare: (ProfileShareAction) -> Unit,
    onDelete: () -> Unit,
    onSelectionChange: () -> Unit,
    focusHandles: ProfileCardFocusHandles,
) {
    val televisionUi = isTelevisionUi()
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = if (compact) 2.dp else 12.dp,
                        top = if (compact) 2.dp else 8.dp,
                        bottom = if (compact) 2.dp else 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (model.countryVisible) {
                    CountryBadge(model.entity, if (compact) 16 else 22)
                }
                Text(
                    text = model.name,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (compact) 3.dp else 6.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = if (compact) 14.sp else 14.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (model.showEdit) {
                ProfileIconAction(
                    R.drawable.ic_image_edit,
                    R.string.edit,
                    compact,
                    model.editEnabled,
                    focusHandles.edit,
                    focusHandles.body,
                    onEdit,
                )
            }
            if (model.showUrlTest) {
                ProfileIconAction(
                    R.drawable.ic_baseline_shutter_speed_24,
                    R.string.connection_test_url_test,
                    compact,
                    model.urlTestEnabled,
                    focusHandles.urlTest,
                    if (model.showEdit) focusHandles.edit else focusHandles.body,
                    onUrlTest,
                )
            }
            if (model.showShare) {
                ProfileShareMenu(
                    model = model,
                    compact = compact,
                    focusRequester = focusHandles.share,
                    leftFocusRequester = when {
                        model.showUrlTest -> focusHandles.urlTest
                        model.showEdit -> focusHandles.edit
                        else -> focusHandles.body
                    },
                    onShare = onShare,
                )
            }
            if (model.showDelete) {
                ProfileIconAction(
                    R.drawable.ic_action_delete,
                    R.string.delete,
                    compact,
                    model.editEnabled,
                    focusHandles.delete,
                    when {
                        model.showShare -> focusHandles.share
                        model.showUrlTest -> focusHandles.urlTest
                        model.showEdit -> focusHandles.edit
                        else -> focusHandles.body
                    },
                    onDelete,
                )
            }
            if (model.batchSelection) {
                Checkbox(
                    checked = model.batchSelected,
                    onCheckedChange = { onSelectionChange() },
                    modifier = Modifier.size(if (compact) 28.dp else 48.dp),
                )
            }
        }

        if (compact) {
            CompactDetails(model, onStatusClick)
        } else {
            if (model.middleRowVisible || model.middleRowReserved) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MarqueeText(
                        text = model.address,
                        modifier = Modifier.weight(1f),
                        color = if (model.middleRowVisible) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else Color.Transparent,
                        fontSize = 14.sp,
                        lineHeight = 16.sp,
                    )
                    if (model.traffic.isNotEmpty()) {
                        Text(
                            model.traffic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    model.type,
                    color = Color(model.typeColor),
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.width(4.dp))
                MarqueeText(
                    text = model.status,
                    modifier = Modifier
                        .weight(1f)
                        .focusProperties { canFocus = !televisionUi }
                        .statusClick(onStatusClick),
                    color = Color(model.statusColor),
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun CompactDetails(model: ProfileCardModel, onStatusClick: (() -> Unit)?) {
    val televisionUi = isTelevisionUi()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, end = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            model.type,
            color = Color(model.typeColor),
            fontSize = 10.sp,
            lineHeight = 12.sp,
        )
        if (model.status.isNotEmpty()) {
            MarqueeText(
                text = model.status,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .widthIn(max = 140.dp)
                    .focusProperties { canFocus = !televisionUi }
                    .statusClick(onStatusClick),
                color = Color(model.statusColor),
                fontSize = 10.sp,
                lineHeight = 12.sp,
            )
        }
        if (model.middleRowVisible) {
            Spacer(Modifier.width(8.dp))
            MarqueeText(
                text = model.address,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                textAlign = TextAlign.End,
            )
            if (model.traffic.isNotEmpty()) {
                Text(
                    model.traffic,
                    modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun DoubleProfileContent(
    model: ProfileCardModel,
    onStatusClick: (() -> Unit)?,
    onEdit: () -> Unit,
    onUrlTest: () -> Unit,
    onShare: (ProfileShareAction) -> Unit,
    onDelete: () -> Unit,
    onSelectionChange: () -> Unit,
    focusHandles: ProfileCardFocusHandles,
) {
    val televisionUi = isTelevisionUi()
    Box(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                if (model.countryVisible) CountryBadge(model.entity, 22)
                Text(
                    model.name,
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            start = if (model.countryVisible) 6.dp else 0.dp,
                            end = 40.dp,
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                model.type,
                modifier = Modifier.padding(top = 2.dp),
                color = Color(model.typeColor),
                fontSize = if (model.layout == ProfileCardLayout.ALTERNATE) 13.sp else 12.sp,
                lineHeight = 16.sp,
            )
            if (model.middleRowVisible) {
                MarqueeText(
                    text = model.address,
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                )
            }
            Spacer(Modifier.height(3.dp))
            DoubleProfileFooter(model, televisionUi, onStatusClick)
        }
        if (model.batchSelection) {
            Checkbox(
                checked = model.batchSelected,
                onCheckedChange = { onSelectionChange() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp),
            )
        } else if (model.showOverflow) {
            ProfileOverflowMenu(
                model,
                modifier = Modifier.align(Alignment.TopEnd),
                onEdit = onEdit,
                onUrlTest = onUrlTest,
                onShare = onShare,
                onDelete = onDelete,
                focusRequester = focusHandles.overflow,
                leftFocusRequester = focusHandles.body,
            )
        }
    }
}

@Composable
private fun DoubleProfileFooter(
    model: ProfileCardModel,
    televisionUi: Boolean,
    onStatusClick: (() -> Unit)?,
) {
    val showStatus = model.statusVisible && model.status.isNotEmpty()
    val showTraffic = model.traffic.isNotEmpty()
    val spacing = 8.dp
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            if (showStatus) {
                MarqueeText(
                    text = model.status,
                    modifier = Modifier
                        .focusProperties { canFocus = !televisionUi }
                        .statusClick(onStatusClick),
                    color = Color(model.statusColor),
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                )
            }
            if (showTraffic) {
                Text(
                    model.traffic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                )
            }
        },
    ) { measurables, constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        var index = 0
        val status = if (showStatus) measurables[index++].measure(looseConstraints) else null
        val traffic = if (showTraffic) measurables[index].measure(looseConstraints) else null
        val spacingPx = spacing.roundToPx()
        val naturalWidth = (status?.width ?: 0) + (traffic?.width ?: 0) +
            if (status != null && traffic != null) spacingPx else 0
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            naturalWidth.coerceAtLeast(constraints.minWidth)
        }
        val wrap = shouldWrapDoubleProfileFooter(
            availableWidth = width,
            statusWidth = status?.width ?: 0,
            trafficWidth = traffic?.width ?: 0,
            spacing = spacingPx,
        )
        val height = if (wrap) {
            (traffic?.height ?: 0) + (status?.height ?: 0)
        } else {
            maxOf(status?.height ?: 0, traffic?.height ?: 0)
        }
        val constrainedHeight = height.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, constrainedHeight) {
            if (wrap) {
                traffic?.placeRelative(width - traffic.width, 0)
                status?.placeRelative(0, traffic?.height ?: 0)
            } else {
                val statusTrailingSpace = if (traffic == null) 0 else traffic.width + spacingPx
                traffic?.placeRelative(
                    width - traffic.width,
                    (height - traffic.height) / 2,
                )
                status?.placeRelative(
                    width - statusTrailingSpace - status.width,
                    (height - status.height) / 2,
                )
            }
        }
    }
}

@Composable
private fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier.basicMarquee(
            iterations = Int.MAX_VALUE,
            repeatDelayMillis = 1_200,
            spacing = MarqueeSpacing(24.dp),
        ),
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        textAlign = textAlign,
        maxLines = 1,
    )
}

private fun Modifier.statusClick(onClick: (() -> Unit)?): Modifier = then(
    if (onClick == null) Modifier else Modifier.clickable(
        interactionSource = null,
        indication = null,
        onClick = onClick,
    ),
)

@Composable
private fun ProfileOverflowMenu(
    model: ProfileCardModel,
    modifier: Modifier,
    onEdit: () -> Unit,
    onUrlTest: () -> Unit,
    onShare: (ProfileShareAction) -> Unit,
    onDelete: () -> Unit,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
) {
    var expanded by remember(model.entity.id) { mutableStateOf(false) }
    var shareExpanded by remember(model.entity.id) { mutableStateOf(false) }
    Box(modifier) {
        ProfileIconAction(
            R.drawable.ic_baseline_more_vert_24,
            R.string.profile_actions,
            compact = false,
            enabled = true,
            focusRequester = focusRequester,
            leftFocusRequester = leftFocusRequester,
        ) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ProfileMenuItem(R.string.edit, model.editEnabled) { expanded = false; onEdit() }
            ProfileMenuItem(R.string.connection_test_url_test, model.urlTestEnabled) {
                expanded = false
                onUrlTest()
            }
            ProfileMenuItem(R.string.share, true) {
                expanded = false
                shareExpanded = true
            }
            ProfileMenuItem(R.string.delete, model.editEnabled) { expanded = false; onDelete() }
        }
        ProfileShareDropdown(
            model = model,
            expanded = shareExpanded,
            onDismissRequest = { shareExpanded = false },
            onShare = onShare,
        )
    }
}

private enum class ProfileShareSection(val title: Int) {
    QR(R.string.share_qr_nfc),
    CLIPBOARD(R.string.action_export_clipboard),
    CONFIGURATION(R.string.menu_configuration),
}

@Composable
private fun ProfileShareMenu(
    model: ProfileCardModel,
    compact: Boolean,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    onShare: (ProfileShareAction) -> Unit,
) {
    var expanded by remember(model.entity.id) { mutableStateOf(false) }
    Box {
        ProfileIconAction(
            R.drawable.ic_social_share,
            R.string.share,
            compact,
            enabled = true,
            focusRequester = focusRequester,
            leftFocusRequester = leftFocusRequester,
        ) { expanded = true }
        ProfileShareDropdown(
            model = model,
            expanded = expanded,
            onDismissRequest = { expanded = false },
            onShare = onShare,
        )
    }
}

@Composable
private fun ProfileShareDropdown(
    model: ProfileCardModel,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onShare: (ProfileShareAction) -> Unit,
) {
    var section by remember(model.entity.id) { mutableStateOf<ProfileShareSection?>(null) }
    val capabilities = ProfileShareCapabilities.from(model.entity)
    val dismiss = {
        section = null
        onDismissRequest()
    }
    val select: (ProfileShareAction) -> Unit = { action ->
        dismiss()
        onShare(action)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
        val currentSection = section
        if (currentSection == null) {
            if (capabilities.links) {
                ProfileMenuItem(R.string.share_qr_nfc, true) {
                    section = ProfileShareSection.QR
                }
                ProfileMenuItem(R.string.action_export_clipboard, true) {
                    section = ProfileShareSection.CLIPBOARD
                }
            }
            if (capabilities.configuration) {
                ProfileMenuItem(R.string.menu_configuration, true) {
                    section = ProfileShareSection.CONFIGURATION
                }
            }
        } else {
            DropdownMenuItem(
                text = { Text(stringResource(currentSection.title)) },
                leadingIcon = {
                    Icon(
                        painterResource(R.drawable.baseline_arrow_back_24),
                        contentDescription = null,
                    )
                },
                onClick = { section = null },
            )
            when (currentSection) {
                ProfileShareSection.QR -> {
                    if (capabilities.standardLinks) {
                        ProfileMenuItem(R.string.standard, true) {
                            select(ProfileShareAction.STANDARD_QR)
                        }
                    }
                    ProfileMenuItem(R.string.sn_link, true) {
                        select(ProfileShareAction.UNIVERSAL_QR)
                    }
                }
                ProfileShareSection.CLIPBOARD -> {
                    if (capabilities.standardLinks) {
                        ProfileMenuItem(R.string.standard, true) {
                            select(ProfileShareAction.STANDARD_CLIPBOARD)
                        }
                    }
                    ProfileMenuItem(R.string.sn_link, true) {
                        select(ProfileShareAction.UNIVERSAL_CLIPBOARD)
                    }
                }
                ProfileShareSection.CONFIGURATION -> {
                    ProfileMenuItem(R.string.action_export_clipboard, true) {
                        select(ProfileShareAction.CONFIGURATION_CLIPBOARD)
                    }
                    ProfileMenuItem(R.string.action_export_file, true) {
                        select(ProfileShareAction.CONFIGURATION_FILE)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileMenuItem(
    @StringRes title: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(title)) },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun ProfileIconAction(
    @DrawableRes icon: Int,
    @StringRes description: Int,
    compact: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 28.dp, height = if (compact) 28.dp else 48.dp)
            .tvFocusTarget(enabled)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier,
            )
            .focusProperties {
                if (leftFocusRequester != null) left = leftFocusRequester
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = stringResource(description),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (enabled) 1f else 0.38f,
            ),
        )
    }
}

@Composable
private fun CountryBadge(entity: ProxyEntity, sizeDp: Int) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    val countryCode = remember(entity) { ProfileCountryResolver.effectiveCountryCode(entity) }
    val bitmap = remember(countryCode, sizePx) {
        CountryFlagRenderer.renderCircularIcon(context, countryCode, sizePx)?.asImageBitmap()
    } ?: return
    Image(
        bitmap = bitmap,
        contentDescription = stringResource(R.string.profile_country, countryCode),
        modifier = Modifier.size(sizeDp.dp),
    )
}
