package com.gitmob.app.ui.library

import com.gitmob.app.ui.common.pressSelectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gitmob.app.R
import com.gitmob.app.data.model.DEFAULT_FAVORITE_GROUP
import com.gitmob.app.data.model.FavoriteGroup
import com.gitmob.app.data.model.FavoriteItem
import com.gitmob.app.ui.common.ActionButtonContent
import com.gitmob.app.ui.common.ActionIconButton

@Composable
internal fun groupLabel(group: FavoriteGroup): String =
    if (group.isDefault) stringResource(R.string.favorites_default_group) else group.name

@Composable
internal fun LibraryConfirm(title: String, text: String, busy: Boolean, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(title) }, text = { Text(text) },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.library_cancel)) } },
        confirmButton = { TextButton(onClick = confirm, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = busy, label = stringResource(R.string.library_confirm)) } },
    )
}

@Composable
internal fun GroupNameDialog(initial: String?, busy: Boolean, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(if (initial == null) R.string.favorites_new_group else R.string.favorites_rename_group)) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, enabled = !busy, label = { Text(stringResource(R.string.favorites_group_name)) }, supportingText = { Text("${name.trim().length}/30") }, shape = MaterialTheme.shapes.medium) },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.library_cancel)) } },
        confirmButton = { Button(onClick = { save(name) }, enabled = !busy && name.trim().length in 1..30, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { ActionButtonContent(busy = busy, label = stringResource(R.string.library_save)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteSheet(
    item: FavoriteItem,
    existing: FavoriteItem?,
    groups: List<FavoriteGroup>?,
    busy: Boolean,
    dismiss: () -> Unit,
    create: (String, (String) -> Unit) -> Unit,
    save: (FavoriteItem, String, () -> Unit) -> Unit,
    remove: (FavoriteItem, () -> Unit) -> Unit,
    moveOnly: Boolean = false,
    groupsFailed: Boolean = false,
    retryGroups: () -> Unit = {},
) {
    var selected by rememberSaveable(item.key) { mutableStateOf(existing?.groupId ?: DEFAULT_FAVORITE_GROUP) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!busy) dismiss() }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(when { moveOnly -> R.string.favorites_move_title; existing != null -> R.string.favorites_manage; else -> R.string.favorites_choose_group }), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                ActionIconButton(onClick = dismiss, enabled = !busy) { Icon(Icons.Default.Close, stringResource(R.string.library_close)) }
            }
            Text(item.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LibraryLoading(groups != null, groupsFailed, retryGroups, Modifier.heightIn(max = 320.dp)) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(vertical = 16.dp)) {
                    items(groups.orEmpty(), key = { it.id }) { group ->
                        Row(Modifier.fillMaxWidth().pressSelectable(selected == group.id, enabled = !busy, role = Role.RadioButton) { selected = group.id }.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected == group.id, onClick = null, enabled = !busy)
                            Text(groupLabel(group), Modifier.weight(1f).padding(start = 12.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showCreate = true }, enabled = !busy && groups != null, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.favorites_new_group)) }
                Spacer(Modifier.weight(1f))
                if (existing != null && !moveOnly) TextButton(onClick = { confirmRemove = true }, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.favorites_remove)) }
                Button(onClick = { save(item, selected, dismiss) }, enabled = !busy && groups?.any { it.id == selected } == true, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { ActionButtonContent(busy = busy && !showCreate && !confirmRemove, label = stringResource(if (existing == null) R.string.favorites_add else R.string.library_save)) }
            }
        }
    }
    if (showCreate) GroupNameDialog(null, busy, { showCreate = false }) { name ->
        create(name) { selected = it; showCreate = false }
    }
    if (confirmRemove) LibraryConfirm(stringResource(R.string.favorites_remove), stringResource(R.string.favorites_remove_confirm, item.title), busy, { confirmRemove = false }) { remove(item) { confirmRemove = false; dismiss() } }
}
