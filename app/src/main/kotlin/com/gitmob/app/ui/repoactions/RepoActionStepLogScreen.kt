package com.gitmob.app.ui.repoactions

import android.content.ClipData
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.data.model.ActionLogAvailability
import com.gitmob.app.data.model.RepoActionStepLogKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoActionStepLogScreen(
    logKey: RepoActionStepLogKey,
    onBack: () -> Unit,
    viewModel: RepoActionStepLogViewModel = hiltViewModel(),
) {
    LaunchedEffect(logKey) { viewModel.init(logKey) }
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val log = state.log
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val title = log?.stepName ?: stringResource(R.string.actions_logs_title)
    val copyLabel = stringResource(if (log?.truncated == true) R.string.actions_logs_copy_partial else R.string.actions_logs_copy)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            log?.jobName?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp)) }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.actions_logs_context, logKey.stepNumber, logKey.attempt?.toString() ?: stringResource(R.string.actions_logs_unknown_attempt)),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                ActionLogTooltip(stringResource(R.string.actions_logs_refresh)) {
                    IconButton(onClick = viewModel::load, modifier = Modifier.size(48.dp), enabled = !state.isLoading) { Icon(Icons.Default.Refresh, stringResource(R.string.actions_logs_refresh)) }
                }
                ActionLogTooltip(copyLabel) {
                    IconButton(onClick = {
                        val raw = log?.text ?: return@IconButton
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(title, raw)))
                            viewModel.copied()
                        }
                    }, modifier = Modifier.size(48.dp), enabled = !log?.text.isNullOrEmpty() && !state.isLoading) { Icon(Icons.Default.ContentCopy, copyLabel) }
                }
                ActionLogTooltip(stringResource(R.string.actions_logs_wrap)) {
                    IconToggleButton(
                        checked = state.wrapLines, onCheckedChange = viewModel::setWrapLines,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.iconToggleButtonColors(
                            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) { Icon(Icons.AutoMirrored.Filled.WrapText, stringResource(R.string.actions_logs_wrap)) }
                }
            }
            HorizontalDivider()
            if (log?.truncated == true) Text(stringResource(R.string.actions_logs_truncated), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.error != null -> ActionLogMessage(stringResource(actionLogErrorResource(state.error!!)), viewModel::load)
                    log?.availability == ActionLogAvailability.WAITING -> ActionLogMessage(stringResource(R.string.actions_logs_waiting), viewModel::load)
                    log?.availability == ActionLogAvailability.SKIPPED -> ActionLogMessage(stringResource(R.string.actions_logs_skipped))
                    log == null || log.text.isEmpty() -> ActionLogMessage(stringResource(R.string.actions_logs_empty), viewModel::load)
                    else -> ActionLogContent(log.text, state.wrapLines, listState)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionLogTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}

@Composable
private fun ActionLogMessage(message: String, retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message)
        if (retry != null) TextButton(onClick = retry) { Text(stringResource(R.string.common_retry)) }
    }
}

private fun actionLogErrorResource(error: ApiError): Int = when (error) {
    ApiError.Unauthorized -> R.string.error_unauthorized
    ApiError.Forbidden -> R.string.error_forbidden
    ApiError.RateLimited -> R.string.error_rate_limited
    ApiError.NetworkError -> R.string.error_network
    is ApiError.UserVisible -> error.messageRes
    else -> R.string.common_load_failed
}

internal data class ActionLogRow(val number: Int, val fragment: Int, val text: String)
internal data class ActionLogLayout(val rows: List<ActionLogRow> = emptyList(), val width: Float = 0f)

/** Render only visible rows and columns, including a single line up to the preview byte limit. */
@Composable
private fun ActionLogContent(text: String, wrap: Boolean, listState: LazyListState) {
    val density = LocalDensity.current
    val fontPixels = with(density) { 14.sp.toPx() }
    val paint = remember(fontPixels) { TextPaint().apply { textSize = fontPixels; typeface = Typeface.MONOSPACE; isAntiAlias = true } }
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 24.sp, letterSpacing = 0.sp, textDirection = TextDirection.Ltr)
    val gutter = with(density) { paint.measureText("10000").toDp() } + 24.dp
    var layout by remember(text) { mutableStateOf(ActionLogLayout()) }
    var horizontalOffset by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(density) { (maxWidth - gutter - 28.dp).toPx().coerceAtLeast(fontPixels * 2) }
        LaunchedEffect(text, wrap, width, fontPixels) {
            val anchor = layout.rows.getOrNull(listState.firstVisibleItemIndex)?.number ?: 1
            val next = withContext(Dispatchers.Default) {
                layoutActionLog(text, wrap, width, TextPaint(paint))
            }
            layout = next
            listState.requestScrollToItem(next.rows.indexOfFirst { it.number >= anchor }.coerceAtLeast(0))
        }
        val maxOffset = (layout.width - width).coerceAtLeast(0f)
        val scroll = rememberScrollableState { delta ->
            val before = horizontalOffset
            horizontalOffset = (before - delta).coerceIn(0f, maxOffset)
            before - horizontalOffset
        }
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().scrollable(scroll, Orientation.Horizontal, enabled = !wrap)
                    .semantics { if (!wrap) horizontalScrollAxisRange = ScrollAxisRange({ horizontalOffset }, { maxOffset }) },
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            ) {
                items(layout.rows, key = { "${it.number}:${it.fragment}" }) { row ->
                    val offset = if (wrap) 0f else horizontalOffset.coerceAtMost(maxOffset)
                    // Skip offscreen columns without asking Compose to lay out a megabyte-wide Text.
                    val visible = remember(row.text, offset, width, wrap) {
                        if (wrap) row.text to 0f else {
                            val measured = FloatArray(1)
                            var start = paint.breakText(row.text, 0, row.text.length, true, offset, measured)
                            if (start > 0 && start < row.text.length && row.text[start - 1].isHighSurrogate()) start--
                            val prefix = if (start == 0) 0f else paint.measureText(row.text, 0, start)
                            var count = paint.breakText(row.text, start, row.text.length, true, width + fontPixels * 2, null)
                            if (start + count < row.text.length && count > 0 && row.text[start + count - 1].isHighSurrogate()) count++
                            row.text.substring(start, (start + count).coerceAtMost(row.text.length)) to (prefix - offset)
                        }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp)) {
                        androidx.compose.foundation.text.selection.DisableSelection {
                            Text(if (row.fragment == 0) row.number.toString() else "", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(gutter).padding(horizontal = 12.dp))
                        }
                        Box(Modifier.weight(1f).clipToBounds().padding(start = 12.dp, end = 16.dp)) {
                            Text(visible.first.ifEmpty { " " }, style = style, softWrap = false, modifier = Modifier.offset { IntOffset(visible.second.roundToInt(), 0) })
                        }
                    }
                }
            }
        }
    }
}

// Limit each shaping operation so wrapping a megabyte line does not repeatedly shape its entire suffix.
internal suspend fun layoutActionLog(text: String, wrap: Boolean, width: Float, metric: TextPaint): ActionLogLayout {
    val rows = mutableListOf<ActionLogRow>()
    var widest = 0f
    val lines = actionLogDisplayLines(text)
    lines.forEachIndexed { index, line ->
        coroutineContext.ensureActive()
        if (!wrap || line.isEmpty()) {
            rows += ActionLogRow(index + 1, 0, line)
            if (!wrap) widest = maxOf(widest, metric.measureText(line))
        } else {
            var start = 0
            var fragment = 0
            while (start < line.length) {
                coroutineContext.ensureActive()
                var count = metric.breakText(line, start, minOf(line.length, start + 4096), true, width, null).coerceAtLeast(1)
                if (start + count < line.length && line[start + count - 1].isHighSurrogate()) count = if (count > 1) count - 1 else 2
                count = count.coerceAtMost(line.length - start)
                rows += ActionLogRow(index + 1, fragment++, line.substring(start, start + count))
                start += count
            }
        }
    }
    return ActionLogLayout(rows, widest)
}

private val ansiSequence = Regex("""\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(?:\u0007|\u001B\\))""")
internal fun actionLogDisplayLines(raw: String): List<String> {
    val cleaned = ansiSequence.replace(raw, "").filter { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() }
    return cleaned.replace("\r\n", "\n").replace("\r", "").replace("\t", "    ").split('\n').let {
        if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it
    }
}
