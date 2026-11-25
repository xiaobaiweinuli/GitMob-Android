package com.gitmob.app.ui.stars

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import com.gitmob.app.ui.common.pressToggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gitmob.app.R
import com.gitmob.app.data.model.UserListSummary
import com.gitmob.app.ui.common.ActionButtonContent

@Composable
fun EditListDialog(
    list: UserListSummary,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String?, isPrivate: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(list.id) { mutableStateOf(list.name) }
    var description by remember(list.id) { mutableStateOf(list.description ?: "") }
    var isPrivate by remember(list.id) { mutableStateOf(list.isPrivate) }
    var confirmingDelete by remember { mutableStateOf(false) }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { if (!isSaving) confirmingDelete = false },
            title = { Text(stringResource(R.string.stars_delete_list_title)) },
            text = { Text(stringResource(R.string.stars_delete_list_message)) },
            confirmButton = {
                TextButton(onClick = onDelete, enabled = !isSaving, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = isSaving, label = stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }, enabled = !isSaving, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.stars_edit_list)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    enabled = !isSaving,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.stars_list_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = description,
                    enabled = !isSaving,
                    onValueChange = { description = it },
                    placeholder = { Text(stringResource(R.string.stars_list_description_optional)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    shape = MaterialTheme.shapes.medium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .heightIn(min = 48.dp)
                        .pressToggleable(isPrivate, enabled = !isSaving, role = Role.Checkbox) { isPrivate = it }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = isPrivate, onCheckedChange = null, enabled = !isSaving)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(stringResource(R.string.stars_list_private))
                        Text(stringResource(R.string.stars_list_private_desc), style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(
                    onClick = { confirmingDelete = true },
                    enabled = !isSaving,
                    modifier = Modifier.padding(top = 8.dp),
                    shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text(stringResource(R.string.stars_delete_this_list), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), description.trim().ifBlank { null }, isPrivate) },
                enabled = name.isNotBlank() && !isSaving,
                shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                ActionButtonContent(busy = isSaving, label = stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
