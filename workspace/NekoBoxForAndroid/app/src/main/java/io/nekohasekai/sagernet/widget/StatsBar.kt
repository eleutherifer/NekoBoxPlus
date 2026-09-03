package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.DeadObjectException
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.TextViewCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.bottomappbar.BottomAppBarTopEdgeTreatment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.AutomaticConnectionTestPolicy
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.ProfileStatusUpdater
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.ConnectionIpResolver
import io.nekohasekai.sagernet.utils.ProfileCountryResolver
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.Protocols
import kotlin.math.abs

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    companion object {
        private const val SCROLL_TOGGLE_THRESHOLD_DP = 8f
        private const val COMPACT_STACK_THRESHOLD_DP = 600
        private const val COMPACT_DIVIDER_HEIGHT_DP = 32
    }

    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var ipText: TextView
    private lateinit var compactStatusText: TextView
    private lateinit var compactTxText: TextView
    private lateinit var compactRxText: TextView
    private lateinit var compactIpText: TextView
    private lateinit var regularContent: View
    private lateinit var compactContent: View
    private lateinit var compactInfoGroup: View
    private lateinit var compactSpeedGroup: LinearLayout
    private lateinit var compactSectionDivider: View
    private lateinit var compactSpeedDivider: View
    private var regularShapeAppearance: ShapeAppearanceModel? = null
    private var regularTopEdgeTreatment: BottomAppBarTopEdgeTreatment? = null
    private var compactShapeAppearance: ShapeAppearanceModel? = null
    private var compactTopEdgeTreatment: BottomAppBarTopEdgeTreatment? = null
    @Suppress("unused")
    private lateinit var behavior: YourBehavior

    private var compactMode = false
    private var regularLeftMargin = 0
    private var regularTopMargin = 0
    private var regularRightMargin = 0
    private var regularBottomMargin = 0
    private var lastTxRate = 0L
    private var lastRxRate = 0L
    private var compactSpeedsStacked = false

    private var scrollHidden = false
    private var scrollDirection = 0
    private var scrollAccumulatedDy = 0
    private val scrollToggleThresholdPx =
        (SCROLL_TOGGLE_THRESHOLD_DP * resources.displayMetrics.density).toInt().coerceAtLeast(8)

    var useExternalScrollDriver = false
        set(value) {
            if (field == value) return
            field = value
            syncScrollHiddenFromView()
            resetScrollDriverState()
        }
    var allowShow = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) cancelPendingTransition()
        }
    private var masterDnsVPNResolverChecking = false
    private var connectedStateRendered = false
    private var connectionCheckPolicy = StatsBarConnectionCheckPolicy()
    private val reconnectPolicy = StatsBarReconnectPolicy()
    private var transitionGeneration = 0
    private var transitionJob: Job? = null
    private var connectionTestJob: Job? = null
    private var connectionTestGeneration = 0

    internal fun bindConnectionCheckService(service: ISagerNetService) {
        connectionCheckPolicy.bindService(service)
        connectionCheckPolicy.retainedPresentation()?.let { (status, ipInfo) ->
            renderStatus(status)
            renderIpInfo(ipInfo)
        }
    }

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior()
        return behavior
    }

    inner class YourBehavior : Behavior() {

        override fun onStartNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            directTarget: View,
            target: View,
            nestedScrollAxes: Int,
            type: Int,
        ): Boolean {
            if (useExternalScrollDriver) return false
            return super.onStartNestedScroll(
                coordinatorLayout,
                child,
                directTarget,
                target,
                nestedScrollAxes,
                type,
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!allowShow) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!allowShow) return
            super.slideDown(child)
        }
    }


    override fun onFinishInflate() {
        super.onFinishInflate()
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        ipText = findViewById(R.id.ip)
        compactStatusText = findViewById(R.id.compact_status)
        compactTxText = findViewById(R.id.compact_tx)
        compactRxText = findViewById(R.id.compact_rx)
        compactIpText = findViewById(R.id.compact_ip)
        regularContent = findViewById(R.id.stats_regular_content)
        compactContent = findViewById(R.id.stats_compact_content)
        compactInfoGroup = findViewById(R.id.compact_info_group)
        compactSpeedGroup = findViewById(R.id.compact_speed_group)
        compactSectionDivider = findViewById(R.id.compact_section_divider)
        compactSpeedDivider = findViewById(R.id.compact_speed_divider)
        regularShapeAppearance = (background as? MaterialShapeDrawable)?.shapeAppearanceModel
        regularTopEdgeTreatment =
            regularShapeAppearance?.topEdge as? BottomAppBarTopEdgeTreatment
        compactTopEdgeTreatment = BottomAppBarTopEdgeTreatment(
            fabCradleMargin,
            fabCradleRoundedCornerRadius,
            cradleVerticalOffset,
        ).apply {
            fabDiameter = 0f
        }
        compactShapeAppearance = regularShapeAppearance?.toBuilder()
            ?.setTopEdge(compactTopEdgeTreatment!!)
            ?.setAllCornerSizes(dp2pxf(18))
            ?.build()
        (layoutParams as? CoordinatorLayout.LayoutParams)?.let {
            regularLeftMargin = it.leftMargin
            regularTopMargin = it.topMargin
            regularRightMargin = it.rightMargin
            regularBottomMargin = it.bottomMargin
        }
        resizeCompactIcons()
        normalizeCompactDividers()
        updateCompactSpeedLayout(resources.configuration.screenWidthDp < COMPACT_STACK_THRESHOLD_DP)
        setCompactMode(DataStore.compactStatsBar)
        applyThemeColors()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!this::compactSpeedGroup.isInitialized || w <= 0) return
        updateCompactSpeedLayout(w < dp2px(COMPACT_STACK_THRESHOLD_DP))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyBarShape()
    }

    override fun draw(canvas: Canvas) {
        applyBarShape()
        super.draw(canvas)
    }

    override fun setOnClickListener(l: OnClickListener?) {
        super.setOnClickListener(l)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (this::statusText.isInitialized) {
            applyThemeColors()
            post {
                applyBarMargins()
                applyBarShape()
                requestLayout()
            }
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus && SagerNet.isTv && !DataStore.serviceState.connected) {
            post(::hideStats)
        }
    }

    private fun applyThemeColors() {
        val usePrimary = Theme.isCustom() && DataStore.customThemeStatsBarPrimary
        val backgroundColor = context.getColorAttr(
            if (usePrimary) R.attr.colorPrimary else R.attr.colorSurfaceContainerHigh
        )
        val textColor = context.getColorAttr(
            if (usePrimary) R.attr.colorOnPrimary else R.attr.colorOnSurface
        )
        backgroundTintList = ColorStateList.valueOf(backgroundColor)
        listOf(
            statusText,
            txText,
            rxText,
            ipText,
            compactStatusText,
            compactTxText,
            compactRxText,
            compactIpText,
        ).forEach {
            it.setTextColor(textColor)
            TextViewCompat.setCompoundDrawableTintList(it, ColorStateList.valueOf(textColor))
        }
        val rawDividerColor = context.getColorAttr(
            if (usePrimary) R.attr.colorOnPrimary else R.attr.colorOutlineVariant
        )
        val dividerColor = if (usePrimary) {
            ColorUtils.setAlphaComponent(rawDividerColor, 64)
        } else {
            rawDividerColor
        }
        compactSectionDivider.setBackgroundColor(dividerColor)
        compactSpeedDivider.setBackgroundColor(dividerColor)
    }

    fun setCompactMode(enabled: Boolean) {
        compactMode = enabled
        if (!this::regularContent.isInitialized) return

        regularContent.isGone = enabled
        compactContent.isVisible = enabled
        // Keep the cradle anchor geometry in both modes so toggling the layout never changes the
        // FAB's CoordinatorLayout gravity. Compact mode only suppresses the visual cutout below.
        fabAnchorMode = FAB_ANCHOR_MODE_CRADLE
        applyBarShape()
        applyBarMargins()
        updateSpeed(lastTxRate, lastRxRate)
        applyThemeColors()
        requestLayout()
        (parent as? CoordinatorLayout)?.let { coordinator ->
            coordinator.getDependents(this)
                .filterIsInstance<FabCluster>()
                .forEach { it.syncWithStatsBar(this) }
            coordinator.requestLayout()
        }
    }

    private fun applyBarMargins() {
        (layoutParams as? CoordinatorLayout.LayoutParams)?.let {
            it.setMargins(
                regularLeftMargin,
                regularTopMargin,
                regularRightMargin,
                regularBottomMargin,
            )
            layoutParams = it
        }
    }

    private fun applyBarShape() {
        if (!this::regularContent.isInitialized) return
        if (compactMode) {
            compactTopEdgeTreatment?.fabDiameter = 0f
            (compactShapeAppearance?.topEdge as? BottomAppBarTopEdgeTreatment)?.fabDiameter = 0f
        }
        (background as? MaterialShapeDrawable)?.let { shapeDrawable ->
            val targetShape = if (compactMode) compactShapeAppearance else regularShapeAppearance
            if (targetShape != null && shapeDrawable.shapeAppearanceModel != targetShape) {
                shapeDrawable.shapeAppearanceModel = targetShape
            }
            val targetInterpolation = if (compactMode) 0f else 1f
            if (shapeDrawable.interpolation != targetInterpolation) {
                shapeDrawable.interpolation = targetInterpolation
            }
        }
    }

    internal val fabClusterTranslationY: Float
        get() = if (compactMode) -dp2pxf(12) else 0f

    internal fun updateFabCradle(fab: FloatingActionButton) {
        if (!this::regularContent.isInitialized || fab.measuredWidth <= 0) return
        val contentRect = Rect()
        fab.getMeasuredContentRect(contentRect)
        if (contentRect.isEmpty) return

        val topEdge = regularTopEdgeTreatment ?: return
        val diameter = contentRect.height().toFloat()
        val cornerSize = fab.shapeAppearanceModel.topLeftCornerSize.getCornerSize(RectF(contentRect))
        if (topEdge.fabDiameter != diameter) topEdge.fabDiameter = diameter
        if (topEdge.fabCornerRadius != cornerSize) topEdge.setFabCornerSize(cornerSize)
        invalidate()
    }

    private fun resizeCompactIcons() {
        val size = dp2px(18)
        listOf(compactStatusText, compactTxText, compactRxText).forEach { textView ->
            val drawables = textView.compoundDrawablesRelative
            drawables.filterNotNull().forEach { drawable ->
                DrawableCompat.wrap(drawable).setBounds(0, 0, size, size)
            }
            textView.setCompoundDrawablesRelative(
                drawables[0],
                drawables[1],
                drawables[2],
                drawables[3],
            )
        }
    }

    private fun normalizeCompactDividers() {
        val dividerHeight = dp2px(COMPACT_DIVIDER_HEIGHT_DP)
        compactSectionDivider.layoutParams = compactSectionDivider.layoutParams.apply {
            height = dividerHeight
        }
        compactSpeedDivider.layoutParams = compactSpeedDivider.layoutParams.apply {
            height = dividerHeight
            if (this is LinearLayout.LayoutParams) {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = 0
                marginEnd = 0
            }
        }
    }

    private fun updateCompactSpeedLayout(stacked: Boolean) {
        if (compactSpeedsStacked == stacked && compactSpeedGroup.width > 0) return
        compactSpeedsStacked = stacked
        compactSpeedGroup.orientation =
            if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        (compactSpeedGroup.layoutParams as? ConstraintLayout.LayoutParams)?.let { params ->
            params.width = dp2px(if (stacked) 112 else 184)
            compactSpeedGroup.layoutParams = params
        }
        listOf(compactTxText, compactRxText).forEach { textView ->
            (textView.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0
                params.height = if (stacked) 0 else ViewGroup.LayoutParams.MATCH_PARENT
                params.weight = 1f
                textView.layoutParams = params
            }
        }
        compactSpeedDivider.isGone = stacked || !compactSpeedGroup.isVisible
    }

    fun onListScrolled(dy: Int) {
        if (!useExternalScrollDriver || !allowShow || !hideOnScroll || dy == 0) return
        if (scrollDirection == 0) syncScrollHiddenFromView()

        val direction = if (dy > 0) 1 else -1
        if (direction != scrollDirection) {
            scrollDirection = direction
            scrollAccumulatedDy = 0
        }
        scrollAccumulatedDy += dy

        val wantHidden = scrollAccumulatedDy > 0
        if (wantHidden == scrollHidden) {
            if (abs(scrollAccumulatedDy) > scrollToggleThresholdPx) {
                scrollAccumulatedDy = direction * scrollToggleThresholdPx
            }
            return
        }
        if (abs(scrollAccumulatedDy) < scrollToggleThresholdPx) return

        scrollHidden = wantHidden
        scrollAccumulatedDy = 0
        if (wantHidden) hideStats() else showStats()
    }

    private fun resetScrollDriverState() {
        scrollDirection = 0
        scrollAccumulatedDy = 0
    }

    private fun syncScrollHiddenFromView() {
        if (!isLaidOut || height <= 0) return
        scrollHidden = translationY >= height / 2f
    }

    private fun showStats() {
        scrollHidden = false
        resetScrollDriverState()
        animate().cancel()
        if (isScrolledUp && translationY != 0f) {
            // HideBottomViewOnScrollBehavior records SCROLLED_UP before its animation completes.
            // If that animation was interrupted, performShow() returns without fixing the view.
            translationY = 0f
        } else {
            performShow()
        }
        reconcileAnchoredViewsAfterTransition()
    }

    internal fun revealForTvFocus() {
        if (allowShow) showStats()
    }

    private fun hideStats() {
        scrollHidden = true
        resetScrollDriverState()
        animate().cancel()
        if (isScrolledDown && height > 0 && translationY < height.toFloat()) {
            // Match showStats(): reconcile the real position even when Material already considers
            // the bar hidden and would otherwise ignore another performHide() request.
            translationY = height.toFloat()
        } else {
            performHide()
        }
        reconcileAnchoredViewsAfterTransition()
    }

    private fun reconcileAnchoredViewsAfterTransition() {
        postOnAnimation {
            (parent as? CoordinatorLayout)?.requestLayout()
        }
        animate().withEndAction {
            (parent as? CoordinatorLayout)?.requestLayout()
        }
    }

    private fun cancelPendingTransition() {
        transitionGeneration++
        transitionJob?.cancel()
        transitionJob = null
    }

    fun cancelPendingTransitions() {
        cancelPendingTransition()
        animate().cancel()
        resetScrollDriverState()
    }

    private fun scheduleTransition(
        show: Boolean,
        afterTransition: () -> Unit = {},
    ) {
        cancelPendingTransition()
        val generation = transitionGeneration
        val activity = context.mainActivity() ?: return
        transitionJob = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted {
                if (generation != transitionGeneration) return@whenStarted
                transitionJob = null
                if (show) {
                    if (!allowShow) return@whenStarted
                    showStats()
                } else {
                    hideStats()
                }
                afterTransition()
            }
        }
    }

    private fun setStatus(text: CharSequence, retain: Boolean = true) {
        if (retain) connectionCheckPolicy.retainStatus(text)
        renderStatus(text)
    }

    private fun renderStatus(text: CharSequence) {
        statusText.text = text
        compactStatusText.text = text
        TooltipCompat.setTooltipText(this, buildTooltipText(text))
    }

    private fun setIpInfo(text: CharSequence?) {
        connectionCheckPolicy.retainIpInfo(text)
        renderIpInfo(text)
    }

    private fun renderIpInfo(text: CharSequence?) {
        if (text.isNullOrBlank()) {
            ipText.text = " "
            compactIpText.text = " "
        } else {
            ipText.text = text
            compactIpText.text = text
        }
        TooltipCompat.setTooltipText(this, buildTooltipText(statusText.text))
    }

    private fun buildTooltipText(status: CharSequence): CharSequence {
        val activeIpText = if (compactMode) compactIpText else ipText
        val ip = activeIpText.text?.takeIf { activeIpText.isVisible && it.isNotBlank() }
        return if (ip == null) status else "$ip\n$status"
    }

    private tailrec fun Context.mainActivity(): MainActivity? {
        return when (this) {
            is MainActivity -> this
            is ContextWrapper -> baseContext.mainActivity()
            else -> null
        }
    }

    fun changeState(
        state: BaseService.State,
        previousState: BaseService.State,
    ) {
        if (
            masterDnsVPNResolverChecking &&
            (state == BaseService.State.Connecting || state == BaseService.State.Connected)
        ) {
            return
        }
        val preservePositionForProfileReconnect = reconnectPolicy.shouldPreservePosition(
            state,
            previousState,
            profileChanged = DataStore.selectedProxy != DataStore.currentProfile,
        )
        if (state != BaseService.State.Connecting) {
            masterDnsVPNResolverChecking = false
            isEnabled = true
        }
        if (state != BaseService.State.Connected) {
            connectedStateRendered = false
            connectionCheckPolicy.onDisconnected()
            connectionTestGeneration++
            connectionTestJob?.cancel()
            connectionTestJob = null
        }
        if ((state == BaseService.State.Connected).also { hideOnScroll = it }) {
            val connectedEvent = connectionCheckPolicy.onConnected()
            val firstConnectedRender = !connectedStateRendered
            val hasRetainedPresentation =
                connectionCheckPolicy.retainedPresentation() != null
            val runAutomaticCheck =
                connectedEvent.shouldRunAutomaticCheck && DataStore.automaticConnectionCheck
            connectedStateRendered = true
            scheduleTransition(show = true) {
                if (connectionCheckPolicy.isCurrent(connectedEvent.session)) {
                    if (runAutomaticCheck && DataStore.serviceState.connected) {
                        testConnection(automatic = true)
                    } else if (firstConnectedRender && !hasRetainedPresentation) {
                        setStatus(app.getText(R.string.vpn_connected))
                    }
                }
            }
        } else {
            if (preservePositionForProfileReconnect) {
                cancelPendingTransition()
            } else {
                scheduleTransition(show = false)
            }
            setIpInfo(null)
            updateSpeed(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    fun showMasterDnsVPNResolverProgress(found: Int, total: Int, ready: Boolean) {
        if (ready) {
            masterDnsVPNResolverChecking = false
            isEnabled = DataStore.serviceState.connected
            hideOnScroll = true
            val connectedEvent = connectionCheckPolicy.onConnected()
            val hasRetainedPresentation =
                connectionCheckPolicy.retainedPresentation() != null
            val runAutomaticCheck =
                connectedEvent.shouldRunAutomaticCheck && DataStore.automaticConnectionCheck
            connectedStateRendered = true
            if (runAutomaticCheck) {
                setIpInfo(null)
            } else if (!hasRetainedPresentation) {
                setStatus(app.getText(R.string.vpn_connected))
            }
            scheduleTransition(show = true) {
                if (
                    runAutomaticCheck &&
                    connectionCheckPolicy.isCurrent(connectedEvent.session) &&
                    DataStore.serviceState.connected
                ) {
                    testConnection(automatic = true)
                }
            }
            return
        }
        connectedStateRendered = false
        connectionCheckPolicy.onDisconnected()
        masterDnsVPNResolverChecking = true
        hideOnScroll = false
        isEnabled = false
        setIpInfo(null)
        updateSpeed(0, 0)
        setStatus(context.getString(R.string.masterdnsvpn_checking_resolvers, found, total))
        scheduleTransition(show = true)
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        lastTxRate = txRate
        lastRxRate = rxRate
        if (DataStore.speedInterval <= 0) {
            txText.text = " "
            rxText.isGone = true
            setCompactSpeedVisible(false)
            return
        }

        rxText.isGone = false
        setCompactSpeedVisible(true)
        val txSpeed = context.getString(
            R.string.speed,
            Formatter.formatFileSize(context, txRate),
        )
        val rxSpeed = context.getString(
            R.string.speed,
            Formatter.formatFileSize(context, rxRate),
        )
        txText.text = "▲  $txSpeed"
        rxText.text = "▼  $rxSpeed"
        compactTxText.text = txSpeed
        compactRxText.text = rxSpeed
    }

    private fun setCompactSpeedVisible(visible: Boolean) {
        compactSpeedGroup.isVisible = visible
        compactSectionDivider.isVisible = visible
        (compactInfoGroup.layoutParams as? ConstraintLayout.LayoutParams)?.let { params ->
            if (visible) {
                params.endToEnd = ConstraintLayout.LayoutParams.UNSET
                params.endToStart = R.id.compact_section_divider
            } else {
                params.endToStart = ConstraintLayout.LayoutParams.UNSET
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
            compactInfoGroup.layoutParams = params
        }
        compactSpeedDivider.isVisible = visible && !compactSpeedsStacked
    }

    fun testConnection(automatic: Boolean = false) {
        val activity = context.mainActivity() ?: return
        val profileId = DataStore.currentProfile
        val testSession = connectionCheckPolicy.currentSession
        val testGeneration = ++connectionTestGeneration
        connectionTestJob?.cancel()
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing), retain = false)
        connectionTestJob = runOnDefaultDispatcher {
            fun isCurrentTest(): Boolean {
                return connectionCheckPolicy.isCurrent(testSession) &&
                    testGeneration == connectionTestGeneration &&
                    DataStore.serviceState.connected &&
                    DataStore.currentProfile == profileId
            }
            try {
                if (automatic) {
                    delay(AutomaticConnectionTestPolicy.START_DELAY_MILLIS)
                    if (!isCurrentTest()) return@runOnDefaultDispatcher
                }
                val elapsed = activity.urlTest(automatic)
                if (!isCurrentTest()) return@runOnDefaultDispatcher
                updateCurrentProfileStatus(profileId, status = 1, ping = elapsed, error = null)
                val status = app.getString(
                    if (DataStore.connectionTestURL.startsWith("https://")) {
                        R.string.connection_test_available
                    } else {
                        R.string.connection_test_available_http
                    }, elapsed
                )
                val ipResult = async(Dispatchers.IO) { ConnectionIpResolver.resolve() }
                onMainDispatcher {
                    if (!isCurrentTest()) return@onMainDispatcher
                    isEnabled = true
                    setStatus(status)
                    setIpInfo(null)
                }
                val ipInfo = ipResult.await()
                if (ipInfo != null && isCurrentTest()) {
                    val countryUpdated = ipInfo.countryCode?.let { countryCode ->
                        ProfileCountryResolver.updateFromCountryCode(
                            profileId,
                            countryCode,
                            ProfileCountryResolver.SOURCE_OUTBOUND,
                        )
                    } ?: ProfileCountryResolver.updateFromAddress(
                            profileId,
                            ipInfo.ip,
                            ProfileCountryResolver.SOURCE_OUTBOUND,
                        )
                    if (countryUpdated && isCurrentTest()) {
                        SagerNet.updateNotificationCountryIndicator(
                            DataStore.notificationCountryIndicator
                        )
                    }
                }
                onMainDispatcher {
                    if (!isCurrentTest()) return@onMainDispatcher
                    if (
                        ipInfo == null &&
                        DataStore.serviceMode == Key.MODE_VPN &&
                        !DataStore.requireProxyInVPN
                    ) {
                        setIpInfo(null)
                    } else {
                        setIpInfo(ipInfo?.displayText ?: context.getString(R.string.failed_to_obtain_ip))
                    }
                }
            } catch (_: CancellationException) {
                // Replacing the active profile cancels the old core and its test.
            } catch (_: DeadObjectException) {
                // The binder may disappear while an obsolete service process is recovered.
            } catch (e: Exception) {
                if (!isCurrentTest()) return@runOnDefaultDispatcher
                Logs.w(e.toString())
                updateCurrentProfileStatus(profileId, status = 3, error = e.readableMessage)
                val errorStatus = Protocols.genFriendlyMsg(e.readableMessage)
                onMainDispatcher {
                    if (!isCurrentTest()) return@onMainDispatcher
                    isEnabled = true
                    setStatus(errorStatus)

                    activity.snackbar(errorStatus).show()
                }
            } finally {
                if (testGeneration == connectionTestGeneration) {
                    connectionTestJob = null
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        connectionTestGeneration++
        connectionTestJob?.cancel()
        connectionTestJob = null
        super.onDetachedFromWindow()
    }

    private suspend fun updateCurrentProfileStatus(
        profileId: Long,
        status: Int,
        ping: Int = 0,
        error: String?,
    ) {
        ProfileStatusUpdater.update(
            profileId,
            status,
            ping,
            error,
            reloadDelayOrderedGroup = false
        )
    }

}
