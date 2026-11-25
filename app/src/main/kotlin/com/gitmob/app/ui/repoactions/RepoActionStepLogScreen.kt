package com.gitmob.app.ui.repoactions

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.data.model.ActionLogAvailability
import com.gitmob.app.data.model.RepoActionStepLogKey
import com.gitmob.app.ui.common.SoraTextViewer
import com.gitmob.app.ui.common.SoraViewerDocument
import com.gitmob.app.ui.common.rememberSoraViewerState
import io.github.rosemoe.sora.text.Content
import kotlinx.coroutines.launch

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
    val readingState = rememberSoraViewerState(logKey.toString())
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
                    else -> SoraTextViewer(
                        documentKey = log.text,
                        wrapLines = state.wrapLines,
                        state = readingState,
                        modifier = Modifier.fillMaxSize(),
                    ) { SoraViewerDocument(Content(actionLogDisplayText(log.text))) }
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

private val ansiSequence = Regex("""\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(?:\u0007|\u001B\\))""")

internal fun actionLogDisplayText(raw: String): String = ansiSequence.replace(raw, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() }
    .replace("\r\n", "\n").replace("\r", "")
    .replace("\t", "    ").removeSuffix("\n")
