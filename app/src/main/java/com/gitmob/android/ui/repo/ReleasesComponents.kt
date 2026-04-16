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
import kotlinx.coroutines.launch
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
fun ReleasesTab(
    state: RepoDetailState,
    vm: RepoDetailViewModel? = null,
    c: GmColors,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val releases = state.releases
    PullToRefreshBox(
        isRefreshing = state.releasesRefreshing,
        onRefresh = onRefresh,
    ) {
        if (releases.isEmpty()) {
            EmptyBox("暂无发行版")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(releases, key = { it.id }) { release ->
                    SwipeableReleaseCard(release = release, c = c, vm = vm, permission = permission, isArchived = isArchived)
                }
            }
        }
    }
}

// ── 左滑删除包装 ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableReleaseCard(release: GHRelease, c: GmColors, vm: RepoDetailViewModel?, permission: RepoPermission, isArchived: Boolean = false) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog   by remember { mutableStateOf(false) }
    var errorMsg         by remember { mutableStateOf<String?>(null) }
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    
    if (permission.isOwner && !isArchived) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            onDismiss = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) {
                    showDeleteDialog = true
                }
                scope.launch {
                    dismissState.reset()
                }
            },
            backgroundContent = {
                val color by animateColorAsState(
                    if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                        Color(0xFFD32F2F) else c.border,
                    label = "swipe_bg",
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(color, RoundedCornerShape(12.dp)),
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
            ReleaseCard(
                release   = release,
                c         = c,
                vm        = vm,
                permission = permission,
                onEditClick = { showEditDialog = true },
            )
        }
    } else {
        ReleaseCard(
            release   = release,
            c         = c,
            vm        = vm,
            permission = permission,
            onEditClick = if (!isArchived && permission.isOwner) { { showEditDialog = true } } else null,
        )
    }

    // ── 删除确认弹窗 ──
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor   = c.bgCard,
            icon  = { Icon(Icons.Default.Delete, null, tint = Color(0xFFD32F2F)) },
            title = { Text("删除发行版？", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "即将删除发行版「${release.name ?: release.tagName}」，此操作不可撤销。",
                        color = c.textSecondary, fontSize = 13.sp,
                    )
                    Text(
                        "注意：此操作仅删除 Release 记录，不会删除对应的 Git Tag。",
                        color = c.textTertiary, fontSize = 11.sp,
                    )
                    errorMsg?.let {
                        Text(it, color = Color(0xFFD32F2F), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm?.deleteRelease(
                            releaseId = release.id,
                            onSuccess = { showDeleteDialog = false },
                            onError   = { errorMsg = it },
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false; errorMsg = null }) {
                    Text("取消", color = c.textSecondary)
                }
            },
        )
    }

    // ── 编辑弹窗 ──
    if (showEditDialog) {
        EditReleaseDialog(
            release = release,
            c       = c,
            onDismiss = { showEditDialog = false },
            onConfirm = { tagName, name, body, draft, prerelease ->
                vm?.updateRelease(
                    releaseId   = release.id,
                    tagName     = tagName,
                    name        = name,
                    body        = body,
                    draft       = draft,
                    prerelease  = prerelease,
                    onSuccess   = { showEditDialog = false },
                    onError     = { /* Toast 由 ViewModel 处理 */ },
                )
            },
        )
    }
}

// ── 编辑弹窗 ─────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditReleaseDialog(
    release: GHRelease,
    c: GmColors,
    onDismiss: () -> Unit,
    onConfirm: (tagName: String, name: String, body: String, draft: Boolean, prerelease: Boolean) -> Unit,
) {
    var tagName    by remember { mutableStateOf(release.tagName) }
    var name       by remember { mutableStateOf(release.name ?: "") }
    var body       by remember { mutableStateOf(release.body ?: "") }
    var draft      by remember { mutableStateOf(release.draft) }
    var prerelease by remember { mutableStateOf(release.prerelease) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor   = Coral,
        unfocusedBorderColor = c.border,
        focusedTextColor     = c.textPrimary,
        unfocusedTextColor   = c.textPrimary,
        focusedContainerColor   = c.bgItem,
        unfocusedContainerColor = c.bgItem,
        focusedLabelColor    = Coral,
        unfocusedLabelColor  = c.textTertiary,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = c.bgCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Edit, null, tint = Coral, modifier = Modifier.size(18.dp))
                Text("编辑发行版", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = tagName, onValueChange = { tagName = it },
                    label = { Text("Tag 名称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors,
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("发行版标题") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors,
                )
                OutlinedTextField(
                    value = body, onValueChange = { body = it },
                    label = { Text("发行说明（支持 Markdown）") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    maxLines = 8,
                    colors = fieldColors,
                )
                // 草稿 / 预发布 开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .background(c.bgItem, RoundedCornerShape(8.dp))
                            .clickable { draft = !draft }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Checkbox(
                            checked = draft, onCheckedChange = { draft = it },
                            colors = CheckboxDefaults.colors(checkedColor = Coral),
                        )
                        Text("草稿", fontSize = 13.sp, color = c.textPrimary)
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .background(c.bgItem, RoundedCornerShape(8.dp))
                            .clickable { prerelease = !prerelease }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Checkbox(
                            checked = prerelease, onCheckedChange = { prerelease = it },
                            colors = CheckboxDefaults.colors(checkedColor = Coral),
                        )
                        Text("预发布", fontSize = 13.sp, color = c.textPrimary)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(tagName.trim(), name.trim(), body.trim(), draft, prerelease) },
                enabled = tagName.isNotBlank(),
                colors  = ButtonDefaults.buttonColors(containerColor = Coral),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textSecondary)
            }
        },
    )
}

@Composable
fun ReleaseCard(
    release: GHRelease,
    c: GmColors,
    vm: RepoDetailViewModel? = null,
    permission: RepoPermission,
    onEditClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var showDetailSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(12.dp))
            .clickable { showDetailSheet = true }
            .padding(12.dp)
    ) {
        // ── 顶行：badge + tag + 编辑 ─────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (release.prerelease) GmBadge("预发布", YellowDim, Yellow)
            if (release.draft) GmBadge("草稿", c.textTertiary, c.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(release.tagName, fontSize = 12.sp, color = Coral, fontFamily = FontFamily.Monospace)
            PermissionRequired(permission = permission, requireOwner = true) {
                if (vm != null && onEditClick != null) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(c.bgItem, RoundedCornerShape(6.dp))
                            .clickable { onEditClick() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Edit, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // ── 标题 ─────────────────────────────────────────────────────────
        Text(
            release.name ?: release.tagName,
            fontSize = 14.sp, color = c.textPrimary, fontWeight = FontWeight.Medium,
        )
        // ── 摘要（最多2行，截断后显示省略号，提示可点击查看详情）───────
        release.body?.takeIf { it.isNotBlank() }?.let { bodyText ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = bodyText.lines().take(3).joinToString("\n").let {
                    if (bodyText.lines().size > 3) "$it…" else it
                },
                fontSize = 12.sp,
                color = c.textSecondary,
                maxLines = 3,
            )
            Spacer(Modifier.height(2.dp))
            Text("点击查看完整内容", fontSize = 10.sp, color = Coral.copy(alpha = 0.7f))
        }
        // ── 资产列表 ─────────────────────────────────────────────────────
        if (release.assets.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            GmDivider()
            Spacer(Modifier.height(6.dp))
            Text("附件 (${release.assets.size})", fontSize = 11.sp, color = c.textTertiary)
            Spacer(Modifier.height(4.dp))
            release.assets.forEach { asset ->
                val taskKey = "asset_${asset.id}"
                val taskId  = vm?.state?.collectAsState()?.value?.downloadTaskIds?.get(taskKey)
                val dlStatus  = taskId?.let { com.gitmob.android.util.GmDownloadManager.statusOf(it) }
                    ?.collectAsState()?.value
                val dlPct = (dlStatus as? com.gitmob.android.util.DownloadStatus.Progress)?.percent ?: 0
                val isDownload = dlStatus is com.gitmob.android.util.DownloadStatus.Progress
                val isDone = dlStatus is com.gitmob.android.util.DownloadStatus.Success
                val downloadedFile = (dlStatus as? com.gitmob.android.util.DownloadStatus.Success)?.file

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .background(c.bgItem, RoundedCornerShape(8.dp))
                ) {
                    // 进度背景层
                    if (isDownload && dlPct > 0)
                        Box(modifier = Modifier
                            .fillMaxWidth(dlPct / 100f)
                            .matchParentSize()
                            .background(Coral.copy(alpha = 0.18f), RoundedCornerShape(8.dp)))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .clickable {
                                if (isDone && downloadedFile != null) {
                                    val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(
                                            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", downloadedFile),
                                            "*/*"
                                        )
                                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(openIntent)
                                } else {
                                    vm?.downloadAsset(asset)
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Attachment, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(asset.name, fontSize = 12.sp, color = c.textPrimary, maxLines = 1)
                            if (isDownload) {
                                Text("${dlPct}%", fontSize = 10.sp, color = Coral)
                            } else if (isDone) {
                                Text("点击打开", fontSize = 10.sp, color = Green)
                            } else {
                                Text(formatSize(asset.size), fontSize = 10.sp, color = c.textTertiary)
                            }
                        }
                        when {
                            isDone -> Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(14.dp))
                            isDownload -> Icon(Icons.Default.Close, null, tint = Coral, modifier = Modifier.size(14.dp))
                            else -> Icon(Icons.Default.Download, null, tint = Coral, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }

    // ── 详情 BottomSheet ──────────────────────────────────────────────────
    if (showDetailSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDetailSheet = false },
            containerColor = c.bgCard,
            dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 顶行
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (release.prerelease) GmBadge("预发布", YellowDim, Yellow)
                    if (release.draft) GmBadge("草稿", c.textTertiary, c.textSecondary)
                    Spacer(Modifier.weight(1f))
                    Text(release.tagName, fontSize = 12.sp, color = Coral, fontFamily = FontFamily.Monospace)
                }
                Text(release.name ?: release.tagName, fontSize = 17.sp, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                // 完整 body Markdown 渲染
                release.body?.takeIf { it.isNotBlank() }?.let { bodyText ->
                    GmMarkdownWebView(
                        markdown = bodyText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp),
                    )
                }
                // 资产
                if (release.assets.isNotEmpty()) {
                    HorizontalDivider(color = c.border)
                    Text("附件 (${release.assets.size})", fontSize = 12.sp, color = c.textTertiary, fontWeight = FontWeight.Medium)
                    release.assets.forEach { asset ->
                        val taskKey = "asset_${asset.id}"
                        val taskId  = vm?.state?.collectAsState()?.value?.downloadTaskIds?.get(taskKey)
                        val dlStatus  = taskId?.let { com.gitmob.android.util.GmDownloadManager.statusOf(it) }
                            ?.collectAsState()?.value
                        val dlPct = (dlStatus as? com.gitmob.android.util.DownloadStatus.Progress)?.percent ?: 0
                        val isDownload = dlStatus is com.gitmob.android.util.DownloadStatus.Progress
                        val isDone = dlStatus is com.gitmob.android.util.DownloadStatus.Success
                        val downloadedFile = (dlStatus as? com.gitmob.android.util.DownloadStatus.Success)?.file

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(c.bgItem, RoundedCornerShape(8.dp))
                                .clickable {
                                    if (isDone && downloadedFile != null) {
                                        val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(
                                                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", downloadedFile),
                                                "*/*"
                                            )
                                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(openIntent)
                                    } else {
                                        vm?.downloadAsset(asset)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.Attachment, null, tint = c.textTertiary, modifier = Modifier.size(15.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(asset.name, fontSize = 13.sp, color = c.textPrimary)
                                if (isDownload) {
                                    Text("${dlPct}%", fontSize = 10.sp, color = Coral)
                                } else if (isDone) {
                                    Text("点击打开", fontSize = 10.sp, color = Green)
                                } else {
                                    Text(formatSize(asset.size), fontSize = 10.sp, color = c.textTertiary)
                                }
                            }
                            when {
                                isDone -> Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(16.dp))
                                isDownload -> Icon(Icons.Default.Close, null, tint = Coral, modifier = Modifier.size(16.dp))
                                else -> Icon(Icons.Default.Download, null, tint = Coral, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Actions Tab ─────────────────────────────────────────────────────
