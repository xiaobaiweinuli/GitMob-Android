package com.gitmob.app.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.InputType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.gitmob.app.R
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.LayoutStateChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlin.math.roundToInt

/** UI-scoped commands synchronize toolbar changes with Sora's input connection. */
class SoraEditorController internal constructor(private val state: SoraEditorState) {
    internal var view: SoraEditingView? = null
    fun edit(action: (SoraEditorState) -> Unit) {
        if (view?.isEditable == false) return
        action(state)
        view?.requestFocus()
        view?.syncSelection()
    }
    fun undo() {
        if (view?.isEditable == false) return
        view?.undo() ?: state.undo()
    }
    fun redo() {
        if (view?.isEditable == false) return
        view?.redo() ?: state.redo()
    }
}

@Composable
fun rememberSoraEditorController(state: SoraEditorState): SoraEditorController =
    remember(state) { SoraEditorController(state) }

@Composable
fun SoraTextEditor(
    state: SoraEditorState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showLineNumbers: Boolean = true,
    wrapLines: Boolean = true,
    controller: SoraEditorController = rememberSoraEditorController(state),
    embedded: Boolean = false,
    minLines: Int = 1,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val textSize = with(density) { 14.sp.toPx() }
    val imeBottom = WindowInsets.ime.getBottom(density)
    var focused by remember(state) { mutableStateOf(false) }
    LaunchedEffect(controller, focused, imeBottom) {
        if (focused) {
            // An embedded native view keeps its height when the page is resized by the IME.
            withFrameNanos { }
            controller.view?.takeIf { it.isFocused && !it.isReleased }?.ensureSelectionVisible()
        }
    }
    key(state) {
        val view: @Composable (Modifier, SoraEmbeddedState?) -> Unit = { viewModifier, embeddedState ->
            AndroidView(
                modifier = viewModifier,
                factory = { context -> SoraEditingView(context, state).also { controller.view = it } },
                update = { editor ->
                    editor.embeddedState = embeddedState
                    editor.focusChanged = { focused = it; onFocusChanged(it) }
                    editor.isVerticalScrollBarEnabled = embeddedState == null
                    editor.isHighlightCurrentLine = embeddedState == null
                    editor.setEditable(enabled)
                    editor.configure(wrapLines, showLineNumbers, textSize)
                    editor.syncSelection()
                    editor.applySoraColors(colors)
                },
                onRelease = { editor ->
                    if (controller.view === editor) controller.view = null
                    editor.dispose()
                },
            )
        }
        if (embedded) SoraEmbeddedViewport(modifier, minLines) { m, s -> view(m, s) }
        else view(modifier.fillMaxSize(), null)
    }
}

/** Draw field chrome without measuring a border child to the height of a huge document. */
@Composable
internal fun SoraTextField(
    state: SoraEditorState,
    enabled: Boolean,
    showLineNumbers: Boolean,
    label: @Composable () -> Unit,
    placeholder: (@Composable () -> Unit)? = null,
    minLines: Int = 4,
) {
    var focused by remember { mutableStateOf(false) }
    var labelHeight by remember { mutableIntStateOf(0) }
    val colors = MaterialTheme.colorScheme
    val borderColor = when {
        !enabled -> colors.onSurface.copy(alpha = 0.12f)
        focused -> colors.primary
        else -> colors.outline
    }
    Layout(
        modifier = Modifier.fillMaxWidth().drawBehind {
            val stroke = (if (focused && enabled) 2.dp else 1.dp).toPx()
            val top = labelHeight / 2f
            drawRoundRect(
                borderColor, Offset(stroke / 2, top + stroke / 2),
                Size(size.width - stroke, (size.height - top - stroke).coerceAtLeast(0f)),
                CornerRadius(4.dp.toPx()), style = Stroke(stroke),
            )
        },
        content = {
            Box(Modifier.background(colors.surface).padding(horizontal = 4.dp)) {
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodySmall,
                    LocalContentColor provides if (focused && enabled) colors.primary else colors.onSurfaceVariant,
                ) { label() }
            }
            Box {
                SoraTextEditor(
                    state = state, modifier = Modifier.fillMaxWidth(), enabled = enabled,
                    showLineNumbers = showLineNumbers, embedded = true, minLines = minLines,
                    onFocusChanged = { focused = it },
                )
                if (state.text.isEmpty()) {
                    CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) { placeholder?.invoke() }
                }
            }
        },
    ) { children, constraints ->
        val padding = 16.dp.roundToPx()
        val width = constraints.maxWidth
        val innerWidth = (width - 2 * padding).coerceAtLeast(0)
        val labelPlaceable = children[0].measure(Constraints(maxWidth = innerWidth))
        labelHeight = labelPlaceable.height
        val top = labelPlaceable.height + 8.dp.roundToPx()
        val editor = children[1].measure(Constraints(minWidth = innerWidth, maxWidth = innerWidth))
        val height = constraints.constrainHeight(top + editor.height + padding)
        layout(width, height) {
            editor.placeRelative(padding, top)
            labelPlaceable.placeRelative(padding - 4.dp.roundToPx(), 0)
        }
    }
}

/** Restoring selection must not move focus from another field or open an input session. */
internal open class SoraDocumentView(context: Context) : CodeEditor(context) {
    private var restoringSelection = false

    fun restoreSelection(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int, scroll: Boolean = false) {
        restoringSelection = true
        try {
            setSelectionRegion(startLine, startColumn, endLine, endColumn, scroll)
        } finally {
            restoringSelection = false
        }
    }

    override fun requestFocus(direction: Int, previouslyFocusedRect: android.graphics.Rect?): Boolean =
        if (restoringSelection) isFocused else super.requestFocus(direction, previouslyFocusedRect)
}

internal class SoraEditingView(context: Context, private val state: SoraEditorState) : SoraDocumentView(context) {
    internal var embeddedState: SoraEmbeddedState? = null
    internal var focusChanged: ((Boolean) -> Unit)? = null
    private var layoutBusy = true
    private var hasDrawnContent = false
    private var restoreEmbeddedPosition = false
    private var appliedSelectionRequest = -1
    private var applyingSelection = false
    private var pendingViewport: SoraEditorViewport? = state.viewport
    private var configuredTextSize = 0f
    private val restore = Runnable { restoreWhenReady() }

    init {
        typefaceText = Typeface.MONOSPACE
        typefaceLineNumber = Typeface.MONOSPACE
        setPinLineNumber(true)
        isScalable = false
        isCursorAnimationEnabled = false
        isHighlightCurrentBlock = false
        isHighlightBracketPair = false
        subscribeEvent(LayoutStateChangeEvent::class.java) { event, _ ->
            layoutBusy = event.isLayoutBusy
            if (layoutBusy) hasDrawnContent = false
            if (!layoutBusy) scheduleRestore()
        }
        setText(state.content, true, null)
        subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
            if (!applyingSelection && appliedSelectionRequest == state.selectionRequest) state.recordSelection(TextRange(cursor.left, cursor.right))
        }
    }

    fun configure(wrapLines: Boolean, lineNumbers: Boolean, textSizePx: Float) {
        if (isWordwrap == wrapLines && isLineNumberEnabled == lineNumbers && configuredTextSize == textSizePx) return
        restoreEmbeddedPosition = restoreEmbeddedPosition || hasDrawnContent
        pendingViewport = captureViewport()
        isLineNumberEnabled = lineNumbers
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            (if (lineNumbers) InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        if (configuredTextSize != textSizePx) {
            setTextSizePx(textSizePx)
            configuredTextSize = textSizePx
        }
        setWordwrap(wrapLines, false)
        scheduleRestore()
    }

    fun syncSelection() {
        if (appliedSelectionRequest == state.selectionRequest) return
        if (layoutBusy || !hasDrawnContent) { scheduleRestore(); return }
        val selection = state.selection
        val left = text.indexer.getCharPosition(selection.min.coerceIn(0, text.length))
        val right = text.indexer.getCharPosition(selection.max.coerceIn(0, text.length))
        applyingSelection = true
        try {
            restoreSelection(left.line, left.column, right.line, right.column, isFocused && pendingViewport == null)
            if (isFocused) notifyIMEExternalCursorChange()
            appliedSelectionRequest = state.selectionRequest
        } finally { applyingSelection = false }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (oldw > 0 && w != oldw && !isReleased) {
            restoreEmbeddedPosition = restoreEmbeddedPosition || hasDrawnContent
            pendingViewport = captureViewport()
        }
        super.onSizeChanged(w, h, oldw, oldh)
        scheduleRestore()
    }

    override fun onDraw(canvas: Canvas) {
        if (!layoutBusy) embeddedState?.update(this)
        super.onDraw(canvas)
        if (!layoutBusy) {
            hasDrawnContent = true
            if (pendingViewport != null || appliedSelectionRequest != state.selectionRequest) scheduleRestore()
            else state.viewport = captureViewport()
        }
    }

    private fun scheduleRestore() { removeCallbacks(restore); post(restore) }

    private fun captureViewport(): SoraEditorViewport {
        pendingViewport?.let { return it }
        if (layoutBusy || layout.rowCount == 0) return state.viewport
        val row = layout.getRowAt(firstVisibleRow.coerceIn(0, layout.rowCount - 1))
        return SoraEditorViewport(row.lineIndex, row.startColumn,
            (offsetY % rowHeight).toFloat() / rowHeight,
            if (isWordwrap) state.viewport.horizontalDp else offsetX / resources.displayMetrics.density)
    }

    private fun restoreWhenReady() {
        if (isReleased || layoutBusy || !hasDrawnContent || width == 0 || height == 0) return
        syncSelection()
        val position = pendingViewport ?: return
        val line = position.line.coerceIn(0, text.lineCount - 1)
        val index = text.getCharIndex(line, position.column.coerceIn(0, text.getColumnCount(line)))
        val row = layout.getRowIndexForPosition(index)
        val y = ((row + position.rowFraction) * rowHeight).roundToInt().coerceIn(0, scrollMaxY)
        val x = if (isWordwrap) 0 else (position.horizontalDp * resources.displayMetrics.density).roundToInt().coerceIn(0, scrollMaxX)
        scroller.startScroll(x, y, 0, 0, 0)
        scroller.abortAnimation()
        pendingViewport = null
        embeddedState?.let { if (restoreEmbeddedPosition) it.restoreScroll(this, y) else it.syncScroll(this) }
        restoreEmbeddedPosition = false
        state.viewport = captureViewport()
        invalidate()
    }

    override fun ensurePositionVisible(line: Int, column: Int, noAnimation: Boolean) {
        super.ensurePositionVisible(line, column, noAnimation)
        embeddedState?.revealPosition(this, line, column)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        focusChanged?.invoke(gainFocus)
    }

    fun dispose() {
        state.viewport = captureViewport()
        removeCallbacks(restore)
        release()
    }
}

@Composable
internal fun SoraUndoRedoButtons(state: SoraEditorState, controller: SoraEditorController, enabled: Boolean) {
    IconButton(onClick = controller::undo, enabled = enabled && state.canUndo) {
        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.editor_undo))
    }
    IconButton(onClick = controller::redo, enabled = enabled && state.canRedo) {
        Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo))
    }
}

/** Shared palette; reading and editing retain their existing background and current-line styles. */
internal fun CodeEditor.applySoraColors(colors: ColorScheme, readOnly: Boolean = false) {
    val background = if (readOnly) colors.background else colors.surface
    colorScheme.apply {
        setColor(EditorColorScheme.WHOLE_BACKGROUND, background.toArgb())
        setColor(EditorColorScheme.TEXT_NORMAL, colors.onSurface.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, background.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER, colors.onSurfaceVariant.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_CURRENT, (if (readOnly) colors.onSurfaceVariant else colors.primary).toArgb())
        setColor(EditorColorScheme.LINE_DIVIDER, colors.outlineVariant.toArgb())
        if (!readOnly) setColor(EditorColorScheme.CURRENT_LINE, colors.surfaceContainer.toArgb())
        if (!readOnly) setColor(EditorColorScheme.TEXT_SELECTED, colors.onPrimaryContainer.toArgb())
        setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, colors.primaryContainer.toArgb())
        setColor(EditorColorScheme.SELECTION_HANDLE, colors.primary.toArgb())
        setColor(EditorColorScheme.SELECTION_INSERT, colors.primary.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_PANEL, colors.surfaceContainerHigh.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_PANEL_TEXT, colors.onSurface.toArgb())
        setColor(EditorColorScheme.SCROLL_BAR_THUMB, colors.outline.toArgb())
        setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, colors.primary.toArgb())
        setColor(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND, colors.surfaceContainerHigh.toArgb())
        setColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR, colors.onSurface.toArgb())
    }
}
