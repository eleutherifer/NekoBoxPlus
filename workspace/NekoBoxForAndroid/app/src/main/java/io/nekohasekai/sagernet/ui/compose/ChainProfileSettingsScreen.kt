package io.nekohasekai.sagernet.ui.compose

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.profileCardType
import io.nekohasekai.sagernet.database.shouldHighlightAsInsecure
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.ProfileCountryResolver
import io.nekohasekai.sagernet.widget.CountryFlagRenderer
import moe.matsuri.nb4a.Protocols.getProtocolColor

@Composable
internal fun ChainProfileSettingsScreen(
    profiles: List<ProxyEntity>,
    onLoad: () -> Unit,
    onAdd: () -> Unit,
    onReplace: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    val context = LocalContext.current
    val notSet = stringResource(R.string.not_set)
    val nameTitle = stringResource(R.string.profile_name)
    var name by remember { mutableStateOf(DataStore.profileName) }
    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            val fromIndex = profiles.indexOfFirst { it.id == from }
            val toIndex = profiles.indexOfFirst { it.id == to }
            if (fromIndex >= 0 && toIndex >= 0) onMove(fromIndex, toIndex)
        },
        onMoveFinished = {},
    )
    LaunchedEffect(Unit) { onLoad() }
    LazyColumn(
        state = reorderState.listState,
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
    ) {
        item("profile-name") {
            ProfileActionRow(
                R.drawable.ic_social_emoji_symbols,
                R.string.profile_name,
                name.ifBlank { notSet },
            ) {
                context.showComposeTextInputDialog(nameTitle, name, onPositive = {
                    name = it
                    DataStore.profileName = it
                })
            }
        }
        item("add") { AddProfileCard(onAdd) }
        items(profiles, key = { profile -> profile.id }) { profile ->
            val isDragging = reorderState.isDragging(profile.id)
            ChainProfileRow(
                profile = profile,
                modifier = (if (isDragging) Modifier else Modifier.animateItem())
                    .zIndex(if (isDragging) 1f else 0f),
                onReplace = {
                    profiles.indexOfFirst { it.id == profile.id }
                        .takeIf { it >= 0 }
                        ?.let(onReplace)
                },
                onDelete = {
                    profiles.indexOfFirst { it.id == profile.id }
                        .takeIf { it >= 0 }
                        ?.let(onDelete)
                },
                reorderState = reorderState,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChainProfileRow(
    profile: ProxyEntity,
    modifier: Modifier,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
    reorderState: ReorderableLazyListState,
) {
    val context = LocalContext.current
    val isDragging = reorderState.isDragging(profile.id)
    var deleteRequested by remember(profile.id) { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart && !deleteRequested) {
            deleteRequested = true
            onDelete()
        }
        false
    })
    SwipeToDismissBox(
        modifier = modifier,
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .then(
                        if (isDragging) {
                            Modifier.dragTargetOutline(MaterialTheme.colorScheme.primary)
                        } else {
                            Modifier.background(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RectangleShape,
                            )
                        },
                    )
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (!isDragging) {
                    Icon(
                        painterResource(R.drawable.ic_action_delete),
                        stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
    ) {
        Box(Modifier.reorderableItem(reorderState, profile.id)) {
            val showTraffic = profile.rx + profile.tx != 0L
            val traffic = if (showTraffic) stringResource(
                R.string.traffic,
                Formatter.formatFileSize(context, profile.tx),
                Formatter.formatFileSize(context, profile.rx),
            ) else ""
            val countryVisible = DataStore.profileCountryIndicator && CountryFlagRenderer.loadSvg(
                context,
                ProfileCountryResolver.effectiveCountryCode(profile),
            ) != null
            ProfileCard(
                model = ProfileCardModel(
                    entity = profile,
                    layout = ProfileCardLayout.SINGLE,
                    name = ProfileCountryResolver.presentationName(profile, countryVisible),
                    type = profile.profileCardType(DataStore.shortProfileProtocolInfo),
                    countryVisible = countryVisible,
                    address = "",
                    traffic = "",
                    status = traffic,
                    typeColor = context.getProtocolColor(profile.type),
                    statusColor = context.getColorAttr(android.R.attr.textColorSecondary),
                    selected = false,
                    insecure = profile.shouldHighlightAsInsecure(
                        DataStore.globalAllowInsecure,
                        DataStore.dontHighlightInsecureProfiles,
                    ),
                    borders = DataStore.profileCardBorders,
                    middleRowVisible = false,
                    middleRowReserved = false,
                    statusVisible = showTraffic,
                    batchSelection = false,
                    batchSelected = false,
                    showEdit = true,
                    editEnabled = true,
                    showUrlTest = false,
                    urlTestEnabled = false,
                    showShare = false,
                    showDelete = false,
                    showOverflow = false,
                    minimumHeightDp = 0,
                ),
                onClick = {},
                onStatusClick = null,
                onEdit = onReplace,
                onUrlTest = {},
                onShare = {},
                onDelete = {},
                onSelectionChange = {},
            )
        }
    }
}
