package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.viewpager2.widget.ViewPager2
import io.nekohasekai.sagernet.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    pageNames: List<String>,
    selectedPage: Int,
    onOpenDrawer: () -> Unit,
    onPageSelected: (Int) -> Unit,
    createPager: () -> ViewPager2,
) {
    val televisionUi = isTelevisionUi()
    val firstTabFocusRequester = androidx.compose.runtime.remember { FocusRequester() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_tools)) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer, modifier = Modifier.tvFocusTarget()) {
                        Icon(
                            painter = painterResource(R.drawable.ic_navigation_menu),
                            contentDescription = stringResource(R.string.menu_tools),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryTabRow(selectedTabIndex = selectedPage) {
                pageNames.forEachIndexed { index, name ->
                    Tab(
                        selected = index == selectedPage,
                        onClick = { onPageSelected(index) },
                        text = { Text(name) },
                        modifier = Modifier
                            .tvFocusTarget()
                            .then(
                                if (index == 0) Modifier.focusRequester(firstTabFocusRequester)
                                else Modifier,
                            ),
                    )
                }
            }
            AndroidView(
                factory = { createPager() },
                update = { pager ->
                    if (pager.currentItem != selectedPage) {
                        pager.setCurrentItem(selectedPage, true)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            )
        }
    }
    LaunchedEffect(Unit) {
        if (televisionUi) firstTabFocusRequester.requestFocus()
    }
}
