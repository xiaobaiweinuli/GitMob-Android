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
import androidx.compose.ui.unit.dp
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
            title = { Text("删除列表？") },
            text = { Text("此操作无法撤销，列表内的仓库不会被取消星标。") },
            confirmButton = {
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("取消") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑列表") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("列表名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = { Text("描述（可选）") },
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
                        Text("私有列表")
                        Text("仅自己可见", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(
                    onClick = { confirmingDelete = true },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("删除此列表", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), description.trim().ifBlank { null }, isPrivate) },
                enabled = name.isNotBlank() && !isSaving,
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
