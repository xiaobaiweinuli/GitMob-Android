package com.gitmob.app.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gitmob.app.R
import kotlinx.coroutines.launch

/** Whole-row long press, live placement, and a single write when the drag completes. */
@Composable
internal fun <T> LibraryReorderList(
    source: List<T>, key: (T) -> String, movable: (T) -> Boolean,
    editing: Boolean, busy: Boolean, modifier: Modifier = Modifier,
    commit: (List<String>, (Boolean) -> Unit) -> Unit,
    grouped: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    row: @Composable LazyItemScope.(T, Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val edgePx = with(LocalDensity.current) { 88.dp.toPx() }
    var ordered by remember { mutableStateOf(source) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var settling by remember { mutableStateOf<String?>(null) }
    var pendingIds by remember { mutableStateOf<List<String>?>(null) }
    var origin by remember { mutableFloatStateOf(0f) }
    var distance by remember { mutableFloatStateOf(0f) }
    var dragSize by remember { mutableIntStateOf(0) }
    var releaseTop by remember { mutableFloatStateOf(0f) }
    val settleProgress = remember { Animatable(1f) }
    val latestSource by rememberUpdatedState(source)
    val latestKey by rememberUpdatedState(key)
    val latestMovable by rememberUpdatedState(movable)
    val latestCommit by rememberUpdatedState(commit)
    val latestEnabled by rememberUpdatedState(editing && !busy)
    val headerCount = if (header == null) 0 else 1
    val latestHeaderCount by rememberUpdatedState(headerCount)

    LaunchedEffect(source, dragged, settling, pendingIds) {
        if (pendingIds == source.map(key)) pendingIds = null
        if (dragged == null && settling == null && pendingIds == null) ordered = source
    }
    LaunchedEffect(editing) {
        if (!editing && dragged != null) { dragged = null; ordered = latestSource }
    }

    fun persist(ids: List<String>) {
        pendingIds = ids
        latestCommit(ids) { success ->
            if (!success) { pendingIds = null; ordered = latestSource }
            else if (latestSource.map(latestKey) == ids) pendingIds = null
        }
    }

    fun reorderAtCenter() {
        val id = dragged ?: return
        val center = origin + distance + dragSize / 2f
        val current = ordered.indexOfFirst { latestKey(it) == id }
        if (current < 0) return
        val layout = listState.layoutInfo
        // Wait for the previous order to reach layout before evaluating another crossing.
        if (layout.visibleItemsInfo.any { info ->
            val index = ordered.indexOfFirst { latestKey(it) == info.key }
            index >= 0 && info.index != index + latestHeaderCount
        }) return
        val candidates = layout.visibleItemsInfo.filter { info ->
            info.key != id && ordered.any { latestKey(it) == info.key && latestMovable(it) }
        }
        val below = candidates.filter { info ->
            ordered.indexOfFirst { latestKey(it) == info.key } > current && center > info.offset + info.size / 2f
        }.maxByOrNull { it.index }
        val above = candidates.filter { info ->
            ordered.indexOfFirst { latestKey(it) == info.key } < current && center < info.offset + info.size / 2f
        }.minByOrNull { it.index }
        val target = below ?: above ?: return
        val to = ordered.indexOfFirst { latestKey(it) == target.key }
        val firstIndex = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        ordered = ordered.toMutableList().apply { add(to, removeAt(current)) }
        if (current + latestHeaderCount == firstIndex || to + latestHeaderCount == firstIndex) {
            listState.requestScrollToItem(firstIndex, firstOffset)
        }
    }

    fun finishDrag(cancel: Boolean) {
        val id = dragged ?: return
        releaseTop = origin + distance
        settling = id
        // Keep the lifted layer until its offset has reached the final layout position.
        scope.launch {
            settleProgress.snapTo(0f)
            dragged = null
            if (cancel) ordered = latestSource
            else ordered.map(latestKey).takeIf { it != latestSource.map(latestKey) }?.let(::persist)
            try { settleProgress.animateTo(1f, spring(stiffness = 500f, dampingRatio = 1f)) }
            finally { settling = null }
        }
    }

    LaunchedEffect(dragged) {
        if (dragged == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (dragged != null) {
            val now = withFrameNanos { it }
            val layout = listState.layoutInfo
            val center = origin + distance + dragSize / 2f
            val speed = when {
                center < layout.viewportStartOffset + edgePx -> -((layout.viewportStartOffset + edgePx - center) / edgePx).coerceIn(0f, 1f) * 900
                center > layout.viewportEndOffset - edgePx -> ((center - layout.viewportEndOffset + edgePx) / edgePx).coerceIn(0f, 1f) * 900
                else -> 0f
            }
            if (speed != 0f) listState.scrollBy(speed * (now - previous) / 1_000_000_000f)
            reorderAtCenter()
            previous = now
        }
    }
    val before = stringResource(R.string.library_move_before)
    val after = stringResource(R.string.library_move_after)
    val gestures = if (!editing) Modifier else Modifier.pointerInput(listState) {
        detectDragGesturesAfterLongPress(
            onDragStart = { point ->
                if (latestEnabled && settling == null && pendingIds == null) {
                    val layout = listState.layoutInfo
                    val y = point.y - layout.beforeContentPadding
                    val info = layout.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }
                    val item = ordered.firstOrNull { latestKey(it) == info?.key }
                    if (info != null && item != null && latestMovable(item)) {
                        dragged = latestKey(item); origin = info.offset.toFloat(); distance = 0f; dragSize = info.size
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            },
            onDrag = { change, delta -> if (dragged != null) { change.consume(); distance += delta.y; reorderAtCenter() } },
            onDragCancel = { finishDrag(cancel = true) },
            onDragEnd = { finishDrag(cancel = false) },
        )
    }
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val divider = MaterialTheme.colorScheme.outlineVariant
    LazyColumn(
        state = listState, userScrollEnabled = dragged == null && settling == null,
        modifier = modifier.then(gestures), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(if (grouped) 0.dp else 12.dp),
    ) {
        header?.let { item(key = "library_header", contentType = "header") { it() } }
        items(ordered, key = key) { item ->
            val id = key(item)
            val active = id == dragged
            val dropping = id == settling
            val lifted = active || dropping
            val index = ordered.indexOfFirst { key(it) == id }
            val actions = if (editing && movable(item) && !busy && pendingIds == null && dragged == null && settling == null)
                listOf(-1 to before, 1 to after).mapNotNull { (delta, label) ->
                    val target = index + delta
                    if (target !in ordered.indices || !movable(ordered[target])) null
                    else CustomAccessibilityAction(label) {
                        ordered = ordered.toMutableList().apply { add(target, removeAt(index)) }
                        persist(ordered.map(key)); true
                    }
                } else emptyList()
            val shape = if (grouped) RoundedCornerShape(
                topStart = if (index == 0 || lifted) 20.dp else 0.dp,
                topEnd = if (index == 0 || lifted) 20.dp else 0.dp,
                bottomStart = if (index == ordered.lastIndex || lifted) 20.dp else 0.dp,
                bottomEnd = if (index == ordered.lastIndex || lifted) 20.dp else 0.dp,
            ) else CardDefaults.shape
            val itemModifier = Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (lifted) null else spring(stiffness = 500f, dampingRatio = 1f))
                .zIndex(if (lifted) 1f else 0f)
                .graphicsLayer {
                    // Layout changes are observed in drawing, not by every row's composition.
                    translationY = when {
                        active -> origin + distance - (listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset ?: 0)
                        dropping -> (releaseTop - (listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset ?: releaseTop.toInt())) * (1f - settleProgress.value)
                        else -> 0f
                    }
                    shadowElevation = if (lifted) 8.dp.toPx() else 0f
                    this.shape = shape
                }
                .then(if (grouped) Modifier.clip(shape).background(surface).drawBehind {
                    if (index != ordered.lastIndex && !lifted) drawLine(divider, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                } else Modifier)
                .semantics { customActions = actions }
            row(item, itemModifier)
        }
        footer?.let { item(key = "library_footer", contentType = "footer") { it() } }
    }
}
