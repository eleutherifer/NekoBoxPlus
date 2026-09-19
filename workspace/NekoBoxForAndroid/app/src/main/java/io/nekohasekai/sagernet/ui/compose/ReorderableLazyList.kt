package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
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
    private var lastTargetKey: Any? = null
    private var offsetAtLastMove: Int? = null

    val isDragging: Boolean
        get() = draggedKey != null

    fun isDragging(key: Any): Boolean = draggedKey == key

    fun translationY(key: Any): Float {
        if (draggedKey != key) return 0f
        val currentOffset = itemInfo(key)?.offset?.toFloat() ?: lastKnownItemOffset
        return dragStartOffset + dragAmount - currentOffset
    }

    fun startDrag(key: Any) {
        val item = itemInfo(key) ?: return
        draggedKey = key
        dragStartOffset = item.offset.toFloat()
        lastKnownItemOffset = dragStartOffset
        dragAmount = 0f
        lastTargetKey = null
        offsetAtLastMove = null
    }

    fun dragBy(deltaY: Float) {
        val key = draggedKey ?: return
        dragAmount += deltaY
        val draggedItem = itemInfo(key) ?: return
        if (offsetAtLastMove != null && draggedItem.offset != offsetAtLastMove) {
            lastTargetKey = null
            offsetAtLastMove = null
        }
        lastKnownItemOffset = draggedItem.offset.toFloat()
        val translatedTop = draggedItem.offset + translationY(key)
        val translatedBottom = translatedTop + draggedItem.size
        val translatedCenter = (translatedTop + translatedBottom) / 2f

        val targetKey = findReorderTarget(
            draggedKey = key,
            draggedTop = draggedItem.offset,
            draggedSize = draggedItem.size,
            translatedCenter = translatedCenter,
            direction = deltaY,
            items = listState.layoutInfo.visibleItemsInfo.map {
                ReorderItemBounds(it.key, it.offset, it.size)
            },
        )

        if (targetKey != null && targetKey != lastTargetKey) {
            lastTargetKey = targetKey
            offsetAtLastMove = draggedItem.offset
            onMove(key, targetKey)
        }
        autoScroll(translatedTop, translatedBottom)
    }

    fun finishDrag() {
        if (draggedKey == null) return
        draggedKey = null
        dragAmount = 0f
        lastTargetKey = null
        offsetAtLastMove = null
        onMoveFinished()
    }

    private fun itemInfo(key: Any): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    private fun autoScroll(top: Float, bottom: Float) {
        val layout = listState.layoutInfo
        val overflow = when {
            top < layout.viewportStartOffset -> top - layout.viewportStartOffset
            bottom > layout.viewportEndOffset -> bottom - layout.viewportEndOffset
            else -> 0f
        }
        if (overflow != 0f) scope.launch { listState.scrollBy(overflow) }
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
    return remember(listState, scope) {
        ReorderableLazyListState(
            listState = listState,
            scope = scope,
            onMove = { from, to -> currentOnMove(from, to) },
            onMoveFinished = { currentOnMoveFinished() },
        )
    }
}

internal fun Modifier.reorderableItem(
    state: ReorderableLazyListState,
    key: Any,
    enabled: Boolean = true,
): Modifier {
    val visualModifier = zIndex(if (state.isDragging(key)) 1f else 0f)
        .graphicsLayer {
            translationY = state.translationY(key)
            shadowElevation = if (state.isDragging(key)) 8.dp.toPx() else 0f
        }
    if (!enabled) return visualModifier
    return visualModifier.pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.startDrag(key) },
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
