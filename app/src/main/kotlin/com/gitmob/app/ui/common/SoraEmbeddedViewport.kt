package com.gitmob.app.ui.common

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Constraints
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MAX_EMBEDDED_PAGE_HEIGHT = 1 shl 22

/** The page scrolls a bounded coordinate space; Sora draws the corresponding document window. */
@Composable
internal fun SoraEmbeddedViewport(
    modifier: Modifier,
    minLines: Int,
    content: @Composable (Modifier, SoraEmbeddedState) -> Unit,
) {
    val state = remember { SoraEmbeddedState() }
    val root = LocalView.current
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    DisposableEffect(state, requester) {
        var bringJob: Job? = null
        state.reveal = { rect ->
            bringJob?.cancel()
            bringJob = scope.launch {
                // Content height is published during Sora drawing. Let the page measure that
                // height before asking its scroll container to reveal the new caret position.
                withFrameNanos { }
                requester.bringIntoView(rect)
            }
        }
        onDispose { bringJob?.cancel(); state.reveal = null }
    }
    state.minLines = minLines
    Layout(
        modifier = modifier.bringIntoViewRequester(requester).onGloballyPositioned { coordinates ->
            // Clipping ancestors include the page, bottom sheet and IME-resized content area.
            val clipped = coordinates.boundsInWindow()
            val top = coordinates.positionInWindow().y
            val page = generateSequence(coordinates.parentLayoutCoordinates) { it.parentLayoutCoordinates }
                .firstOrNull { it.size.height in 1..root.height }
            state.visibleHeight = (page?.boundsInWindow()?.height ?: root.height.toFloat()).roundToInt().coerceAtLeast(1)
            state.windowTop = (clipped.top - top).roundToInt().coerceAtLeast(0)
        },
        content = { content(Modifier, state) },
    ) { measurables, constraints ->
        val height = constraints.constrainHeight(state.contentHeight)
        // Never pass the document height to AndroidView: Constraints and native drawing both
        // require a bounded physical viewport even when the logical document is very tall.
        val windowHeight = minOf(height, root.height.coerceAtLeast(1))
        state.measureWindow(height, windowHeight)
        val child = measurables.single().measure(Constraints.fixed(constraints.maxWidth, windowHeight))
        layout(child.width, height) {
            val top = state.windowTop.coerceIn(0, (height - child.height).coerceAtLeast(0))
            state.moveWindow(top)
            child.placeRelative(0, top)
        }
    }
}

internal class SoraEmbeddedState {
    var contentHeight by mutableIntStateOf(1)
        private set
    var windowTop by mutableIntStateOf(0)
    var visibleHeight = 1
    var minLines = 1
    var reveal: ((Rect) -> Unit)? = null
    private var editor: CodeEditor? = null
    private var offset = 0
    private var documentHeight = 1
    private var pageHeight = 1
    private var viewportHeight = 1
    private var measuredContentHeight = 0
    private var pendingPosition: Pair<Int, Int>? = null
    private var pendingScroll: Int? = null

    fun update(view: CodeEditor) {
        editor = view
        val height = maxOf(view.layout.layoutHeight, minLines * view.rowHeight, 1)
        documentHeight = height
        // Compose layout sizes have a 24-bit limit, independent of AndroidView constraints.
        // Reserve room for surrounding page chrome and map the entire document into this space.
        val measuredHeight = height.coerceAtMost(MAX_EMBEDDED_PAGE_HEIGHT)
        if (contentHeight != measuredHeight) contentHeight = measuredHeight
        if (measuredContentHeight != contentHeight) return
        offset = documentOffset(windowTop.coerceIn(0, (pageHeight - viewportHeight).coerceAtLeast(0)))
        syncScroll(view)
        pendingScroll?.let { target ->
            pendingScroll = null
            val top = pageOffset(target)
            reveal?.invoke(Rect(0f, top.toFloat(), view.width.toFloat(), (top + visibleHeight).toFloat()))
        }
        pendingPosition?.let { (requestedLine, requestedColumn) ->
            pendingPosition = null
            val line = requestedLine.coerceIn(0, view.text.lineCount - 1)
            val column = requestedColumn.coerceIn(0, view.text.getColumnCount(line))
            val bottom = view.layout.getCharLayoutOffset(line, column)[0]
            val top = (bottom - view.rowHeight).coerceAtLeast(0f)
            if (documentHeight <= pageHeight) {
                reveal?.invoke(Rect(0f, top, view.width.toFloat(), bottom + view.rowHeight))
            } else {
                // Reveal a complete page window. Scaling only the caret rectangle would leave
                // the caret outside the native window after the parent scrolls it into view.
                val visible = visibleHeight.coerceAtMost(viewportHeight)
                val currentTop = offset + (windowTop - (pageHeight - viewportHeight).coerceAtLeast(0)).coerceAtLeast(0)
                val target = when {
                    top < currentTop -> top.roundToInt()
                    bottom + view.rowHeight > currentTop + visible -> (bottom + view.rowHeight - visible).roundToInt()
                    else -> currentTop
                }.coerceIn(0, (documentHeight - visible).coerceAtLeast(0))
                val nativeTop = target.coerceAtMost((documentHeight - viewportHeight).coerceAtLeast(0))
                val pageTop = pageOffset(nativeTop) + target - nativeTop
                reveal?.invoke(Rect(0f, pageTop.toFloat(), view.width.toFloat(), (pageTop + visible).toFloat()))
            }
        }
    }

    fun measureWindow(height: Int, windowHeight: Int) {
        pageHeight = height
        viewportHeight = windowHeight
        measuredContentHeight = contentHeight
    }

    private fun documentOffset(top: Int): Int = mapOffset(top, pageHeight, documentHeight)
    private fun pageOffset(top: Int): Int = mapOffset(top, documentHeight, pageHeight)

    private fun mapOffset(top: Int, fromHeight: Int, toHeight: Int): Int {
        val fromRange = (fromHeight - viewportHeight).coerceAtLeast(0)
        val toRange = (toHeight - viewportHeight).coerceAtLeast(0)
        if (fromRange == 0) return 0
        return (top.toDouble().coerceIn(0.0, fromRange.toDouble()) * toRange / fromRange).roundToInt()
    }

    fun moveWindow(top: Int) {
        offset = documentOffset(top)
        editor?.let { syncScroll(it) }
    }

    fun syncScroll(view: CodeEditor) {
        if (view.offsetY == offset) return
        view.scroller.startScroll(view.offsetX, offset, 0, 0, 0)
        view.scroller.abortAnimation()
        view.invalidate()
    }

    // Reflow changes a logical line's pixel offset. Move the owning page to that new offset.
    fun restoreScroll(view: CodeEditor, top: Int) {
        pendingScroll = top
        update(view)
        view.invalidate()
    }

    fun revealPosition(view: CodeEditor, line: Int, column: Int) {
        pendingPosition = line to column
        view.invalidate()
    }
}
