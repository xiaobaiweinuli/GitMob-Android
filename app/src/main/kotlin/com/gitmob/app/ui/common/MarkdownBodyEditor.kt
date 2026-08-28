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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
    value: TextFieldValue,
    state: MarkdownEditorUiState,
    onValueChange: (TextFieldValue) -> Unit,
    onTabSelected: (MarkdownEditorTab) -> Unit,
    modifier: Modifier = Modifier,
    accessoryContent: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        PrimaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
            Tab(
                selected = state.selectedTab == MarkdownEditorTab.EDIT,
                onClick = { onTabSelected(MarkdownEditorTab.EDIT) },
                text = { Text(stringResource(R.string.conversation_edit_tab)) },
            )
            Tab(
                selected = state.selectedTab == MarkdownEditorTab.PREVIEW,
                onClick = { onTabSelected(MarkdownEditorTab.PREVIEW) },
                text = { Text(stringResource(R.string.conversation_preview_tab)) },
            )
        }
        when (state.selectedTab) {
            MarkdownEditorTab.EDIT -> MarkdownEditPane(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
            )
            MarkdownEditorTab.PREVIEW -> MarkdownPreviewPane(
                markdown = value.text,
                state = state,
                modifier = Modifier.weight(1f),
            )
        }
        accessoryContent?.let { content ->
            Box(Modifier.fillMaxWidth()) {
                content()
            }
        }
        if (state.selectedTab == MarkdownEditorTab.EDIT) {
            HorizontalDivider()
            MarkdownToolbar(onAction = { action -> onValueChange(applyMarkdownEdit(value, action)) })
        }
    }
}

@Composable
private fun MarkdownEditPane(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier,
) {
    val requester = remember { FocusRequester() }
    Box(modifier.fillMaxWidth().padding(16.dp)) {
        if (value.text.isEmpty()) {
            Text(
                stringResource(R.string.conversation_markdown_placeholder),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxSize().focusRequester(requester),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun MarkdownToolbar(onAction: (MarkdownEditAction) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarkdownToolButton(Icons.Default.FormatBold, R.string.conversation_tool_bold) { onAction(MarkdownEditAction.BOLD) }
        MarkdownToolButton(Icons.Default.FormatItalic, R.string.conversation_tool_italic) { onAction(MarkdownEditAction.ITALIC) }
        MarkdownToolButton(Icons.Default.StrikethroughS, R.string.conversation_tool_strikethrough) { onAction(MarkdownEditAction.STRIKETHROUGH) }
        MarkdownToolButton(Icons.Default.Title, R.string.conversation_tool_heading) { onAction(MarkdownEditAction.HEADING) }
        MarkdownToolButton(Icons.AutoMirrored.Filled.FormatListBulleted, R.string.conversation_tool_bullet) { onAction(MarkdownEditAction.BULLET) }
        MarkdownToolButton(Icons.Default.FormatListNumbered, R.string.conversation_tool_numbered) { onAction(MarkdownEditAction.NUMBERED) }
        MarkdownToolButton(Icons.Default.CheckBox, R.string.conversation_tool_task) { onAction(MarkdownEditAction.TASK) }
        MarkdownToolButton(Icons.Default.FormatQuote, R.string.conversation_tool_quote) { onAction(MarkdownEditAction.QUOTE) }
        MarkdownToolButton(Icons.Default.Code, R.string.conversation_tool_inline_code) { onAction(MarkdownEditAction.INLINE_CODE) }
        MarkdownToolButton(Icons.Default.DataObject, R.string.conversation_tool_code_block) { onAction(MarkdownEditAction.CODE_BLOCK) }
    }
}

@Composable
private fun MarkdownToolButton(icon: ImageVector, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, stringResource(label))
    }
}

@Composable
private fun MarkdownPreviewPane(
    markdown: String,
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
        else -> Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            MarkdownWebView(state.previewHtml)
        }
    }
}

internal fun applyMarkdownEdit(value: TextFieldValue, action: MarkdownEditAction): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    val selected = value.text.substring(start, end)
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
    val newText = value.text.replaceRange(start, end, replacement.text)
    return value.copy(
        text = newText,
        selection = TextRange(start + replacement.selectionStart, start + replacement.selectionEnd),
    )
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
