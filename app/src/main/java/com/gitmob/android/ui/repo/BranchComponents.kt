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
fun BranchesTab(
    state: RepoDetailState, c: GmColors,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onSwitch: (String) -> Unit, onNewBranch: () -> Unit,
    onDelete: (String) -> Unit, onRename: (String, String) -> Unit,
    onSetDefault: (String) -> Unit,
    onRefresh: () -> Unit = {},
) {
    var showRenameDialog by remember { mutableStateOf<GHBranch?>(null) }
    var showDeleteDialog by remember { mutableStateOf<GHBranch?>(null) }
    val defaultBranch = state.repo?.defaultBranch ?: ""
    
    PullToRefreshBox(
        isRefreshing = state.branchesRefreshing,
        onRefresh = onRefresh,
    ) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                PermissionRequired(permission = permission, requireOwner = true, isArchived = isArchived) {
                    Button(onClick = onNewBranch, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isArchived) c.bgItem else CoralDim, 
                            contentColor = if (isArchived) c.textTertiary else Coral
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isArchived
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("新建分支", fontSize = 13.sp)
                    }
                }
            }
            items(state.branches, key = { "${it.name}_${it.commit.sha}" }) { branch ->
                val isCurrent = branch.name == state.currentBranch
                val isDefault = branch.name == defaultBranch
                var showMenu by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth().background(c.bgCard, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.AccountTree, null,
                        tint = if (isCurrent) Coral else c.textTertiary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(branch.name, fontSize = 13.sp,
                            color = if (isCurrent) Coral else c.textPrimary,
                            fontFamily = FontFamily.Monospace)
                        if (isDefault) Text("默认分支", fontSize = 10.sp, color = Green, modifier = Modifier.padding(top = 1.dp))
                    }
                    if (isCurrent) {
                        GmBadge("当前", CoralDim, Coral)
                        Spacer(Modifier.width(4.dp))
                    }
                    // 菜单
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.MoreVert, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(c.bgCard)) {
                            if (!isCurrent) {
                                DropdownMenuItem(
                                    text = { Text("切换到此分支", fontSize = 13.sp) },
                                    leadingIcon = { Icon(Icons.Default.AccountTree, null, tint = BlueColor, modifier = Modifier.size(15.dp)) },
                                    onClick = { onSwitch(branch.name); showMenu = false },
                                )
                            }
                            PermissionRequired(permission = permission, requireOwner = true, isArchived = isArchived) {
                                if (!isDefault) {
                                    ArchivedAwareDropdownMenuItem(
                                        text = { Text("设为默认分支", fontSize = 13.sp) },
                                        isArchived = isArchived,
                                        leadingIcon = { Icon(Icons.Default.Star, null, tint = if (isArchived) c.textTertiary else Yellow, modifier = Modifier.size(15.dp)) },
                                        onClick = { onSetDefault(branch.name); showMenu = false },
                                    )
                                }
                                ArchivedAwareDropdownMenuItem(
                                    text = { Text("重命名", fontSize = 13.sp) },
                                    isArchived = isArchived,
                                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null, tint = if (isArchived) c.textTertiary else c.textSecondary, modifier = Modifier.size(15.dp)) },
                                    onClick = { showRenameDialog = branch; showMenu = false },
                                )
                                if (!isDefault && !isCurrent) {
                                    ArchivedAwareDropdownMenuItem(
                                        text = { Text("删除分支", fontSize = 13.sp, color = if (isArchived) c.textTertiary else RedColor) },
                                        isArchived = isArchived,
                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = if (isArchived) c.textTertiary else RedColor, modifier = Modifier.size(15.dp)) },
                                        onClick = { showDeleteDialog = branch; showMenu = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    }

    showRenameDialog?.let { branch ->
        RenameBranchDialog(branch.name, c = c,
            onConfirm = { newName -> onRename(branch.name, newName); showRenameDialog = null },
            onDismiss = { showRenameDialog = null })
    }
    showDeleteDialog?.let { branch ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor = c.bgCard,
            title = { Text("删除分支", color = c.textPrimary) },
            text = { Text("确认删除分支 \"${branch.name}\"？此操作不可撤销。", color = c.textSecondary) },
            confirmButton = {
                Button(onClick = { onDelete(branch.name); showDeleteDialog = null },
                    colors = ButtonDefaults.buttonColors(containerColor = RedColor)) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = null }) { Text("取消", color = c.textSecondary) } },
        )
    }
}

/**
 * Pull Request标签页组件
 * 
 * @param state 仓库详情状态
 * @param c 颜色主题
 * @param onRefresh 刷新回调
 */
@Composable
fun BranchPickerDialog(
    branches: List<GHBranch>,
    current: String,
    c: GmColors,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("选择分支", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "当前分支：$current",
                    fontSize = 12.sp,
                    color = c.textSecondary,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(branches, key = { "${it.name}_${it.commit.sha}" }) { branch ->
                        val isCurrent = branch.name == current
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isCurrent) CoralDim else c.bgItem,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable {
                                    if (!isCurrent) onSelect(branch.name) else onDismiss()
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.AccountTree,
                                null,
                                tint = if (isCurrent) Coral else c.textTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                branch.name,
                                fontSize = 13.sp,
                                color = if (isCurrent) Coral else c.textPrimary,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            if (isCurrent) {
                                GmBadge("当前", CoralDim, Coral)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = c.textSecondary)
            }
        },
        dismissButton = {},
    )
}

@Composable
fun NewBranchDialog(
    c: GmColors,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var branchName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("新建分支", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "请输入新的分支名称。",
                    fontSize = 12.sp,
                    color = c.textSecondary,
                )
                OutlinedTextField(
                    value = branchName,
                    onValueChange = { branchName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("feature/my-branch", color = c.textTertiary) },
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BlueColor,
                        unfocusedBorderColor = c.border,
                        focusedTextColor = c.textPrimary,
                        unfocusedTextColor = c.textPrimary,
                        focusedContainerColor = c.bgItem,
                        unfocusedContainerColor = c.bgItem,
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (branchName.isNotBlank()) onConfirm(branchName.trim()) },
                enabled = branchName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = BlueColor),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("创建", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textSecondary)
            }
        },
    )
}

@Composable
fun RenameBranchDialog(
    oldName: String,
    c: GmColors,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf(oldName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("重命名分支", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "当前分支：$oldName",
                    fontSize = 12.sp,
                    color = c.textSecondary,
                    fontFamily = FontFamily.Monospace,
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("新的分支名称", color = c.textTertiary) },
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BlueColor,
                        unfocusedBorderColor = c.border,
                        focusedTextColor = c.textPrimary,
                        unfocusedTextColor = c.textPrimary,
                        focusedContainerColor = c.bgItem,
                        unfocusedContainerColor = c.bgItem,
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val name = newName.trim()
                    if (name.isNotBlank() && name != oldName) {
                        onConfirm(name)
                    }
                },
                enabled = newName.isNotBlank() && newName != oldName,
                colors = ButtonDefaults.buttonColors(containerColor = BlueColor),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("保存", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textSecondary)
            }
        },
    )
}

// ─── Repo-level Dialogs ──────────────────────────────────────────────────────
