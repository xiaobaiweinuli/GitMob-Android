package com.gitmob.app.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.LayoutStateChangeEvent
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.EditorRenderer
import io.github.rosemoe.sora.lang.styling.Styles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal data class SoraViewerDocument(
    val content: Content,
    val lineLabels: List<String>? = null,
    val styles: Styles? = null,
)

@Composable
internal fun SoraTextViewer(
    text: String,
    wrapLines: Boolean,
    state: SoraViewerState,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    lineNumbersAtStart: Boolean = false,
    showLineNumbers: Boolean = true,
) = SoraTextViewer(text, wrapLines, state, modifier, embedded, lineNumbersAtStart, showLineNumbers) { SoraViewerDocument(Content(text)) }

/** Content construction and transformations run outside the UI thread. */
@Composable
internal fun SoraTextViewer(
    documentKey: Any,
    wrapLines: Boolean,
    state: SoraViewerState,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    lineNumbersAtStart: Boolean = false,
    showLineNumbers: Boolean = true,
    prepareDocument: () -> SoraViewerDocument,
) {
    key(state.documentId, documentKey) {
        val document by produceState<SoraViewerDocument?>(null) {
            value = withContext(Dispatchers.Default) {
                prepareDocument().also { it.content.isUndoEnabled = false }
            }
        }
        val colors = MaterialTheme.colorScheme
        val textSize = with(LocalDensity.current) { 14.sp.toPx() }
        val lineHeight = with(LocalDensity.current) { 24.sp.toPx() }
        Box(modifier, contentAlignment = Alignment.Center) {
            val ready = document
            if (ready == null) CircularProgressIndicator()
            else {
                val view: @Composable (Modifier, SoraEmbeddedState?) -> Unit = { viewModifier, embeddedState ->
                    AndroidView(
                        modifier = viewModifier,
                        factory = { context -> SoraViewerView(context, state, ready.content, ready.lineLabels, ready.styles) },
                        update = { editor ->
                            editor.lineNumberAlign = if (lineNumbersAtStart) Paint.Align.LEFT else Paint.Align.RIGHT
                            editor.embeddedState = embeddedState
                            editor.isVerticalScrollBarEnabled = embeddedState == null
                            editor.configure(wrapLines, showLineNumbers, textSize, lineHeight)
                            editor.applySoraColors(colors, readOnly = true)
                        },
                        onRelease = { it.dispose() },
                    )
                }
                if (embedded) SoraEmbeddedViewport(Modifier, minLines = 1) { m, s -> view(m, s) }
                else view(Modifier.fillMaxSize(), null)
            }
        }
    }
}

/** Only view lifecycle and logical reading state are adapted; text rendering stays inside Sora. */
// Created programmatically with document state; not inflated from XML.
@SuppressLint("ViewConstructor")
internal class SoraViewerView(
    context: Context,
    private val readingState: SoraViewerState,
    content: Content,
    private val lineLabels: List<String>? = null,
    documentStyles: Styles? = null,
) : SoraDocumentView(context) {
    internal var embeddedState: SoraEmbeddedState? = null
    private val lineLabelSample = lineLabels?.maxByOrNull { it.length }?.let { "0".repeat(it.length) }
    private var pendingPosition: SourceReadingPosition? = readingState.position
    private var layoutBusy = true
    private var hasDrawnContent = false
    private var restoreEmbeddedPosition = false
    private var configuredTextSize = 0f
    private var configuredLineHeight = 0f
    private val restorePosition = Runnable { restoreWhenReady() }

    init {
        setEditable(false)
        isUndoEnabled = false
        isLineNumberEnabled = true
        setPinLineNumber(true)
        isFirstLineNumberAlwaysVisible = lineLabels == null
        typefaceText = Typeface.MONOSPACE
        typefaceLineNumber = Typeface.MONOSPACE
        isHighlightCurrentLine = false
        isHighlightCurrentBlock = false
        isHighlightBracketPair = false
        isCursorAnimationEnabled = false
        isScalable = false
        props.adjustToSelectionOnResize = false
        subscribeEvent(LayoutStateChangeEvent::class.java) { event, _ ->
            layoutBusy = event.isLayoutBusy
            if (layoutBusy) hasDrawnContent = false
            if (!layoutBusy) scheduleRestore()
        }
        setText(content, true, null)
        if (documentStyles != null) styles = documentStyles
        readingState.readPosition = ::capturePosition
    }

    // Diff labels use Sora's visible-row traversal; layout, wrapping and selection stay native.
    override fun measureLineNumber(): Float {
        val sample = lineLabelSample ?: return super.measureLineNumber()
        return otherPaint.measureText(sample) + lineNumberMarginLeft
    }

    override fun onCreateRenderer(): EditorRenderer = object : EditorRenderer(this) {
        override fun drawLineNumber(canvas: Canvas, line: Int, row: Int, offsetX: Float, width: Float, color: Int) {
            val labels = lineLabels
            if (labels == null) { super.drawLineNumber(canvas, line, row, offsetX, width, color); return }
            val label = labels.getOrNull(line) ?: return
            val paint = paintOther
            paint.color = color
            paint.textAlign = lineNumberAlign
            val metrics = lineNumberMetrics
            val y = (getRowBottom(row) + getRowTop(row)) / 2f - (metrics.descent - metrics.ascent) / 2f - metrics.ascent - offsetY
            canvas.drawText(label, if (lineNumberAlign == Paint.Align.LEFT) offsetX else offsetX + width, y, paint)
        }
    }

    fun configure(wrapLines: Boolean, lineNumbers: Boolean, textSizePx: Float, lineHeightPx: Float) {
        if (isWordwrap == wrapLines && isLineNumberEnabled == lineNumbers && configuredTextSize == textSizePx && configuredLineHeight == lineHeightPx) return
        restoreEmbeddedPosition = restoreEmbeddedPosition || hasDrawnContent
        pendingPosition = capturePosition()
        isLineNumberEnabled = lineNumbers
        if (configuredTextSize != textSizePx || configuredLineHeight != lineHeightPx) {
            setTextSizePx(textSizePx)
            setLineSpacing((lineHeightPx - rowHeightOfText).coerceAtLeast(0f), 1f)
            configuredTextSize = textSizePx
            configuredLineHeight = lineHeightPx
        }
        // Character wrapping avoids repeated word-boundary scans on minified source.
        setWordwrap(wrapLines, false)
        scheduleRestore()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // A width change rebuilds Sora's wrapped rows asynchronously.
        if (oldw > 0 && oldh > 0 && !isReleased) {
            restoreEmbeddedPosition = restoreEmbeddedPosition || (w != oldw && hasDrawnContent)
            pendingPosition = capturePosition()
        }
        super.onSizeChanged(w, h, oldw, oldh)
        scheduleRestore()
    }

    override fun onDraw(canvas: Canvas) {
        if (!layoutBusy) embeddedState?.update(this)
        super.onDraw(canvas)
        if (!layoutBusy) {
            hasDrawnContent = true
            if (pendingPosition != null) scheduleRestore()
        }
    }

    private fun scheduleRestore() {
        removeCallbacks(restorePosition)
        post(restorePosition)
    }

    internal fun capturePosition(): SourceReadingPosition {
        pendingPosition?.let { return it }
        if (layoutBusy || layout.rowCount == 0) return readingState.position
        val row = layout.getRowAt(firstVisibleRow.coerceIn(0, layout.rowCount - 1))
        val cursor = cursor
        return SourceReadingPosition(
            line = row.lineIndex,
            column = row.startColumn,
            rowFraction = (offsetY % rowHeight).toFloat() / rowHeight,
            horizontalDp = if (isWordwrap) readingState.position.horizontalDp else offsetX / resources.displayMetrics.density,
            selectionStartLine = cursor.leftLine,
            selectionStartColumn = cursor.leftColumn,
            selectionEndLine = cursor.rightLine,
            selectionEndColumn = cursor.rightColumn,
        ).also { readingState.position = it }
    }

    private fun restoreWhenReady() {
        val p = pendingPosition ?: return
        if (isReleased || layoutBusy || !hasDrawnContent || width == 0 || height == 0) return
        fun line(value: Int) = value.coerceIn(0, text.lineCount - 1)
        fun column(line: Int, value: Int) = value.coerceIn(0, text.getColumnCount(line))
        val left = line(p.selectionStartLine)
        val right = line(p.selectionEndLine)
        restoreSelection(left, column(left, p.selectionStartColumn), right, column(right, p.selectionEndColumn))
        val anchorLine = line(p.line)
        val anchorIndex = text.getCharIndex(anchorLine, column(anchorLine, p.column))
        val row = layout.getRowIndexForPosition(anchorIndex)
        val y = ((row + p.rowFraction) * rowHeight).roundToInt().coerceIn(0, scrollMaxY)
        val x = if (isWordwrap) 0 else (p.horizontalDp * resources.displayMetrics.density).roundToInt().coerceIn(0, scrollMaxX)
        scroller.startScroll(x, y, 0, 0, 0)
        scroller.abortAnimation()
        pendingPosition = null
        embeddedState?.let { if (restoreEmbeddedPosition) it.restoreScroll(this, y) else it.syncScroll(this) }
        restoreEmbeddedPosition = false
        invalidate()
    }

    override fun ensurePositionVisible(line: Int, column: Int, noAnimation: Boolean) {
        super.ensurePositionVisible(line, column, noAnimation)
        embeddedState?.revealPosition(this, line, column)
    }

    fun dispose() {
        readingState.position = capturePosition()
        readingState.readPosition = null
        removeCallbacks(restorePosition)
        release()
    }
}
