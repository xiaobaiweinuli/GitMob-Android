package com.gitmob.app.ui.repocode

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One selectable text preserves the original payload, including off-screen lines and CRLF. */
@Composable
internal fun RepoFileSource(
    text: String,
    wrapLines: Boolean,
    verticalScrollState: ScrollState,
    horizontalScrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val lineStarts = remember(text) { sourceLineStarts(text) }
    var textLayout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 24.sp,
        textDirection = TextDirection.Ltr,
    )
    val measurer = rememberTextMeasurer()
    val widestNumber = measurer.measure(lineStarts.size.toString(), textStyle)
    val gutterWidth = with(density) { widestNumber.size.width.toDp() } + 24.dp
    val gutterHeight = with(density) { (textLayout?.size?.height ?: 0).toDp() }
    val numberColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val baselines = remember(textLayout, lineStarts) {
        textLayout?.let { layout ->
            FloatArray(lineStarts.size) { layout.getLineBaseline(layout.getLineForOffset(lineStarts[it])) }
        }
    }

    Row(modifier.fillMaxWidth().verticalScroll(verticalScrollState).padding(top = 8.dp, bottom = 24.dp)) {
        // The gutter is drawn outside SelectionContainer, so it never enters copied text.
        Canvas(Modifier.width(gutterWidth).height(gutterHeight)) {
            drawLine(dividerColor, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
            val positions = baselines ?: return@Canvas
            val visibleTop = (verticalScrollState.value - 8.dp.toPx()).coerceAtLeast(0f)
            val visibleBottom = visibleTop + verticalScrollState.viewportSize + textStyle.lineHeight.toPx()
            val found = positions.binarySearch(visibleTop)
            val first = (if (found >= 0) found else -found - 1).coerceAtMost(positions.size)
            for (index in first until positions.size) {
                if (positions[index] > visibleBottom) break
                val number = measurer.measure((index + 1).toString(), textStyle)
                drawText(number, numberColor, Offset(size.width - 12.dp.toPx() - number.size.width, positions[index] - number.firstBaseline))
            }
        }
        Box(Modifier.weight(1f)) {
            SelectionContainer {
                Text(
                    text = text,
                    modifier = (if (wrapLines) Modifier.fillMaxWidth() else Modifier.horizontalScroll(horizontalScrollState))
                        .padding(start = 12.dp, end = 16.dp),
                    style = textStyle,
                    softWrap = wrapLines,
                    onTextLayout = { textLayout = it },
                )
            }
        }
    }
}

internal fun sourceLineStarts(text: String): IntArray = buildList {
    add(0)
    text.forEachIndexed { index, character ->
        if (character == '\n') add(index + 1)
    }
}.toIntArray()
