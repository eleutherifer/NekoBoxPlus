package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.format.Formatter
import android.text.style.ReplacementSpan
import android.view.KeyEvent
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.withStyledAttributes
import androidx.core.net.toUri
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.get
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.size
import kotlinx.coroutines.delay
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProfileTransferOperation
import io.nekohasekai.sagernet.database.ProfileTransferTargetUnavailableException
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.RuleType
import io.nekohasekai.sagernet.database.isInsecureProfile
import io.nekohasekai.sagernet.database.profileCardType
import io.nekohasekai.sagernet.database.shouldHighlightAsInsecure
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.routing.RoutingLinkProcessors
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.FixedGridLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.happCryptUnsupportedDialog
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.ktx.triggerFullRestart
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.ProfileCard
import io.nekohasekai.sagernet.ui.compose.ProfileCardLayout
import io.nekohasekai.sagernet.ui.compose.ProfileCardModel
import io.nekohasekai.sagernet.ui.compose.ProfileShareAction
import io.nekohasekai.sagernet.ui.compose.SubscriptionBannerCard
import io.nekohasekai.sagernet.ui.compose.showComposeItemDialog
import io.nekohasekai.sagernet.ui.compose.showComposeMessageDialog
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.JuicitySettingsActivity
import io.nekohasekai.sagernet.ui.profile.MasterDnsVPNSettingsActivity
import io.nekohasekai.sagernet.ui.profile.MasqueSettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.OpenConnectSettingsActivity
import io.nekohasekai.sagernet.ui.profile.OpenVPNSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ProxySetSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksRSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SnellSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanGoSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TailscaleSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrustTunnelSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.AmneziaWGSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import io.nekohasekai.sagernet.ui.toolbar.ProfileToolbarActionCatalog
import io.nekohasekai.sagernet.ui.toolbar.ProfileToolbarActionId
import io.nekohasekai.sagernet.ui.toolbar.ProfileToolbarActionKind
import io.nekohasekai.sagernet.ui.toolbar.ProfileToolbarLayout
import io.nekohasekai.sagernet.widget.CountryFlagRenderer
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.ProfileListRecyclerView
import io.nekohasekai.sagernet.widget.TvProfileFocusTarget
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.byedpi.ByeDPISettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.direct.DirectSettingsActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import okhttp3.internal.closeQuietly
import java.text.Collator
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.collections.set
import kotlin.math.abs
import kotlin.math.roundToInt
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.AmneziaApiKeyUnsupportedException
import io.nekohasekai.sagernet.utils.ProfileCountryResolver

private const val STATE_PROFILE_SELECTION_MODE = "profile_selection_mode"
private const val STATE_SELECTED_PROFILE_IDS = "selected_profile_ids"
private const val GROUP_LAYOUT_SINGLE = 0
private const val GROUP_LAYOUT_DOUBLE = 1
private const val GROUP_LAYOUT_COMPACT = 2
private const val GROUP_LAYOUT_ALTERNATE = 3

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false, val selectedItem: ProxyEntity? = null, val titleRes: Int = 0
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2
    private var quickToolbar: ViewGroup? = null
    private var quickToolbarActions: LinearLayout? = null
    private var quickSearchExpanded = false
    private var profileSearchExpanded = false
    private var groupTabMediator: TabLayoutMediator? = null
    private var profileSelectionMode = false
    private val selectedProfileIds = linkedSetOf<Long>()
    private val pendingBatchDeleteIds = linkedSetOf<Long>()
    private var pendingConfigurationZip: ByteArray? = null
    private var pendingAmneziaWGJson: String? = null

    private val copyProfilesToGroup =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val targetGroupId =
                    result.data?.getLongExtra(GroupPickerActivity.EXTRA_GROUP_ID, 0L) ?: 0L
                if (targetGroupId > 0L) {
                    transferSelectedProfiles(targetGroupId, ProfileTransferOperation.COPY)
                }
            }
        }

    private val moveProfilesToGroup =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val targetGroupId =
                    result.data?.getLongExtra(GroupPickerActivity.EXTRA_GROUP_ID, 0L) ?: 0L
                if (targetGroupId > 0L) {
                    transferSelectedProfiles(targetGroupId, ProfileTransferOperation.MOVE)
                }
            }
        }

    private val navigateToGroup =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val targetGroupId =
                    result.data?.getLongExtra(GroupPickerActivity.EXTRA_GROUP_ID, 0L) ?: 0L
                if (targetGroupId > 0L) {
                    syncSelectedGroup(targetGroupId)
                }
            }
        }

    val isProfileSelectionMode: Boolean
        get() = profileSelectionMode

    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    enum class ProfileReloadReason {
        General,
        Manual,
        AppStartOrResume,
        UrlTest,
        SubscriptionUpdate,
    }

    fun getCurrentGroupFragment(): GroupFragment? {
        return try {
            if (::adapter.isInitialized) {
                adapter.groupFragments[DataStore.selectedGroup]
            } else {
                childFragmentManager.findFragmentByTag("f" + DataStore.selectedGroup) as GroupFragment?
            }
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    fun switchAllGroupFragmentsLayout() {
        adapter.groupFragments.values.forEach { fragment ->
            if (fragment.isAdded && fragment.view != null) {
                fragment.switchLayoutMode()
            }
        }
    }

    fun replaceAllGroupFragments() {
        adapter.replaceFragments()
    }

    fun refreshAllGroupFragmentsCardStyle() {
        adapter.groupFragments.values.forEach { fragment ->
            if (fragment.isAdded && fragment.view != null) {
                fragment.adapter?.notifyDataSetChanged()
            }
        }
    }

    fun refreshVisibleTraffic() {
        getCurrentGroupFragment()?.refreshVisibleTraffic()
    }

    fun refreshSubscriptionTrafficUnits() {
        getCurrentGroupFragment()?.refreshSubscriptionTrafficUnits()
    }

    fun refreshVisibleProfileActions() {
        if (view == null || !::adapter.isInitialized) return
        adapter.groupFragments.values.forEach { it.refreshVisibleProfileActions() }
    }

    private fun refreshProfileSelectionPresentation() {
        adapter.groupFragments.values.forEach { fragment ->
            fragment.adapter?.notifyDataSetChanged()
        }
    }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (adapter.groupList.size > position) {
                DataStore.selectedGroup = adapter.groupList[position].id
            }
        }
    }

    override fun onQueryTextChange(query: String): Boolean {
        getCurrentGroupFragment()?.adapter?.filter(query)
        return false
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    @SuppressLint("DetachAndAttachSameFragment")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        profileSelectionMode = savedInstanceState?.getBoolean(STATE_PROFILE_SELECTION_MODE) == true
        selectedProfileIds.addAll(
            (savedInstanceState?.getLongArray(STATE_SELECTED_PROFILE_IDS) ?: longArrayOf()).asIterable()
        )

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupQuickToolbar(view)

        if (!select) {
            if (profileSelectionMode) setupProfileSelectionToolbarMenu() else setupProfileToolbarMenu()
            syncToolbarMode()
        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
            refreshSelectToolbarMenu()
        }

        setupSearchView()
        (activity as? ThemedActivity)?.applyHeaderColors()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(profileSelectionMode) {
                override fun handleOnBackPressed() {
                    if (profileSearchExpanded || quickSearchExpanded) {
                        closeProfileSearch()
                    } else {
                        exitProfileSelectionMode()
                    }
                }
            }.also { selectionBackCallback = it }
        )

        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 2

        groupTabMediator = TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                val group = adapter.groupList[position]
                adapter.renderGroupTab(tab, group)
                configureGroupTabGestures(tab, group)
            }
        }.also { it.attach() }

        syncToolbarMode()
        configureTvToolbarFocus()

        DataStore.profileCacheStore.registerChangeListener(this)
        DataStore.configurationStore.registerChangeListener(this)
    }

    private var selectionBackCallback: OnBackPressedCallback? = null

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PROFILE_SELECTION_MODE, profileSelectionMode)
        outState.putLongArray(STATE_SELECTED_PROFILE_IDS, selectedProfileIds.toLongArray())
        super.onSaveInstanceState(outState)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.action_global_mode)?.isChecked = DataStore.globalMode
        syncMenuState(menu)
        super.onPrepareOptionsMenu(menu)
    }

    fun refreshToolbarMenuState() {
        if (select) return
        val activeToolbar = toolbarOrNull() ?: return
        syncMenuState(activeToolbar.menu)
    }

    private fun syncMenuState(menu: Menu) {
        menu.findItem(R.id.action_clash_mode)?.isVisible = DataStore.serviceState.started
    }

    fun refreshSelectToolbarMenu() {
        if (!select) return
        val selectToolbar = toolbarOrNull() ?: return
        selectToolbar.menu.clear()
        val switchActivity = activity as? SwitchActivity ?: return
        if (!switchActivity.canShowClashModeSwitcher()) return

        selectToolbar.menu.add(R.string.clash_mode).apply {
            setIcon(R.drawable.ic_baseline_tune_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                switchActivity.showClashModeChooser()
                true
            }
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            // editingGroup
            if (store == DataStore.profileCacheStore && key == Key.PROFILE_GROUP) {
                syncSelectedGroup(DataStore.editingGroup)
            } else if (store == DataStore.configurationStore && key == Key.USE_TOOLBAR) {
                if (!select) {
                    try {
                        setupProfileToolbarMenu()
                    } catch (_: UninitializedPropertyAccessException) {
                    }
                }
                syncToolbarMode()
            } else if (store == DataStore.configurationStore &&
                key in setOf(Key.TOOLBAR_LAYOUT, Key.GLOBAL_MODE, Key.ENABLE_CORE_PROFILING)
            ) {
                renderQuickToolbarActions()
            } else if (store == DataStore.configurationStore && key == Key.SHOW_PROFILE_COUNT_ON_TABS) {
                if (::adapter.isInitialized) {
                    adapter.refreshAllGroupTabs()
                }
            } else if (store == DataStore.configurationStore &&
                key in setOf(Key.SHORT_PROFILE_PROTOCOL_INFO, Key.PROFILE_COUNTRY_INDICATOR)
            ) {
                if (::adapter.isInitialized) {
                    adapter.groupFragments.values.forEach { fragment ->
                        fragment.adapter?.notifyDataSetChanged()
                    }
                }
            } else if (store == DataStore.configurationStore && key == Key.OPEN_GROUP_SETTINGS_ON_LONG_PRESS) {
                if (::adapter.isInitialized) {
                    adapter.refreshAllGroupTabs()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun configureGroupTabGestures(tab: TabLayout.Tab, group: ProxyGroup) {
        val tabView = tab.view
        val touchSlop = ViewConfiguration.get(tabView.context).scaledTouchSlop
        val longPressDelayMillis = 800L
        var downRawX = 0F
        var downRawY = 0F
        var openedSettings = false
        var openSettingsRunnable: Runnable? = null

        fun cancelOpenSettings() {
            openSettingsRunnable?.let(tabView::removeCallbacks)
            openSettingsRunnable = null
        }

        fun openGroupSettings() {
            if (select || !DataStore.openGroupSettingsOnLongPress || !isAdded || view == null) {
                return
            }
            startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).apply {
                putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
            })
        }

        val gestureDetector = GestureDetector(
            tabView.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent) = true

                override fun onDoubleTap(event: MotionEvent): Boolean {
                    if (select || !DataStore.tabDoubleTapToNavigate || !isAdded || view == null) {
                        return false
                    }
                    cancelOpenSettings()
                    openTabNavigator()
                    return true
                }
            },
        )

        tabView.setOnLongClickListener {
            openGroupSettings()
            true
        }
        var lastCenterDown = Long.MIN_VALUE
        var suppressCenterUp = false
        tabView.setOnKeyListener { focusedView, keyCode, keyEvent ->
            if (!SagerNet.isTv) {
                return@setOnKeyListener false
            }
            if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                val currentIndex = adapter.groupList.indexOfFirst { it.id == group.id }
                val targetIndex = currentIndex +
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                val targetTab = tabLayout.getTabAt(targetIndex)
                    ?: return@setOnKeyListener true
                targetTab.select()
                targetTab.view.post { targetTab.view.requestFocus() }
                return@setOnKeyListener true
            }
            if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
                keyCode == KeyEvent.KEYCODE_DPAD_UP
            ) {
                return@setOnKeyListener focusTvToolbarAction()
            }
            if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            ) {
                return@setOnKeyListener (getCurrentGroupFragment()?.configurationListView
                    as? ProfileListRecyclerView)?.focusFirstTvProfile() == true
            }
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER) return@setOnKeyListener false
            when (keyEvent.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (keyEvent.repeatCount > 0) return@setOnKeyListener false
                    val elapsed = keyEvent.eventTime - lastCenterDown
                    lastCenterDown = keyEvent.eventTime
                    if (DataStore.tabDoubleTapToNavigate &&
                        elapsed in 1..ViewConfiguration.getDoubleTapTimeout().toLong()
                    ) {
                        suppressCenterUp = true
                        lastCenterDown = Long.MIN_VALUE
                        openTabNavigator()
                        true
                    } else {
                        false
                    }
                }

                KeyEvent.ACTION_UP -> {
                    if (suppressCenterUp) {
                        suppressCenterUp = false
                        true
                    } else {
                        focusedView.post { focusedView.requestFocus() }
                        false
                    }
                }

                else -> false
            }
        }
        tabView.setOnFocusChangeListener { focusedView, hasFocus ->
            if (SagerNet.isTv && hasFocus && !tab.isSelected) {
                tab.select()
                focusedView.post { focusedView.requestFocus() }
            }
        }
        tabView.setOnTouchListener { _, event ->
            if (select) {
                return@setOnTouchListener false
            }

            val consumeEvent = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    openedSettings = false
                    cancelOpenSettings()
                    if (DataStore.openGroupSettingsOnLongPress) {
                        openSettingsRunnable = Runnable {
                            if (!isAdded || view == null) return@Runnable
                            openedSettings = true
                            startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).apply {
                                putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                            })
                        }.also {
                            tabView.postDelayed(it, longPressDelayMillis)
                        }
                    }
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downRawX) > touchSlop || abs(event.rawY - downRawY) > touchSlop) {
                        cancelOpenSettings()
                    }
                    false
                }

                MotionEvent.ACTION_UP -> {
                    cancelOpenSettings()
                    openedSettings
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelOpenSettings()
                    false
                }

                else -> false
            }
            gestureDetector.onTouchEvent(event)
            consumeEvent
        }
    }

    private fun openTabNavigator() {
        if (!::adapter.isInitialized || adapter.groupList.isEmpty()) return
        navigateToGroup.launch(
            GroupPickerActivity.createNavigationIntent(
                requireContext(),
                adapter.groupList.map { it.id }.toLongArray(),
            )
        )
    }

    fun canHandleTvDpadShortcut(): Boolean =
        isAdded && view != null && !profileSearchExpanded && !quickSearchExpanded

    fun closeTvSearchIfExpanded(): Boolean {
        val searchItem = toolbarOrNull()?.menu?.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        if (!profileSearchExpanded && !quickSearchExpanded &&
            searchItem?.isActionViewExpanded != true && searchView?.isIconified != false
        ) return false
        closeProfileSearch()
        return true
    }

    fun focusSelectedGroupTab() {
        if (!::tabLayout.isInitialized || !::groupPager.isInitialized) return
        tabLayout.getTabAt(groupPager.currentItem)?.view?.requestFocus()
    }

    private fun configureTvToolbarFocus() {
        if (!SagerNet.isTv || !::tabLayout.isInitialized || !::groupPager.isInitialized) return
        val activeToolbar = toolbarOrNull() ?: return
        activeToolbar.post {
            if (!isAdded || view == null || tabLayout.tabCount == 0) return@post
            val selectedTab = tabLayout.getTabAt(groupPager.currentItem)?.view ?: return@post
            if (selectedTab.id == View.NO_ID) selectedTab.id = View.generateViewId()

            val quickTargets = if (quickToolbar?.isVisible == true) {
                buildList {
                    quickToolbar?.findViewById<View>(R.id.quick_toolbar_navigation)?.let(::add)
                    quickToolbarActions?.children?.filter { it.isVisible }?.forEach(::add)
                    quickToolbar?.findViewById<View>(R.id.quick_toolbar_search)?.let(::add)
                    quickToolbar?.findViewById<View>(R.id.quick_toolbar_add)?.let(::add)
                    quickToolbar?.findViewById<View>(R.id.quick_toolbar_more)?.let(::add)
                }.filter { it.isVisible }.distinctBy { it.id }
            } else {
                emptyList()
            }
            val navigation: View? =
                activeToolbar.children.filterIsInstance<ImageButton>().firstOrNull()
            navigation?.apply {
                if (id == View.NO_ID) id = View.generateViewId()
                contentDescription = getString(R.string.abc_action_bar_up_description)
            }
            val regularTargets = listOfNotNull(
                navigation,
                activeToolbar.findViewById(androidx.appcompat.R.id.search_button),
                activeToolbar.findViewById(R.id.action_add),
                activeToolbar.findViewById(R.id.action_misc),
            ).filter { it.isVisible }
            val targets = if (quickTargets.isNotEmpty()) quickTargets else regularTargets
            targets.forEach { target ->
                if (target.id == View.NO_ID) target.id = View.generateViewId()
                target.installTvFocusOutline()
            }
            targets.forEachIndexed { index, target ->
                target.nextFocusDownId = selectedTab.id
                target.nextFocusLeftId = targets.getOrNull(index - 1)?.id ?: target.id
                target.nextFocusRightId = targets.getOrNull(index + 1)?.id ?: target.id
                target.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
                        return@setOnKeyListener false
                    }
                    val destination = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> targets.getOrNull(index - 1)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> targets.getOrNull(index + 1)
                        KeyEvent.KEYCODE_DPAD_DOWN -> selectedTab
                        else -> null
                    } ?: return@setOnKeyListener false
                    destination.requestFocus()
                }
            }
            selectedTab.nextFocusUpId =
                targets.firstOrNull { it.id == R.id.action_add }?.id
                    ?: targets.firstOrNull()?.id ?: View.NO_ID
            selectedTab.nextFocusDownId = R.id.configuration_list
            selectedTab.installTvFocusOutline()
        }
    }

    private fun focusTvToolbarAction(): Boolean {
        val activeToolbar = toolbarOrNull() ?: return false
        val target = quickToolbar?.findViewById<View>(R.id.quick_toolbar_search)
            ?.takeIf { it.isVisible }
            ?: activeToolbar.findViewById(R.id.action_add)
            ?: activeToolbar.findViewById(R.id.action_search)
        target.isFocusable = true
        target.isFocusableInTouchMode = true
        return target.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        GroupConnectionTestController.attach(this)
        syncToolbarMode()
        if (::adapter.isInitialized && ::groupPager.isInitialized) {
            syncSelectedGroup(DataStore.selectedGroup)
            scrollSelectedGroupTabIntoView()
            getCurrentGroupFragment()?.let { fragment ->
                runOnDefaultDispatcher {
                    fragment.adapter?.reloadProfiles(ProfileReloadReason.AppStartOrResume)
                }
            }
        }
    }

    override fun onPause() {
        toolbarOrNull()?.findViewById<SearchView>(R.id.action_search)?.let(::cancelSearch)
        super.onPause()
    }

    override fun onDestroyView() {
        quickToolbar = null
        quickToolbarActions = null
        GroupConnectionTestController.detach()
        groupTabMediator?.detach()
        groupTabMediator = null
        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
            adapter.close()
        }
        if (::groupPager.isInitialized) {
            groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            groupPager.adapter = null
        }
        super.onDestroyView()
    }

    private fun syncSelectedGroup(targetId: Long) {
        if (targetId <= 0 || !::adapter.isInitialized || !::groupPager.isInitialized) return
        if (DataStore.selectedGroup != targetId) {
            DataStore.selectedGroup = targetId
        }
        val targetIndex = GroupTabSelectionPolicy.selectedIndex(adapter.groupList.map { it.id }, targetId)
        if (targetIndex >= 0) {
            groupPager.setCurrentItem(targetIndex, false)
            scrollSelectedGroupTabIntoView(targetIndex)
        } else {
            adapter.reload()
        }
    }

    private fun scrollSelectedGroupTabIntoView(position: Int = groupPager.currentItem) {
        if (!::tabLayout.isInitialized || position < 0) return

        tabLayout.post {
            scrollSelectedGroupTabIntoViewWhenReady(position)
        }
    }

    private fun scrollSelectedGroupTabIntoViewWhenReady(position: Int) {
        if (!::tabLayout.isInitialized || position < 0 || position >= tabLayout.tabCount) return

        val tabView = tabLayout.getTabAt(position)?.view ?: return
        tabLayout.doOnLayout {
            tabView.doOnLayout {
                if (!::tabLayout.isInitialized || position >= tabLayout.tabCount) return@doOnLayout
                tabLayout.setScrollPosition(position, 0F, true)
                tabView.requestRectangleOnScreen(Rect(0, 0, tabView.width, tabView.height), false)
                configureTvToolbarFocus()
            }
        }
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        DataStore.configurationStore.unregisterChangeListener(this)

        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }

        super.onDestroy()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        return super.onKeyDown(ketCode, event)
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            val context = context ?: return@registerForActivityResult
            val mainActivity = activity as? MainActivity ?: return@registerForActivityResult
            if (file != null) runOnDefaultDispatcher {
                try {
                    val fileName =
                        context.contentResolver.query(file, null, null, null, null)
                            ?.use { cursor ->
                                cursor.moveToFirst()
                                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                                    .let(cursor::getString)
                            }
                    val proxies = mutableListOf<AbstractBean>()
                    val subscriptions = mutableListOf<SubscriptionLinkImportPolicy.Candidate>()
                    var qrImportError: Exception? = null
                    var happCryptFound = false
                    when (
                        ProfileFileImportPolicy.classify(
                            fileName,
                            context.contentResolver.getType(file),
                        )
                    ) {
                        ProfileFileImportKind.ZIP -> {
                            // try parse wireguard zip
                            val zip =
                                ZipInputStream(context.contentResolver.openInputStream(file)!!)
                            while (true) {
                                val entry = zip.nextEntry ?: break
                                if (entry.isDirectory) continue
                                val fileText = zip.bufferedReader().readText()
                                RawUpdater.parseRaw(fileText, entry.name)
                                    ?.let { pl -> proxies.addAll(pl) }
                                zip.closeEntry()
                            }
                            zip.closeQuietly()
                        }

                        ProfileFileImportKind.IMAGE -> {
                            val codes = QrCodeImageDecoder.decode(context, file)
                            val routingLink = codes.singleOrNull()
                                ?.takeIf { RoutingLinkProcessors.forLink(it) != null }
                            if (routingLink != null) {
                                onMainDispatcher {
                                    mainActivity.requestRoutingImport(routingLink)
                                }
                                return@runOnDefaultDispatcher
                            }
                            codes.forEach { code ->
                                if (SubscriptionLinkImportPolicy.isHappCryptLink(code)) {
                                    happCryptFound = true
                                    return@forEach
                                }
                                try {
                                    when (val result = QrCodeImportParser.parse(code)) {
                                        is QrCodeImportResult.Profiles -> {
                                            proxies.addAll(result.profiles)
                                        }
                                        is QrCodeImportResult.Subscription -> {
                                            subscriptions.add(result.candidate)
                                        }
                                        QrCodeImportResult.Empty -> Unit
                                    }
                                } catch (error: Exception) {
                                    Logs.w(error)
                                    if (qrImportError == null) qrImportError = error
                                }
                            }
                        }

                        ProfileFileImportKind.TEXT -> {
                            val fileText = context.contentResolver.openInputStream(file)!!.use {
                                it.bufferedReader().readText()
                            }
                            RawUpdater.parseRaw(fileText, fileName ?: "")
                                ?.let { pl -> proxies.addAll(pl) }
                        }
                    }
                    if (proxies.isNotEmpty()) {
                        import(proxies)
                    }
                    subscriptions.distinct().forEach { candidate ->
                        mainActivity.importSubscription(
                            SubscriptionLinkImportPolicy.toImportLink(candidate).toUri(),
                        )
                    }
                    if (happCryptFound) {
                        onMainDispatcher {
                            context.happCryptUnsupportedDialog().show()
                        }
                    }
                    if (proxies.isEmpty() && subscriptions.isEmpty() && !happCryptFound) {
                        qrImportError?.let { throw it }
                        onMainDispatcher {
                            snackbar(getString(R.string.no_proxies_found_in_file)).show()
                        }
                    }
                } catch (e: SubscriptionFoundException) {
                    mainActivity.importSubscription(e.link.toUri())
                } catch (e: AmneziaApiKeyUnsupportedException) {
                    onMainDispatcher {
                        snackbar(getString(R.string.amnezia_api_key_unsupported)).show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }

    suspend fun import(proxies: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()
        ProfileManager.createProfiles(targetId, proxies)
        onMainDispatcher {
            DataStore.editingGroup = targetId
            snackbar(
                requireContext().resources.getQuantityString(
                    R.plurals.added, proxies.size, proxies.size
                )
            ).show()
        }

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_select_profiles -> {
                enterProfileSelectionMode()
                return true
            }

            R.id.action_selection_cancel -> {
                exitProfileSelectionMode()
                return true
            }

            R.id.action_selection_all -> {
                updateCurrentGroupSelection(ProfileSelectionOperation.All)
                return true
            }

            R.id.action_selection_none -> {
                updateCurrentGroupSelection(ProfileSelectionOperation.None)
                return true
            }

            R.id.action_selection_invert -> {
                updateCurrentGroupSelection(ProfileSelectionOperation.Invert)
                return true
            }

            R.id.action_selection_available -> {
                updateCurrentGroupFilteredSelection { it.status == 1 }
                return true
            }

            R.id.action_selection_unavailable -> {
                updateCurrentGroupFilteredSelection { it.status != 0 && it.status != 1 }
                return true
            }

            R.id.action_selection_insecure -> {
                updateCurrentGroupFilteredSelection {
                    it.isInsecureProfile(DataStore.globalAllowInsecure)
                }
                return true
            }

            R.id.action_selection_group_copy -> {
                openGroupPicker(ProfileTransferOperation.COPY)
                return true
            }

            R.id.action_selection_group_move -> {
                openGroupPicker(ProfileTransferOperation.MOVE)
                return true
            }

            R.id.action_selection_clear_traffic -> {
                clearSelectedTraffic()
                return true
            }

            R.id.action_selection_clear_tests -> {
                clearSelectedTestResults()
                return true
            }

            R.id.action_selection_icmp_ping -> {
                testSelectedProfilesIcmpPing()
                return true
            }

            R.id.action_selection_tcp_ping -> {
                testSelectedProfiles(urlTest = false)
                return true
            }

            R.id.action_selection_url_test -> {
                testSelectedProfiles(urlTest = true)
                return true
            }

            R.id.action_selection_standard_qr -> {
                exportSelectedProfiles(BatchExportKind.Standard, BatchExportTarget.Qr)
                return true
            }

            R.id.action_selection_universal_qr -> {
                exportSelectedProfiles(BatchExportKind.Universal, BatchExportTarget.Qr)
                return true
            }

            R.id.action_selection_standard_clipboard -> {
                exportSelectedProfiles(BatchExportKind.Standard, BatchExportTarget.Clipboard)
                return true
            }

            R.id.action_selection_universal_clipboard -> {
                exportSelectedProfiles(BatchExportKind.Universal, BatchExportTarget.Clipboard)
                return true
            }

            R.id.action_selection_config_clipboard -> {
                exportSelectedProfiles(BatchExportKind.Configuration, BatchExportTarget.Clipboard)
                return true
            }

            R.id.action_selection_config_file -> {
                exportSelectedProfiles(BatchExportKind.Configuration, BatchExportTarget.File)
                return true
            }

            R.id.action_selection_amneziawg_json_clipboard -> {
                exportSelectedProfiles(BatchExportKind.AmneziaWGJson, BatchExportTarget.Clipboard)
                return true
            }

            R.id.action_selection_amneziawg_json_file -> {
                exportSelectedProfiles(BatchExportKind.AmneziaWGJson, BatchExportTarget.File)
                return true
            }

            R.id.action_selection_delete -> {
                deleteSelectedProfiles()
                return true
            }

            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }

            R.id.action_import_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else if (RoutingLinkProcessors.forLink(text) != null) {
                    (requireActivity() as MainActivity).requestRoutingImport(text)
                } else if (SubscriptionLinkImportPolicy.isHappCryptLink(text)) {
                    requireContext().happCryptUnsupportedDialog().show()
                } else runOnDefaultDispatcher {
                    try {
                        val proxies = RawUpdater.parseRaw(text)
                        if (proxies.isNullOrEmpty()) {
                            val subscription =
                                SubscriptionLinkImportPolicy.singleHttpCandidate(text)
                            if (subscription == null) {
                                onMainDispatcher {
                                    snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                                }
                            } else {
                                importClipboardSubscription(subscription)
                            }
                        } else {
                            import(proxies)
                        }
                    } catch (e: SubscriptionFoundException) {
                        onMainDispatcher {
                            if (e.link.startsWith("sn://")) {
                                (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                            } else {
                                val subscriptionLink = Uri.parse(e.link).getQueryParameter("url") ?: e.link

                                val group = ProxyGroup(type = GroupType.SUBSCRIPTION)
                                val subscription = SubscriptionBean()
                                group.subscription = subscription
                                subscription.link = subscriptionLink
                                subscription.autoUpdate = false
                                group.name = ""
                                startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).apply {
                                    putExtra(GroupSettingsActivity.EXTRA_FROM_CLIPBOARD, true)
                                    putExtra(GroupSettingsActivity.EXTRA_GROUP_SUBSCRIPTION_LINK, subscriptionLink)
                                })
                            }
                        }
                    } catch (e: AmneziaApiKeyUnsupportedException) {
                        onMainDispatcher {
                            snackbar(getString(R.string.amnezia_api_key_unsupported)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }

            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }

            R.id.action_new_socks -> {
                startActivity(Intent(requireActivity(), SocksSettingsActivity::class.java))
            }

            R.id.action_new_http -> {
                startActivity(Intent(requireActivity(), HttpSettingsActivity::class.java))
            }

            R.id.action_new_ss -> {
                startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            }

            R.id.action_new_ssr -> {
                startActivity(Intent(requireActivity(), ShadowsocksRSettingsActivity::class.java))
            }

            R.id.action_new_vmess -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            }

            R.id.action_new_vless -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java).apply {
                    putExtra("vless", true)
                })
            }

            R.id.action_new_trojan -> {
                startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            }

            R.id.action_new_trojan_go -> {
                startActivity(Intent(requireActivity(), TrojanGoSettingsActivity::class.java))
            }

            R.id.action_new_mieru -> {
                startActivity(Intent(requireActivity(), MieruSettingsActivity::class.java))
            }

            R.id.action_new_naive -> {
                startActivity(Intent(requireActivity(), NaiveSettingsActivity::class.java))
            }

            R.id.action_new_hysteria -> {
                startActivity(Intent(requireActivity(), HysteriaSettingsActivity::class.java))
            }

            R.id.action_new_tuic -> {
                startActivity(Intent(requireActivity(), TuicSettingsActivity::class.java))
            }

            R.id.action_new_juicity -> {
                startActivity(Intent(requireActivity(), JuicitySettingsActivity::class.java))
            }

            R.id.action_new_trusttunnel -> {
                startActivity(Intent(requireActivity(), TrustTunnelSettingsActivity::class.java))
            }

            R.id.action_new_masterdnsvpn -> {
                startActivity(Intent(requireActivity(), MasterDnsVPNSettingsActivity::class.java))
            }

            R.id.action_new_byedpi -> {
                startActivity(Intent(requireActivity(), ByeDPISettingsActivity::class.java))
            }

            R.id.action_new_ssh -> {
                startActivity(Intent(requireActivity(), SSHSettingsActivity::class.java))
            }

            R.id.action_new_wg -> {
                startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
            }

            R.id.action_new_awg -> {
                startActivity(Intent(requireActivity(), AmneziaWGSettingsActivity::class.java))
            }

            R.id.action_new_shadowtls -> {
                startActivity(Intent(requireActivity(), ShadowTLSSettingsActivity::class.java))
            }

            R.id.action_new_anytls -> {
                startActivity(Intent(requireActivity(), AnyTLSSettingsActivity::class.java))
            }

            R.id.action_new_masque -> {
                startActivity(Intent(requireActivity(), MasqueSettingsActivity::class.java))
            }

            R.id.action_new_tailscale -> {
                startActivity(Intent(requireActivity(), TailscaleSettingsActivity::class.java))
            }

            R.id.action_new_openvpn -> {
                startActivity(Intent(requireActivity(), OpenVPNSettingsActivity::class.java))
            }

            R.id.action_new_openconnect -> {
                startActivity(Intent(requireActivity(), OpenConnectSettingsActivity::class.java))
            }

            R.id.action_new_snell -> {
                startActivity(Intent(requireActivity(), SnellSettingsActivity::class.java))
            }

            R.id.action_new_config -> {
                startActivity(Intent(requireActivity(), ConfigSettingActivity::class.java))
            }

            R.id.action_new_direct -> {
                startActivity(Intent(requireActivity(), DirectSettingsActivity::class.java))
            }

            R.id.action_new_chain -> {
                startActivity(Intent(requireActivity(), ChainSettingsActivity::class.java))
            }

            R.id.action_new_proxy_set -> {
                startActivity(Intent(requireActivity(), ProxySetSettingsActivity::class.java))
            }

            R.id.action_update_subscription -> {
                val group = DataStore.currentGroup()
                if (group.type != GroupType.SUBSCRIPTION) {
                    snackbar(R.string.group_not_subscription).show()
                    Logs.e("onMenuItemClick: Group(${group.displayName()}) is not subscription")
                } else {
                    runOnLifecycleDispatcher {
                        GroupUpdater.startUpdate(group, true)
                    }
                }
            }

            R.id.action_clear_traffic_statistics -> {
                val trafficService = (activity as? MainActivity)?.connection?.service
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.tx != 0L || profile.rx != 0L) {
                            profile.tx = 0
                            profile.rx = 0
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                    try {
                        trafficService?.resetTraffic(profiles.map { it.id }.toLongArray())
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                    onMainDispatcher {
                        getCurrentGroupFragment()?.adapter?.clearTrafficStatistics()
                    }
                }
            }

            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0) {
                            profile.status = 0
                            profile.ping = 0
                            profile.error = null
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                    onMainDispatcher {
                        getCurrentGroupFragment()?.adapter?.clearTestResults()
                    }
                }
            }

            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0 && profile.status != 1) {
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            requireContext().showComposeMessageDialog(
                                title = getText(R.string.confirm),
                                message = getText(R.string.delete_unavailable_confirm_prompt),
                                positiveButton = getText(R.string.yes),
                                negativeButton = getText(R.string.no),
                                onPositive = {
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            R.id.action_remove_duplicate -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    val uniqueProxyHashes = LinkedHashSet<String>()
                    for (pf in profiles) {
                        val proxyHash = pf.requireBean().hash
                        if (!uniqueProxyHashes.add(proxyHash)) {
                            toClear += pf
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            requireContext().showComposeMessageDialog(
                                title = getText(R.string.confirm),
                                message =
                                    getString(R.string.delete_confirm_prompt) + "\n" +
                                            toClear.mapIndexedNotNull { index, proxyEntity ->
                                                if (index < 20) {
                                                    proxyEntity.displayName()
                                                } else if (index == 20) {
                                                    "......"
                                                } else {
                                                    null
                                                }
                                            }.joinToString("\n"),
                                positiveButton = getText(R.string.yes),
                                negativeButton = getText(R.string.no),
                                onPositive = {
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            R.id.action_remove_insecure -> {
                runOnDefaultDispatcher {
                    val toClear = SagerDatabase.proxyDao
                        .getByGroup(DataStore.currentGroupId())
                        .filter { it.isInsecureProfile(DataStore.globalAllowInsecure) }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            requireContext().showComposeMessageDialog(
                                title = getText(R.string.confirm),
                                message =
                                    getString(R.string.remove_insecure_confirm_prompt) + "\n" +
                                            toClear.mapIndexedNotNull { index, proxyEntity ->
                                                if (index < 20) {
                                                    proxyEntity.displayName()
                                                } else if (index == 20) {
                                                    "......"
                                                } else {
                                                    null
                                                }
                                            }.joinToString("\n"),
                                positiveButton = getText(R.string.yes),
                                negativeButton = getText(R.string.no),
                                onPositive = {
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            R.id.action_connection_icmp_ping -> {
                pingTest(true)
            }

            R.id.action_connection_tcp_ping -> {
                pingTest(false)
            }

            R.id.action_connection_url_test -> {
                urlTest()
            }

            R.id.action_global_mode -> {
                item.isChecked = !item.isChecked
                DataStore.globalMode = item.isChecked
                if (DataStore.serviceState.canStop) {
                    runOnDefaultDispatcher {
                        try {
                            // 等待一段时间确保配置已保存
                            delay(500)
                            snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                                runOnDefaultDispatcher {
                                    try {
                                        // 再次等待确保配置已保存
                                        delay(100)
                                        SagerNet.reloadService()
                                    } catch (e: Exception) {
                                        Logs.w(e)
                                        onMainDispatcher {
                                            snackbar(getString(R.string.service_failed)).show()
                                        }
                                    }
                                }
                            }.show()
                        } catch (e: Exception) {
                            Logs.w(e)
                            onMainDispatcher {
                                snackbar(getString(R.string.service_failed)).show()
                            }
                        }
                    }
                }
                return true
            }

            R.id.action_clash_mode -> {
                if (!DataStore.serviceState.started) {
                    Toast.makeText(requireContext(), R.string.clash_mode_unavailable, Toast.LENGTH_SHORT).show()
                    return true
                }
                startActivity(SwitchActivity.createIntent(requireContext(), initialClashMode = true))
                return true
            }

            R.id.action_show_active -> {
                focusSelectedProfileGroupAndScroll()
            }
        }
        return false
    }

    private suspend fun importClipboardSubscription(
        candidate: SubscriptionLinkImportPolicy.Candidate,
    ) {
        val group = SagerDatabase.groupDao.subscriptions().firstOrNull {
            val link = it.subscription?.link?.takeIf(String::isNotBlank)
                ?: return@firstOrNull false
            SubscriptionLinkImportPolicy.linkWithoutFragment(link) == candidate.link
        } ?: GroupManager.createGroup(ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
            name = candidate.name ?: "Subscription #${System.currentTimeMillis()}"
            subscription = SubscriptionBean().apply {
                link = candidate.link
                autoUpdate = false
            }
        })

        DataStore.selectedGroup = group.id
        onMainDispatcher {
            syncSelectedGroup(group.id)
        }
        GroupUpdater.startUpdate(group, true)
    }

    fun pingTest(icmpPing: Boolean) {
        if (icmpPing) {
            GroupConnectionTestController.startIcmpPing(this)
            return
        }
        GroupConnectionTestController.startTcpPing(this)
    }

    fun urlTest() {
        GroupConnectionTestController.startUrlTest(this)
    }

    private enum class BatchExportKind { Standard, Universal, Configuration, AmneziaWGJson }
    private enum class BatchExportTarget { Clipboard, Qr, File }

    private fun updateCurrentGroupSelection(operation: ProfileSelectionOperation) {
        val groupId = DataStore.currentGroupId()
        runOnDefaultDispatcher {
            val ids = SagerDatabase.proxyDao.getIdsByGroup(groupId)
            onMainDispatcher {
                val updated = updateProfileSelection(selectedProfileIds, ids, operation)
                selectedProfileIds.clear()
                selectedProfileIds.addAll(updated)
                notifySelectionChanged()
            }
        }
    }

    private fun updateCurrentGroupFilteredSelection(predicate: (ProxyEntity) -> Boolean) {
        val groupId = DataStore.currentGroupId()
        runOnDefaultDispatcher {
            val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
            val matchingIds = profiles.filter(predicate).map(ProxyEntity::id)
            onMainDispatcher {
                val updated = addMatchingProfileSelection(
                    selectedProfileIds,
                    matchingIds,
                )
                selectedProfileIds.clear()
                selectedProfileIds.addAll(updated)
                notifySelectionChanged()
            }
        }
    }

    private fun selectedProfiles(block: (List<ProxyEntity>) -> Unit) {
        val ids = selectedProfileIds.toList()
        if (ids.isEmpty()) {
            snackbar(R.string.no_profiles_selected).show()
            return
        }
        runOnDefaultDispatcher {
            val profilesById = SagerDatabase.proxyDao.getEntities(ids).associateBy { it.id }
            val profiles = ids.mapNotNull(profilesById::get)
            onMainDispatcher { block(profiles) }
        }
    }

    private fun openGroupPicker(operation: ProfileTransferOperation) {
        if (selectedProfileIds.isEmpty()) {
            snackbar(R.string.no_profiles_selected).show()
            return
        }
        val intent = GroupPickerActivity.createIntent(requireContext(), operation)
        when (operation) {
            ProfileTransferOperation.COPY -> copyProfilesToGroup.launch(intent)
            ProfileTransferOperation.MOVE -> moveProfilesToGroup.launch(intent)
        }
    }

    private fun transferSelectedProfiles(
        targetGroupId: Long,
        operation: ProfileTransferOperation,
    ) {
        val profileIds = selectedProfileIds.toList()
        if (profileIds.isEmpty()) {
            snackbar(R.string.no_profiles_selected).show()
            return
        }
        runOnDefaultDispatcher {
            try {
                val result = ProfileManager.transferProfiles(profileIds, targetGroupId, operation)
                onMainDispatcher {
                    if (!isAdded) return@onMainDispatcher
                    exitProfileSelectionMode()
                    val message = when (operation) {
                        ProfileTransferOperation.COPY -> resources.getQuantityString(
                            R.plurals.profiles_copied,
                            result.changedCount,
                            result.changedCount,
                        )

                        ProfileTransferOperation.MOVE -> resources.getQuantityString(
                            R.plurals.profiles_moved,
                            result.changedCount,
                            result.changedCount,
                        )
                    }
                    snackbar(message).show()
                }
            } catch (_: ProfileTransferTargetUnavailableException) {
                onMainDispatcher {
                    if (isAdded) snackbar(R.string.profile_group_target_unavailable).show()
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    if (isAdded) snackbar(e.readableMessage).show()
                }
            }
        }
    }

    private fun clearSelectedTraffic() = selectedProfiles { profiles ->
        val trafficService = (activity as? MainActivity)?.connection?.service
        exitProfileSelectionMode()
        runOnDefaultDispatcher {
            val changed = profiles.filter { it.tx != 0L || it.rx != 0L }.onEach {
                it.tx = 0L
                it.rx = 0L
            }
            if (changed.isNotEmpty()) ProfileManager.updateProfile(changed)
            try {
                trafficService?.resetTraffic(profiles.map { it.id }.toLongArray())
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    private fun clearSelectedTestResults() = selectedProfiles { profiles ->
        exitProfileSelectionMode()
        runOnDefaultDispatcher {
            val changed = profiles.filter {
                it.status != 0 || it.ping != 0 || it.error != null
            }.onEach {
                it.status = 0
                it.ping = 0
                it.error = null
            }
            if (changed.isNotEmpty()) ProfileManager.updateProfile(changed)
        }
    }

    private fun testSelectedProfiles(urlTest: Boolean) = selectedProfiles { profiles ->
        exitProfileSelectionMode()
        if (profiles.size == 1) {
            if (urlTest) {
                ProfileUrlTestController.start(profiles.single())
            } else {
                ProfileTcpPingController.start(profiles.single())
            }
        } else {
            val ids = profiles.map { it.id }
            if (urlTest) {
                GroupConnectionTestController.startUrlTest(this, ids)
            } else {
                GroupConnectionTestController.startTcpPing(this, ids)
            }
        }
    }

    private fun testSelectedProfilesIcmpPing() = selectedProfiles { profiles ->
        exitProfileSelectionMode()
        GroupConnectionTestController.startIcmpPing(this, profiles.map { it.id })
    }

    private fun exportSelectedProfiles(kind: BatchExportKind, target: BatchExportTarget) =
        selectedProfiles { profiles ->
            val result = when (kind) {
                BatchExportKind.Standard -> ProfileBatchExport.standardLinks(profiles)
                BatchExportKind.Universal -> ProfileBatchExport.universalLinks(profiles)
                BatchExportKind.Configuration -> ProfileBatchExport.configurations(profiles)
                BatchExportKind.AmneziaWGJson -> ProfileBatchExport.amneziaWGJson(profiles)
            }
            exitProfileSelectionMode()
            if (result.entries.isEmpty()) {
                snackbar(R.string.no_profiles_support_export).show()
                return@selectedProfiles
            }
            val text = if (kind == BatchExportKind.Configuration) {
                ProfileBatchExport.configurationClipboardText(result.entries)
            } else {
                result.text
            }
            when (target) {
                BatchExportTarget.Clipboard -> {
                    val success = SagerNet.trySetPrimaryClip(text)
                    snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                        .show()
                }
                BatchExportTarget.Qr -> {
                    QRCodeDialog(text, getString(R.string.selected_profiles))
                        .showAllowingStateLoss(parentFragmentManager)
                }
                BatchExportTarget.File -> {
                    if (kind == BatchExportKind.AmneziaWGJson) {
                        pendingAmneziaWGJson = text
                        startFilesForResult(exportSelectedAmneziaWGJson, "amneziawg.json")
                    } else {
                        pendingConfigurationZip = ProfileBatchExport.configurationZip(result.entries)
                        startFilesForResult(exportSelectedConfigurations, "profiles.zip")
                    }
                }
            }
            if (result.skipped > 0) {
                snackbar(
                    resources.getQuantityString(
                        R.plurals.profiles_skipped,
                        result.skipped,
                        result.skipped,
                    )
                ).show()
            }
        }

    private fun deleteSelectedProfiles() = selectedProfiles { profiles ->
        val runningId = DataStore.currentProfile.takeIf { DataStore.serviceState.started }
        val skipped = profiles.count { it.id == runningId }
        val removable = profiles.filterNot { it.id == runningId }
        val delete = {
            exitProfileSelectionMode()
            if (removable.isNotEmpty()) {
                showBatchDeleteUndo(removable)
            }
            if (skipped > 0) {
                snackbar(
                    resources.getQuantityString(
                        R.plurals.running_profiles_skipped,
                        skipped,
                        skipped,
                    )
                ).show()
            }
        }
        if (removable.isNotEmpty() && DataStore.confirmProfileDelete) {
            requireContext().showComposeMessageDialog(
                title = getText(R.string.delete_confirm_prompt),
                positiveButton = getText(R.string.yes),
                negativeButton = getText(R.string.no),
                onPositive = delete,
            )
        } else {
            delete()
        }
    }

    private fun showBatchDeleteUndo(profiles: List<ProxyEntity>) {
        val ids = profiles.map { it.id }.toSet()
        pendingBatchDeleteIds.addAll(ids)
        adapter.groupFragments.values.forEach { it.adapter?.hideProfiles(ids) }
        val bar = (activity as MainActivity).snackbar(
            resources.getQuantityString(R.plurals.removed, profiles.size, profiles.size)
        )
        var undone = false
        bar.setAction(R.string.undo) {
            undone = true
            pendingBatchDeleteIds.removeAll(ids)
            adapter.groupFragments.values.forEach {
                it.adapter?.reloadProfiles(ProfileReloadReason.General)
            }
        }
        bar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (undone || event == DISMISS_EVENT_ACTION) return
                runOnDefaultDispatcher {
                    profiles.forEach { ProfileManager.deleteProfile(it.groupId, it.id) }
                    onMainDispatcher {
                        pendingBatchDeleteIds.removeAll(ids)
                    }
                }
            }
        })
        bar.show()
    }

    private fun View.findTextView(): TextView? {
        if (this is TextView) return this
        if (this !is ViewGroup) return null
        children.forEach { child ->
            child.findTextView()?.let { return it }
        }
        return null
    }

    private class ProfileCountSpan(
        private val leftMargin: Int,
        private val horizontalPadding: Int,
        private val verticalPadding: Int,
        private val minHeight: Int,
        private val cornerRadius: Float,
        private val backgroundColor: Int,
        private val textColor: Int,
        private val scale: Float = 0.85F,
    ) : ReplacementSpan() {

        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val scaledHorizontalPadding = (horizontalPadding * scale).roundToInt()
        private val scaledVerticalPadding = (verticalPadding * scale).roundToInt()
        private val scaledMinHeight = (minHeight * scale).roundToInt()
        private val scaledCornerRadius = cornerRadius * scale

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            textPaint.set(paint)
            textPaint.textSize = paint.textSize * scale
            return (leftMargin + textPaint.measureText(text, start, end) + scaledHorizontalPadding * 2)
                .roundToInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            textPaint.set(paint)
            textPaint.textSize = paint.textSize * scale
            textPaint.color = textColor
            backgroundPaint.color = backgroundColor

            val textWidth = textPaint.measureText(text, start, end)
            val height = maxOf(scaledMinHeight.toFloat(), textPaint.fontMetrics.run {
                descent - ascent + scaledVerticalPadding * 2
            })
            val pillStart = x + leftMargin
            val textCenterY = y +
                    (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2F
            val rect = RectF(
                pillStart,
                textCenterY - height / 2F,
                pillStart + textWidth + scaledHorizontalPadding * 2,
                textCenterY + height / 2F,
            )

            canvas.drawRoundRect(rect, scaledCornerRadius, scaledCornerRadius, backgroundPaint)
            canvas.drawText(
                text,
                start,
                end,
                rect.left + scaledHorizontalPadding,
                rect.centerY() - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2F,
                textPaint,
            )
        }
    }

    inner class GroupPagerAdapter : FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, GroupFragment> = HashMap()
        private val prefetchedProfiles = HashMap<Long, List<ProxyEntity>>()
        private var nextFragmentItemId = 0L
        private val fragmentItemIds = HashMap<Long, Long>()
        private var reloadJob: Job? = null
        @Volatile
        private var closed = false

        private fun itemIdFor(groupId: Long): Long {
            return fragmentItemIds.getOrPut(groupId) {
                nextFragmentItemId++
            }
        }

        fun close() {
            closed = true
            reloadJob?.cancel()
            reloadJob = null
        }

        private fun hasLiveView(): Boolean {
            return !closed &&
                    this@ConfigurationFragment.isAdded &&
                    view != null &&
                    ::tabLayout.isInitialized &&
                    ::groupPager.isInitialized
        }

        private fun postToTabLayout(block: () -> Unit) {
            if (!hasLiveView()) return
            tabLayout.post {
                if (!hasLiveView()) return@post
                block()
            }
        }

        fun renderGroupTab(tab: TabLayout.Tab, group: ProxyGroup) {
            val tabContext = this@ConfigurationFragment.context ?: return
            tab.customView = null
            tab.view.installForegroundRipple(tabContext)
            if (!DataStore.showProfileCountOnTabs) {
                tab.text = group.displayName()
                return
            }

            val count = SagerDatabase.proxyDao.countByGroup(group.id).toString()
            val title = group.displayName()
            tab.text = SpannableStringBuilder(title)
                .append(
                    count,
                    ProfileCountSpan(
                        leftMargin = dp2px(6),
                        horizontalPadding = dp2px(5),
                        verticalPadding = dp2px(1),
                        minHeight = dp2px(18),
                        cornerRadius = dp2px(9).toFloat(),
                        backgroundColor = tabContext
                            .getColorAttr(com.google.android.material.R.attr.colorPrimaryContainer),
                        textColor = tabContext
                            .getColorAttr(com.google.android.material.R.attr.colorOnPrimaryContainer),
                    ),
                    SPAN_EXCLUSIVE_EXCLUSIVE,
                )
        }

        private fun View.installForegroundRipple(context: android.content.Context) {
            if (foreground is RippleDrawable) return
            foreground = RippleDrawable(
                ColorStateList.valueOf(context.getColorAttr(R.attr.tabRippleColor)),
                null,
                null,
            )
            background = null
        }

        private fun refreshGroupTab(groupId: Long) {
            if (!hasLiveView()) return
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return
            val tab = tabLayout.getTabAt(index) ?: return
            renderGroupTab(tab, groupList[index])
        }

        fun refreshAllGroupTabs() {
            if (!hasLiveView()) return
            groupList.forEachIndexed { index, group ->
                tabLayout.getTabAt(index)?.let { tab ->
                    renderGroupTab(tab, group)
                }
            }
        }

        private fun applyGroupTabVisibility() {
            if (!hasLiveView()) return
            val hideTab = groupList.size < 2
            tabLayout.isGone = hideTab
            toolbarOrNull()?.let { activeToolbar ->
                activeToolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
                syncQuickToolbarBackground(activeToolbar)
            }
        }

        fun replaceFragments() {
            groupFragments.clear()
            fragmentItemIds.clear()
            notifyDataSetChanged()
        }

        fun reload(now: Boolean = false) {
            if (!hasLiveView()) return

            val prefetchInitialProfiles = groupList.isEmpty()

            if (!select) {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            }

            reloadJob?.cancel()
            reloadJob = runOnDefaultDispatcher {
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                if (newGroupList.isEmpty()) {
                    SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                }
                newGroupList.find { it.ungrouped }?.let {
                    if (newGroupList.size > 1 && SagerDatabase.proxyDao.countByGroup(it.id) == 0L) {
                        newGroupList.remove(it)
                    }
                }

                val selectedGroup = selectedItem?.groupId ?: DataStore.currentGroupId()
                var set = false
                if (selectedGroup > 0L) {
                    selectedGroupIndex = GroupTabSelectionPolicy.selectedIndex(
                        newGroupList.map { it.id },
                        selectedGroup
                    )
                    set = selectedGroupIndex >= 0
                }
                if (!set && newGroupList.isNotEmpty()) {
                    selectedGroupIndex = 0
                    val fallbackGroup = newGroupList[0].id
                    if (DataStore.selectedGroup != fallbackGroup) {
                        DataStore.selectedGroup = fallbackGroup
                    }
                    set = true
                }

                val selectedGroupProfiles = if (set && prefetchInitialProfiles) {
                    newGroupList.getOrNull(selectedGroupIndex)?.let { group ->
                        group.id to SagerDatabase.proxyDao.getByGroup(group.id)
                    }
                } else {
                    null
                }

                if (!isActive || !hasLiveView()) return@runOnDefaultDispatcher

                val applyChanges = Runnable {
                    if (!hasLiveView()) return@Runnable
                    groupList = newGroupList
                    prefetchedProfiles.clear()
                    selectedGroupProfiles?.let { (groupId, profiles) ->
                        prefetchedProfiles[groupId] = profiles
                    }
                    val groupIds = groupList.map { it.id }.toHashSet()
                    groupFragments.keys.retainAll(groupIds)
                    fragmentItemIds.keys.retainAll(groupIds)
                    notifyDataSetChanged()
                    if (set) {
                        groupPager.setCurrentItem(selectedGroupIndex, false)
                        scrollSelectedGroupTabIntoView(selectedGroupIndex)
                    }
                    refreshAllGroupTabs()
                    applyGroupTabVisibility()
                    if (!select && hasLiveView()) {
                        groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                    }
                }
                if (now) {
                    activity?.runOnUiThread(applyChanges)
                } else {
                    groupPager.post(applyChanges)
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun createFragment(position: Int): Fragment {
            return GroupFragment().apply {
                proxyGroup = groupList[position]
                initialProfiles = prefetchedProfiles.remove(proxyGroup.id)
                groupFragments[proxyGroup.id] = this
                if (position == selectedGroupIndex) {
                    selected = true
                }
            }
        }

        override fun getItemId(position: Int): Long {
            return itemIdFor(groupList[position].id)
        }

        override fun containsItem(itemId: Long): Boolean {
            return fragmentItemIds.any { (groupId, fragmentItemId) ->
                fragmentItemId == itemId && groupList.any { it.id == groupId }
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            postToTabLayout {
                if (group.ungrouped) {
                    reload()
                    return@postToTabLayout
                }
                groupList.add(group)

                if (groupList.any { !it.ungrouped }) postToTabLayout {
                    tabLayout.isVisible = true
                }

                notifyItemInserted(groupList.size - 1)
                refreshGroupTab(group.id)
                tabLayout.getTabAt(groupList.size - 1)?.select()
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val existingIds = SagerDatabase.proxyDao.getEntities(selectedProfileIds.toList())
                .mapTo(hashSetOf()) { it.id }
            onMainDispatcher {
                if (selectedProfileIds.retainAll(existingIds)) updateSelectionMenuEnabledState()
            }
            postToTabLayout {
                reload()
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) return

            postToTabLayout {
                groupList[index] = group
                refreshGroupTab(group.id)
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            postToTabLayout {
                refreshGroupTab(groupId)
            }
        }

        override suspend fun groupProfileCountChanged(groupId: Long) {
            postToTabLayout {
                refreshGroupTab(groupId)
            }
        }

        override suspend fun onAdd(profile: ProxyEntity) {
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload()
            } else {
                postToTabLayout {
                    refreshGroupTab(profile.groupId)
                }
            }
        }

        override suspend fun onUpdated(data: List<TrafficData>) = Unit

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            removeProfileFromSelection(profileId)
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            } else {
                postToTabLayout {
                    refreshGroupTab(groupId)
                }
            }
        }
    }

    class GroupFragment : Fragment() {

        lateinit var proxyGroup: ProxyGroup
        var initialProfiles: List<ProxyEntity>? = null
        var selected = false

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            return LayoutProfileListBinding.inflate(inflater).root
        }

        lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
        var adapter: ConfigurationAdapter? = null
        private lateinit var bannerAdapter: SubscriptionBannerAdapter
        private lateinit var combinedAdapter: ConcatAdapter

        private val usesDoubleColumnCard: Boolean
            get() = DataStore.groupLayoutMode == GROUP_LAYOUT_DOUBLE ||
                    DataStore.groupLayoutMode == GROUP_LAYOUT_ALTERNATE

        val bannerItemOffset: Int
            get() = if (::bannerAdapter.isInitialized) bannerAdapter.itemCount else 0

        fun refreshVisibleTraffic() {
            if (!::configurationListView.isInitialized) return
            configurationListView.post {
                adapter?.refreshVisibleTraffic()
            }
        }

        fun refreshSubscriptionTrafficUnits() {
            if (::bannerAdapter.isInitialized) bannerAdapter.refresh()
        }

        fun refreshVisibleProfileActions() {
            if (!::configurationListView.isInitialized) return
            configurationListView.post {
                adapter?.refreshVisibleProfileActions()
            }
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            if (::proxyGroup.isInitialized) {
                outState.putParcelable("proxyGroup", proxyGroup)
            }
        }

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            savedInstanceState?.getParcelable<ProxyGroup>("proxyGroup")?.also {
                proxyGroup = it
                onViewCreated(requireView(), null)
            }
        }

        private val isEnabled: Boolean
            get() {
                return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
            }

        lateinit var layoutManager: RecyclerView.LayoutManager
        private lateinit var itemTouchHelper: ItemTouchHelper
        private val alwaysShowAddress: Boolean
            get() = (parentFragment as? ConfigurationFragment)?.alwaysShowAddress == true

        private fun setupItemTouchHelper() {
            if (select) return

            if (::itemTouchHelper.isInitialized) {
                itemTouchHelper.attachToRecyclerView(null)
            }

            itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, 0) {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int {
                    if (viewHolder.bindingAdapter !== adapter) return makeMovementFlags(0, 0)
                    val dragFlags = if (DataStore.groupLayoutMode == GROUP_LAYOUT_DOUBLE) {
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                    } else {
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN
                    }
                    return makeMovementFlags(dragFlags, 0) // No swipe flags
                }

                override fun getSwipeDirs(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ): Int {
                    return 0
                }

                override fun getDragDirs(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ): Int {
                    return if (isEnabled) {
                        if (DataStore.groupLayoutMode == GROUP_LAYOUT_DOUBLE) {
                            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                        } else {
                            ItemTouchHelper.UP or ItemTouchHelper.DOWN
                        }
                    } else 0
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
                ): Boolean {
                    if (viewHolder.bindingAdapter !== adapter || target.bindingAdapter !== adapter) {
                        return false
                    }
                    val fromPosition = viewHolder.bindingAdapterPosition
                    val toPosition = target.bindingAdapterPosition

                    if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
                        return false
                    }

                    adapter?.move(fromPosition, toPosition)
                    return true
                }

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) {
                    super.clearView(recyclerView, viewHolder)
                    adapter?.commitMove()
                }

            })
            itemTouchHelper.attachToRecyclerView(configurationListView)
        }
        lateinit var configurationListView: RecyclerView
        private var didInitialPositionList = false
        private var lastGroupUpdateStamp: Int? = null
        private var pendingManualOrderReload = false

        val select by lazy {
            try {
                (parentFragment as ConfigurationFragment).select
            } catch (e: Exception) {
                Logs.e(e)
                false
            }
        }
        val selectedItem by lazy {
            try {
                (parentFragment as ConfigurationFragment).selectedItem
            } catch (e: Exception) {
                Logs.e(e)
                null
            }
        }

        override fun onResume() {
            super.onResume()

            if (!::configurationListView.isInitialized) {
                onViewCreated(requireView(), null)
            }
            if (::configurationListView.isInitialized) {
                val prefetchedProfiles = initialProfiles.also { initialProfiles = null }
                runOnDefaultDispatcher {
                    adapter?.reloadProfilesIfChanged(
                        ProfileReloadReason.AppStartOrResume,
                        prefetchedProfiles,
                    )
                }
            }
            checkOrderMenu()
            if (requireActivity().currentFocus == null) {
                configurationListView.post {
                    val firstProfile = configurationListView.getChildAt(bannerItemOffset)
                    if (firstProfile?.requestFocus() != true) configurationListView.requestFocus()
                }
            }
        }

        override fun onDestroyView() {
            lastGroupUpdateStamp = null
            didInitialPositionList = false
            if (::configurationListView.isInitialized) {
                configurationListView.stopScroll()
                configurationListView.adapter = null
            }
            if (::itemTouchHelper.isInitialized) {
                itemTouchHelper.attachToRecyclerView(null)
            }
            adapter?.let {
                ProfileManager.removeListener(it)
                GroupManager.removeListener(it)
            }
            adapter = null
            if (::undoManager.isInitialized) {
                undoManager.flush()
            }
            super.onDestroyView()
        }

        fun checkOrderMenu() {
            if (select || (parentFragment as? ConfigurationFragment)?.isProfileSelectionMode == true) {
                return
            }

            val pf = requireParentFragment() as? ToolbarFragment ?: return
            val menu = pf.toolbarOrNull()?.menu ?: return
            val origin = menu.findItem(R.id.action_order_origin)
            val byName = menu.findItem(R.id.action_order_by_name)
            val byDelay = menu.findItem(R.id.action_order_by_delay)
            val manual = menu.findItem(R.id.action_order_manual)
            when (proxyGroup.order) {
                GroupOrder.ORIGIN -> {
                    origin.isChecked = true
                }

                GroupOrder.BY_NAME -> {
                    byName.isChecked = true
                }

                GroupOrder.BY_DELAY -> {
                    byDelay.isChecked = true
                }

                GroupOrder.MANUAL -> {
                    manual.isChecked = true
                }
            }

            fun updateTo(order: Int) {
                pendingManualOrderReload = true
                if (proxyGroup.order == order) {
                    runOnDefaultDispatcher {
                        GroupManager.postReload(proxyGroup.id, GroupManager.ReloadReason.Manual)
                    }
                    return
                }
                runOnDefaultDispatcher {
                    if (order == GroupOrder.MANUAL) {
                        adapter?.persistCurrentProfileOrder()
                    }
                    proxyGroup.order = order
                    GroupManager.updateGroup(proxyGroup)
                }
            }

            origin.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.ORIGIN)
                true
            }
            byName.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_NAME)
                true
            }
            byDelay.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_DELAY)
                true
            }
            manual.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.MANUAL)
                true
            }

            val orderModeAlways = menu.findItem(R.id.action_order_mode_always)
            val orderModeUrlTest = menu.findItem(R.id.action_order_mode_urltest)
            val orderModeUpdate = menu.findItem(R.id.action_order_mode_update)

            fun syncOrderModeMenu() {
                val always = DataStore.groupOrderModeAlways
                orderModeAlways.isChecked = always
                orderModeUrlTest.isChecked = always || DataStore.groupOrderModeUrlTest
                orderModeUpdate.isChecked = always || DataStore.groupOrderModeUpdate
                orderModeUrlTest.isEnabled = !always
                orderModeUpdate.isEnabled = !always
            }

            syncOrderModeMenu()
            orderModeAlways.setOnMenuItemClickListener {
                DataStore.groupOrderModeAlways = !DataStore.groupOrderModeAlways
                syncOrderModeMenu()
                true
            }
            orderModeUrlTest.setOnMenuItemClickListener {
                DataStore.groupOrderModeUrlTest = !DataStore.groupOrderModeUrlTest
                syncOrderModeMenu()
                true
            }
            orderModeUpdate.setOnMenuItemClickListener {
                DataStore.groupOrderModeUpdate = !DataStore.groupOrderModeUpdate
                syncOrderModeMenu()
                true
            }

            val layoutSingle = menu.findItem(R.id.action_layout_single)
            val layoutDouble = menu.findItem(R.id.action_layout_double)
            val layoutCompact = menu.findItem(R.id.action_layout_compact)
            val layoutAlternate = menu.findItem(R.id.action_layout_alternate)
            when (DataStore.groupLayoutMode) {
                GROUP_LAYOUT_SINGLE -> layoutSingle.isChecked = true
                GROUP_LAYOUT_DOUBLE -> layoutDouble.isChecked = true
                GROUP_LAYOUT_COMPACT -> layoutCompact.isChecked = true
                GROUP_LAYOUT_ALTERNATE -> layoutAlternate.isChecked = true
            }
            layoutSingle.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.groupLayoutMode != GROUP_LAYOUT_SINGLE) {
                    DataStore.groupLayoutMode = GROUP_LAYOUT_SINGLE

                    (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()
                    (parentFragment as? ConfigurationFragment)?.replaceAllGroupFragments()
                }
                true
            }
            layoutDouble.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.groupLayoutMode != GROUP_LAYOUT_DOUBLE) {
                    DataStore.groupLayoutMode = GROUP_LAYOUT_DOUBLE

                    (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()
                    (parentFragment as? ConfigurationFragment)?.replaceAllGroupFragments()
                }
                true
            }
            layoutCompact.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.groupLayoutMode != GROUP_LAYOUT_COMPACT) {
                    DataStore.groupLayoutMode = GROUP_LAYOUT_COMPACT

                    (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()
                    (parentFragment as? ConfigurationFragment)?.replaceAllGroupFragments()
                }
                true
            }
            layoutAlternate.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.groupLayoutMode != GROUP_LAYOUT_ALTERNATE) {
                    DataStore.groupLayoutMode = GROUP_LAYOUT_ALTERNATE

                    (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()
                    (parentFragment as? ConfigurationFragment)?.replaceAllGroupFragments()
                }
                true
            }
            val profileCardBorders = menu.findItem(R.id.action_profile_card_borders)
            profileCardBorders.isChecked = DataStore.profileCardBorders
            profileCardBorders.setOnMenuItemClickListener {
                it.isChecked = !it.isChecked
                DataStore.profileCardBorders = it.isChecked
                (parentFragment as? ConfigurationFragment)?.refreshAllGroupFragmentsCardStyle()
                true
            }
        }

        private fun setupLayoutManager() {
            layoutManager = if (DataStore.groupLayoutMode == GROUP_LAYOUT_DOUBLE) {
                FixedGridLayoutManager(configurationListView, 2).apply {
                    spanSizeLookup =
                        object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                            override fun getSpanSize(position: Int): Int {
                                return if (bannerItemOffset > 0 && position == 0) spanCount else 1
                            }
                        }
                }
            } else {
                FixedLinearLayoutManager(configurationListView)
            }
        }

        fun switchLayoutMode() {
            setupLayoutManager()
            configurationListView.layoutManager = layoutManager
            if (::bannerAdapter.isInitialized) bannerAdapter.refresh()

            setupItemTouchHelper()

            adapter?.notifyDataSetChanged()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            if (!::proxyGroup.isInitialized) return

            configurationListView = view.findViewById(R.id.configuration_list)
            configurationListView.isInvisible = true
            setupLayoutManager()
            configurationListView.layoutManager = layoutManager
            adapter = ConfigurationAdapter()
            bannerAdapter = SubscriptionBannerAdapter()
            ProfileManager.addListener(adapter!!)
            GroupManager.addListener(adapter!!)
            combinedAdapter = ConcatAdapter(
                ConcatAdapter.Config.Builder()
                    .setStableIdMode(ConcatAdapter.Config.StableIdMode.SHARED_STABLE_IDS)
                    .build(),
                bannerAdapter,
                adapter,
            )
            configurationListView.adapter = combinedAdapter
            configurationListView.setItemViewCacheSize(20)
            configurationListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        adapter?.flushPendingTrafficUpdates()
                    }
                }
            })

            if (!select) {
                undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)
                setupItemTouchHelper()
                setupBottomBarScrollDriver()
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun setupBottomBarScrollDriver() {
            val mainActivity = activity as? MainActivity ?: return
            configurationListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) mainActivity.driveBottomBar(dy)
                }
            })

            val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
            var lastRawY = 0f
            configurationListView.setOnTouchListener { recyclerView, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> lastRawY = event.rawY
                    MotionEvent.ACTION_MOVE -> {
                        val cannotScroll = !recyclerView.canScrollVertically(-1) &&
                                !recyclerView.canScrollVertically(1)
                        if (cannotScroll) {
                            val fingerDy = event.rawY - lastRawY
                            if (abs(fingerDy) >= touchSlop) {
                                mainActivity.driveBottomBar(-fingerDy.toInt())
                                lastRawY = event.rawY
                            }
                        }
                    }
                }
                false
            }
        }

        override fun onDestroy() {
            adapter?.let {
                ProfileManager.removeListener(it)
                GroupManager.removeListener(it)
            }

            super.onDestroy()
        }

        inner class ConfigurationAdapter : RecyclerView.Adapter<ConfigurationHolder>(),
            ProfileManager.Listener,
            GroupManager.Listener,
            UndoSnackbarManager.Interface<ProxyEntity> {

            init {
                setHasStableIds(true)
            }

            var configurationIdList: MutableList<Long> = mutableListOf()
            private var allConfigurationIdList: MutableList<Long> = mutableListOf()
            private var searchQuery = ""
            val configurationList = HashMap<Long, ProxyEntity>()
            private val pendingTrafficUpdates = HashSet<Long>()
            private val removedFullPositions = HashMap<Long, Int>()

            private fun getItem(profileId: Long): ProxyEntity {
                var profile = configurationList[profileId]
                if (profile == null) {
                    profile = ProfileManager.getProfile(profileId)
                    if (profile != null) {
                        configurationList[profileId] = profile
                    }
                }
                return profile!!
            }

            private fun getItemAt(index: Int) = getItem(configurationIdList[index])

            fun notifySelectionChanged(profileId: Long) {
                val index = configurationIdList.indexOf(profileId)
                if (index >= 0) notifyItemChanged(index)
            }

            private fun isActiveAdapter(): Boolean {
                return adapter === this &&
                        ::configurationListView.isInitialized &&
                        ::combinedAdapter.isInitialized &&
                        configurationListView.adapter === combinedAdapter
            }

            private fun hasMiddleRow(profile: ProxyEntity): Boolean {
                val showTraffic = shouldShowProfileTraffic() && profile.tx + profile.rx != 0L
                val bean = profile.requireBean()
                val address = if (alwaysShowAddress && bean.name.isNotBlank()) {
                    bean.displayAddress()
                } else {
                    ""
                }
                val trafficUsesMiddleRow = showTraffic && when (DataStore.groupLayoutMode) {
                    GROUP_LAYOUT_DOUBLE, GROUP_LAYOUT_ALTERNATE -> true
                    GROUP_LAYOUT_COMPACT -> profile.status > 0
                    else -> false
                }
                return trafficUsesMiddleRow || address.isNotBlank()
            }

            fun neighbourHasMiddleRow(position: Int): Boolean {
                if (position == RecyclerView.NO_POSITION) return false
                val grid = layoutManager as? FixedGridLayoutManager ?: return false
                val row = grid.rowIndexOf(position)
                val rowStart = (row * grid.spanCount).coerceAtLeast(0)
                val rowEnd = ((row + 1) * grid.spanCount - 1).coerceAtMost(itemCount - 1)
                for (index in rowStart..rowEnd) {
                    if (index == position) continue
                    if (runCatching { hasMiddleRow(getItemAt(index)) }.getOrDefault(false)) {
                        return true
                    }
                }
                return false
            }

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): ConfigurationHolder {
                return ConfigurationHolder(ComposeView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
                    )
                })
            }

            override fun getItemId(position: Int): Long {
                return configurationIdList[position]
            }

            override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
                if (!isActiveAdapter()) return
                holder.itemView.nextFocusDownId =
                    if (position == itemCount - 1) R.id.fab else View.NO_ID
                try {
                    holder.bind(getItemAt(position))
                } catch (ignored: NullPointerException) { // when group deleted
                }
            }

            override fun onViewRecycled(holder: ConfigurationHolder) {
                holder.lastSelfHasMiddleRow = null
                holder.lastBoundTx = Long.MIN_VALUE
                holder.lastBoundRx = Long.MIN_VALUE
            }

            override fun onViewAttachedToWindow(holder: ConfigurationHolder) {
                super.onViewAttachedToWindow(holder)
                val profileId = holder.itemId
                val cached = configurationList[profileId] ?: return
                if (holder.lastBoundTx == cached.tx && holder.lastBoundRx == cached.rx) return
                if (configurationListView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                    pendingTrafficUpdates.add(profileId)
                    return
                }
                updateVisibleTraffic(profileId, holder)
            }

            private fun updateVisibleTraffic(
                profileId: Long,
                visibleHolder: ConfigurationHolder? = null,
            ) {
                val cached = configurationList[profileId] ?: return
                val holder = visibleHolder
                    ?: configurationListView.findViewHolderForItemId(profileId)
                        as? ConfigurationHolder
                    ?: return
                if (holder.lastBoundTx == cached.tx && holder.lastBoundRx == cached.rx) return

                val index = holder.bindingAdapterPosition
                val previousHasMiddleRow = holder.lastSelfHasMiddleRow
                holder.bindTraffic()
                if (index != RecyclerView.NO_POSITION && previousHasMiddleRow != null &&
                    previousHasMiddleRow != holder.lastSelfHasMiddleRow
                ) {
                    refreshSameRowNeighbours(index)
                }
            }

            fun flushPendingTrafficUpdates() {
                if (pendingTrafficUpdates.isEmpty()) return
                for (index in 0 until configurationListView.childCount) {
                    val holder = configurationListView.getChildViewHolder(
                        configurationListView.getChildAt(index)
                    ) as? ConfigurationHolder ?: continue
                    val profileId = holder.itemId
                    if (profileId in pendingTrafficUpdates) {
                        updateVisibleTraffic(profileId, holder)
                    }
                }
                pendingTrafficUpdates.clear()
            }

            private fun refreshSameRowNeighbours(position: Int) {
                if (position == RecyclerView.NO_POSITION) return
                val grid = layoutManager as? FixedGridLayoutManager ?: return
                val row = grid.rowIndexOf(position)
                val rowStart = (row * grid.spanCount).coerceAtLeast(0)
                val rowEnd = ((row + 1) * grid.spanCount - 1).coerceAtMost(itemCount - 1)
                configurationListView.post {
                    for (index in rowStart..rowEnd) {
                        if (index != position) notifyItemChanged(index)
                    }
                }
            }

            private fun refreshFromPosition(startPosition: Int) {
                if (layoutManager !is FixedGridLayoutManager) return
                val start = startPosition.coerceAtLeast(0)
                if (start >= itemCount) return
                configurationListView.post {
                    notifyItemRangeChanged(start, itemCount - start)
                }
            }

            override fun getItemCount(): Int {
                return configurationIdList.size
            }

            private val updated = LinkedHashMap<Long, ProxyEntity>()

            fun filter(name: String) {
                val candidates = ProfileSearchPolicy.candidates(
                    searchQuery,
                    name,
                    configurationIdList,
                    allConfigurationIdList,
                )
                searchQuery = name
                configurationIdList = filteredProfileIds(candidates, name)
                notifyDataSetChanged()
            }

            private fun filteredProfileIds(profileIds: List<Long>, query: String): MutableList<Long> {
                if (query.isEmpty()) return profileIds.toMutableList()
                val lower = query.lowercase()
                return profileIds.filter { profileId ->
                    val profile = configurationList[profileId] ?: return@filter false
                    profile.displayName().lowercase().contains(lower) ||
                            profile.profileCardType(DataStore.shortProfileProtocolInfo)
                                .lowercase()
                                .contains(lower) ||
                            profile.displayAddress().lowercase().contains(lower)
                }.toMutableList()
            }

            private fun profileMatchesSearch(profileId: Long): Boolean {
                return searchQuery.isEmpty() ||
                        filteredProfileIds(listOf(profileId), searchQuery).isNotEmpty()
            }

            private fun syncFullListVisibleOrder() {
                if (searchQuery.isEmpty()) {
                    allConfigurationIdList = configurationIdList.toMutableList()
                    return
                }
                val visibleIds = configurationIdList.toHashSet()
                val reorderedVisibleIds = configurationIdList.iterator()
                allConfigurationIdList = allConfigurationIdList.map { profileId ->
                    if (profileId in visibleIds) reorderedVisibleIds.next() else profileId
                }.toMutableList()
            }

            private fun visibleInsertionIndex(profileId: Long): Int {
                val fullIndex = allConfigurationIdList.indexOf(profileId)
                if (fullIndex < 0) return configurationIdList.size
                val visibleIds = configurationIdList.toHashSet()
                return allConfigurationIdList.take(fullIndex).count(visibleIds::contains)
            }

            private fun fullListInsertionIndex(profile: ProxyEntity): Int {
                return allConfigurationIdList.indexOfFirst { profileId ->
                    configurationList[profileId]?.userOrder?.let { it > profile.userOrder } == true
                }.takeIf { it >= 0 } ?: allConfigurationIdList.size
            }

            fun move(from: Int, to: Int) {
                if (from == to) return

                if (layoutManager is FixedGridLayoutManager) {
                    moveDualColumn(from, to)
                } else {
                    moveLinear(from, to)
                }
            }

            private fun moveLinear(from: Int, to: Int) {
                moveById(from, to)
            }

            private fun moveDualColumn(from: Int, to: Int) {
                moveById(from, to)
            }

            private fun moveById(from: Int, to: Int) {
                val draggedItemId = configurationIdList[from]

                configurationIdList.removeAt(from)
                configurationIdList.add(to, draggedItemId)
                syncFullListVisibleOrder()

                for (i in allConfigurationIdList.indices) {
                    val item = getItem(allConfigurationIdList[i])
                    val newOrder = (i + 1).toLong()
                    if (item.userOrder != newOrder) {
                        item.userOrder = newOrder
                        updated[item.id] = item
                    }
                }

                notifyItemMoved(from, to)
            }

            fun commitMove() = runOnDefaultDispatcher {
                if (updated.isNotEmpty() && shouldPersistProfileOrder()) {
                    SagerDatabase.proxyDao.updateProxy(updated.values.toList())
                } else if (updated.isNotEmpty()) {
                    reloadProfiles(ProfileReloadReason.General)
                }
                updated.clear()
                onMainDispatcher {
                    if (layoutManager is FixedGridLayoutManager) notifyDataSetChanged()
                }
            }

            fun clearTrafficStatistics() {
                configurationList.values.forEach { profile ->
                    profile.tx = 0L
                    profile.rx = 0L
                }
                notifyDataSetChanged()
            }

            fun clearTestResults() {
                configurationList.values.forEach { profile ->
                    profile.status = 0
                    profile.ping = 0
                    profile.error = null
                }
                notifyDataSetChanged()
            }

            private fun shouldShowProfileTraffic(): Boolean {
                return DataStore.profileTrafficUpdateInterval > 0 && DataStore.profileTrafficStatistics
            }

            fun remove(pos: Int) {
                if (pos < 0) return
                val profileId = configurationIdList.removeAt(pos)
                removedFullPositions[profileId] = allConfigurationIdList.indexOf(profileId)
                allConfigurationIdList.remove(profileId)
                notifyItemRemoved(pos)
                refreshFromPosition(pos - 1)
            }

            fun hideProfiles(profileIds: Set<Long>) {
                if (profileIds.isEmpty()) return
                configurationIdList.removeAll(profileIds)
                allConfigurationIdList.removeAll(profileIds)
                profileIds.forEach(configurationList::remove)
                notifyDataSetChanged()
            }

            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                for ((_, item) in actions) {
                    configurationListView.post {
                        if (!isActiveAdapter()) return@post
                        configurationList[item.id] = item
                        val fullIndex = removedFullPositions.remove(item.id)
                            ?.coerceIn(0, allConfigurationIdList.size)
                            ?: fullListInsertionIndex(item)
                        allConfigurationIdList.add(fullIndex, item.id)
                        if (profileMatchesSearch(item.id)) {
                            val visibleIndex = visibleInsertionIndex(item.id)
                            configurationIdList.add(visibleIndex, item.id)
                            notifyItemInserted(visibleIndex)
                            refreshFromPosition(visibleIndex - 1)
                        }
                    }
                }
            }

            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                val profiles = actions.map { it.second }
                runOnDefaultDispatcher {
                    for (entity in profiles) {
                        ProfileManager.deleteProfile(entity.groupId, entity.id)
                    }
                }
            }

            override suspend fun onAdd(profile: ProxyEntity) {
                if (profile.groupId != proxyGroup.id) return

                configurationListView.post {
                    if (!isActiveAdapter()) return@post
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    configurationList[profile.id] = profile
                    allConfigurationIdList.add(profile.id)
                    if (profileMatchesSearch(profile.id)) {
                        val pos = itemCount
                        configurationIdList.add(profile.id)
                        notifyItemInserted(pos)
                        refreshFromPosition(pos - 1)
                    }
                }
            }

            override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
                if (profile.groupId != proxyGroup.id) return
                configurationListView.post {
                    if (!isActiveAdapter()) return@post
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val cached = configurationList[profile.id]
                    val updatedProfile = if (noTraffic && cached != null) {
                        profile.copy(tx = cached.tx, rx = cached.rx).also {
                            it.dirty = profile.dirty
                        }
                    } else {
                        profile
                    }
                    val holder = configurationListView.findViewHolderForItemId(profile.id)
                        as? ConfigurationHolder
                    val previous = holder?.lastSelfHasMiddleRow
                    val wasVisible = profile.id in configurationIdList
                    configurationList[profile.id] = updatedProfile
                    val isVisible = profileMatchesSearch(profile.id)
                    if (wasVisible != isVisible) {
                        configurationIdList = filteredProfileIds(allConfigurationIdList, searchQuery)
                        notifyDataSetChanged()
                    } else if (isVisible) {
                        val index = configurationIdList.indexOf(profile.id)
                        notifyItemChanged(index)
                        if (previous != null && previous != hasMiddleRow(updatedProfile)) {
                            refreshSameRowNeighbours(index)
                        }
                    }
                }
            }

            override suspend fun onUpdated(data: List<TrafficData>) {
                try {
                    onMainDispatcher {
                        if (!isActiveAdapter()) return@onMainDispatcher
                        if (!shouldShowProfileTraffic()) {
                            refreshVisibleTraffic()
                            return@onMainDispatcher
                        }
                        for (update in data) {
                            val cached = configurationList[update.id] ?: continue
                            if (cached.tx == update.tx && cached.rx == update.rx) continue
                            cached.tx = update.tx
                            cached.rx = update.rx
                            if (configurationListView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                                pendingTrafficUpdates.add(update.id)
                            } else {
                                updateVisibleTraffic(update.id)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }

            fun refreshVisibleTraffic() {
                for (i in 0 until configurationListView.childCount) {
                    val holder = configurationListView.getChildViewHolder(
                        configurationListView.getChildAt(i)
                    ) as? ConfigurationHolder ?: continue
                    holder.bindTraffic()
                }
            }

            fun refreshVisibleProfileActions() {
                for (i in 0 until configurationListView.childCount) {
                    val holder = configurationListView.getChildViewHolder(
                        configurationListView.getChildAt(i)
                    ) as? ConfigurationHolder ?: continue
                    holder.updateActionState()
                }
            }

            fun shouldShowTraffic(): Boolean {
                return shouldShowProfileTraffic()
            }

            override suspend fun onRemoved(groupId: Long, profileId: Long) {
                if (groupId != proxyGroup.id) return
                (parentFragment as? ConfigurationFragment)?.removeProfileFromSelection(profileId)

                configurationListView.post {
                    if (!isActiveAdapter()) return@post
                    val index = configurationIdList.indexOf(profileId)
                    if (index >= 0) {
                        configurationIdList.removeAt(index)
                        notifyItemRemoved(index)
                        refreshFromPosition(index - 1)
                    }
                    allConfigurationIdList.remove(profileId)
                    removedFullPositions.remove(profileId)
                    configurationList.remove(profileId)
                }
            }

            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit

            override suspend fun groupUpdated(group: ProxyGroup) {
                if (group.id != proxyGroup.id) return
                val reloadReason = when {
                    pendingManualOrderReload -> {
                        pendingManualOrderReload = false
                        ProfileReloadReason.Manual
                    }

                    lastGroupUpdateStamp != null &&
                            (group.subscription?.lastUpdated ?: -1) != lastGroupUpdateStamp -> {
                        ProfileReloadReason.SubscriptionUpdate
                    }

                    else -> {
                        ProfileReloadReason.General
                    }
                }
                proxyGroup = group
                configurationListView.post { bannerAdapter.refresh() }
                reloadProfiles(reloadReason)
            }

            override suspend fun groupUpdated(groupId: Long) {
                if (groupId != proxyGroup.id) return
                proxyGroup = SagerDatabase.groupDao.getById(groupId) ?: return
                configurationListView.post { bannerAdapter.refresh() }
                reloadProfiles(ProfileReloadReason.General)
            }

            override suspend fun groupReloaded(groupId: Long, reason: GroupManager.ReloadReason) {
                if (groupId != proxyGroup.id) return
                if (pendingManualOrderReload) {
                    pendingManualOrderReload = false
                }
                proxyGroup = SagerDatabase.groupDao.getById(groupId) ?: return
                configurationListView.post { bannerAdapter.refresh() }
                reloadProfiles(
                    when (reason) {
                        GroupManager.ReloadReason.Manual -> ProfileReloadReason.Manual
                        GroupManager.ReloadReason.UrlTest -> ProfileReloadReason.UrlTest
                        GroupManager.ReloadReason.General -> ProfileReloadReason.General
                    }
                )
            }

            private fun currentGroupUpdateStamp(): Int {
                return proxyGroup.subscription?.lastUpdated ?: -1
            }

            private fun shouldApplyProfileOrder(reason: ProfileReloadReason): Boolean {
                return when {
                    proxyGroup.order == GroupOrder.MANUAL -> true
                    reason == ProfileReloadReason.Manual -> true
                    DataStore.groupOrderModeAlways -> true
                    reason == ProfileReloadReason.UrlTest -> DataStore.groupOrderModeUrlTest
                    reason == ProfileReloadReason.SubscriptionUpdate -> DataStore.groupOrderModeUpdate
                    else -> false
                }
            }

            private fun shouldPersistProfileOrder(): Boolean {
                return proxyGroup.order == GroupOrder.MANUAL ||
                        !DataStore.groupOrderModeAlways ||
                        proxyGroup.order == GroupOrder.ORIGIN
            }

            private fun sortProfileNameKey(profile: ProxyEntity): String {
                val name = profile.displayName().trim()
                val firstSortableIndex = name.indexOfFirst { it.isLetterOrDigit() }
                return if (firstSortableIndex >= 0) {
                    name.substring(firstSortableIndex).trim()
                } else {
                    name
                }
            }

            private fun persistProfileOrder(profiles: List<ProxyEntity>) {
                val changed = profiles.mapIndexedNotNull { index, profile ->
                    val newOrder = (index + 1).toLong()
                    if (profile.userOrder == newOrder) {
                        null
                    } else {
                        profile.apply { userOrder = newOrder }
                    }
                }
                if (changed.isNotEmpty()) {
                    SagerDatabase.proxyDao.updateProxy(changed)
                }
            }

            fun persistCurrentProfileOrder() {
                persistProfileOrder(allConfigurationIdList.map { getItem(it) })
            }

            private fun applySubscriptionOriginOrder(profiles: List<ProxyEntity>): List<ProxyEntity> {
                if (proxyGroup.type != GroupType.SUBSCRIPTION) return profiles
                val originOrderIds = proxyGroup.originOrderIds()
                if (originOrderIds.isEmpty()) return profiles

                val originIndex = originOrderIds.withIndex().associate { it.value to it.index }
                val originalPosition = profiles.withIndex().associate { it.value.id to it.index }
                return profiles.sortedWith { left, right ->
                    val leftOriginIndex = originIndex[left.id]
                    val rightOriginIndex = originIndex[right.id]
                    when {
                        leftOriginIndex != null && rightOriginIndex != null -> {
                            leftOriginIndex.compareTo(rightOriginIndex)
                        }

                        leftOriginIndex != null -> -1
                        rightOriginIndex != null -> 1
                        else -> {
                            originalPosition.getValue(left.id).compareTo(originalPosition.getValue(right.id))
                        }
                    }
                }
            }

            fun reloadProfilesIfChanged(
                reason: ProfileReloadReason = ProfileReloadReason.General,
                prefetchedProfiles: List<ProxyEntity>? = null,
            ) {
                val updateStamp = currentGroupUpdateStamp()
                if (lastGroupUpdateStamp == updateStamp) return
                reloadProfiles(
                    if (lastGroupUpdateStamp == null) reason else ProfileReloadReason.SubscriptionUpdate,
                    prefetchedProfiles,
                )
            }

            fun reloadProfiles(
                reason: ProfileReloadReason = ProfileReloadReason.General,
                prefetchedProfiles: List<ProxyEntity>? = null,
            ) {
                var newProfiles = prefetchedProfiles ?: SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
                ProfileCountryResolver.backfillLiteralAddresses(newProfiles).takeIf {
                    it.isNotEmpty()
                }?.let(SagerDatabase.proxyDao::updateProxy)
                val configurationFragment = parentFragment as? ConfigurationFragment
                newProfiles = newProfiles.filterNot {
                    configurationFragment?.isPendingBatchDelete(it.id) == true
                }
                if (shouldApplyProfileOrder(reason)) {
                    when (proxyGroup.order) {
                        GroupOrder.ORIGIN -> {
                            newProfiles = applySubscriptionOriginOrder(newProfiles)
                            if (proxyGroup.type == GroupType.SUBSCRIPTION &&
                                proxyGroup.originOrderIds().isNotEmpty()
                            ) {
                                persistProfileOrder(newProfiles)
                            }
                        }

                        GroupOrder.BY_NAME -> {
                            val collator = Collator.getInstance(Locale.ROOT).apply {
                                strength = Collator.PRIMARY
                            }
                            newProfiles = newProfiles.sortedWith { left, right ->
                                val nameCompare = collator.compare(
                                    sortProfileNameKey(left),
                                    sortProfileNameKey(right)
                                )
                                if (nameCompare != 0) nameCompare else left.id.compareTo(right.id)
                            }
                        }

                        GroupOrder.BY_DELAY -> {
                            newProfiles =
                                newProfiles.sortedWith(
                                    compareBy<ProxyEntity> { if (it.status == 1) it.ping else 114514 }
                                        .thenBy { it.id }
                                )
                        }

                        GroupOrder.MANUAL -> Unit
                    }
                    if (proxyGroup.order != GroupOrder.ORIGIN &&
                        proxyGroup.order != GroupOrder.MANUAL &&
                        shouldPersistProfileOrder()
                    ) {
                        persistProfileOrder(newProfiles)
                    }
                }

                configurationList.clear()
                configurationList.putAll(newProfiles.associateBy { it.id })
                val newProfileIds = newProfiles.map { it.id }

                var selectedProfileIndex = -1

                if (selected) {
                    val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                    selectedProfileIndex = newProfileIds.indexOf(selectedProxy)
                }

                configurationListView.post {
                    if (!isActiveAdapter()) return@post
                    val initialLayout = !didInitialPositionList
                    val initialItemAnimator = if (initialLayout) {
                        configurationListView.itemAnimator.also {
                            configurationListView.itemAnimator = null
                        }
                    } else {
                        null
                    }
                    allConfigurationIdList = newProfileIds.toMutableList()
                    configurationIdList = filteredProfileIds(allConfigurationIdList, searchQuery)
                    notifyDataSetChanged()

                    if (initialLayout) {
                        didInitialPositionList = true
                        val initialPosition = if (selectedProfileIndex != -1) {
                            selectedProfileIndex + bannerItemOffset
                        } else {
                            0
                        }
                        (layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(initialPosition, 0)
                            ?: configurationListView.scrollToPosition(initialPosition)
                        configurationListView.doOnNextLayout {
                            configurationListView.itemAnimator = initialItemAnimator
                            configurationListView.isVisible = true
                            if (selected) {
                                (activity as? MainActivity)?.releaseInitialProfileDraw()
                            }
                        }
                    }

                }
                lastGroupUpdateStamp = currentGroupUpdateStamp()
            }

        }

        private inner class SubscriptionBannerAdapter :
            RecyclerView.Adapter<SubscriptionBannerHolder>() {

            private var presentation =
                proxyGroup.subscription?.let(::subscriptionBannerPresentation)

            init {
                setHasStableIds(true)
            }

            override fun getItemId(position: Int): Long = Long.MIN_VALUE

            override fun getItemCount(): Int {
                return if (
                    proxyGroup.type == GroupType.SUBSCRIPTION &&
                    presentation?.visible == true
                ) {
                    1
                } else {
                    0
                }
            }

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): SubscriptionBannerHolder {
                return SubscriptionBannerHolder(ComposeView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
                    )
                })
            }

            override fun onBindViewHolder(holder: SubscriptionBannerHolder, position: Int) {
                val subscription = proxyGroup.subscription ?: return
                val current = presentation ?: return
                holder.bind(subscription, current)
            }

            override fun onViewRecycled(holder: SubscriptionBannerHolder) {
                holder.recycle()
                super.onViewRecycled(holder)
            }

            fun refresh() {
                presentation = proxyGroup.subscription?.let(::subscriptionBannerPresentation)
                notifyDataSetChanged()
                (layoutManager as? FixedGridLayoutManager)
                    ?.spanSizeLookup
                    ?.invalidateSpanIndexCache()
            }
        }

        private inner class SubscriptionBannerHolder(
            private val composeView: ComposeView,
        ) : RecyclerView.ViewHolder(composeView) {

            private var presentation by mutableStateOf<SubscriptionBannerPresentation?>(null)
            private var links by mutableStateOf(emptyList<SubscriptionBannerLink>())

            init {
                composeView.setContent {
                    NekoComposeTheme {
                        presentation?.let { current ->
                            SubscriptionBannerCard(
                                presentation = current,
                                canOpenLinks = current.clickable && links.isNotEmpty(),
                                onOpenLinks = { showLinkDialog(links) },
                            )
                        }
                    }
                }
            }

            fun bind(
                subscription: io.nekohasekai.sagernet.database.SubscriptionBean,
                presentation: SubscriptionBannerPresentation,
            ) {
                this.presentation = presentation
                links = subscriptionBannerLinks(subscription)
            }

            fun recycle() {
                presentation = null
                links = emptyList()
            }

            private fun showLinkDialog(links: List<SubscriptionBannerLink>) {
                val context = composeView.context
                val labels =
                    links.map {
                        when (it.destination) {
                            SubscriptionBannerDestination.ANNOUNCEMENT ->
                                R.string.subscription_link_announcement
                            SubscriptionBannerDestination.SUPPORT ->
                                R.string.subscription_link_support
                            SubscriptionBannerDestination.EMAIL_SUPPORT ->
                                R.string.subscription_link_email_support
                            SubscriptionBannerDestination.SUBSCRIPTION_PAGE ->
                                R.string.subscription_link_page
                        }.let(context::getString)
                    }.toTypedArray()

                context.showComposeItemDialog(
                    title = context.getText(R.string.subscription_open_link_prompt),
                    items = labels.toList(),
                    onItemSelected = { index ->
                        val link = links[index]
                        if (link.destination == SubscriptionBannerDestination.EMAIL_SUPPORT) {
                            Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.fromParts("mailto", link.value, null)
                            }.takeIf {
                                it.resolveActivity(context.packageManager) != null
                            }?.let(context::startActivity)
                        } else {
                            context.launchCustomTab(link.value)
                        }
                    },
                )
            }
        }

        val profileAccess = Mutex()

        inner class ConfigurationHolder(
            private val composeView: ComposeView,
        ) : RecyclerView.ViewHolder(composeView), TvProfileFocusTarget {

            private val bodyFocusRequester = FocusRequester()
            lateinit var entity: ProxyEntity
            private var presentation by mutableStateOf<ProfileCardModel?>(null)
            var lastSelfHasMiddleRow: Boolean? = null
            var lastBoundTx = Long.MIN_VALUE
            var lastBoundRx = Long.MIN_VALUE

            init {
                composeView.setContent {
                    NekoComposeTheme {
                        presentation?.let { model ->
                            ProfileCard(
                                model = model,
                                bodyFocusRequester = bodyFocusRequester,
                                onClick = ::handleClick,
                                onStatusClick = if (entity.status == 3) ::handleStatusClick else null,
                                onEdit = ::edit,
                                onUrlTest = ::urlTest,
                                onShare = ::showShareMenu,
                                onDelete = ::delete,
                                onSelectionChange = {
                                    (parentFragment as? ConfigurationFragment)
                                        ?.toggleProfileSelection(entity.id)
                                },
                            )
                        }
                    }
                }
            }

            override fun requestTvProfileFocus(): Boolean {
                bodyFocusRequester.requestFocus()
                return true
            }

            private val isCurrentRunningProfile: Boolean
                get() = DataStore.serviceState.started && DataStore.currentProfile == entity.id

            fun updateActionState() {
                if (::entity.isInitialized) render()
            }

            fun bindTraffic(trafficData: TrafficData? = null) {
                if (::entity.isInitialized) render(trafficData)
            }

            fun bind(proxyEntity: ProxyEntity, trafficData: TrafficData? = null) {
                entity = proxyEntity
                render(trafficData)
            }

            private fun render(trafficData: TrafficData? = null) {
                val parent = parentFragment as? ConfigurationFragment ?: return
                val context = composeView.context
                var tx = entity.tx
                var rx = entity.rx
                if (adapter?.shouldShowTraffic() != true) {
                    tx = 0L
                    rx = 0L
                } else if (trafficData != null) {
                    tx = trafficData.tx
                    rx = trafficData.rx
                }
                val showTraffic = tx + rx != 0L
                val traffic = if (showTraffic) {
                    context.getString(
                        R.string.traffic,
                        Formatter.formatFileSize(context, tx),
                        Formatter.formatFileSize(context, rx),
                    )
                } else ""
                val layout = when (DataStore.groupLayoutMode) {
                    GROUP_LAYOUT_COMPACT -> ProfileCardLayout.COMPACT
                    GROUP_LAYOUT_DOUBLE -> ProfileCardLayout.DOUBLE
                    GROUP_LAYOUT_ALTERNATE -> ProfileCardLayout.ALTERNATE
                    else -> ProfileCardLayout.SINGLE
                }
                val double = layout == ProfileCardLayout.DOUBLE ||
                    layout == ProfileCardLayout.ALTERNATE
                val compact = layout == ProfileCardLayout.COMPACT
                val showTrafficSeparately = !compact || entity.status > 0
                val address = entity.displayAddress().takeIf {
                    entity.requireBean().name.isNotBlank() && parent.alwaysShowAddress
                }.orEmpty()
                val trafficUsesMiddleRow = showTraffic && showTrafficSeparately
                val hasMiddleRow = trafficUsesMiddleRow || address.isNotBlank()
                val reserveMiddleRow = !double && !hasMiddleRow &&
                    adapter?.neighbourHasMiddleRow(bindingAdapterPosition) == true
                lastSelfHasMiddleRow = hasMiddleRow

                var status = ""
                var statusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                when {
                    entity.status <= 0 && showTraffic && !showTrafficSeparately -> status = traffic
                    entity.status == 1 -> {
                        status = getString(R.string.available, entity.ping)
                        statusColor = context.getColour(R.color.material_green_500)
                    }
                    entity.status == 2 -> {
                        status = entity.error.orEmpty()
                        statusColor = context.getColour(R.color.material_red_500)
                    }
                    entity.status == 3 -> {
                        status = Protocols.genFriendlyMsg(entity.error ?: "<?>")
                        statusColor = context.getColour(R.color.material_red_500)
                    }
                }
                val countryCode = ProfileCountryResolver.effectiveCountryCode(entity)
                val countryVisible = DataStore.profileCountryIndicator &&
                    CountryFlagRenderer.loadSvg(context, countryCode) != null
                val batchSelection = parent.isProfileSelectionMode
                val selectOrChain = select || entity.type == ProxyEntity.TYPE_CHAIN
                val showUrlTest = ProfileCardActionPolicy.shouldShowUrlTest(
                    selectMode = select,
                    notificationSwitchPopup = activity is SwitchActivity,
                    batchSelection = batchSelection,
                )
                val showShare = !selectOrChain && !batchSelection && entity.nekoBean == null
                val showTrafficInOwnField = showTraffic && showTrafficSeparately
                presentation = ProfileCardModel(
                    entity = entity,
                    layout = layout,
                    name = ProfileCountryResolver.presentationName(entity, countryVisible),
                    type = entity.profileCardType(DataStore.shortProfileProtocolInfo),
                    countryVisible = countryVisible,
                    address = address,
                    traffic = if (showTrafficInOwnField) traffic else "",
                    status = status,
                    typeColor = context.getProtocolColor(entity.type),
                    statusColor = statusColor,
                    selected = (selectedItem?.id ?: DataStore.selectedProxy) == entity.id,
                    insecure = entity.shouldHighlightAsInsecure(
                        DataStore.globalAllowInsecure,
                        DataStore.dontHighlightInsecureProfiles,
                    ),
                    borders = DataStore.profileCardBorders,
                    middleRowVisible = hasMiddleRow,
                    middleRowReserved = reserveMiddleRow,
                    statusVisible = !double || entity.status > 0,
                    batchSelection = batchSelection,
                    batchSelected = parent.isProfileSelected(entity.id),
                    showEdit = !double && !select && !batchSelection,
                    editEnabled = !isCurrentRunningProfile,
                    showUrlTest = !double && showUrlTest,
                    urlTestEnabled = !UrlTest.isUnsupportedProfile(entity),
                    showShare = !double && showShare,
                    showDelete = !double && !select && !batchSelection,
                    showOverflow = double && !batchSelection,
                    minimumHeightDp = if (double) {
                        when {
                            adapter?.shouldShowTraffic() == true && parent.alwaysShowAddress -> 112
                            adapter?.shouldShowTraffic() == true || parent.alwaysShowAddress -> 92
                            else -> 0
                        }
                    } else 0,
                )
                lastBoundTx = tx
                lastBoundRx = rx
            }

            private fun handleClick() {
                val parent = parentFragment as? ConfigurationFragment
                when {
                    parent?.isProfileSelectionMode == true -> parent.toggleProfileSelection(entity.id)
                    select -> (requireActivity() as SelectCallback).returnProfile(entity.id)
                    else -> runOnDefaultDispatcher {
                        var update: Boolean
                        var lastSelected: Long
                        var serviceState: BaseService.State
                        profileAccess.withLock {
                            update = DataStore.selectedProxy != entity.id
                            lastSelected = DataStore.selectedProxy
                            DataStore.selectedProxy = entity.id
                            serviceState = DataStore.serviceState
                        }
                        onMainDispatcher { render() }
                        if (update) {
                            ProfileManager.postUpdate(lastSelected)
                            if (ProfileSelectionReloadPolicy.shouldReload(update, serviceState)) {
                                SagerNet.reloadService(entity.id)
                            }
                        } else if (SagerNet.isTv) {
                            if (DataStore.serviceState.started) SagerNet.stopService()
                            else SagerNet.startService()
                        }
                    }
                }
            }

            private fun handleStatusClick() {
                handleClick()
                val parent = parentFragment as? ConfigurationFragment
                if (entity.status == 3 && !select && parent?.isProfileSelectionMode != true) {
                    alert(entity.error ?: "<?>").tryToShow()
                }
            }

            private fun edit() {
                if (isCurrentRunningProfile) return
                composeView.context.startActivity(
                    entity.settingIntent(
                        composeView.context,
                        proxyGroup.type == GroupType.SUBSCRIPTION,
                    ),
                )
            }

            private fun urlTest() {
                if (!UrlTest.isUnsupportedProfile(entity)) ProfileUrlTestController.start(entity)
            }

            private fun delete() {
                if (isCurrentRunningProfile) return
                val currentAdapter = adapter ?: return
                val index = currentAdapter.configurationIdList.indexOf(entity.id)
                if (index < 0) return
                val remove = {
                    currentAdapter.remove(index)
                    undoManager.remove(index to entity)
                }
                if (DataStore.confirmProfileDelete) {
                    composeView.context.showComposeMessageDialog(
                        title = composeView.context.getText(R.string.delete_confirm_prompt),
                        positiveButton = composeView.context.getText(R.string.yes),
                        negativeButton = composeView.context.getText(R.string.no),
                        onPositive = remove,
                    )
                } else remove()
            }

            private fun showCode(link: String) {
                QRCodeDialog(link, entity.displayName())
                    .showAllowingStateLoss(parentFragmentManager)
            }

            private fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                (activity as MainActivity)
                    .snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            private fun showShareMenu(action: ProfileShareAction) {
                runCatching {
                    when (action) {
                        ProfileShareAction.STANDARD_QR -> showCode(entity.toStdLink())
                        ProfileShareAction.STANDARD_CLIPBOARD -> export(entity.toStdLink())
                        ProfileShareAction.UNIVERSAL_QR ->
                            showCode(entity.requireBean().toUniversalLink())
                        ProfileShareAction.UNIVERSAL_CLIPBOARD ->
                            export(entity.requireBean().toUniversalLink())
                        ProfileShareAction.CONFIGURATION_CLIPBOARD ->
                            export(entity.exportConfig().first)
                        ProfileShareAction.CONFIGURATION_FILE -> {
                            val config = entity.exportConfig()
                            DataStore.serverConfig = config.first
                            startFilesForResult(
                                (parentFragment as ConfigurationFragment).exportConfig,
                                config.second,
                            )
                        }
                    }
                }.onFailure {
                    Logs.w(it)
                    (activity as MainActivity).snackbar(it.readableMessage).show()
                }
            }
        }


    }

    private val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(data)!!
                            .bufferedWriter()
                            .use {
                                it.write(DataStore.serverConfig)
                            }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }

                }
            }
        }

    private val exportSelectedConfigurations =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { data ->
            val bytes = pendingConfigurationZip
            pendingConfigurationZip = null
            if (data == null || bytes == null) return@registerForActivityResult
            runOnDefaultDispatcher {
                try {
                    requireActivity().contentResolver.openOutputStream(data)!!.use {
                        it.write(bytes)
                    }
                    onMainDispatcher {
                        snackbar(R.string.action_export_msg).show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }

    private val exportSelectedAmneziaWGJson =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { data ->
            val content = pendingAmneziaWGJson
            pendingAmneziaWGJson = null
            if (data == null || content == null) return@registerForActivityResult
            runOnDefaultDispatcher {
                try {
                    requireActivity().contentResolver.openOutputStream(data)!!
                        .bufferedWriter()
                        .use { it.write(content) }
                    onMainDispatcher {
                        snackbar(R.string.action_export_msg).show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }

    private fun cancelSearch(searchView: SearchView) {
        searchView.setQuery("", false)
        searchView.isIconified = true
        searchView.clearFocus()
    }

    private fun closeProfileSearch() {
        profileSearchExpanded = false
        quickSearchExpanded = false
        toolbarOrNull()?.menu?.findItem(R.id.action_search)?.apply {
            (actionView as? SearchView)?.let(::cancelSearch)
            collapseActionView()
        }
        selectionBackCallback?.isEnabled = profileSelectionMode
        syncToolbarMode()
        toolbarOrNull()?.post { focusTvToolbarAction() }
    }

    private fun syncToolbarMode() {
        if (select || profileSelectionMode) return
        val activeToolbar = toolbarOrNull() ?: return

        val useToolbar = DataStore.useToolbar
        val menu = activeToolbar.menu
        menu.findItem(R.id.action_add)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)

        activeToolbar.post {
            if (toolbarOrNull() !== activeToolbar ||
                DataStore.useToolbar != useToolbar ||
                profileSelectionMode
            ) {
                return@post
            }
            val showQuickToolbar = useToolbar && !quickSearchExpanded
            syncQuickToolbarBackground(activeToolbar)
            quickToolbar?.isVisible = showQuickToolbar
            activeToolbar.isInvisible = showQuickToolbar
            activeToolbar.titleTextView()?.apply {
                isGone = useToolbar
                isClickable = !useToolbar
                isFocusable = !useToolbar
                setOnClickListener(if (useToolbar) null else View.OnClickListener {
                    focusSelectedProfileGroupAndScroll()
                })
            }
            if (showQuickToolbar) renderQuickToolbarActions()
            configureTvToolbarFocus()
        }
    }

    private fun setupQuickToolbar(view: View) {
        quickToolbar = view.findViewById(R.id.quick_toolbar)
        quickToolbarActions = view.findViewById(R.id.quick_toolbar_actions)
        view.findViewById<View>(R.id.quick_toolbar_navigation)?.apply {
            configureQuickToolbarButton(contentDescription)
            setOnClickListener {
                (activity as? MainActivity)?.openDrawer()
            }
        }
        view.findViewById<View>(R.id.quick_toolbar_search)?.apply {
            configureQuickToolbarButton(contentDescription)
            setOnClickListener {
                quickSearchExpanded = true
                selectionBackCallback?.isEnabled = true
                syncToolbarMode()
                toolbarOrNull()?.post {
                    toolbarOrNull()?.menu?.findItem(R.id.action_search)?.apply {
                        expandActionView()
                        (actionView as? SearchView)?.apply {
                            isIconified = false
                            requestFocus()
                        }
                    }
                }
            }
        }
        view.findViewById<View>(R.id.quick_toolbar_add)?.apply {
            configureQuickToolbarButton(contentDescription)
            setOnClickListener { showClonedSubmenu(it, R.id.action_add) }
        }
        view.findViewById<View>(R.id.quick_toolbar_more)?.apply {
            configureQuickToolbarButton(contentDescription)
            setOnClickListener { showClonedSubmenu(it, R.id.action_misc) }
        }
    }

    private fun View.configureQuickToolbarButton(label: CharSequence?) {
        contentDescription = label
        TooltipCompat.setTooltipText(this, label)
        (background as? RippleDrawable)?.radius = dp2px(20)
        if (SagerNet.isTv) installTvFocusOutline()
    }

    private fun syncQuickToolbarBackground(activeToolbar: Toolbar = toolbar) {
        quickToolbar?.background =
            activeToolbar.background.constantState?.newDrawable(resources)?.mutate()
                ?: activeToolbar.background
    }

    private fun closeQuickToolbarSearch() {
        closeProfileSearch()
    }

    private fun renderQuickToolbarActions() {
        val container = quickToolbarActions ?: return
        container.removeAllViews()
        val layout = ProfileToolbarLayout.decode(DataStore.toolbarLayout)
        layout.active.forEach { actionId ->
            val action = ProfileToolbarActionCatalog[actionId]
            container.addView(AppCompatImageButton(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp2px(40), ViewGroup.LayoutParams.MATCH_PARENT)
                minimumWidth = 0
                setPadding(dp2px(8), paddingTop, dp2px(8), paddingBottom)
                setImageResource(action.iconRes)
                requireContext().withStyledAttributes(
                    attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
                ) {
                    background = getDrawable(0)
                }
                configureQuickToolbarButton(getString(action.titleRes))
                alpha = if (action.kind == ProfileToolbarActionKind.TOGGLE &&
                    !isQuickToolbarToggleEnabled(actionId)
                ) {
                    0.38f
                } else {
                    1f
                }
                setOnClickListener { executeQuickToolbarAction(actionId, this) }
            })
        }
        (activity as? ThemedActivity)?.applyHeaderColors()
    }

    private fun executeQuickToolbarAction(actionId: ProfileToolbarActionId, anchor: View) {
        val sourceMenuItemId = when (actionId) {
            ProfileToolbarActionId.UPDATE_SUBSCRIPTION -> R.id.action_update_subscription
            ProfileToolbarActionId.CLEAR_TRAFFIC -> R.id.action_clear_traffic_statistics
            ProfileToolbarActionId.CLEAR_TEST_RESULTS -> R.id.action_connection_test_clear_results
            ProfileToolbarActionId.REMOVE_DUPLICATES -> R.id.action_remove_duplicate
            ProfileToolbarActionId.DELETE_UNAVAILABLE -> R.id.action_connection_test_delete_unavailable
            ProfileToolbarActionId.REMOVE_INSECURE -> R.id.action_remove_insecure
            ProfileToolbarActionId.ICMP_PING -> R.id.action_connection_icmp_ping
            ProfileToolbarActionId.TCP_PING -> R.id.action_connection_tcp_ping
            ProfileToolbarActionId.URL_TEST -> R.id.action_connection_url_test
            ProfileToolbarActionId.GLOBAL_MODE -> R.id.action_global_mode
            ProfileToolbarActionId.CLASH_MODE -> R.id.action_clash_mode
            ProfileToolbarActionId.ACTIVE_PROFILE -> R.id.action_show_active
            else -> null
        }
        if (sourceMenuItemId != null) {
            toolbar.menu.performIdentifierAction(sourceMenuItemId, 0)
            renderQuickToolbarActions()
            return
        }

        val submenuItemId = when (actionId) {
            ProfileToolbarActionId.STATISTICS_MENU -> R.id.action_statistics_menu
            ProfileToolbarActionId.DELETE_MENU -> R.id.action_delete_menu
            ProfileToolbarActionId.SORT_AND_LAYOUT -> R.id.action_order_layout
            else -> null
        }
        if (submenuItemId != null) {
            showClonedSubmenu(anchor, submenuItemId)
            return
        }

        val activity = activity as? MainActivity ?: return
        when (actionId) {
            ProfileToolbarActionId.STUN_TEST ->
                startActivity(Intent(requireContext(), StunActivity::class.java))
            ProfileToolbarActionId.SPEED_TEST ->
                startActivity(Intent(requireContext(), SpeedTestActivity::class.java))
            ProfileToolbarActionId.RULESET_MATCH ->
                startActivity(Intent(requireContext(), RuleSetMatchActivity::class.java))
            ProfileToolbarActionId.CELLULAR_NETWORK ->
                startActivity(Intent(requireContext(), CellularNetworkActivity::class.java))
            ProfileToolbarActionId.BACKUP_PANEL ->
                activity.displayToolsFragment(ToolsFragment.backupPanel())

            ProfileToolbarActionId.NAV_PROFILES ->
                activity.displayFragmentWithId(R.id.nav_configuration)
            ProfileToolbarActionId.NAV_GROUPS -> activity.displayFragmentWithId(R.id.nav_group)
            ProfileToolbarActionId.NAV_ROUTING -> activity.displayFragmentWithId(R.id.nav_route)
            ProfileToolbarActionId.NAV_APPS -> activity.displayFragmentWithId(R.id.nav_route_apps)
            ProfileToolbarActionId.NAV_ADBLOCK -> activity.displayFragmentWithId(R.id.nav_adblock)
            ProfileToolbarActionId.NAV_SETTINGS -> activity.displayFragmentWithId(R.id.nav_settings)
            ProfileToolbarActionId.NAV_LOGS -> activity.displayFragmentWithId(R.id.nav_logcat)
            ProfileToolbarActionId.NAV_DASHBOARD -> activity.displayFragmentWithId(R.id.nav_traffic)
            ProfileToolbarActionId.NAV_TOOLS -> activity.displayFragmentWithId(R.id.nav_tools)
            ProfileToolbarActionId.NAV_ABOUT -> activity.displayFragmentWithId(R.id.nav_about)

            ProfileToolbarActionId.MANAGE_ROUTE_ASSETS ->
                startActivity(Intent(requireContext(), AssetsActivity::class.java))
            ProfileToolbarActionId.ADD_NORMAL_RULE ->
                startActivity(Intent(requireContext(), RouteSettingsActivity::class.java))
            ProfileToolbarActionId.ADD_DNS_RULE ->
                startActivity(Intent(requireContext(), RouteSettingsActivity::class.java).apply {
                    putExtra(RouteSettingsActivity.EXTRA_ROUTE_TYPE, RuleType.DNS.value)
                })

            ProfileToolbarActionId.SETTINGS_INTERFACE -> activity.displaySettingsGroup("interface")
            ProfileToolbarActionId.SETTINGS_CONNECTION -> activity.displaySettingsGroup("connection")
            ProfileToolbarActionId.SETTINGS_CORE -> activity.displaySettingsGroup("core")
            ProfileToolbarActionId.SETTINGS_INBOUND -> activity.displaySettingsGroup("inbound")
            ProfileToolbarActionId.SETTINGS_ROUTING -> activity.displaySettingsGroup("routing")
            ProfileToolbarActionId.SETTINGS_DNS -> activity.displaySettingsGroup("dns")
            ProfileToolbarActionId.SETTINGS_CONNECTION_TESTING ->
                activity.displaySettingsGroup("connectionTesting")
            ProfileToolbarActionId.SETTINGS_DEVELOPERS ->
                activity.displaySettingsGroup("developers")
            ProfileToolbarActionId.SETTINGS_OTHERS -> activity.displaySettingsGroup("others")

            ProfileToolbarActionId.ENABLE_CORE_PROFILING -> toggleCoreProfiling()
            ProfileToolbarActionId.RESTART_APP -> triggerFullRestart(requireContext())
            ProfileToolbarActionId.KILL_BACKGROUND_PROCESS ->
                BackgroundProcessController.confirmKill(requireContext())
            else -> Unit
        }
    }

    private fun toggleCoreProfiling() {
        val enabled = !DataStore.enableCoreProfiling
        DataStore.enableCoreProfiling = enabled
        renderQuickToolbarActions()
        val service = (activity as? MainActivity)?.connection?.service ?: return
        if (!DataStore.serviceState.connected) return
        runOnDefaultDispatcher {
            runCatching {
                if (enabled) {
                    service.startCoreProfiling(DataStore.coreProfilerMode)
                } else {
                    service.stopCoreProfiling()
                }
            }.onFailure { Logs.w(it) }
        }
    }

    private fun showClonedSubmenu(anchor: View, sourceItemId: Int) {
        val source = toolbar.menu.findItem(sourceItemId)?.subMenu ?: return
        PopupMenu(requireContext(), anchor).apply {
            copyMenu(source, menu)
            setForceShowIcon(true)
            setOnMenuItemClickListener { selected ->
                toolbar.menu.performIdentifierAction(selected.itemId, 0)
            }
            show()
        }
    }

    private fun copyMenu(source: Menu, target: Menu) {
        for (index in 0 until source.size) {
            val sourceItem = source[index]
            val targetItem = if (sourceItem.hasSubMenu()) {
                target.addSubMenu(
                    sourceItem.groupId,
                    sourceItem.itemId,
                    sourceItem.order,
                    sourceItem.title,
                ).also { copyMenu(sourceItem.subMenu!!, it) }.item
            } else {
                target.add(
                    sourceItem.groupId,
                    sourceItem.itemId,
                    sourceItem.order,
                    sourceItem.title,
                )
            }
            targetItem.icon = sourceItem.icon
            targetItem.isCheckable = sourceItem.isCheckable
            targetItem.isChecked = sourceItem.isChecked
            targetItem.isEnabled = sourceItem.isEnabled
            targetItem.isVisible = sourceItem.isVisible
        }
    }

    private fun isQuickToolbarToggleEnabled(actionId: ProfileToolbarActionId): Boolean {
        return when (actionId) {
            ProfileToolbarActionId.GLOBAL_MODE -> DataStore.globalMode
            ProfileToolbarActionId.ENABLE_CORE_PROFILING -> DataStore.enableCoreProfiling
            else -> false
        }
    }

    private fun setupProfileToolbarMenu() {
        val activeToolbar = toolbarOrNull() ?: return
        activeToolbar.menu.clear()
        activeToolbar.inflateMenu(R.menu.add_profile_menu)
        activeToolbar.menu.findItem(R.id.action_global_mode)?.isChecked = DataStore.globalMode
        syncMenuState(activeToolbar.menu)
        activeToolbar.setOnMenuItemClickListener(this)
        setupSearchView()
        configureTvToolbarFocus()
        (activity as? ThemedActivity)?.applyHeaderColors()
    }

    private fun setupProfileSelectionToolbarMenu() {
        val activeToolbar = toolbarOrNull() ?: return
        activeToolbar.menu.clear()
        activeToolbar.inflateMenu(R.menu.profile_selection_menu)
        activeToolbar.setOnMenuItemClickListener(this)
        updateSelectionMenuEnabledState()
    }

    private fun enterProfileSelectionMode() {
        if (select || profileSelectionMode) return
        quickSearchExpanded = false
        quickToolbar?.isGone = true
        toolbarOrNull()?.isVisible = true
        toolbarOrNull()?.findViewById<SearchView>(R.id.action_search)?.let(::cancelSearch)
        profileSelectionMode = true
        selectionBackCallback?.isEnabled = true
        setupProfileSelectionToolbarMenu()
        refreshProfileSelectionPresentation()
    }

    private fun exitProfileSelectionMode() {
        if (!profileSelectionMode) return
        profileSelectionMode = false
        selectedProfileIds.clear()
        selectionBackCallback?.isEnabled = false
        setupProfileToolbarMenu()
        syncToolbarMode()
        getCurrentGroupFragment()?.checkOrderMenu()
        refreshProfileSelectionPresentation()
    }

    fun isProfileSelected(profileId: Long): Boolean = profileId in selectedProfileIds

    fun isPendingBatchDelete(profileId: Long): Boolean = profileId in pendingBatchDeleteIds

    fun toggleProfileSelection(profileId: Long) {
        if (!profileSelectionMode) return
        if (!selectedProfileIds.add(profileId)) selectedProfileIds.remove(profileId)
        notifySelectionChanged(profileId)
    }

    fun removeProfileFromSelection(profileId: Long) {
        runOnMainDispatcher {
            if (selectedProfileIds.remove(profileId)) updateSelectionMenuEnabledState()
        }
    }

    private fun notifySelectionChanged(profileId: Long? = null) {
        adapter.groupFragments.values.forEach { fragment ->
            if (profileId == null) {
                fragment.adapter?.notifyDataSetChanged()
            } else {
                fragment.adapter?.notifySelectionChanged(profileId)
            }
        }
        updateSelectionMenuEnabledState()
    }

    private fun updateSelectionMenuEnabledState() {
        val enabled = selectedProfileIds.isNotEmpty()
        val menu = toolbarOrNull()?.menu ?: return
        sequenceOf(
            R.id.action_selection_clear_traffic,
            R.id.action_selection_clear_tests,
            R.id.action_selection_icmp_ping,
            R.id.action_selection_tcp_ping,
            R.id.action_selection_url_test,
            R.id.action_selection_group_copy,
            R.id.action_selection_group_move,
            R.id.action_selection_standard_qr,
            R.id.action_selection_universal_qr,
            R.id.action_selection_standard_clipboard,
            R.id.action_selection_universal_clipboard,
            R.id.action_selection_config_clipboard,
            R.id.action_selection_config_file,
            R.id.action_selection_delete,
        ).forEach { menu.findItem(it)?.isEnabled = enabled }
    }

    private fun setupSearchView() {
        val activeToolbar = toolbarOrNull() ?: return
        activeToolbar.menu.findItem(R.id.action_search)?.setOnActionExpandListener(
            object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                    profileSearchExpanded = true
                    selectionBackCallback?.isEnabled = true
                    return true
                }

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    profileSearchExpanded = false
                    selectionBackCallback?.isEnabled = profileSelectionMode
                    return true
                }
            },
        )
        activeToolbar.findViewById<SearchView>(R.id.action_search)?.apply {
            setOnQueryTextListener(this@ConfigurationFragment)
            maxWidth = Int.MAX_VALUE
            setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    cancelSearch(this)
                    if (quickSearchExpanded) {
                        quickSearchExpanded = false
                        syncToolbarMode()
                    }
                }
            }
            findViewById<View>(androidx.appcompat.R.id.search_close_btn)?.apply {
                isFocusable = true
            }
            findViewById<View>(androidx.appcompat.R.id.search_src_text)?.setOnKeyListener {
                    _, keyCode, event ->
                if (!SagerNet.isTv || event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
                    return@setOnKeyListener false
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        findViewById<View>(androidx.appcompat.R.id.search_close_btn)?.requestFocus() == true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        getCurrentGroupFragment()?.configurationListView?.requestFocus() == true
                    }
                    else -> false
                }
            }
        }
    }

    private fun Toolbar.titleTextView(): TextView? {
        val expectedTitle = title?.toString() ?: return null

        return children
            .filterIsInstance<TextView>()
            .firstOrNull { it.text?.toString() == expectedTitle }
    }

    private fun scrollCurrentGroupToSelectedProfile() {
        val fragment = getCurrentGroupFragment() ?: return
        val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy

        val selectedProfileIndex =
            fragment.adapter?.configurationIdList?.indexOf(selectedProxy) ?: -1

        if (selectedProfileIndex >= 0) {
            fragment.configurationListView.scrollTo(
                selectedProfileIndex + fragment.bannerItemOffset,
                true,
            )
        } else {
            fragment.configurationListView.scrollTo(0)
        }
    }

    private fun focusSelectedProfileGroupAndScroll() {
        val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
        if (selectedProxy <= 0) return

        runOnDefaultDispatcher {
            val selectedProfile = SagerDatabase.proxyDao.getById(selectedProxy) ?: return@runOnDefaultDispatcher
            val targetGroupId = selectedProfile.groupId

            onMainDispatcher {
                val targetIndex = adapter.groupList.indexOfFirst { it.id == targetGroupId }
                if (targetIndex < 0) return@onMainDispatcher

                if (DataStore.selectedGroup != targetGroupId || groupPager.currentItem != targetIndex) {
                    DataStore.selectedGroup = targetGroupId
                    groupPager.setCurrentItem(targetIndex, false)

                    groupPager.post {
                        scrollCurrentGroupToSelectedProfile()
                    }
                } else {
                    scrollCurrentGroupToSelectedProfile()
                }
            }
        }
    }
}
