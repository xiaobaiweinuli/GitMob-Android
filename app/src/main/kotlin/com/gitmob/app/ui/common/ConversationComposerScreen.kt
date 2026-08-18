package com.gitmob.app.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.navigation.ConversationComposerRoute
import com.gitmob.app.navigation.ConversationComposerTarget

data class ConversationComposeRequest(
    val target: ConversationComposerTarget,
    val subjectId: String,
    val initialText: String = "",
    val commentId: String? = null,
    val replyToId: String? = null,
    val reviewEvent: String? = null,
)

internal enum class MarkdownEditAction { BOLD, ITALIC, STRIKETHROUGH, HEADING, BULLET, NUMBERED, TASK, QUOTE, INLINE_CODE, CODE_BLOCK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationComposerScreen(
    route: ConversationComposerRoute,
    onBack: () -> Unit,
    viewModel: ConversationComposerViewModel = hiltViewModel(),
) {
    LaunchedEffect(route) {
        viewModel.init(route.owner, route.name, route.number, route.target, route.subjectId, route.initialText, route.commentId, route.replyToId, route.reviewEvent)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editorValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(route.initialText, TextRange(route.initialText.length))) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = state.text != route.initialText

    LaunchedEffect(state.text) {
        if (editorValue.text != state.text) editorValue = editorValue.copy(text = state.text, selection = TextRange(state.text.length))
    }
    LaunchedEffect(state.submitted) { if (state.submitted) onBack() }

    fun requestBack() {
        if (dirty && !state.isSubmitting) confirmDiscard = true else if (!state.isSubmitting) onBack()
    }
    BackHandler(onBack = ::requestBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(composerTitle(route)) },
                navigationIcon = { IconButton(onClick = ::requestBack, enabled = !state.isSubmitting) { Icon(Icons.Default.Close, stringResource(R.string.common_cancel)) } },
                actions = {
                    IconButton(onClick = viewModel::submit, enabled = (state.text.isNotBlank() || state.allowsEmptySubmission) && !state.isSubmitting) {
                        if (state.isSubmitting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.conversation_send))
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            PrimaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
                Tab(selected = state.selectedTab == ComposerTab.EDIT, onClick = { viewModel.selectTab(ComposerTab.EDIT) }, text = { Text(stringResource(R.string.conversation_edit_tab)) })
                Tab(selected = state.selectedTab == ComposerTab.PREVIEW, onClick = { viewModel.selectTab(ComposerTab.PREVIEW) }, text = { Text(stringResource(R.string.conversation_preview_tab)) })
            }
            when (state.selectedTab) {
                ComposerTab.EDIT -> MarkdownEditor(
                    value = editorValue,
                    onValueChange = { editorValue = it; viewModel.updateText(it.text) },
                    onAction = { action -> editorValue = applyMarkdownEdit(editorValue, action).also { viewModel.updateText(it.text) } },
                    modifier = Modifier.weight(1f),
                )
                ComposerTab.PREVIEW -> PreviewPane(state, Modifier.weight(1f))
            }
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.conversation_discard_title)) },
        text = { Text(stringResource(R.string.conversation_discard_message)) },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.common_cancel)) } },
        confirmButton = { TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.conversation_discard)) } },
    )
}

@Composable
private fun composerTitle(route: ConversationComposerRoute): String = stringResource(
    when {
        route.commentId != null -> R.string.conversation_edit_comment
        route.target == ConversationComposerTarget.PULL_REQUEST_REVIEW -> R.string.pr_review
        route.replyToId != null || route.target == ConversationComposerTarget.PULL_REQUEST_THREAD -> R.string.conversation_reply
        else -> R.string.conversation_write_comment
    },
)

@Composable
private fun MarkdownEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onAction: (MarkdownEditAction) -> Unit,
    modifier: Modifier,
) {
    val requester = remember { FocusRequester() }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
            if (value.text.isEmpty()) Text(stringResource(R.string.conversation_markdown_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxSize().focusRequester(requester),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
        HorizontalDivider()
        MarkdownToolbar(onAction)
    }
}

@Composable
private fun MarkdownToolbar(onAction: (MarkdownEditAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(Icons.Default.FormatBold, R.string.conversation_tool_bold) { onAction(MarkdownEditAction.BOLD) }
        ToolButton(Icons.Default.FormatItalic, R.string.conversation_tool_italic) { onAction(MarkdownEditAction.ITALIC) }
        ToolButton(Icons.Default.StrikethroughS, R.string.conversation_tool_strikethrough) { onAction(MarkdownEditAction.STRIKETHROUGH) }
        ToolButton(Icons.Default.Title, R.string.conversation_tool_heading) { onAction(MarkdownEditAction.HEADING) }
        ToolButton(Icons.AutoMirrored.Filled.FormatListBulleted, R.string.conversation_tool_bullet) { onAction(MarkdownEditAction.BULLET) }
        ToolButton(Icons.Default.FormatListNumbered, R.string.conversation_tool_numbered) { onAction(MarkdownEditAction.NUMBERED) }
        ToolButton(Icons.Default.CheckBox, R.string.conversation_tool_task) { onAction(MarkdownEditAction.TASK) }
        ToolButton(Icons.Default.FormatQuote, R.string.conversation_tool_quote) { onAction(MarkdownEditAction.QUOTE) }
        ToolButton(Icons.Default.Code, R.string.conversation_tool_inline_code) { onAction(MarkdownEditAction.INLINE_CODE) }
        ToolButton(Icons.Default.DataObject, R.string.conversation_tool_code_block) { onAction(MarkdownEditAction.CODE_BLOCK) }
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, stringResource(label)) }
}

@Composable
private fun PreviewPane(state: ConversationComposerUiState, modifier: Modifier) {
    when {
        state.text.isBlank() -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.conversation_preview_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.isRenderingPreview -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.previewFailed -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.conversation_preview_failed), color = MaterialTheme.colorScheme.error) }
        else -> Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { MarkdownWebView(state.previewHtml) }
    }
}

internal fun applyMarkdownEdit(value: TextFieldValue, action: MarkdownEditAction): TextFieldValue {
    val selection = value.selection
    val start = selection.min.coerceIn(0, value.text.length)
    val end = selection.max.coerceIn(start, value.text.length)
    val selected = value.text.substring(start, end)
    val (replacement, innerStart, innerEnd) = when (action) {
        MarkdownEditAction.BOLD -> wrap(selected, "**", "**", "bold")
        MarkdownEditAction.ITALIC -> wrap(selected, "*", "*", "italic")
        MarkdownEditAction.STRIKETHROUGH -> wrap(selected, "~~", "~~", "text")
        MarkdownEditAction.INLINE_CODE -> wrap(selected, "`", "`", "code")
        MarkdownEditAction.CODE_BLOCK -> wrap(selected, "```\n", "\n```", "code")
        MarkdownEditAction.HEADING -> linePrefix(selected, "# ", "Heading")
        MarkdownEditAction.BULLET -> linePrefix(selected, "- ", "List item")
        MarkdownEditAction.NUMBERED -> linePrefix(selected, "1. ", "List item")
        MarkdownEditAction.TASK -> linePrefix(selected, "- [ ] ", "Task")
        MarkdownEditAction.QUOTE -> linePrefix(selected, "> ", "Quote")
    }
    val newText = value.text.replaceRange(start, end, replacement)
    return value.copy(text = newText, selection = TextRange(start + innerStart, start + innerEnd))
}

private fun wrap(selected: String, before: String, after: String, placeholder: String): EditReplacement {
    val content = selected.ifEmpty { placeholder }
    return EditReplacement(before + content + after, before.length, before.length + content.length)
}

private fun linePrefix(selected: String, prefix: String, placeholder: String): EditReplacement {
    val content = selected.ifEmpty { placeholder }.lineSequence().joinToString("\n") { prefix + it }
    val selectionStart = prefix.length
    return EditReplacement(content, selectionStart, content.length)
}

private data class EditReplacement(val text: String, val selectionStart: Int, val selectionEnd: Int)
