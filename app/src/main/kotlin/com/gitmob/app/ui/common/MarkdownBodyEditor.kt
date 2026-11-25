package com.gitmob.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.gitmob.app.R

enum class MarkdownEditorTab { EDIT, PREVIEW }

data class MarkdownEditorUiState(
    val selectedTab: MarkdownEditorTab = MarkdownEditorTab.EDIT,
    val previewHtml: String = "",
    val isRenderingPreview: Boolean = false,
    val previewFailed: Boolean = false,
)

internal enum class MarkdownEditAction {
    BOLD,
    ITALIC,
    STRIKETHROUGH,
    HEADING,
    BULLET,
    NUMBERED,
    TASK,
    QUOTE,
    INLINE_CODE,
    CODE_BLOCK,
}

@Composable
fun MarkdownBodyEditor(
    textState: SoraEditorState,
    state: MarkdownEditorUiState,
    onTabSelected: (MarkdownEditorTab) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessoryContent: (@Composable () -> Unit)? = null,
    embedded: Boolean = false,
    minLines: Int = 1,
    controller: SoraEditorController = rememberSoraEditorController(textState),
    showToolbar: Boolean = true,
) {
    val previewState = rememberMarkdownViewportState("markdown-editor")
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun selectTab(tab: MarkdownEditorTab) {
        if (tab == state.selectedTab) return
        // Release native focus before recomposition removes an AndroidView from the lazy item.
        controller.view?.clearFocus()
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        onTabSelected(tab)
    }
    Column(modifier.fillMaxWidth()) {
        PrimaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
            Tab(
                selected = state.selectedTab == MarkdownEditorTab.EDIT,
                onClick = { selectTab(MarkdownEditorTab.EDIT) },
                text = { Text(stringResource(R.string.conversation_edit_tab)) },
            )
            Tab(
                selected = state.selectedTab == MarkdownEditorTab.PREVIEW,
                onClick = { selectTab(MarkdownEditorTab.PREVIEW) },
                text = { Text(stringResource(R.string.conversation_preview_tab)) },
            )
        }
        when (state.selectedTab) {
            MarkdownEditorTab.EDIT -> MarkdownEditPane(
                textState = textState,
                controller = controller,
                minLines = minLines,
                enabled = enabled,
                modifier = if (embedded) Modifier else Modifier.weight(1f),
                embedded = embedded,
            )
            MarkdownEditorTab.PREVIEW -> MarkdownPreviewPane(
                markdown = textState.snapshot(),
                viewportState = previewState,
                state = state,
                modifier = if (embedded) Modifier else Modifier.weight(1f),
                embedded = embedded,
            )
        }
        accessoryContent?.let { content ->
            Box(Modifier.fillMaxWidth()) {
                content()
            }
        }
        if (showToolbar && state.selectedTab == MarkdownEditorTab.EDIT) {
            MarkdownEditorToolbar(textState, controller, enabled = enabled)
        }
    }
}

@Composable
fun MarkdownEditorToolbar(
    textState: SoraEditorState,
    controller: SoraEditorController,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider()
        MarkdownToolbar(
            enabled = enabled,
            onAction = { action -> controller.edit { applyMarkdownEdit(it, action) } },
            history = { SoraUndoRedoButtons(textState, controller, enabled) },
        )
    }
}

@Composable
private fun MarkdownEditPane(
    textState: SoraEditorState,
    controller: SoraEditorController,
    minLines: Int,
    embedded: Boolean,
    enabled: Boolean,
    modifier: Modifier,
) {
    Box(modifier.fillMaxWidth().padding(16.dp)) {
        SoraTextEditor(textState, if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
            enabled = enabled, showLineNumbers = false, controller = controller, embedded = embedded, minLines = minLines)
        if (textState.text.isEmpty()) {
            Text(stringResource(R.string.conversation_markdown_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MarkdownToolbar(enabled: Boolean, onAction: (MarkdownEditAction) -> Unit, history: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarkdownToolButton(Icons.Default.FormatBold, R.string.conversation_tool_bold, enabled) { onAction(MarkdownEditAction.BOLD) }
        MarkdownToolButton(Icons.Default.FormatItalic, R.string.conversation_tool_italic, enabled) { onAction(MarkdownEditAction.ITALIC) }
        MarkdownToolButton(Icons.Default.StrikethroughS, R.string.conversation_tool_strikethrough, enabled) { onAction(MarkdownEditAction.STRIKETHROUGH) }
        MarkdownToolButton(Icons.Default.Title, R.string.conversation_tool_heading, enabled) { onAction(MarkdownEditAction.HEADING) }
        MarkdownToolButton(Icons.AutoMirrored.Filled.FormatListBulleted, R.string.conversation_tool_bullet, enabled) { onAction(MarkdownEditAction.BULLET) }
        MarkdownToolButton(Icons.Default.FormatListNumbered, R.string.conversation_tool_numbered, enabled) { onAction(MarkdownEditAction.NUMBERED) }
        MarkdownToolButton(Icons.Default.CheckBox, R.string.conversation_tool_task, enabled) { onAction(MarkdownEditAction.TASK) }
        MarkdownToolButton(Icons.Default.FormatQuote, R.string.conversation_tool_quote, enabled) { onAction(MarkdownEditAction.QUOTE) }
        MarkdownToolButton(Icons.Default.Code, R.string.conversation_tool_inline_code, enabled) { onAction(MarkdownEditAction.INLINE_CODE) }
        MarkdownToolButton(Icons.Default.DataObject, R.string.conversation_tool_code_block, enabled) { onAction(MarkdownEditAction.CODE_BLOCK) }
        history()
    }
}

@Composable
private fun MarkdownToolButton(icon: ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, stringResource(label))
    }
}

@Composable
private fun MarkdownPreviewPane(
    markdown: String,
    viewportState: MarkdownViewportState,
    embedded: Boolean,
    state: MarkdownEditorUiState,
    modifier: Modifier,
) {
    when {
        markdown.isBlank() -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.conversation_preview_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.isRenderingPreview -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.previewFailed -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.conversation_preview_failed),
                color = MaterialTheme.colorScheme.error,
            )
        }
        else -> MarkdownWebView(state.previewHtml, modifier = modifier,
            layoutMode = if (embedded) MarkdownLayoutMode.Embedded else MarkdownLayoutMode.Viewport, viewportState = viewportState)
    }
}

internal fun applyMarkdownEdit(textState: SoraEditorState, action: MarkdownEditAction) = textState.edit {
    val start = selection.min
    val end = selection.max
    val selected = asCharSequence().subSequence(start, end).toString()
    val replacement = when (action) {
        MarkdownEditAction.BOLD -> wrapMarkdown(selected, "**", "**", "bold")
        MarkdownEditAction.ITALIC -> wrapMarkdown(selected, "*", "*", "italic")
        MarkdownEditAction.STRIKETHROUGH -> wrapMarkdown(selected, "~~", "~~", "text")
        MarkdownEditAction.INLINE_CODE -> wrapMarkdown(selected, "`", "`", "code")
        MarkdownEditAction.CODE_BLOCK -> wrapMarkdown(selected, "```\n", "\n```", "code")
        MarkdownEditAction.HEADING -> prefixMarkdownLines(selected, "# ", "Heading")
        MarkdownEditAction.BULLET -> prefixMarkdownLines(selected, "- ", "List item")
        MarkdownEditAction.NUMBERED -> prefixMarkdownLines(selected, "1. ", "List item")
        MarkdownEditAction.TASK -> prefixMarkdownLines(selected, "- [ ] ", "Task")
        MarkdownEditAction.QUOTE -> prefixMarkdownLines(selected, "> ", "Quote")
    }
    replace(start, end, replacement.text)
    selection = TextRange(start + replacement.selectionStart, start + replacement.selectionEnd)
}

private fun wrapMarkdown(selected: String, before: String, after: String, placeholder: String): MarkdownReplacement {
    val content = selected.ifEmpty { placeholder }
    return MarkdownReplacement(before + content + after, before.length, before.length + content.length)
}

private fun prefixMarkdownLines(selected: String, prefix: String, placeholder: String): MarkdownReplacement {
    val content = selected.ifEmpty { placeholder }.lineSequence().joinToString("\n") { prefix + it }
    return MarkdownReplacement(content, prefix.length, content.length)
}

private data class MarkdownReplacement(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)
