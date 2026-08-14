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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.data.model.RepoBranch

/**
 * 合并后的"分支"页面：整行点击 = 轻量切换当前分支（能直接返回上一页）；
 * 行尾"⋮"溢出菜单 = 重操作（设默认/删除），只有 canManageBranchProtection 为真才显示，
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
    canManageBranchProtection: Boolean,
    onBack: () -> Unit,
    viewModel: BranchesViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name) { viewModel.init(owner, name) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分支") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
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
                        Text("加载失败")
                        Button(
                            onClick = viewModel::retry,
                            modifier = Modifier.padding(top = 12.dp),
                        ) { Text("重试") }
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.branches) { branch ->
                            BranchRow(
                                branch = branch,
                                isCurrent = branch.name == currentRef,
                                showOverflowMenu = canManageBranchProtection,
                                onClick = { viewModel.switchBranch(branch.name); onBack() },
                                onSetDefault = { viewModel.setDefaultBranch(branch.name) },
                                onDelete = { viewModel.deleteBranch(branch.id) },
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
}

@Composable
private fun BranchRow(
    branch: RepoBranch,
    isCurrent: Boolean,
    showOverflowMenu: Boolean,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除分支 ${branch.name}？") },
            text = { Text("此操作无法撤销。") },
            confirmButton = {
                Text(
                    "删除",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable {
                        confirmingDelete = false
                        onDelete()
                    }.padding(12.dp),
                )
            },
            dismissButton = {
                Text("取消", modifier = Modifier.clickable { confirmingDelete = false }.padding(12.dp))
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
                        " 默认",
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
            Icon(Icons.Default.CheckCircle, contentDescription = "当前分支", tint = MaterialTheme.colorScheme.primary)
        }
        if (showOverflowMenu) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (!branch.isDefault) {
                        DropdownMenuItem(
                            text = { Text("设为默认分支") },
                            onClick = {
                                menuExpanded = false
                                onSetDefault()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除分支", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; confirmingDelete = true },
                        )
                    }
                }
            }
        }
    }
}
