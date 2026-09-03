package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ui.compose.GlobalSettingsGroupScreen
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.SettingsCategoryScreen
import io.nekohasekai.sagernet.ui.compose.SettingsSearchBar
import io.nekohasekai.sagernet.ui.compose.SettingsSearchResult
import io.nekohasekai.sagernet.ui.compose.SettingsSearchResultsScreen
import io.nekohasekai.sagernet.ui.compose.globalSettingsFor
import androidx.preference.PreferenceDataStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class SettingsFragment : ToolbarFragment(),
    OnPreferenceDataStoreChangeListener {

    companion object {
        private const val INTERFACE_GROUP_ID = "interface"
        private var openInterfaceOnCreate = false

        fun restoreInterfaceOnNextCreate() {
            openInterfaceOnCreate = true
        }
    }

    private var page by mutableStateOf<Page>(Page.Top)
    private var pageDirection by mutableStateOf(Direction.NONE)
    private var searchQuery by mutableStateOf("")
    private var revision by mutableIntStateOf(0)
    // Activity-result launchers must be registered before this Fragment reaches CREATED.
    // Keeping the controller eager also prevents first opening a category from becoming the
    // accidental registration point during composition.
    private val controller = GlobalSettingsController(this) { revision++ }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        DataStore.initGlobal()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NekoComposeTheme {
                    SettingsContent()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (openInterfaceOnCreate) {
            openInterfaceOnCreate = false
            openGroup(INTERFACE_GROUP_ID, animate = false)
        } else showTopLevel()
    }

    fun openGroup(groupId: String, highlightKey: String? = null, animate: Boolean = true) {
        pageDirection = if (animate) Direction.FORWARD else Direction.NONE
        page = Page.Group(groupId, highlightKey)
    }

    private fun showTopLevel() {
        pageDirection = if (page == Page.Top) Direction.NONE else Direction.BACK
        page = Page.Top
    }

    private fun showSearch() {
        pageDirection = Direction.FORWARD
        page = Page.Search
        searchQuery = ""
    }

    private fun searchResults(query: String): List<SettingsSearchResult> {
        if (query.isBlank()) return emptyList()
        val normalized = query.trim().lowercase()
        val englishContext = requireContext().createConfigurationContext(
            android.content.res.Configuration(resources.configuration).apply {
                setLocale(Locale.ENGLISH)
            },
        )
        return SETTINGS_GROUPS.flatMap { group ->
            globalSettingsFor(group.id).mapNotNull { item ->
                val title = getString(item.title)
                val summary = item.fixedSummary.takeIf { it != 0 }?.let(::getString).orEmpty()
                val englishTitle = englishContext.getString(item.title)
                val englishSummary = item.fixedSummary.takeIf { it != 0 }
                    ?.let(englishContext::getString).orEmpty()
                if (listOf(title, summary, englishTitle, englishSummary)
                        .none { it.lowercase().contains(normalized) }
                ) null
                else SettingsSearchResult(
                    item.key, group.id, title, summary, item.icon,
                    controller.isVisible(item.key) && controller.isEnabled(item.key),
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DataStore.configurationStore.registerChangeListener(this)
    }

    override fun onStop() {
        DataStore.configurationStore.unregisterChangeListener(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        revision++
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        revision++
    }

    fun syncServiceState() { revision++ }

    override fun onBackPressed(): Boolean = when (page) {
        Page.Top -> false
        else -> { showTopLevel(); true }
    }

    private sealed interface Page {
        data object Top : Page
        data object Search : Page
        data class Group(val id: String, val highlightKey: String?) : Page
    }

    private enum class Direction { NONE, FORWARD, BACK }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun SettingsContent() {
        val current = page
        val mainActivity = activity as? MainActivity
        val bottomBarScrollConnection = remember(mainActivity) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val dy = (-available.y).roundToInt()
                    if (dy != 0) mainActivity?.driveBottomBar(dy)
                    return Offset.Zero
                }
            }
        }
        BackHandler(enabled = current != Page.Top, onBack = ::showTopLevel)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        when (current) {
                            Page.Search -> SettingsSearchBar(
                                searchQuery,
                                { searchQuery = it },
                                ::showTopLevel,
                            )
                            Page.Top -> Text(stringResource(R.string.settings))
                            is Page.Group -> Text(stringResource(settingsGroupTitle(current.id)))
                        }
                    },
                    navigationIcon = {
                        if (current != Page.Search) {
                            IconButton(onClick = {
                                if (current == Page.Top) {
                                    (activity as? MainActivity)?.openDrawer()
                                } else {
                                    showTopLevel()
                                }
                            }) {
                                Icon(
                                    painterResource(
                                        if (current == Page.Top) R.drawable.ic_navigation_menu
                                        else R.drawable.baseline_arrow_back_24,
                                    ),
                                    contentDescription = null,
                                )
                            }
                        }
                    },
                    actions = {
                        if (current == Page.Top) {
                            IconButton(onClick = ::showSearch) {
                                Icon(
                                    painterResource(R.drawable.ic_toolbar_search),
                                    contentDescription = stringResource(R.string.settings_search_hint),
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            AnimatedSettingsPage(
                current = current,
                direction = pageDirection,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nestedScroll(bottomBarScrollConnection),
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun AnimatedSettingsPage(
        current: Page,
        direction: Direction,
        modifier: Modifier = Modifier,
    ) {
        BoxWithConstraints(modifier) {
            val widthPx = constraints.maxWidth.toFloat()
            key(current) {
                val initialOffset = when (direction) {
                    Direction.FORWARD -> 0.08f
                    Direction.BACK -> -0.08f
                    Direction.NONE -> 0f
                }
                val offset = remember { Animatable(initialOffset) }
                val alpha = remember { Animatable(if (direction == Direction.NONE) 1f else 0.82f) }
                androidx.compose.runtime.LaunchedEffect(current) {
                    if (direction != Direction.NONE) coroutineScope {
                        launch {
                            offset.animateTo(
                                0f,
                                tween(durationMillis = 180, easing = FastOutSlowInEasing),
                            )
                        }
                        launch {
                            alpha.animateTo(
                                1f,
                                tween(durationMillis = 180, easing = FastOutSlowInEasing),
                            )
                        }
                    }
                }
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        translationX = widthPx * offset.value
                        this.alpha = alpha.value
                    },
                ) {
                    when (current) {
                        Page.Top -> SettingsCategoryScreen(SETTINGS_GROUPS, ::openGroup)
                        is Page.Group -> GlobalSettingsGroupScreen(
                            groupId = current.id,
                            highlightKey = current.highlightKey,
                            revision = revision,
                            isVisible = controller::isVisible,
                            isEnabled = controller::isEnabled,
                            validateChange = controller::validateChange,
                            onChanged = controller::onChanged,
                            onAction = controller::onAction,
                        )
                        Page.Search -> SettingsSearchResultsScreen(
                            query = searchQuery,
                            results = searchResults(searchQuery),
                            onResultClick = { openGroup(it.groupId, it.key) },
                        )
                    }
                }
            }
        }
    }
}
