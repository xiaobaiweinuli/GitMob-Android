package com.gitmob.app.ui.stars

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.stars_delete_list_title)) },
            text = { Text(stringResource(R.string.stars_delete_list_message)) },
            confirmButton = {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stars_edit_list)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.stars_list_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = { Text(stringResource(R.string.stars_list_description_optional)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = isPrivate, onCheckedChange = { isPrivate = it })
                    Column {
                        Text(stringResource(R.string.stars_list_private))
                        Text(stringResource(R.string.stars_list_private_desc), style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(
                    onClick = { confirmingDelete = true },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.stars_delete_this_list), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), description.trim().ifBlank { null }, isPrivate) },
                enabled = name.isNotBlank() && !isSaving,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
