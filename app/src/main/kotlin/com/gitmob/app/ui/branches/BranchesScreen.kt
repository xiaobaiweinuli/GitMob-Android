package com.gitmob.app.ui.branches

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoBranch
import com.gitmob.app.data.model.BranchCreationSpec
import com.gitmob.app.ui.common.RepositoryContextTitle

/**
 * 合并后的"分支"页面：整行点击 = 轻量切换当前分支（能直接返回上一页）；
 * 行尾"⋮"溢出菜单 = 重操作（重命名/设默认/删除），按 canPush 和管理权限显示，
 * 避免"本来只想切个分支，结果误触删除"。见 references/architecture.md 的分支合并方案。
 *
 * TopAppBar 的返回图标走 [onBack]（BranchesScreen 参数里已经有，之前只是没接到 navigationIcon）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchesScreen(
    owner: String,
    name: String,
    currentRef: String,
    canPush: Boolean,
    canManageBranchProtection: Boolean,
    onBack: () -> Unit,
    onOwnerClick: (String) -> Unit,
    onRepositoryClick: (String, String) -> Unit,
    viewModel: BranchesViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name) { viewModel.init(owner, name) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var createDialogOpen by remember { mutableStateOf(false) }
    var selectedCreationSpec by remember { mutableStateOf<BranchCreationSpec?>(null) }
    var selectedRef by remember(currentRef) { mutableStateOf(currentRef) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    RepositoryContextTitle(
                        owner = owner,
                        repository = name,
                        pageTitle = stringResource(R.string.common_branches),
                        onOwnerClick = onOwnerClick,
                        onRepositoryClick = onRepositoryClick,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    if (canPush) {
                        IconButton(onClick = { createDialogOpen = true }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.branches_create),
                            )
                        }
                    }
                },
                // ★ 与 Scaffold contentWindowInsets 一致，不用 WindowInsets(0)
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.isLoading && state.branches.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.loadFailed -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(stringResource(R.string.common_load_failed))
                        Button(
                            onClick = viewModel::retry,
                            modifier = Modifier.padding(top = 12.dp),
                        ) { Text(stringResource(R.string.common_retry)) }
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.branches) { branch ->
                            val canRename = canPush && (!branch.isDefault || canManageBranchProtection)
                            val canSetDefault = canManageBranchProtection && !branch.isDefault
                            val canDelete = canPush && !branch.isDefault
                            BranchRow(
                                branch = branch,
                                isCurrent = branch.name == selectedRef,
                                showOverflowMenu = canRename || canSetDefault || canDelete,
                                canRename = canRename,
                                canSetDefault = canSetDefault,
                                canDelete = canDelete,
                                onClick = { viewModel.switchBranch(branch.name); onBack() },
                                onSetDefault = { viewModel.setDefaultBranch(branch.name) },
                                onDelete = { viewModel.deleteBranch(branch.id) },
                                onRename = { newName, onFinished ->
                                    viewModel.renameBranch(branch.name, newName) { success ->
                                        if (success && branch.name == selectedRef) {
                                            selectedRef = newName
                                            viewModel.switchBranch(newName)
                                        }
                                        onFinished(success)
                                    }
                                },
                            )
                            // 到达最后一项时触发 loadMore，用 branch.id 作为 key 防止重组时重复触发
                            if (branch == state.branches.lastOrNull() &&
                                state.hasNextPage && !state.isLoadingMore
                            ) {
                                LaunchedEffect(branch.id) { viewModel.loadMore() }
                            }
                        }
                        // 底部加载指示器
                        if (state.isLoadingMore) {
                            item(key = "loading_more") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(strokeWidth = 2.dp)
                                }
                            }
                        }
                        // Push route 底部：navigationBars + captionBar 高度
                        item(key = "bottom_spacer") {
                            Spacer(
                                Modifier.height(
                                    WindowInsets.navigationBars.asPaddingValues()
                                        .calculateBottomPadding() +
                                        WindowInsets.captionBar.asPaddingValues()
                                            .calculateBottomPadding(),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    if (createDialogOpen && selectedCreationSpec == null) {
        AlertDialog(
            onDismissRequest = { createDialogOpen = false },
            title = { Text(stringResource(R.string.branches_create)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { selectedCreationSpec = BranchCreationSpec.FromExisting(selectedRef) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.branches_create_from_existing_title))
                            Text(
                                stringResource(R.string.branches_create_from_existing_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(
                        onClick = { selectedCreationSpec = BranchCreationSpec.Empty },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.branches_create_empty_title))
                            Text(
                                stringResource(R.string.branches_create_empty_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { createDialogOpen = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (createDialogOpen && selectedCreationSpec != null) {
        BranchNameDialog(
            title = stringResource(R.string.branches_create),
            confirmLabel = stringResource(R.string.branches_create_action),
            initialValue = "",
            sourceBranch = (selectedCreationSpec as? BranchCreationSpec.FromExisting)?.sourceBranch,
            onDismiss = {
                selectedCreationSpec = null
                createDialogOpen = false
            },
            onConfirm = { newName, onFinished ->
                viewModel.createBranch(
                    newBranchName = newName,
                    spec = selectedCreationSpec!!,
                ) { success ->
                    if (success) {
                        selectedCreationSpec = null
                        createDialogOpen = false
                    }
                    onFinished(success)
                }
            },
        )
    }
}

@Composable
private fun BranchRow(
    branch: RepoBranch,
    isCurrent: Boolean,
    showOverflowMenu: Boolean,
    canRename: Boolean,
    canSetDefault: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String, (Boolean) -> Unit) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var renameDialogOpen by remember { mutableStateOf(false) }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.branches_delete_branch_title, branch.name)) },
            text = { Text(stringResource(R.string.common_cannot_be_undone)) },
            confirmButton = {
                Text(
                    stringResource(R.string.common_delete),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable {
                        confirmingDelete = false
                        onDelete()
                    }.padding(12.dp),
                )
            },
            dismissButton = {
                Text(stringResource(R.string.common_cancel), modifier = Modifier.clickable { confirmingDelete = false }.padding(12.dp))
            },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(branch.name, style = MaterialTheme.typography.titleSmall)
                if (branch.isDefault) {
                    Text(
                        stringResource(R.string.branches_default_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            branch.commitOid?.let {
                Text(it.take(7), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (isCurrent) {
            Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.branches_current_branch), tint = MaterialTheme.colorScheme.primary)
        }
        if (showOverflowMenu) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.branches_more_actions))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (canSetDefault) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.branches_set_default)) },
                            onClick = {
                                menuExpanded = false
                                onSetDefault()
                            },
                        )
                    }
                    if (canRename) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.branches_rename)) },
                            onClick = {
                                menuExpanded = false
                                renameDialogOpen = true
                            },
                        )
                    }
                    if (canDelete) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.branches_delete_branch), color = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; confirmingDelete = true },
                        )
                    }
                }
            }
        }
    }

    if (renameDialogOpen) {
        BranchNameDialog(
            title = stringResource(R.string.branches_rename),
            confirmLabel = stringResource(R.string.common_save),
            initialValue = branch.name,
            onDismiss = { renameDialogOpen = false },
            onConfirm = { newName, onFinished ->
                onRename(newName) { success ->
                    if (success) renameDialogOpen = false
                    onFinished(success)
                }
            },
        )
    }
}

@Composable
private fun BranchNameDialog(
    title: String,
    confirmLabel: String,
    initialValue: String,
    sourceBranch: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String, (Boolean) -> Unit) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    var isSubmitting by remember { mutableStateOf(false) }
    val normalized = value.trim()
    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sourceBranch?.let {
                    Text(
                        stringResource(R.string.branches_create_from, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.branches_name)) },
                    singleLine = true,
                    enabled = !isSubmitting,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isSubmitting = true
                    onConfirm(normalized) { success -> if (!success) isSubmitting = false }
                },
                enabled = normalized.isNotEmpty() && normalized != initialValue && !isSubmitting,
            ) {
                if (isSubmitting) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                else Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
