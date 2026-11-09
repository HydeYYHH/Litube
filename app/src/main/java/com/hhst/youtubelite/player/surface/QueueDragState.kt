package com.hhst.youtubelite.player.surface

import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hhst.youtubelite.player.QueueItem
import com.hhst.youtubelite.player.QueueOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class QueueDragState(
    val list: LazyListState,
    private val scope: CoroutineScope,
    initialItems: List<QueueItem>,
) {
    var items by mutableStateOf(initialItems)
        private set
    var draggingKey by mutableStateOf<String?>(null)
        private set
    var pointerY by mutableFloatStateOf(Float.NaN)
        private set
    private var grabOffset = 0f
    private var draggedHeight = 0
    private var originalOrder = emptyList<String>()
    private var settlingKey by mutableStateOf<String?>(null)
    private var settlingTop = 0f
    private var settlingFraction by mutableFloatStateOf(0f)
    private var settleJob: Job? = null
    private var lastDragEndAt = 0L
    /** Queue owner captured at drag start; an identity switch cancels the commit. */
    private var dragOwner: QueueOwner? = null

    fun canTap() = draggingKey == null && SystemClock.uptimeMillis() - lastDragEndAt >= GestureMath.TAP_SUPPRESS_MS

    fun synchronize(source: List<QueueItem>) {
        if (draggingKey == null) items = source
    }

    fun start(y: Float, owner: QueueOwner?): Boolean {
        val row = list.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?: return false
        val key = row.key as? String ?: return false
        settleJob?.cancel()
        settlingKey = null
        originalOrder = items.map { it.videoId }
        grabOffset = y - row.offset
        draggedHeight = row.size
        pointerY = y
        draggingKey = key
        dragOwner = owner
        return true
    }

    fun dragBy(delta: Float) { pointerY += delta }

    fun translation(key: String): Float = when (key) {
        draggingKey -> list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
            ?.let { pointerY - grabOffset - it.offset } ?: 0f
        settlingKey -> list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
            ?.let { (settlingTop - it.offset) * (1f - settlingFraction) } ?: 0f
        else -> 0f
    }

    fun isRaised(key: String) = key == draggingKey || key == settlingKey

    fun reorderVisible() {
        val key = draggingKey ?: return
        val from = items.indexOfFirst { it.videoId == key }
        val info = list.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        // Wait for the previous move to be measured before choosing another target.
        if (row.index != from) return
        val center = pointerY - grabOffset + draggedHeight / 2f
        val target = if (center > row.offset + row.size / 2f) {
            info.visibleItemsInfo.lastOrNull { it.index > from && center > it.offset + it.size / 2f }
        } else {
            info.visibleItemsInfo.firstOrNull { it.index < from && center < it.offset + it.size / 2f }
        } ?: return
        val first = list.firstVisibleItemIndex
        val scroll = list.firstVisibleItemScrollOffset
        items = items.toMutableList().apply { add(target.index, removeAt(from)) }
        list.requestScrollToItem(first, scroll)
    }

    /**
     * [owner] is the queue owner at release time: a mismatch with drag-start
     * means the identity switched mid-drag, so the commit is cancelled —
     * the dragged index never lands in another owner's list.
     */
    fun finish(source: List<QueueItem>, owner: QueueOwner?, cancelled: Boolean, onMove: (Int, Int, QueueOwner?) -> Unit) {
        val key = draggingKey ?: return
        val releaseTop = pointerY - grabOffset
        val changed = items.map { it.videoId } != originalOrder
        val move = if (!cancelled && changed && dragOwner == owner) queueMoveAgainst(source, items, key) else null
        val first = list.firstVisibleItemIndex
        val scroll = list.firstVisibleItemScrollOffset
        if (move != null) {
            items = source.toMutableList().apply { add(move.second, removeAt(move.first)) }
        } else items = source
        list.requestScrollToItem(first, scroll)
        settlingKey = key
        settlingTop = releaseTop
        settlingFraction = 0f
        lastDragEndAt = SystemClock.uptimeMillis()
        draggingKey = null
        pointerY = Float.NaN
        if (move != null) onMove(move.first, move.second, dragOwner)
        settleJob = scope.launch {
            animate(0f, 1f, animationSpec = spring(stiffness = 650f, dampingRatio = 1f)) { value, _ ->
                settlingFraction = value
            }
            settlingKey = null
        }
    }
}

/** Rebase a drop against surviving neighbors when the source changes during a drag. */
internal fun queueMoveAgainst(source: List<QueueItem>, reordered: List<QueueItem>, key: String): Pair<Int, Int>? {
    val from = source.indexOfFirst { it.videoId == key }
    val at = reordered.indexOfFirst { it.videoId == key }
    if (from < 0 || at < 0) return null
    val without = source.filterNot { it.videoId == key }
    val next = reordered.drop(at + 1).firstNotNullOfOrNull { item ->
        without.indexOfFirst { it.videoId == item.videoId }.takeIf { it >= 0 }
    }
    val previous = reordered.take(at).asReversed().firstNotNullOfOrNull { item ->
        without.indexOfFirst { it.videoId == item.videoId }.takeIf { it >= 0 }
    }
    val to = next ?: previous?.plus(1) ?: return null
    return (from to to).takeIf { from != to }
}
