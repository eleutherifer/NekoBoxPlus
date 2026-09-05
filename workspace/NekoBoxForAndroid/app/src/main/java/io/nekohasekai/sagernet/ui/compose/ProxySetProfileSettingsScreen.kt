package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.profileCardType
import io.nekohasekai.sagernet.database.shouldHighlightAsInsecure
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.ProfileCountryResolver
import io.nekohasekai.sagernet.widget.CountryFlagRenderer
import moe.matsuri.nb4a.Protocols.getProtocolColor

@Composable
internal fun ProxySetProfileSettingsScreen(
    profiles: List<ProxyEntity>,
    testingIds: List<Long>,
    hasEmbeddedMembers: Boolean,
    selectedGroupName: String,
    defaultOutboundName: String,
    onLoad: () -> Unit,
    onSettingChanged: () -> Unit,
    onSelectGroup: () -> Unit,
    onSelectDefaultOutbound: () -> Unit,
    onAdd: () -> Unit,
    onReplace: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
    onUrlTest: (ProxyEntity) -> Unit,
    onShare: (ProfileShareAction, ProxyEntity) -> Unit,
) {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val cancel = stringResource(android.R.string.cancel)
    val textKeys = remember { listOf(
        "profileName", "proxySetTestURL", "proxySetTestInterval", "proxySetTestIdleTimeout",
        "proxySetTestTolerance", "proxySetGroupFilterNotRegex",
    ) }
    val texts = remember { mutableStateMapOf<String, String>().apply {
        textKeys.forEach { put(it, store.getString(it).orEmpty()) }
    } }
    var mode by remember { mutableStateOf(store.getString("proxySetMode").orEmpty()) }
    var type by remember { mutableStateOf(store.getString("proxySetType").orEmpty()) }
    var interrupt by remember { mutableStateOf(store.getBoolean("proxySetInterruptExistConnections", false)) }
    var skipInsecure by remember { mutableStateOf(store.getBoolean("proxySetSkipInsecureProfiles", false)) }
    val isUrlTest = mode.toIntOrNull() == ProxySetBean.MODE_URL_TEST
    val isGroup = type.toIntOrNull() == ProxySetBean.TYPE_GROUP
    val modeEntries = stringArrayResource(R.array.proxy_set_mode_entry).toList()
    val modeValues = stringArrayResource(R.array.int_array_2).toList()
    val typeEntries = stringArrayResource(R.array.proxy_set_collect_type).toList()
    val typeValues = stringArrayResource(R.array.int_array_2).toList()
    val modeTitle = stringResource(R.string.proxy_set_mode)
    val typeTitle = stringResource(R.string.proxy_set_type)
    LaunchedEffect(Unit) { onLoad() }

    @Composable
    fun textRow(icon: Int, titleRes: Int, key: String, number: Boolean = false) {
        val value = texts[key].orEmpty()
        val title = stringResource(titleRes)
        ProfileActionRow(icon, titleRes, value.ifBlank { notSet }) {
            context.showComposeTextInputDialog(
                title,
                value,
                keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
                onPositive = {
                    texts[key] = it
                    store.putString(key, it)
                    onSettingChanged()
                },
            )
        }
    }

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item("name") { textRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name, "profileName") }
        item("mode") {
            ProfileActionRow(
                R.drawable.ic_baseline_tune_24,
                R.string.proxy_set_mode,
                modeEntries.getOrElse(modeValues.indexOf(mode).coerceAtLeast(0)) { mode },
                enabled = !hasEmbeddedMembers,
            ) {
                context.showComposeSingleChoiceDialog(
                    modeTitle, modeEntries,
                    modeValues.indexOf(mode).coerceAtLeast(0), cancel,
                    onItemSelected = {
                    mode = modeValues[it]
                    store.putString("proxySetMode", mode)
                    onSettingChanged()
                    },
                )
            }
        }
        if (!isUrlTest) item("default") {
            ProfileActionRow(R.drawable.ic_baseline_push_pin_24, R.string.proxy_set_default_outbound,
                defaultOutboundName, onClick = onSelectDefaultOutbound)
        }
        item("interrupt") {
            // painterResource supports static vectors and raster assets, not animated-vector XML.
            ProfileSwitchRow(R.drawable.ic_service_idle, R.string.interrupt_exist_connections, interrupt) {
                interrupt = it
                store.putBoolean("proxySetInterruptExistConnections", it)
                onSettingChanged()
            }
        }
        if (isUrlTest) {
            item("test-url") { textRow(R.drawable.ic_baseline_cast_connected_24, R.string.connection_test_url, "proxySetTestURL") }
            item("test-interval") { textRow(R.drawable.ic_baseline_flip_camera_android_24, R.string.urltest_interval, "proxySetTestInterval") }
            item("test-idle") { textRow(R.drawable.ic_image_camera_alt, R.string.idle_timeout, "proxySetTestIdleTimeout") }
            item("test-tolerance") { textRow(R.drawable.ic_baseline_emoji_emotions_24, R.string.urltest_tolerance, "proxySetTestTolerance", true) }
        }
        item("type") {
            ProfileActionRow(
                R.drawable.ic_baseline_nfc_24,
                R.string.proxy_set_type,
                typeEntries.getOrElse(typeValues.indexOf(type).coerceAtLeast(0)) { type },
                enabled = !hasEmbeddedMembers,
            ) {
                context.showComposeSingleChoiceDialog(
                    typeTitle, typeEntries,
                    typeValues.indexOf(type).coerceAtLeast(0), cancel,
                    onItemSelected = {
                    type = typeValues[it]
                    store.putString("proxySetType", type)
                    onSettingChanged()
                    },
                )
            }
        }
        if (isGroup) {
            item("group") {
                ProfileActionRow(R.drawable.ic_baseline_view_list_24, R.string.proxy_set_type_group,
                    selectedGroupName, onClick = onSelectGroup)
            }
            item("group-filter") { textRow(R.drawable.baseline_delete_sweep_24, R.string.filter_regex,
                "proxySetGroupFilterNotRegex") }
        }
        item("skip-insecure") {
            ProfileSwitchRow(R.drawable.ic_baseline_security_24, R.string.proxy_set_skip_insecure_profiles,
                skipInsecure) {
                skipInsecure = it
                store.putBoolean("proxySetSkipInsecureProfiles", it)
                onSettingChanged()
            }
        }
        if (hasEmbeddedMembers || !isGroup) {
            if (!hasEmbeddedMembers) item("add") { AddProfileCard(onAdd) }
            itemsIndexed(profiles, key = { _, profile -> profile.id }) { index, profile ->
                ProxySetProfileRow(
                    profile, index, hasEmbeddedMembers, profile.id in testingIds,
                    onReplace, onMove, onDelete, onUrlTest, onShare,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxySetProfileRow(
    profile: ProxyEntity,
    index: Int,
    embedded: Boolean,
    testing: Boolean,
    onReplace: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
    onUrlTest: (ProxyEntity) -> Unit,
    onShare: (ProfileShareAction, ProxyEntity) -> Unit,
) {
    val context = LocalContext.current
    var deleteRequested by remember(profile.id) { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (!embedded && it == SwipeToDismissBoxValue.EndToStart && !deleteRequested) {
            deleteRequested = true
            onDelete(index)
        }
        false
    })
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !embedded,
        backgroundContent = {
            Box(Modifier.fillMaxSize().padding(2.dp).background(
                MaterialTheme.colorScheme.errorContainer, RectangleShape,
            ).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(painterResource(R.drawable.ic_action_delete), stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        var dragDistance by remember(profile.id) { mutableFloatStateOf(0f) }
        val dragModifier = if (!embedded) Modifier.pointerInput(profile.id, index) {
            detectDragGesturesAfterLongPress(
                onDragStart = { dragDistance = 0f },
                onDragEnd = { dragDistance = 0f },
                onDragCancel = { dragDistance = 0f },
                onDrag = { change, amount ->
                    change.consume()
                    dragDistance += amount.y
                    val threshold = 56.dp.toPx()
                    when {
                        dragDistance > threshold -> { onMove(index, index + 1); dragDistance = 0f }
                        dragDistance < -threshold -> { onMove(index, index - 1); dragDistance = 0f }
                    }
                },
            )
        } else Modifier
        Box(dragModifier) {
            val countryVisible = DataStore.profileCountryIndicator && CountryFlagRenderer.loadSvg(
                context, ProfileCountryResolver.effectiveCountryCode(profile),
            ) != null
            val status = when {
                testing -> stringResource(R.string.connection_test_testing)
                profile.status == 1 -> stringResource(R.string.available, profile.ping)
                profile.status >= 2 -> profile.error.orEmpty()
                else -> ""
            }
            ProfileCard(
                model = ProfileCardModel(
                    entity = profile,
                    layout = ProfileCardLayout.SINGLE,
                    name = ProfileCountryResolver.presentationName(profile, countryVisible),
                    type = profile.profileCardType(DataStore.shortProfileProtocolInfo),
                    countryVisible = countryVisible,
                    address = "", traffic = "", status = status,
                    typeColor = context.getProtocolColor(profile.type),
                    statusColor = context.getColorAttr(android.R.attr.textColorSecondary),
                    selected = false,
                    insecure = profile.shouldHighlightAsInsecure(
                        DataStore.globalAllowInsecure, DataStore.dontHighlightInsecureProfiles,
                    ),
                    borders = DataStore.profileCardBorders,
                    middleRowVisible = false, middleRowReserved = false,
                    statusVisible = status.isNotEmpty(), batchSelection = false, batchSelected = false,
                    showEdit = !embedded, editEnabled = !embedded,
                    showUrlTest = embedded,
                    urlTestEnabled = !UrlTest.isUnsupportedProfile(profile) && !testing,
                    showShare = embedded, showDelete = false, showOverflow = false, minimumHeightDp = 0,
                ),
                onClick = {}, onStatusClick = null, onEdit = { onReplace(index) },
                onUrlTest = { onUrlTest(profile) }, onShare = { onShare(it, profile) },
                onDelete = {}, onSelectionChange = {},
            )
        }
    }
}
