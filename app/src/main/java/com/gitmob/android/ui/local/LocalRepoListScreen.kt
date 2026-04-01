package com.gitmob.android.ui.local

import androidx.compose.animation.animateColorAsState
import com.gitmob.android.ui.filepicker.BookmarkPath
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gitmob.android.local.LocalRepo
import com.gitmob.android.local.LocalRepoStatus
import com.gitmob.android.ui.common.*
import com.gitmob.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalRepoListScreen(
    rootEnabled: Boolean,
    vm: LocalRepoViewModel,
    onShowNewProjectPicker: () -> Unit = {},
    onRepoClick: (String) -> Unit = {},
) {
    val c = LocalGmColors.current
    val state by vm.state.collectAsState()
    val customBookmarks by vm.customBookmarks.collectAsState()

    state.toast?.let { msg ->
        LaunchedEffect(msg) { kotlinx.coroutines.delay(2500); vm.clearToast() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = c.bgDeep,
            topBar = {
                TopAppBar(
                    title = { Text("本地仓库", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.textPrimary) },
                    actions = {
                        // 新建本地项目
                        IconButton(onClick = onShowNewProjectPicker) {
                            Icon(Icons.Default.CreateNewFolder, null, tint = Coral)
                        }
                        // 导入已有目录
                        IconButton(onClick = vm::showFilePicker) {
                            Icon(Icons.Default.FolderOpen, null, tint = c.textSecondary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep),
                )
            },
            snackbarHost = {
                state.toast?.let {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Snackbar(modifier = Modifier.padding(top = 80.dp, start = 16.dp, end = 16.dp),
                            containerColor = c.bgCard, contentColor = c.textPrimary) { Text(it) }
                    }
                }
            },
        ) { padding ->
            if (state.repos.isEmpty()) {
                EmptyLocalState(
                    c = c,
                    onImport = vm::showFilePicker,
                    onNew = onShowNewProjectPicker,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.repos, key = { it.id }) { repo ->
                        SwipeableLocalRepoCard(
                            repo = repo, c = c,
                            onScan = { vm.scanRepo(repo.id) },
                            onRemove = { vm.removeRepo(repo.id) },
                            onClick = { onRepoClick(repo.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableLocalRepoCard(
    repo: LocalRepo, c: GmColors,
    onScan: () -> Unit, onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(dismissState.targetValue) {
        if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
            showDeleteDialog = true
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val color by animateColorAsState(
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) RedColor else c.border,
                label = "swipe_bg",
            )
            Box(
                modifier = Modifier.fillMaxSize().background(color, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(end = 20.dp),
                ) {
                    Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Text("删除", fontSize = 11.sp, color = Color.White, modifier = Modifier.padding(top = 3.dp))
                }
            }
        },
    ) {
        LocalRepoCardContent(repo = repo, c = c, onScan = onScan, onClick = onClick)
    }

    if (showDeleteDialog) {
        DeleteLocalRepoDialog(
            repoName = repo.name,
            onConfirm = { onRemove(); showDeleteDialog = false },
            onDismiss = { showDeleteDialog = false },
            c = c,
        )
    }
}

@Composable
private fun LocalRepoCardContent(
    repo: LocalRepo, c: GmColors,
    onScan: () -> Unit,
    onClick: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    val (statusColor, statusLabel) = when (repo.status) {
        LocalRepoStatus.GIT_INITIALIZED -> Green to "已初始化"
        LocalRepoStatus.PENDING_INIT    -> Yellow to "待初始化"
        LocalRepoStatus.WORKING         -> BlueColor to "处理中…"
        LocalRepoStatus.ERROR           -> RedColor to "错误"
    }

    val isSensitive = repo.path.startsWith("/data") || repo.path.startsWith("/system")
    val borderColor = if (isSensitive) Color(0x30FF6B00) else Color.Transparent

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(14.dp))
            .run { if (isSensitive) padding(1.dp).background(borderColor, RoundedCornerShape(14.dp)) else this }
            .padding(14.dp)
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = if (isSensitive) Color(0xFFFF6B00) else Yellow,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(repo.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.textPrimary)
                Text(repo.path, fontSize = 10.sp, color = c.textTertiary,
                    fontFamily = FontFamily.Monospace, maxLines = 2)
            }
            GmBadge(statusLabel, statusColor.copy(alpha = 0.15f), statusColor)
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.MoreVert, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(c.bgCard)) {
                    DropdownMenuItem(
                        text = { Text("重新扫描", fontSize = 13.sp, color = c.textPrimary) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null, tint = c.textSecondary, modifier = Modifier.size(15.dp)) },
                        onClick = { onScan(); showMenu = false },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (repo.status == LocalRepoStatus.GIT_INITIALIZED) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (repo.currentBranch != null) {
                    Text(repo.currentBranch, fontSize = 11.sp, color = BlueColor,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.background(BlueDim, RoundedCornerShape(20.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp))
                }
                if (repo.lastCommit != null) {
                    Text(repo.lastCommit.take(40), fontSize = 11.sp, color = c.textTertiary, maxLines = 1)
                }
                if (repo.changedFilesCount != null) {
                    Text("${repo.changedFilesCount}个变动", fontSize = 11.sp, color = Coral,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.background(Coral.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp))
                }
            }
            if (repo.remoteUrl != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    repo.remoteUrl.replace(Regex("https://[^@]+@"), "https://"),
                    fontSize = 10.sp, color = c.textTertiary, fontFamily = FontFamily.Monospace, maxLines = 1,
                )
            }
        } else if (repo.error != null) {
            Text(repo.error.take(80), fontSize = 11.sp, color = RedColor,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun DeleteLocalRepoDialog(
    repoName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    c: GmColors,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除本地仓库", color = c.textPrimary) },
        text = { Text("确定要从管理列表中移除 \"$repoName\" 吗？\n\n注意：此操作不会删除本地目录中的文件。", color = c.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = RedColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textSecondary)
            }
        },
        containerColor = c.bgCard,
    )
}

@Composable
private fun EmptyLocalState(c: GmColors, onImport: () -> Unit, onNew: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.FolderOpen, null, tint = c.textTertiary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("暂无本地仓库", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        Text("导入现有目录或新建项目开始管理本地代码", fontSize = 13.sp,
            color = c.textSecondary, modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))

        Button(onClick = onNew, modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Coral)) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("新建本地项目", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, c.border)) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp), tint = c.textSecondary)
            Spacer(Modifier.width(8.dp))
            Text("导入本地目录", color = c.textSecondary)
        }
    }
}
