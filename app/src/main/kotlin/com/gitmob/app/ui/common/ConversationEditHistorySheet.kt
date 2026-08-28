package com.gitmob.app.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gitmob.app.R
import com.gitmob.app.data.model.ConversationEdit
import androidx.compose.ui.res.stringResource

data class ConversationEditHistoryUiState(
    val targetNodeId: String? = null,
    val items: List<ConversationEdit> = emptyList(),
    val selectedEdit: ConversationEdit? = null,
    val isOpen: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val hasNextPage: Boolean = false,
    val endCursor: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationEditHistorySheet(
    edits: List<ConversationEdit>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    loadFailed: Boolean,
    hasNextPage: Boolean,
    selectedEdit: ConversationEdit?,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (ConversationEdit) -> Unit,
    onClearSelected: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        if (selectedEdit != null) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClearSelected) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
                    Text(stringResource(R.string.conversation_edit_history), style = MaterialTheme.typography.titleLarge)
                }
                Text(selectedEdit.editedAt.take(19), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                val diff = selectedEdit.diff
                if (diff.isNullOrBlank()) {
                    Text(stringResource(R.string.conversation_no_edit_diff), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        diff,
                        Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 520.dp).horizontalScroll(rememberScrollState()).padding(16.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            return@ModalBottomSheet
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.conversation_edit_history), style = MaterialTheme.typography.titleLarge)
            when {
                isLoading && edits.isEmpty() -> Box(Modifier.fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                loadFailed && edits.isEmpty() -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(stringResource(R.string.common_load_failed)); Button(onClick = onRetry) { Text(stringResource(R.string.common_retry)) } }
                edits.isEmpty() -> Text(stringResource(R.string.conversation_no_edit_diff), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.heightIn(max = 560.dp)) {
                    items(edits, key = { it.id }) { edit ->
                        TextButton(onClick = { onSelect(edit) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                Text(edit.editor?.login ?: stringResource(R.string.common_deleted_user))
                                Text(edit.editedAt.take(19), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (hasNextPage) item {
                        if (isLoadingMore) Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.widthIn(max = 24.dp)) }
                        else { LaunchedEffect(edits.size) { onLoadMore() } }
                    }
                }
            }
        }
    }
}
