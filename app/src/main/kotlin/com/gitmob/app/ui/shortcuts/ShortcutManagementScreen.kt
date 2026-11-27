package com.gitmob.app.ui.shortcuts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AddHome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.LauncherShortcut
import com.gitmob.app.data.model.ShortcutKind
import com.gitmob.app.data.model.ShortcutPublishResult
import com.gitmob.app.ui.common.ActionButtonContent
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.ActionStateContent
import com.gitmob.app.ui.common.ReorderableList
import com.gitmob.app.ui.common.pressClickable
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutManagementScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: ShortcutViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var rename by remember { mutableStateOf<LauncherShortcut?>(null) }
    var remove by remember { mutableStateOf<LauncherShortcut?>(null) }
    var add by remember { mutableStateOf<LauncherShortcut?>(null) }
    var help by remember { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) { viewModel.refresh(); onPauseOrDispose {} }
    BackHandler(editing) { editing = false }
    val blocked = busy || rename != null || remove != null || add != null || help
    fun open(item: LauncherShortcut) { if (editing) rename = item else onOpen(item.url) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shortcuts_title)) },
                navigationIcon = { ActionIconButton(onClick = { if (editing) editing = false else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                } },
                actions = {
                    ActionIconButton(onClick = { help = true }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.shortcuts_help)) }
                    TextButton(onClick = { editing = !editing }, enabled = !blocked && state?.shortcuts?.isNotEmpty() == true,
                        shapes = ButtonDefaults.shapes()) {
                        ActionStateContent(editing) { Text(stringResource(if (it) R.string.common_done else R.string.common_edit)) }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val current = state
            val dynamic = current?.dynamic.orEmpty()
            val pinned = current?.pinned.orEmpty()
            if (current == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (failed) TextButton(onClick = viewModel::refresh, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.common_retry)) }
                    else CircularProgressIndicator()
                }
            } else if (dynamic.isEmpty() && pinned.isEmpty()) {
                Text(stringResource(R.string.common_empty), Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else ReorderableList(
                source = dynamic, key = { it.id }, movable = { true }, editing = editing, busy = blocked,
                modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(), commit = viewModel::reorder, grouped = true,
                header = if (dynamic.isEmpty()) null else ({
                    ShortcutSectionHeader(stringResource(R.string.shortcuts_menu),
                        stringResource(R.string.shortcuts_menu_count, dynamic.size, current.capacity))
                }),
                footer = if (pinned.isEmpty()) null else ({
                    Column(Modifier.padding(top = if (dynamic.isEmpty()) 0.dp else 24.dp)) {
                        ShortcutSectionHeader(stringResource(R.string.shortcuts_desktop), pinned.size.toString())
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column {
                                pinned.forEachIndexed { index, item ->
                                    ShortcutRow(item, editing, !blocked, onClick = { open(item) }, onAdd = { add = item })
                                    if (index != pinned.lastIndex) HorizontalDivider()
                                }
                            }
                        }
                    }
                }),
            ) { item, modifier ->
                ShortcutSwipeRow(enabled = !editing && !blocked, onRemove = { remove = item }, modifier = modifier) {
                    ShortcutRow(item, editing, !blocked, onClick = { open(item) }, onAdd = {
                        viewModel.pin(item) { if (it == ShortcutPublishResult.PinUnavailable) help = true }
                    })
                }
            }
        }
    }
    rename?.let { item -> ShortcutRenameDialog(item, busy, { rename = null }) { title ->
        viewModel.rename(item, title) { rename = null }
    } }
    remove?.let { item -> AlertDialog(
        onDismissRequest = { if (!busy) remove = null },
        title = { Text(stringResource(R.string.shortcuts_remove)) },
        text = { Text(stringResource(R.string.shortcuts_remove_message, item.title)) },
        confirmButton = { TextButton(onClick = { viewModel.remove(item) { remove = null } }, enabled = !busy,
            shapes = ButtonDefaults.shapes()) { ActionButtonContent(busy, stringResource(R.string.shortcuts_remove)) } },
        dismissButton = { TextButton(onClick = { remove = null }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.common_cancel)) } },
    ) }
    add?.let { ShortcutAddDialog(it, { add = null }, viewModel) }
    if (help) ShortcutHelpDialog({ help = false }, viewModel::settingsUnavailable)
}

@Composable
private fun ShortcutSectionHeader(title: String, count: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ShortcutRow(item: LauncherShortcut, editing: Boolean, enabled: Boolean, onClick: () -> Unit, onAdd: () -> Unit) {
    ListItem(
        modifier = Modifier.pressClickable(enabled = enabled,
            onClickLabel = if (editing) stringResource(R.string.shortcuts_rename) else null, onClick = onClick),
        enabled = enabled,
        content = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(painterResource(if (item.kind == ShortcutKind.REPOSITORY) R.drawable.oct_repo_16 else R.drawable.oct_code_16), null, Modifier.size(24.dp)) },
        trailingContent = if (editing || (item.dynamic && item.pinned)) null else ({
            ActionIconButton(onClick = onAdd, enabled = enabled) {
                Icon(if (item.dynamic) Icons.Outlined.AddHome else Icons.Default.Add,
                    stringResource(if (item.dynamic) R.string.shortcuts_add_desktop else R.string.shortcuts_add_menu))
            }
        }),
        shapes = ListItemDefaults.shapes(shape = RectangleShape),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShortcutSwipeRow(enabled: Boolean, onRemove: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val removeLabel = stringResource(R.string.shortcuts_remove)
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier.semantics {
            if (enabled) dismiss(removeLabel) { onRemove(); true }
        },
        enableDismissFromStartToEnd = false, enableDismissFromEndToStart = enabled,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && enabled) onRemove()
            scope.launch { swipe.reset() }
        },
        backgroundContent = {
            if (swipe.dismissDirection != SwipeToDismissBoxValue.Settled) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                    Icon(Icons.Default.Delete, removeLabel, tint = MaterialTheme.colorScheme.onError)
                }
            }
        },
        content = { content() },
    )
}
