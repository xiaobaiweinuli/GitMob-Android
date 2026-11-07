package com.gitmob.app.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.FavoriteGroup

@Composable
fun FavoritesScreen(accountLibrary: AccountLibraryViewModel, onBack: () -> Unit, onGroup: (FavoriteGroup) -> Unit, viewModel: LibraryViewModel = hiltViewModel()) {
    val settings by accountLibrary.settings.collectAsStateWithLifecycle()
    val groupsState by accountLibrary.groups.collectAsStateWithLifecycle()
    val groups = groupsState.value.orEmpty()
    val loaded = settings.value != null && (settings.value?.favoritesEnabled == false || groupsState.value != null)
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    var editing by rememberSaveable { mutableStateOf(false) }
    var create by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf<FavoriteGroup?>(null) }
    var rename by remember { mutableStateOf<FavoriteGroup?>(null) }
    var delete by remember { mutableStateOf<FavoriteGroup?>(null) }
    var removeRepos by remember { mutableStateOf(false) }
    var defaultNotice by remember { mutableStateOf(false) }
    BackHandler(editing) { editing = false }
    LibraryScaffold(stringResource(R.string.favorites_title), onBack, actions = {
        IconButton(onClick = { create = true }, enabled = loaded && (settings.value?.favoritesEnabled == true) && !busy) { Icon(Icons.Default.Add, stringResource(R.string.favorites_new_group)) }
        TextButton(onClick = { editing = !editing }, enabled = loaded && settings.value?.favoritesEnabled == true && !busy) { Text(stringResource(if (editing) R.string.library_done else R.string.library_edit)) }
    }) { padding ->
        LibraryLoading(loaded, settings.failed || groupsState.failed, { accountLibrary.retrySettings(); accountLibrary.retryGroups() }, Modifier.padding(padding)) {
            if (settings.value?.favoritesEnabled == false) LibraryEmpty(stringResource(R.string.library_disabled))
            else LibraryReorderList(groups, { it.id }, { !it.isDefault }, editing, busy,
                Modifier.fillMaxSize(), viewModel::reorderGroups, grouped = true,
                header = { Text(stringResource(R.string.favorites_summary, groups.sumOf { it.count }, groups.size),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 20.dp, top = 8.dp)) },
                footer = { Text(stringResource(R.string.favorites_local_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp)) },
            ) { group, modifier ->
                Box(modifier) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        if (!editing) onGroup(group)
                        else if (group.isDefault) defaultNotice = true else menu = group
                    }.heightIn(min = 88.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(groupLabel(group), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(if (group.isDefault) R.string.favorites_default_count else R.string.library_group_count, group.count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!editing) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                    DropdownMenu(expanded = menu?.id == group.id, onDismissRequest = { menu = null }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.favorites_rename_group)) }, onClick = { rename = group; menu = null })
                        DropdownMenuItem(text = { Text(stringResource(R.string.favorites_delete_group), color = MaterialTheme.colorScheme.error) }, onClick = { delete = group; removeRepos = false; menu = null })
                    }
                }
            }
        }
    }
    if (create) GroupNameDialog(null, busy, { create = false }) { viewModel.createGroup(it) { create = false } }
    rename?.let { group -> GroupNameDialog(group.name, busy, { rename = null }) { name -> viewModel.renameGroup(group.id, name) { rename = null } } }
    delete?.let { group -> AlertDialog(onDismissRequest = { if (!busy) delete = null }, title = { Text(stringResource(R.string.favorites_delete_group)) }, text = {
        Column { Text(stringResource(R.string.favorites_delete_group_confirm, group.name)); Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { removeRepos = !removeRepos }, verticalAlignment = Alignment.CenterVertically) { Checkbox(removeRepos, onCheckedChange = { removeRepos = it }, enabled = !busy); Text(stringResource(R.string.favorites_delete_group_repos)) } }
    }, dismissButton = { TextButton(onClick = { delete = null }, enabled = !busy) { Text(stringResource(R.string.library_cancel)) } }, confirmButton = { TextButton(onClick = { viewModel.deleteGroup(group.id, removeRepos) { delete = null } }, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.library_delete)) } }) }
    if (defaultNotice) AlertDialog(onDismissRequest = { defaultNotice = false }, text = { Text(stringResource(R.string.library_default_fixed)) }, confirmButton = { TextButton(onClick = { defaultNotice = false }) { Text(stringResource(R.string.library_confirm)) } })
}
