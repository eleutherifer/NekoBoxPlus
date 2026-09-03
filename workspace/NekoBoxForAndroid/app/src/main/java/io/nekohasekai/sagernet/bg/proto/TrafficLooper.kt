package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TrafficLooper(
    val data: BaseService.Data,
    private val sc: CoroutineScope,
) {

    companion object {
        private const val TRAFFIC_BATCH_SIZE = 500
    }

    private data class LoopSnapshot(
        val speed: SpeedDisplayData,
        val trafficUpdates: ArrayList<TrafficData>,
    )

    private var job: Job? = null
    private var trafficUpdater: TrafficUpdater? = null
    private val access = Mutex()
    private val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>()
    private val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>()
    private val pendingTrafficIds = mutableSetOf<Long>()

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        if (!DataStore.profileTrafficStatistics || DataStore.profileTrafficUpdateInterval <= 0) {
            resetState()
            return
        }

        val traffic = access.withLock {
            trafficUpdater?.updateAll()
            buildMap {
                data.proxy?.config?.trafficMap?.forEach { (_, entities) ->
                    for (entity in entities) {
                        val item = idMap[entity.id] ?: continue
                        entity.rx = item.rx
                        entity.tx = item.tx
                        put(
                            entity.id,
                            TrafficData(id = entity.id, rx = entity.rx, tx = entity.tx),
                        )
                    }
                }
            }
        }
        traffic.values.forEach {
            ProfileManager.updateTraffic(it.id, it.rx, it.tx)
        }
        broadcastTraffic(traffic.values)
        resetState()
        Logs.d("finally traffic post done")
    }

    fun start() {
        if (job?.isCompleted == false) return
        job = sc.launch { loop() }
    }

    private suspend fun resetState() {
        access.withLock {
            trafficUpdater = null
            idMap.clear()
            tagMap.clear()
            pendingTrafficIds.clear()
            selectorNowId = -114514L
            selectorNowFakeTag = ""
        }
    }

    var selectorNowId = -114514L
    var selectorNowFakeTag = ""

    suspend fun selectMain(id: Long) {
        access.withLock {
            selectMainLocked(id)
        }
    }

    suspend fun pauseUpdates(work: () -> Unit) {
        access.withLock {
            work()
        }
    }

    suspend fun selectorChanged(id: Long, work: () -> Unit) {
        access.withLock {
            work()
            selectMainLocked(id)
        }
    }

    private fun selectMainLocked(id: Long) {
        if (id == selectorNowId) return
        Logs.d("select traffic count $TAG_PROXY to $id, old id is $selectorNowId")
        val oldData = idMap[selectorNowId]
        val newData = idMap[id] ?: return
        oldData?.apply {
            tag = selectorNowFakeTag
            ignore = true
            if (DataStore.profileTrafficStatistics && DataStore.profileTrafficUpdateInterval > 0) {
                data.proxy?.config?.trafficMap?.get(tag)?.firstOrNull()?.let {
                    it.rx = rx
                    it.tx = tx
                    sc.launch {
                        ProfileManager.updateTraffic(it.id, it.rx, it.tx)
                    }
                }
            }
        }
        selectorNowFakeTag = newData.tag
        selectorNowId = id
        newData.apply {
            tag = TAG_PROXY
            ignore = false
        }
    }

    suspend fun resetTraffic(profileIds: LongArray) {
        val targetIds = profileIds.asSequence().filter { it > 0L }.toHashSet()
        if (targetIds.isEmpty()) return

        val changed = access.withLock {
            trafficUpdater?.updateAll()
            collectTrafficDeltas()

            val updates = linkedMapOf<Long, TrafficData>()
            pendingTrafficIds.forEach { id ->
                if (id !in targetIds) {
                    idMap[id]?.let { item ->
                        updates[id] = TrafficData(id = id, rx = item.rx, tx = item.tx)
                    }
                }
            }
            data.proxy?.config?.trafficMap?.values?.forEach { entities ->
                entities.forEach { entity ->
                    if (entity.id in targetIds) {
                        entity.tx = 0L
                        entity.rx = 0L
                    }
                }
            }
            targetIds.forEach { id ->
                idMap[id]?.apply {
                    tx = 0L
                    rx = 0L
                    txBase = 0L
                    rxBase = 0L
                    txRate = 0L
                    rxRate = 0L
                    hasTrafficDelta = false
                }
                updates[id] = TrafficData(id = id, rx = 0L, tx = 0L)
            }
            pendingTrafficIds.clear()
            updates.values
        }

        ProfileManager.resetTraffic(targetIds.toLongArray())
        broadcastTraffic(changed, foregroundOnly = true)
    }

    private fun collectTrafficDeltas() {
        idMap.forEach { (id, item) ->
            if (id > 0L && item.hasTrafficDelta) pendingTrafficIds += id
        }
    }

    private suspend fun broadcastTraffic(
        updates: Collection<TrafficData>,
        foregroundOnly: Boolean = false,
    ) {
        if (updates.isEmpty()) return
        val batches = updates.chunked(TRAFFIC_BATCH_SIZE).map { TrafficDataBatch(ArrayList(it)) }
        data.binder.broadcast { callback ->
            if (!foregroundOnly || data.binder.callbackIdMap[callback] ==
                SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
            ) {
                batches.forEach(callback::cbTrafficUpdate)
            }
        }
    }

    private suspend fun loop() {
        val speedDelayMs = TrafficLooperPolicy.sanitizeInterval(DataStore.speedInterval.toLong())
        val trafficDelayMs =
            TrafficLooperPolicy.sanitizeInterval(DataStore.profileTrafficUpdateInterval.toLong())
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics && trafficDelayMs > 0
        if (speedDelayMs <= 0L && !profileTrafficStatistics) return
        var lastSpeedUpdate = 0L
        var lastTrafficUpdate = 0L

        val itemBypass = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)

        while (currentCoroutineContext().isActive) {
            val hasForegroundCallback = data.binder.callbackIdMap.containsValue(
                SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND,
            )
            val hasSpeedConsumer = TrafficLooperPolicy.hasSpeedConsumer(
                hasForegroundCallback = hasForegroundCallback,
                notificationListening = data.notification?.listenPostSpeed == true,
            )
            val delayMs = TrafficLooperPolicy.nextDelay(
                speedDelayMs = speedDelayMs,
                trafficDelayMs = trafficDelayMs,
                profileTrafficStatistics = profileTrafficStatistics,
                hasSpeedConsumer = hasSpeedConsumer,
            ) ?: return
            val now = SystemClock.elapsedRealtime()
            val shouldPostSpeed = hasSpeedConsumer &&
                TrafficLooperPolicy.isDue(speedDelayMs, lastSpeedUpdate, now)
            val shouldPostTraffic = profileTrafficStatistics &&
                TrafficLooperPolicy.isDue(trafficDelayMs, lastTrafficUpdate, now)
            val shouldDeliverTraffic = shouldPostTraffic && hasForegroundCallback &&
                    data.state == BaseService.State.Connected
            if (!shouldPostSpeed && !shouldPostTraffic) {
                delay(delayMs)
                continue
            }

            val proxy = data.proxy
            if (proxy == null || !proxy.isInitialized()) {
                delay(delayMs)
                continue
            }

            val snapshot = access.withLock {
                if (trafficUpdater == null) {
                    idMap.clear()
                    tagMap.clear()
                    pendingTrafficIds.clear()
                    idMap[-1] = itemBypass
                    val tags = hashSetOf(TAG_PROXY, TAG_BYPASS)
                    proxy.config.trafficMap.forEach { (tag, entities) ->
                        tags += tag
                        for (entity in entities) {
                            val item = TrafficUpdater.TrafficLooperData(
                                tag = tag,
                                rx = entity.rx,
                                tx = entity.tx,
                                rxBase = entity.rx,
                                txBase = entity.tx,
                                ignore = proxy.config.selectorGroupId >= 0L,
                            )
                            idMap[entity.id] = item
                            tagMap[tag] = item
                            Logs.d("traffic count $tag to ${entity.id}")
                        }
                    }
                    if (proxy.config.selectorGroupId >= 0L) {
                        selectMainLocked(proxy.config.mainEntId)
                    }
                    trafficUpdater = TrafficUpdater(proxy.box, idMap.values.toList())
                    proxy.box.setV2rayStats(tags.joinToString("\n"))
                }

                trafficUpdater!!.updateAll()
                collectTrafficDeltas()

                var mainTxRate = 0L
                var mainRxRate = 0L
                var mainTx = 0L
                var mainRx = 0L
                tagMap.values.forEach { item ->
                    if (!item.ignore) {
                        mainTxRate += item.txRate
                        mainRxRate += item.rxRate
                    }
                    mainTx += item.tx - item.txBase
                    mainRx += item.rx - item.rxBase
                }

                val trafficUpdates = arrayListOf<TrafficData>()
                if (shouldDeliverTraffic) {
                    pendingTrafficIds.forEach { id ->
                        idMap[id]?.let { item ->
                            trafficUpdates += TrafficData(id = id, rx = item.rx, tx = item.tx)
                        }
                    }
                    pendingTrafficIds.clear()
                }
                LoopSnapshot(
                    speed = SpeedDisplayData(
                        mainTxRate,
                        mainRxRate,
                        if (showDirectSpeed) itemBypass.txRate else 0L,
                        if (showDirectSpeed) itemBypass.rxRate else 0L,
                        mainTx,
                        mainRx,
                    ),
                    trafficUpdates = trafficUpdates,
                )
            }
            currentCoroutineContext().ensureActive()

            if (data.state == BaseService.State.Connected && hasForegroundCallback) {
                data.binder.broadcast { callback ->
                    if (data.binder.callbackIdMap[callback] ==
                        SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
                    ) {
                        if (shouldPostSpeed) callback.cbSpeedUpdate(snapshot.speed)
                        if (snapshot.trafficUpdates.isNotEmpty()) {
                            snapshot.trafficUpdates.chunked(TRAFFIC_BATCH_SIZE).forEach {
                                callback.cbTrafficUpdate(TrafficDataBatch(ArrayList(it)))
                            }
                        }
                    }
                }
            }
            if (shouldPostSpeed) lastSpeedUpdate = now
            if (shouldPostTraffic) lastTrafficUpdate = now

            if (shouldPostSpeed && data.notification?.listenPostSpeed == true) {
                data.notification?.postNotificationSpeedUpdate(snapshot.speed)
            }
            delay(delayMs)
        }
    }
}
