package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class ReorderItemBounds(val key: Any, val top: Int, val size: Int)

internal fun findReorderTarget(
    draggedKey: Any,
    draggedTop: Int,
    draggedSize: Int,
    translatedCenter: Float,
    direction: Float,
    items: List<ReorderItemBounds>,
): Any? {
    val currentCenter = draggedTop + draggedSize / 2f
    val crossedItems = items.asSequence()
        .filter { it.key != draggedKey }
        .filter { candidate ->
            val center = candidate.top + candidate.size / 2f
            if (direction > 0f) center in currentCenter..translatedCenter
            else center in translatedCenter..currentCenter
        }
    return if (direction > 0f) {
        crossedItems.minByOrNull { it.top + it.size / 2f }?.key
    } else {
        crossedItems.maxByOrNull { it.top + it.size / 2f }?.key
    }
}

internal fun calculateAutoScrollDelta(
    draggedTop: Float,
    draggedBottom: Float,
    viewportStart: Int,
    viewportEnd: Int,
    maxStep: Float,
): Float {
    if (maxStep <= 0f) return 0f
    val overflow = when {
        draggedTop < viewportStart -> draggedTop - viewportStart
        draggedBottom > viewportEnd -> draggedBottom - viewportEnd
        else -> return 0f
    }
    return overflow.coerceIn(-maxStep, maxStep)
}

@Stable
internal class ReorderableLazyListState(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (Any, Any) -> Unit,
    private val onMoveFinished: () -> Unit,
) {
    private var draggedKey by mutableStateOf<Any?>(null)
    private var dragStartOffset by mutableFloatStateOf(0f)
    private var dragAmount by mutableFloatStateOf(0f)
    private var lastKnownItemOffset by mutableFloatStateOf(0f)
    private var draggedItemSize by mutableIntStateOf(0)
    private var lastTargetKey: Any? = null
    private var lastMoveDirection = 0
    private var pinnedHandle: PinnableContainer.PinnedHandle? = null
    private var autoScrollJob: Job? = null

    val isDragging: Boolean
        get() = draggedKey != null

    fun isDragging(key: Any): Boolean = draggedKey == key

    fun translationY(key: Any): Float {
        if (draggedKey != key) return 0f
        val currentOffset = itemInfo(key)?.offset?.toFloat() ?: lastKnownItemOffset
        return dragStartOffset + dragAmount - currentOffset
    }

    fun startDrag(key: Any, pinnableContainer: PinnableContainer?) {
        val item = itemInfo(key) ?: return
        finishDrag()
        draggedKey = key
        dragStartOffset = item.offset.toFloat()
        lastKnownItemOffset = dragStartOffset
        draggedItemSize = item.size
        dragAmount = 0f
        lastTargetKey = null
        lastMoveDirection = 0
        pinnedHandle = pinnableContainer?.pin()
    }

    fun dragBy(deltaY: Float) {
        if (draggedKey == null) return
        dragAmount += deltaY
        updateDraggedItemInfo()
        moveAcrossTarget(deltaY)
        updateAutoScroll()
    }

    fun finishDrag() {
        if (draggedKey == null) return
        val handle = pinnedHandle
        pinnedHandle = null
        autoScrollJob?.cancel()
        autoScrollJob = null
        draggedKey = null
        dragAmount = 0f
        draggedItemSize = 0
        lastTargetKey = null
        lastMoveDirection = 0
        handle?.release()
        onMoveFinished()
    }

    fun dispose() = finishDrag()

    private fun updateDraggedItemInfo() {
        val key = draggedKey ?: return
        itemInfo(key)?.let { item ->
            lastKnownItemOffset = item.offset.toFloat()
            draggedItemSize = item.size
        }
    }

    private fun moveAcrossTarget(direction: Float) {
        val key = draggedKey ?: return
        if (direction == 0f || draggedItemSize <= 0) return
        updateDraggedItemInfo()
        val directionSign = if (direction > 0f) 1 else -1
        if (lastMoveDirection != directionSign) {
            lastTargetKey = null
            lastMoveDirection = directionSign
        }
        val translatedTop = dragStartOffset + dragAmount
        val translatedCenter = translatedTop + draggedItemSize / 2f

        val targetKey = findReorderTarget(
            draggedKey = key,
            draggedTop = lastKnownItemOffset.toInt(),
            draggedSize = draggedItemSize,
            translatedCenter = translatedCenter,
            direction = direction,
            items = listState.layoutInfo.visibleItemsInfo.map {
                ReorderItemBounds(it.key, it.offset, it.size)
            },
        )

        if (targetKey == null) {
            lastTargetKey = null
        } else if (targetKey != lastTargetKey) {
            lastTargetKey = targetKey
            onMove(key, targetKey)
        }
    }

    private fun itemInfo(key: Any): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    private fun updateAutoScroll() {
        val layout = listState.layoutInfo
        val scrollDelta = calculateAutoScrollDelta(
            draggedTop = dragStartOffset + dragAmount,
            draggedBottom = dragStartOffset + dragAmount + draggedItemSize,
            viewportStart = layout.viewportStartOffset,
            viewportEnd = layout.viewportEndOffset,
            maxStep = draggedItemSize / 3f,
        )
        if (scrollDelta == 0f) {
            autoScrollJob?.cancel()
            autoScrollJob = null
            return
        }
        if (autoScrollJob?.isActive == true) return
        val job = scope.launch {
            while (draggedKey != null) {
                val currentLayout = listState.layoutInfo
                val currentDelta = calculateAutoScrollDelta(
                    draggedTop = dragStartOffset + dragAmount,
                    draggedBottom = dragStartOffset + dragAmount + draggedItemSize,
                    viewportStart = currentLayout.viewportStartOffset,
                    viewportEnd = currentLayout.viewportEndOffset,
                    maxStep = draggedItemSize / 3f,
                )
                if (currentDelta == 0f) break
                withFrameNanos { }
                if (draggedKey == null) break
                val consumed = listState.scrollBy(currentDelta)
                if (consumed == 0f) break
                lastKnownItemOffset -= consumed
                moveAcrossTarget(consumed)
            }
        }
        autoScrollJob = job
        job.invokeOnCompletion {
            if (autoScrollJob === job) autoScrollJob = null
        }
    }
}

@Composable
internal fun rememberReorderableLazyListState(
    onMove: (Any, Any) -> Unit,
    onMoveFinished: () -> Unit,
): ReorderableLazyListState {
    val listState = rememberLazyListState()
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnMoveFinished by rememberUpdatedState(onMoveFinished)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = remember(listState, scope) {
        ReorderableLazyListState(
            listState = listState,
            scope = scope,
            onMove = { from, to -> currentOnMove(from, to) },
            onMoveFinished = { currentOnMoveFinished() },
        )
    }
    DisposableEffect(state) {
        onDispose(state::dispose)
    }
    return state
}

@Composable
internal fun Modifier.reorderableItem(
    state: ReorderableLazyListState,
    key: Any,
    enabled: Boolean = true,
): Modifier {
    val pinnableContainer = LocalPinnableContainer.current
    val visualModifier = zIndex(if (state.isDragging(key)) 1f else 0f)
        .graphicsLayer {
            translationY = state.translationY(key)
            shadowElevation = if (state.isDragging(key)) 8.dp.toPx() else 0f
        }
    if (!enabled) return visualModifier
    return visualModifier.pointerInput(key, pinnableContainer) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.startDrag(key, pinnableContainer) },
            onDragEnd = state::finishDrag,
            onDragCancel = state::finishDrag,
            onDrag = { change, amount ->
                change.consume()
                state.dragBy(amount.y)
            },
        )
    }
}

internal fun Modifier.dragTargetOutline(color: Color): Modifier = drawWithContent {
    drawContent()
    val strokeWidth = 3.dp.toPx()
    val inset = strokeWidth / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokeWidth, size.height - strokeWidth),
        cornerRadius = CornerRadius(12.dp.toPx() - inset),
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(
                intervals = floatArrayOf(8.dp.toPx(), 5.dp.toPx()),
            ),
        ),
    )
}
