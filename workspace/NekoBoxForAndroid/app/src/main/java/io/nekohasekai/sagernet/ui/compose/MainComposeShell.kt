package io.nekohasekai.sagernet.ui.compose

import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R

internal class MainShellState {
    var drawerRequestedOpen by mutableStateOf(false)
    var drawerIsOpen by mutableStateOf(false)
    var selectedItemId by mutableIntStateOf(R.id.nav_configuration)
    var trafficVisible by mutableStateOf(false)
    var proxyAppsEnabled by mutableStateOf(false)

    fun openDrawer() {
        drawerRequestedOpen = true
    }

    fun closeDrawer() {
        drawerRequestedOpen = false
    }
}

private data class DrawerDestination(
    @param:IdRes val id: Int,
    @param:StringRes val title: Int,
    @param:DrawableRes val icon: Int,
)

private val primaryDestinations = listOf(
    DrawerDestination(R.id.nav_configuration, R.string.menu_configuration, R.drawable.ic_action_description),
    DrawerDestination(R.id.nav_group, R.string.menu_group, R.drawable.ic_baseline_view_list_24),
    DrawerDestination(R.id.nav_route, R.string.menu_route, R.drawable.ic_maps_directions),
    DrawerDestination(R.id.nav_adblock, R.string.adblock, R.drawable.ic_baseline_filter_list_24),
    DrawerDestination(R.id.nav_settings, R.string.settings, R.drawable.ic_action_settings),
)

private val utilityDestinations = listOf(
    DrawerDestination(R.id.nav_logcat, R.string.menu_log, R.drawable.ic_baseline_bug_report_24),
    DrawerDestination(R.id.nav_tools, R.string.menu_tools, R.drawable.baseline_construction_24),
)

@Composable
internal fun MainComposeDrawer(
    state: MainShellState,
    onNavigate: (Int) -> Unit,
    onOpenApps: () -> Unit,
    onToggleProxyApps: (Boolean) -> Unit,
    onDrawerActiveChanged: (Boolean) -> Unit = {},
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerFocusRequester = remember { FocusRequester() }
    LaunchedEffect(state.drawerRequestedOpen) {
        if (state.drawerRequestedOpen) {
            drawerState.open()
            drawerFocusRequester.requestFocus()
        } else {
            drawerState.close()
        }
    }
    LaunchedEffect(drawerState.currentValue, drawerState.targetValue) {
        state.drawerIsOpen = drawerState.currentValue == DrawerValue.Open ||
            drawerState.targetValue == DrawerValue.Open
        if (drawerState.currentValue == DrawerValue.Open &&
            drawerState.targetValue == DrawerValue.Open
        ) {
            state.drawerRequestedOpen = true
        } else if (drawerState.currentValue == DrawerValue.Closed &&
            drawerState.targetValue == DrawerValue.Closed
        ) {
            state.drawerRequestedOpen = false
        }
    }
    LaunchedEffect(state.drawerRequestedOpen, state.drawerIsOpen) {
        onDrawerActiveChanged(state.drawerRequestedOpen || state.drawerIsOpen)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .focusGroup()
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                )
                DrawerItems(
                    primaryDestinations,
                    state.selectedItemId,
                    drawerFocusRequester,
                ) { onNavigate(it); state.closeDrawer() }
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.apps)) },
                    icon = { Icon(painterResource(R.drawable.ic_navigation_apps), contentDescription = null) },
                    badge = {
                        Switch(
                            checked = state.proxyAppsEnabled,
                            onCheckedChange = onToggleProxyApps,
                            modifier = Modifier.tvFocusTarget(),
                        )
                    },
                    selected = false,
                    onClick = { onOpenApps(); state.closeDrawer() },
                    modifier = Modifier
                        .tvFocusTarget()
                        .padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Column {
                    if (state.trafficVisible) {
                        DrawerItem(
                            DrawerDestination(R.id.nav_traffic, R.string.menu_dashboard, R.drawable.ic_baseline_transform_24),
                            state.selectedItemId,
                        ) { onNavigate(it); state.closeDrawer() }
                    }
                    DrawerItems(utilityDestinations, state.selectedItemId) { onNavigate(it); state.closeDrawer() }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                DrawerItem(
                    DrawerDestination(R.id.nav_about, R.string.menu_about, R.drawable.ic_baseline_info_24),
                    state.selectedItemId,
                ) { onNavigate(it); state.closeDrawer() }
            }
        },
    ) {
        Spacer(Modifier.fillMaxSize())
    }
}

@Composable
private fun DrawerItems(
    items: List<DrawerDestination>,
    selectedId: Int,
    initialFocusRequester: FocusRequester? = null,
    onClick: (Int) -> Unit,
) {
    items.forEachIndexed { index, item ->
        DrawerItem(item, selectedId, initialFocusRequester.takeIf { index == 0 }, onClick)
    }
}

@Composable
private fun DrawerItem(
    item: DrawerDestination,
    selectedId: Int,
    focusRequester: FocusRequester? = null,
    onClick: (Int) -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(stringResource(item.title)) },
        icon = { Icon(painterResource(item.icon), contentDescription = null) },
        selected = item.id == selectedId,
        onClick = { onClick(item.id) },
        modifier = Modifier
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .tvFocusTarget()
            .padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}
