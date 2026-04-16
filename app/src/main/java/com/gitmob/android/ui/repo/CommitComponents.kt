@file:OptIn(ExperimentalMaterial3Api::class)

package com.gitmob.android.ui.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.gitmob.android.api.*
import com.gitmob.android.ui.common.*
import com.gitmob.android.ui.theme.*
import com.gitmob.android.util.LogManager
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.defaultMinSize
import okhttp3.Request

/**
 * 对Issues列表进行筛选和排序
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitsTab(
    state: RepoDetailState, 
    c: GmColors, 
    onCommitClick: (GHCommit) -> Unit,
    onRefresh: () -> Unit = {},
) {
    PullToRefreshBox(
        isRefreshing = state.commitsRefreshing,
        onRefresh = onRefresh,
    ) {
        if (state.commits.isEmpty()) {
            EmptyBox("暂无提交记录")
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.commits, key = { it.sha }) { commit ->
                    Column(
                        modifier = Modifier.fillMaxWidth().background(c.bgCard, RoundedCornerShape(12.dp))
                            .clickable { onCommitClick(commit) }.padding(12.dp),
                    ) {
                        Text(commit.commit.message.lines().first(), fontSize = 13.sp,
                            color = c.textPrimary, fontWeight = FontWeight.Medium, maxLines = 2)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (commit.author != null) AvatarImage(commit.author.avatarUrl, 20)
                            Text(commit.commit.author.name, fontSize = 11.sp, color = c.textSecondary)
                            Spacer(Modifier.weight(1f))
                            Text(commit.shortSha, fontSize = 10.sp, color = Coral,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.background(CoralDim, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp))
                            Text(repoFormatDate(commit.commit.author.date), fontSize = 11.sp, color = c.textTertiary)
                        }
                    }
                }
            }
        }

    }
}

/**
 * 分支管理标签页组件
 * 
 * @param state 仓库详情状态
 * @param c 颜色主题
 * @param onSwitch 切换分支回调
 * @param onNewBranch 新建分支回调
 * @param onDelete 删除分支回调
 * @param onRename 重命名分支回调
 * @param onSetDefault 设置默认分支回调
 * @param onRefresh 刷新回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitDetailSheet(
    commit: GHCommitFull,
    c: GmColors,
    permission: RepoPermission,
    isArchived: Boolean = false,
    vm: RepoDetailViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var showResetConfirm  by remember { mutableStateOf(false) }
    var showRevertConfirm by remember { mutableStateOf(false) }

    // ── Diff 查看器（二级 Sheet）──────────────────────────────────────────
    state.selectedFilePatch?.let { info ->
        FileDiffSheet(info = info, c = c, vm = vm, isArchived = isArchived, onDismiss = vm::closeFilePatch)
    }

    // ── 回滚确认 ──────────────────────────────────────────────────────────
    if (showResetConfirm) {
        ResetConfirmDialog(
            shortSha = commit.shortSha,
            branch   = state.currentBranch,
            c        = c,
            onConfirm = {
                vm.resetToCommit(commit.sha)
                showResetConfirm = false
            },
            onDismiss = { showResetConfirm = false },
        )
    }

    // ── 撤销确认 ──────────────────────────────────────────────────────────
    if (showRevertConfirm) {
        RevertConfirmDialog(
            commit    = commit,
            branch    = state.currentBranch,
            c         = c,
            onConfirm = { msg ->
                vm.revertCommit(commit.sha, msg)
                showRevertConfirm = false
            },
            onDismiss = { showRevertConfirm = false },
        )
    }

    // ── 操作结果提示 ───────────────────────────────────────────────────────
    state.gitOpResult?.let { result ->
        LaunchedEffect(result) {
            kotlinx.coroutines.delay(3000)
            vm.clearGitOpResult()
        }
        GitOpResultSnackbar(result = result, c = c)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = c.bgCard,
        dragHandle       = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // 操作进行中遮罩
        if (state.gitOpInProgress) {
            Box(
                Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Coral, modifier = Modifier.size(32.dp))
                    Text("正在执行操作…", fontSize = 13.sp, color = c.textSecondary)
                }
            }
            return@ModalBottomSheet
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            // ── Header ──────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    commit.shortSha,
                    fontSize = 12.sp, color = Coral, fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(CoralDim, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                if (commit.stats != null) {
                    Text("+${commit.stats.additions}", fontSize = 12.sp, color = Green)
                    Text("-${commit.stats.deletions}", fontSize = 12.sp, color = RedColor)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(commit.commit.message, fontSize = 14.sp, color = c.textPrimary,
                lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (commit.author != null) AvatarImage(commit.author.avatarUrl, 20)
                Text(commit.commit.author.name, fontSize = 12.sp, color = c.textSecondary)
                Text("·", color = c.textTertiary)
                Text(repoFormatDate(commit.commit.author.date), fontSize = 12.sp, color = c.textTertiary)
            }
            Spacer(Modifier.height(14.dp))
            GmDivider()
            Spacer(Modifier.height(12.dp))

            // ── 变更文件列表（可点击查看 diff）──────────────────────────
            commit.files?.let { files ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("变更文件 (${files.size})", fontSize = 12.sp,
                        color = c.textSecondary, fontWeight = FontWeight.Medium)
                    Text("· 点击查看 diff", fontSize = 11.sp, color = c.textTertiary)
                }
                Spacer(Modifier.height(8.dp))
                files.forEach { file ->
                    val (statusColor, statusLabel) = when (file.status) {
                        "added"    -> Green     to "A"
                        "removed"  -> RedColor  to "D"
                        "modified" -> Yellow    to "M"
                        "renamed"  -> BlueColor to "R"
                        else       -> c.textTertiary to "?"
                    }
                    val hasPatch = !file.patch.isNullOrBlank()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (hasPatch) c.bgItem else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .then(
                                if (hasPatch) Modifier.clickable {
                                    vm.openFilePatch(FilePatchInfo(
                                        filename        = file.filename,
                                        patch           = file.patch,
                                        additions       = file.additions,
                                        deletions       = file.deletions,
                                        status          = file.status,
                                        parentSha       = commit.parentSha,
                                        previousFilename = file.previousFilename,
                                        owner           = vm.owner,
                                        repoName        = vm.state.value.currentRepoName,
                                        currentSha      = commit.sha,
                                        currentBranch   = state.currentBranch,
                                    ))
                                } else Modifier
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            statusLabel, fontSize = 11.sp, color = statusColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                        Text(file.filename, fontSize = 12.sp, color = c.textPrimary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f))
                        if (file.additions > 0 || file.deletions > 0) {
                            Text("+${file.additions}/-${file.deletions}",
                                fontSize = 11.sp, color = c.textTertiary)
                        }
                        if (hasPatch) {
                            Icon(Icons.Default.ChevronRight, null,
                                tint = c.textTertiary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            GmDivider()
            Spacer(Modifier.height(12.dp))

            // ── 操作区 ─────────────────────────────────────────────────
            PermissionRequired(permission = permission, requireOwner = true, isArchived = isArchived) {
                Text("操作", fontSize = 12.sp, color = if (isArchived) c.textTertiary else c.textSecondary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))

                // 撤销（Revert）：创建新 commit，保留历史 ─────────────────────
                Button(
                    onClick = { showRevertConfirm = true },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape  = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isArchived) c.bgItem else BlueColor
                    ),
                    enabled = !isArchived
                ) {
                    Icon(Icons.Default.SwapHoriz, null, 
                        tint = if (isArchived) c.textTertiary else Color.Unspecified,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("撤销此提交", 
                            fontSize = 13.sp, 
                            fontWeight = FontWeight.SemiBold,
                            color = if (isArchived) c.textTertiary else Color.Unspecified)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "· 创建新 commit，内容回退到此提交之前，不重写历史",
                    fontSize = 11.sp, color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                Spacer(Modifier.height(12.dp))

                // 回滚（Reset）：强制移动分支指针 ────────────────────────────
                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape  = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isArchived) c.border else RedColor.copy(alpha = 0.7f)),
                    enabled = !isArchived
                ) {
                    Icon(Icons.Default.RestartAlt, null, 
                        tint = if (isArchived) c.textTertiary else RedColor, 
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("回滚到此提交", 
                        fontSize = 13.sp, 
                        color = if (isArchived) c.textTertiary else RedColor, 
                        fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "· 强制将分支 HEAD 指向此 SHA，之后的提交将消失（危险）",
                    fontSize = 11.sp, color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                Spacer(Modifier.height(16.dp))
            }

            // 在 GitHub 查看 ──────────────────────────────────────────────
            OutlinedButton(
                onClick = {
                    context.startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(commit.htmlUrl)))
                },
                modifier = Modifier.fillMaxWidth(),
                shape  = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, c.border),
            ) {
                Icon(Icons.Default.OpenInBrowser, null,
                    tint = c.textSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("在 GitHub 查看", fontSize = 14.sp, color = c.textSecondary)
            }
        }
    }
}

// ─── 创建文件对话框 ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateFileDialog(
    currentPath: String,
    c: GmColors,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var fileName by remember { mutableStateOf("") }
    var fileContent by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.bgCard,
        dragHandle = null,
        modifier = Modifier.fillMaxHeight()
    ) {
        // 不用 verticalScroll：让内容框通过 weight(1f) 填满剩余高度
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 标题栏 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("创建文件", color = c.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = c.textSecondary)
                }
            }

            if (currentPath.isNotEmpty()) {
                Text(
                    "当前路径: $currentPath/",
                    fontSize = 11.sp,
                    color = c.textTertiary,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // ── 文件名（固定高度）──
            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("文件名 (支持路径)") },
                placeholder = { Text("README.md 或 docs/file.txt", color = c.textTertiary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Coral,
                    unfocusedBorderColor = c.border,
                    focusedTextColor = c.textPrimary,
                    unfocusedTextColor = c.textPrimary,
                    focusedContainerColor = c.bgItem,
                    unfocusedContainerColor = c.bgItem,
                    focusedLabelColor = Coral,
                    unfocusedLabelColor = c.textTertiary,
                ),
            )

            // ── 文件内容（weight(1f) 填满剩余空间）──
            OutlinedTextField(
                value = fileContent,
                onValueChange = { fileContent = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),          // 撑满标题和按钮之间的全部空间
                label = { Text("文件内容") },
                placeholder = { Text("输入文件内容...", color = c.textTertiary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Coral,
                    unfocusedBorderColor = c.border,
                    focusedTextColor = c.textPrimary,
                    unfocusedTextColor = c.textPrimary,
                    focusedContainerColor = c.bgItem,
                    unfocusedContainerColor = c.bgItem,
                    focusedLabelColor = Coral,
                    unfocusedLabelColor = c.textTertiary,
                ),
            )

            // ── 底部按钮（固定在底部）──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消", color = c.textSecondary)
                }
                Button(
                    onClick = {
                        if (fileName.isNotBlank()) {
                            onConfirm(fileName, fileContent)
                        }
                    },
                    enabled = fileName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("创建")
                }
            }
        }
    }
}

// ─── 提交信息对话框 ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitMessageDialog(
    defaultMessage: String = "",
    c: GmColors,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var commitMsg by remember { mutableStateOf(defaultMessage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("提交信息", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = commitMsg,
                onValueChange = { commitMsg = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("提交信息") },
                placeholder = { Text("Describe your changes...", color = c.textTertiary) },
                singleLine = false,
                minLines = 2,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Coral,
                    unfocusedBorderColor = c.border,
                    focusedTextColor = c.textPrimary,
                    unfocusedTextColor = c.textPrimary,
                    focusedContainerColor = c.bgItem,
                    unfocusedContainerColor = c.bgItem,
                    focusedLabelColor = Coral,
                    unfocusedLabelColor = c.textTertiary,
                ),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (commitMsg.isNotBlank()) {
                        onConfirm(commitMsg)
                    }
                },
                enabled = commitMsg.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textSecondary)
            }
        },
    )
}

// ─── ResetConfirmDialog（回滚：重写历史，危险操作）───────────────────────────

@Composable
fun ResetConfirmDialog(
    shortSha: String, branch: String, c: GmColors,
    onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = c.bgCard,
        icon = {
            Icon(Icons.Default.RestartAlt, null, tint = RedColor, modifier = Modifier.size(28.dp))
        },
        title = { Text("确认回滚", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 目标提交
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(c.bgItem, RoundedCornerShape(8.dp)).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("目标", fontSize = 12.sp, color = c.textTertiary)
                    Text(
                        shortSha, fontSize = 13.sp, color = Coral,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.background(CoralDim, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Text("on  $branch", fontSize = 12.sp, color = c.textSecondary)
                }
                // 危险警告
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(RedDim, RoundedCornerShape(8.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Warning, null, tint = RedColor, modifier = Modifier.size(18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("⚠ 危险操作", fontSize = 13.sp, color = RedColor, fontWeight = FontWeight.SemiBold)
                        Text(
                            "此操作将强制把分支指针指向目标提交，之后的所有提交记录将从分支上消失（相当于 git push -f）。如果分支有保护规则，操作可能被拒绝。",
                            fontSize = 12.sp, color = RedColor.copy(alpha = 0.85f), lineHeight = 17.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors  = ButtonDefaults.buttonColors(containerColor = RedColor),
                shape   = RoundedCornerShape(10.dp),
            ) { Text("确认回滚", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    )
}

// ─── RevertConfirmDialog（撤销：创建新 commit，安全）────────────────────────

@Composable
fun RevertConfirmDialog(
    commit: GHCommitFull, branch: String, c: GmColors,
    onConfirm: (message: String) -> Unit, onDismiss: () -> Unit,
) {
    val defaultMsg = "Revert \"${commit.commit.message.lines().first().take(50)}\""
    var revertMsg by remember { mutableStateOf(defaultMsg) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = c.bgCard,
        icon = {
            Icon(Icons.Default.SwapHoriz, null, tint = BlueColor, modifier = Modifier.size(28.dp))
        },
        title = { Text("撤销此提交", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 目标提交信息
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(c.bgItem, RoundedCornerShape(8.dp)).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        commit.shortSha, fontSize = 12.sp, color = Coral,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.background(CoralDim, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Text(
                        commit.commit.message.lines().first(),
                        fontSize = 12.sp, color = c.textPrimary,
                        modifier = Modifier.weight(1f), maxLines = 1,
                    )
                }
                // 安全说明
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(BlueColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Info, null, tint = BlueColor, modifier = Modifier.size(16.dp))
                    Text(
                        "将创建一个新的 revert commit，把分支内容恢复到此提交之前的状态。不重写历史，可安全用于受保护分支。",
                        fontSize = 12.sp, color = BlueColor.copy(alpha = 0.9f), lineHeight = 17.sp,
                    )
                }
                // commit message 编辑
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Revert commit 信息", fontSize = 12.sp,
                        color = c.textSecondary, fontWeight = FontWeight.Medium)
                    OutlinedTextField(
                        value         = revertMsg,
                        onValueChange = { revertMsg = it },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        shape         = RoundedCornerShape(10.dp),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor      = BlueColor,
                            unfocusedBorderColor    = c.border,
                            focusedTextColor        = c.textPrimary,
                            unfocusedTextColor      = c.textPrimary,
                            focusedContainerColor   = c.bgItem,
                            unfocusedContainerColor = c.bgItem,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick  = { if (revertMsg.isNotBlank()) onConfirm(revertMsg.trim()) },
                enabled  = revertMsg.isNotBlank(),
                colors   = ButtonDefaults.buttonColors(containerColor = BlueColor),
                shape    = RoundedCornerShape(10.dp),
            ) { Text("确认撤销", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    )
}

// ─── 操作结果浮动提示 ────────────────────────────────────────────────────────

@Composable
fun GitOpResultSnackbar(result: GitOpResult, c: GmColors) {
    val bg  = if (result.success) GreenDim  else RedDim
    val fg  = if (result.success) Green     else RedColor
    val ico = if (result.success) Icons.Default.CheckCircle else Icons.Default.Error
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg, RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ico, null, tint = fg, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${result.opName}${if (result.success) "成功" else "失败"}",
                    fontSize = 13.sp, color = fg, fontWeight = FontWeight.SemiBold,
                )
                Text(result.detail, fontSize = 12.sp, color = fg.copy(alpha = 0.8f), lineHeight = 16.sp)
            }
        }
    }
}

// ─── FileDiffSheet ──────────────────────────────────────────────────────────

/** 解析后的 diff 行 */
