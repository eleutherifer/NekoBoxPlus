package io.nekohasekai.sagernet.ui

import android.content.DialogInterface
import android.net.Network
import android.net.NetworkCapabilities
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.ProfileStatusUpdater
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(DelicateCoroutinesApi::class)
object GroupConnectionTestController {
    private const val NETWORK_READY_TIMEOUT_MS = 5000L

    private enum class TestType {
        IcmpPing,
        TcpPing,
        UrlTest,
    }

    private val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
    private val finishedN = AtomicInteger(0)
    private val nextRunId = AtomicLong(0)
    private val testJobs = mutableListOf<Job>()

    @Volatile
    private var activeRunId = 0L
    private var testType: TestType? = null
    private var mainJob: Job? = null
    private var notification: ConnectionTestNotification? = null
    private var dialog: AlertDialog? = null
    private var binding: LayoutProgressListBinding? = null
    private var lastProfile: ProxyEntity? = null
    private var proxyN = 0
    private var groupId = 0L
    private var selectedProfileIds: List<Long>? = null
    private var affectedGroupIds: Set<Long> = emptySet()
    private var title = ""
    private var minimized = false
    private var restorePending = false
    @Volatile
    private var completing = false

    val activeGroupId: Long
        get() = groupId

    val isActive: Boolean
        get() = DataStore.runningTest && testType != null && !completing

    val isRestorePending: Boolean
        get() = restorePending

    private fun isActive(runId: Long): Boolean {
        return activeRunId == runId && isActive
    }

    fun startIcmpPing(fragment: ConfigurationFragment, profileIds: List<Long>? = null) {
        if (DataStore.runningTest) return
        val group = DataStore.currentGroup()
        start(
            fragment,
            TestType.IcmpPing,
            group.id,
            "[${group.displayName()}] ${fragment.getString(R.string.connection_test)}",
            profileIds,
        )
    }

    fun startTcpPing(fragment: ConfigurationFragment, profileIds: List<Long>? = null) {
        if (DataStore.runningTest) return
        val group = DataStore.currentGroup()
        start(
            fragment,
            TestType.TcpPing,
            group.id,
            "[${group.displayName()}] ${fragment.getString(R.string.connection_test)}",
            profileIds,
        )
    }

    fun startUrlTest(fragment: ConfigurationFragment, profileIds: List<Long>? = null) {
        if (DataStore.runningTest) return
        val group = DataStore.currentGroup()
        start(
            fragment,
            TestType.UrlTest,
            group.id,
            "[${group.displayName()}] ${fragment.getString(R.string.connection_test)}",
            profileIds,
        )
    }

    fun requestRestore(): Boolean {
        if (!isActive) return false
        restorePending = true
        return true
    }

    fun cancelFromNotification() {
        if (isActive) {
            complete(cancelJobs = true)
        } else {
            mainJob?.cancel()
            testJobs.forEach { it.cancel() }
            DataStore.runningTest = false
            activeRunId = 0L
            testType = null
            restorePending = false
            minimized = false
            notification?.updateNotification(finishedN.get().coerceAtMost(proxyN), proxyN, finished = true)
            notification = null
        }
    }

    fun restore(fragment: ConfigurationFragment) {
        if (!requestRestore()) return
        if (showDialog(fragment)) {
            minimized = false
            restorePending = false
            notification?.updateNotification(finishedN.get().coerceAtMost(proxyN), proxyN, finished = true)
            notification = null
        }
    }

    fun attach(fragment: ConfigurationFragment) {
        if (isActive && (!minimized || restorePending) && showDialog(fragment)) {
            minimized = false
            restorePending = false
            notification?.updateNotification(finishedN.get().coerceAtMost(proxyN), proxyN, finished = true)
            notification = null
        }
    }

    fun detach() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        binding = null
    }

    private fun start(
        fragment: ConfigurationFragment,
        type: TestType,
        testGroupId: Long,
        testTitle: String,
        profileIds: List<Long>?,
    ) {
        DataStore.runningTest = true
        val runId = nextRunId.incrementAndGet()
        activeRunId = runId
        testType = type
        groupId = testGroupId
        selectedProfileIds = profileIds?.distinct()
        affectedGroupIds = emptySet()
        title = testTitle
        proxyN = 0
        minimized = false
        restorePending = false
        completing = false
        lastProfile = null
        results.clear()
        finishedN.set(0)
        testJobs.clear()
        showDialog(fragment, runId)

        mainJob = runOnDefaultDispatcher {
            try {
                when (type) {
                    TestType.IcmpPing -> runIcmpPing(runId)
                    TestType.TcpPing -> runTcpPing(runId)
                    TestType.UrlTest -> runUrlTest(runId)
                }
                testJobs.joinAll()
            } finally {
                runOnMainDispatcher {
                    complete(runId, cancelJobs = false)
                }
            }
        }
    }

    private suspend fun CoroutineScope.runIcmpPing(runId: Long) {
        val profilesList = selectedProfilesOrGroup().filter {
            !it.containsMasterDnsVPN() && it.requireBean().canICMPing()
        }
        affectedGroupIds = profilesList.map { it.groupId }.toSet()
        if (!isActive(runId)) return
        proxyN = profilesList.size
        runOnMainDispatcher { updateUi() }
        if (awaitUnderlyingNetwork() == null) {
            failProfiles(runId, profilesList, app.getString(R.string.connection_test_unreachable))
            return
        }
        val profiles = ConcurrentLinkedQueue(profilesList)
        repeat(minOf(DataStore.connectionTestConcurrent, profilesList.size)) {
            testJobs.add(launch(Dispatchers.IO) {
                while (GroupConnectionTestController.isActive(runId)) {
                    val profile = profiles.poll() ?: break
                    profile.status = 0
                    try {
                        profile.ping = Libcore.icmpPing(
                            profile.requireBean().serverAddress,
                            DataStore.connectionGroupTestTimeout,
                        )
                        if (!GroupConnectionTestController.isActive(runId)) break
                        profile.status = 1
                    } catch (e: Exception) {
                        if (!GroupConnectionTestController.isActive(runId)) break
                        val message = e.readableMessage
                        profile.status = 2
                        when {
                            message.contains("deadline exceeded", ignoreCase = true) ||
                                    message.contains("timed out", ignoreCase = true) -> {
                                profile.error =
                                    app.getString(R.string.connection_test_timeout_error)
                            }

                            message.contains("resolve ICMP ping host", ignoreCase = true) -> {
                                profile.error =
                                    app.getString(R.string.connection_test_domain_not_found)
                            }

                            isAddressFamilyFailure(e) -> {
                                profile.error =
                                    app.getString(R.string.connection_test_unreachable)
                            }

                            else -> {
                                profile.status = 3
                                profile.error = message
                            }
                        }
                    }
                    update(runId, profile)
                }
            })
        }
    }

    private suspend fun CoroutineScope.runTcpPing(runId: Long) {
        val candidates = selectedProfilesOrGroup()
        val profilesList = candidates.filter {
            !it.containsMasterDnsVPN() && it.requireBean().canTCPing()
        }
        affectedGroupIds = profilesList.map { it.groupId }.toSet()
        if (!isActive(runId)) return
        proxyN = profilesList.size
        runOnMainDispatcher { updateUi() }
        val network = awaitUnderlyingNetwork()
        if (network == null) {
            failProfiles(runId, profilesList, app.getString(R.string.connection_test_unreachable))
            return
        }
        val profiles = ConcurrentLinkedQueue(profilesList)
        repeat(minOf(DataStore.connectionTestConcurrent, profilesList.size)) {
            testJobs.add(launch(Dispatchers.IO) {
                while (GroupConnectionTestController.isActive(runId)) {
                    val profile = profiles.poll() ?: break
                    profile.status = 0
                    val bean = profile.requireBean()
                    if (DataStore.connectionTestHardened) {
                        try {
                            profile.ping = Libcore.tcpPing(
                                bean.serverAddress,
                                bean.serverPort.toString(),
                                3000,
                                true,
                                LocalResolverImpl,
                            )
                            if (!GroupConnectionTestController.isActive(runId)) break
                            profile.status = 1
                        } catch (e: Exception) {
                            if (!GroupConnectionTestController.isActive(runId)) break
                            val message = e.readableMessage
                            profile.status = 2
                            when {
                                message.contains("resolve TCP ping host", ignoreCase = true) -> {
                                    profile.error =
                                        app.getString(R.string.connection_test_domain_not_found)
                                }

                                message.contains("ECONNREFUSED") -> {
                                    profile.error = app.getString(R.string.connection_test_refused)
                                }

                                isAddressFamilyFailure(e) -> {
                                    profile.error =
                                        app.getString(R.string.connection_test_unreachable)
                                }

                                message.contains("deadline exceeded", ignoreCase = true) ||
                                        message.contains("timed out", ignoreCase = true) -> {
                                    profile.error =
                                        app.getString(R.string.connection_test_timeout_error)
                                }

                                else -> {
                                    profile.status = 3
                                    profile.error = message
                                }
                            }
                        }
                        update(runId, profile)
                        continue
                    }
                    val addresses = if (bean.serverAddress.isIpAddress()) {
                        listOf(bean.serverAddress)
                    } else {
                        try {
                            network.getAllByName(bean.serverAddress).mapNotNull { it.hostAddress }
                        } catch (_: UnknownHostException) {
                            emptyList()
                        }
                    }
                    if (addresses.isEmpty()) {
                        profile.status = 2
                        profile.error = app.getString(R.string.connection_test_domain_not_found)
                        update(runId, profile)
                        continue
                    }
                    try {
                        var result: Int? = null
                        var lastError: Exception? = null
                        for ((index, address) in addresses.withIndex()) {
                            try {
                                result = Libcore.tcpPing(
                                    address,
                                    bean.serverPort.toString(),
                                    3000,
                                    false,
                                    LocalResolverImpl,
                                )
                                break
                            } catch (e: Exception) {
                                lastError = e
                                if (index == addresses.lastIndex || !isAddressFamilyFailure(e)) throw e
                            }
                        }
                        if (result == null) throw lastError ?: error("TCP ping failed")
                        if (!GroupConnectionTestController.isActive(runId)) break
                        profile.status = 1
                        profile.ping = result
                        update(runId, profile)
                    } catch (e: Exception) {
                        if (!GroupConnectionTestController.isActive(runId)) break
                        val message = e.readableMessage
                        profile.status = 2
                        when {
                            message.contains("ECONNREFUSED") -> {
                                profile.error = app.getString(R.string.connection_test_refused)
                            }

                            isAddressFamilyFailure(e) -> {
                                profile.error = app.getString(R.string.connection_test_unreachable)
                            }

                            message.contains("deadline exceeded", ignoreCase = true) ||
                                    message.contains("timed out", ignoreCase = true) -> {
                                profile.error = app.getString(R.string.connection_test_timeout_error)
                            }

                            else -> {
                                profile.status = 3
                                profile.error = message
                            }
                        }
                        update(runId, profile)
                    }
                }
            })
        }
    }

    private suspend fun CoroutineScope.runUrlTest(runId: Long) {
        val activeProfileId = DataStore.currentProfile.takeIf {
            DataStore.serviceState.connected && it > 0L
        }
        val candidates = selectedProfilesOrGroup()
        val profilesList = candidates
            .filterNot {
                it.id == activeProfileId || UrlTest.isUnsupportedProfile(it)
            }
        affectedGroupIds = profilesList.map { it.groupId }.toSet()
        if (!isActive(runId)) return
        proxyN = profilesList.size
        runOnMainDispatcher { updateUi() }
        if (awaitUnderlyingNetwork() == null) {
            failProfiles(runId, profilesList, app.getString(R.string.connection_test_unreachable))
            return
        }
        val tester = try {
            Libcore.newGroupURLTester(
                resolveGroupConnectionTestURL(
                    DataStore.connectionGroupTestURL,
                    DataStore.connectionTestURL,
                ),
                DataStore.connectionGroupTestTimeout,
                DataStore.connectionTestAttempts,
                DataStore.connectionTestPause,
                DataStore.connectionTestHardened,
                LocalResolverImpl,
            )
        } catch (e: Exception) {
            failProfiles(runId, profilesList, e.readableMessage)
            return
        }
        val profiles = ConcurrentLinkedQueue(profilesList)
        repeat(minOf(DataStore.connectionTestConcurrent, profilesList.size)) {
            testJobs.add(launch(Dispatchers.IO) {
                val urlTest = UrlTest(timeout = DataStore.connectionGroupTestTimeout)
                while (GroupConnectionTestController.isActive(runId)) {
                    val profile = profiles.poll() ?: break
                    profile.status = 0

                    try {
                        val result = urlTest.doGroupTest(profile, tester)
                        profile.status = 1
                        profile.ping = result
                    } catch (e: PluginManager.PluginNotFoundException) {
                        profile.status = 2
                        profile.error = e.readableMessage
                    } catch (e: Exception) {
                        profile.status = 3
                        profile.error = e.readableMessage
                    }

                    update(runId, profile)
                }
            })
        }
    }

    private suspend fun awaitUnderlyingNetwork(): Network? {
        val network = withTimeoutOrNull(NETWORK_READY_TIMEOUT_MS) {
            runCatching { DefaultNetworkListener.get() }.getOrNull()
        } ?: return null
        val capabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) {
            return null
        }
        return network
    }

    private fun selectedProfilesOrGroup(): List<ProxyEntity> {
        val ids = selectedProfileIds ?: return SagerDatabase.proxyDao.getByGroup(groupId)
        val byId = SagerDatabase.proxyDao.getEntities(ids).associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    private fun failProfiles(runId: Long, profiles: List<ProxyEntity>, message: String) {
        for (profile in profiles) {
            if (!isActive(runId)) return
            profile.status = 2
            profile.error = message
            update(runId, profile)
        }
    }

    private fun isAddressFamilyFailure(error: Exception): Boolean {
        val message = error.readableMessage
        return message.contains("ENETUNREACH") ||
                message.contains("EHOSTUNREACH") ||
                message.contains("EAFNOSUPPORT")
    }

    private fun update(runId: Long, profile: ProxyEntity) {
        if (!isActive(runId)) return
        results.add(profile)
        lastProfile = profile
        finishedN.addAndGet(1)
        runOnMainDispatcher {
            if (!isActive(runId)) return@runOnMainDispatcher
            val completed = finishedN.get().coerceAtMost(proxyN)
            val finished = completed >= proxyN
            notification?.updateNotification(completed, proxyN, finished)
            updateUi()
        }
    }

    private fun showDialog(fragment: ConfigurationFragment, runId: Long = activeRunId): Boolean {
        if (!fragment.isAdded || fragment.view == null) return false
        if (dialog?.isShowing == true) return true
        val newBinding = LayoutProgressListBinding.inflate(fragment.layoutInflater)
        binding = newBinding
        dialog = MaterialAlertDialogBuilder(fragment.requireContext())
            .setView(newBinding.root)
            .setPositiveButton(R.string.minimize, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .show()
            .apply {
                getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    minimize()
                }
                getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                    complete(runId, cancelJobs = true)
                }
            }
        updateUi()
        return true
    }

    private fun minimize() {
        minimized = true
        notification = ConnectionTestNotification(SagerNet.application, title)
        notification?.updateNotification(finishedN.get().coerceAtMost(proxyN), proxyN, finished = false)
        detach()
    }

    private fun complete(runId: Long = activeRunId, cancelJobs: Boolean) {
        if (activeRunId != runId || completing) return
        completing = true
        val finishedResults = results.toList()
        val finishedGroupIds = affectedGroupIds.ifEmpty { setOf(groupId) }
        if (cancelJobs) {
            mainJob?.cancel()
            testJobs.forEach { it.cancel() }
        }
        detach()
        notification?.updateNotification(finishedN.get().coerceAtMost(proxyN), proxyN, finished = true)
        notification = null
        activeRunId = 0L
        mainJob = null
        testType = null
        groupId = 0L
        selectedProfileIds = null
        affectedGroupIds = emptySet()
        proxyN = 0
        lastProfile = null
        minimized = false
        restorePending = false
        DataStore.runningTest = false
        results.clear()
        testJobs.clear()
        runOnDefaultDispatcher {
            finishedResults.forEach {
                try {
                    ProfileStatusUpdater.update(
                        it.id,
                        it.status,
                        it.ping,
                        it.error,
                        reloadDelayOrderedGroup = false
                    )
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
            finishedGroupIds.forEach {
                GroupManager.postReload(it, GroupManager.ReloadReason.UrlTest)
            }
            completing = false
        }
    }

    private fun updateUi() {
        val activeBinding = binding ?: return
        val context = dialog?.context ?: return
        val profile = lastProfile
        activeBinding.progress.text = "${finishedN.get().coerceAtMost(proxyN)} / $proxyN"
        if (profile == null) return

        var profileStatusText: String? = null
        var profileStatusColor = 0

        when (profile.status) {
            -1 -> {
                profileStatusText = profile.error
                profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
            }

            0 -> {
                profileStatusText = context.getString(R.string.connection_test_testing)
                profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
            }

            1 -> {
                profileStatusText = context.getString(R.string.available, profile.ping)
                profileStatusColor = context.getColour(R.color.material_green_500)
            }

            2 -> {
                profileStatusText = profile.error
                profileStatusColor = context.getColour(R.color.material_red_500)
            }

            3 -> {
                val err = profile.error ?: ""
                profileStatusText = Protocols.genFriendlyMsg(err)
                profileStatusColor = context.getColour(R.color.material_red_500)
            }
        }

        activeBinding.nowTesting.text = profile.displayName()
        activeBinding.nowTestingStatus.text = SpannableStringBuilder().apply {
            append(
                profile.displayType(),
                ForegroundColorSpan(context.getProtocolColor(profile.type)),
                SPAN_EXCLUSIVE_EXCLUSIVE
            )
            append(" ")
            append(
                profileStatusText,
                ForegroundColorSpan(profileStatusColor),
                SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}

internal fun resolveGroupConnectionTestURL(groupURL: String, fallbackURL: String): String {
    val candidate = groupURL.trim()
    return if (candidate.toHttpUrlOrNull() != null) candidate else fallbackURL
}
