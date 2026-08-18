package com.gitmob.app.ui.common

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gitmob.app.R
import com.gitmob.app.data.model.CommentAuthorAssociation
import com.gitmob.app.data.model.SimpleUser

data class ConversationMenuItem(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun ConversationContentCard(
    author: SimpleUser?,
    createdAt: String,
    bodyHtml: String?,
    url: String,
    authorAssociation: CommentAuthorAssociation,
    isThreadAuthor: Boolean,
    onQuoteReply: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    extraMenuItems: List<ConversationMenuItem> = emptyList(),
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = author?.avatarUrl,
                    contentDescription = author?.login,
                    modifier = Modifier.size(36.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(author?.login ?: stringResource(R.string.common_deleted_user), fontWeight = FontWeight.SemiBold)
                        if (isThreadAuthor) AssistChip(onClick = {}, label = { Text(stringResource(R.string.conversation_author)) })
                        if (authorAssociation == CommentAuthorAssociation.OWNER) AssistChip(onClick = {}, label = { Text(stringResource(R.string.conversation_owner)) })
                    }
                    Text(createdAt.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.issue_more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (url.isNotBlank()) DropdownMenuItem(
                            text = { Text(stringResource(R.string.conversation_share)) },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = { menuOpen = false; shareConversationUrl(context, url) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.conversation_quote_reply)) },
                            leadingIcon = { Icon(Icons.Default.FormatQuote, null) },
                            onClick = { menuOpen = false; onQuoteReply() },
                        )
                        onEdit?.let { edit -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_edit)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuOpen = false; edit() },
                        ) }
                        extraMenuItems.forEach { item -> DropdownMenuItem(
                            text = { Text(item.label, color = if (item.destructive) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified) },
                            onClick = { menuOpen = false; item.onClick() },
                        ) }
                        onDelete?.let { delete -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; delete() },
                        ) }
                    }
                }
            }
            bodyHtml?.takeIf(String::isNotBlank)?.let {
                MarkdownWebView(it, Modifier.fillMaxWidth())
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationComposerSheet(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
    isSubmitting: Boolean = false,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(stringResource(R.string.conversation_markdown_body)) },
                minLines = 6,
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text(stringResource(R.string.common_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSubmit, enabled = value.isNotBlank() && !isSubmitting) {
                    Text(stringResource(if (isSubmitting) R.string.conversation_submitting else R.string.issue_comment_submit))
                }
            }
        }
    }
}

fun quoteMarkdown(body: String, maxLength: Int = 4_000): String {
    val normalized = body.trim().take(maxLength)
    if (normalized.isBlank()) return ""
    val suffix = if (body.trim().length > maxLength) "\n> …" else ""
    return normalized.lineSequence().joinToString("\n") { "> $it" } + suffix + "\n\n"
}

private fun shareConversationUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.conversation_share)))
}
