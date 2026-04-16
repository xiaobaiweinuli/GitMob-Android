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


@Composable
fun RepoRenameDialog(
    currentName: String,
    owner: String,
    c: GmColors,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    val pattern = remember { Regex("[a-zA-Z0-9._-]+") }
    val isValid = name.isNotBlank() && name != currentName && name.matches(pattern)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("重命名仓库", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "$owner / $currentName → $owner / $name",
                    fontSize = 12.sp,
                    color = c.textTertiary,
                    fontFamily = FontFamily.Monospace,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("新名称") },
                    supportingText = {
                        if (name.isNotBlank() && !name.matches(pattern)) {
                            Text("只允许字母、数字、点、连字符和下划线", color = RedColor, fontSize = 11.sp)
                        }
                    },
                    isError = name.isNotBlank() && !name.matches(pattern),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Coral,
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
                onClick = { onConfirm(name) },
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Coral,
                    disabledContainerColor = c.border,
                ),
            ) {
                Text("重命名")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    )
}

@Composable
fun RepoEditDialog(
    repo: GHRepo,
    c: GmColors,
    onConfirm: (String, String, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var desc by remember { mutableStateOf(repo.description ?: "") }
    var website by remember { mutableStateOf(repo.homepage ?: "") }
    var topicsText by remember { mutableStateOf(repo.topics.joinToString(" ")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("编辑仓库信息", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RepoTextField(value = desc, onValueChange = { desc = it }, label = "About", maxLines = 3, c = c)
                RepoTextField(value = website, onValueChange = { website = it }, label = "Website", c = c)
                RepoTextField(value = topicsText, onValueChange = { topicsText = it }, label = "Topics（空格分隔）", c = c)
                Text(
                    "Topics 示例：kotlin android github",
                    fontSize = 11.sp,
                    color = c.textTertiary,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val topics = topicsText.split(" ").map { it.trim() }.filter { it.isNotEmpty() }
                    onConfirm(desc, website, topics)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    )
}

@Composable
fun RepoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    c: GmColors,
    maxLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = maxLines == 1,
        maxLines = maxLines,
        modifier = Modifier.fillMaxWidth(),
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
}

@Composable
fun RepoDeleteDialog(
    repoName: String,
    owner: String,
    c: GmColors,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val fullName = "$owner/$repoName"
    val match = fullName

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        icon = {
            Icon(Icons.Default.DeleteForever, null, tint = RedColor, modifier = Modifier.size(28.dp))
        },
        title = { Text("删除仓库", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "此操作将永久删除 $fullName，且无法恢复。",
                    fontSize = 12.sp,
                    color = c.textSecondary,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "点击右侧文字可复制仓库名：",
                        fontSize = 12.sp,
                        color = c.textTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        repoName,
                        fontSize = 12.sp,
                        color = Coral,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(CoralDim, RoundedCornerShape(6.dp))
                            .clickable {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("repoName", repoName))
                                input = repoName
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("请输入仓库名以确认删除", color = c.textTertiary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RedColor,
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
                onClick = onConfirm,
                enabled = input == repoName,
                colors = ButtonDefaults.buttonColors(
                    containerColor = RedColor,
                    disabledContainerColor = c.border,
                ),
            ) { Text("我已知晓风险，确认删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    )
}

@Composable
fun RepoVisibilityDialog(
    repo: GHRepo,
    c: GmColors,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var isPrivate by remember { mutableStateOf(repo.private) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        icon = {
            Icon(Icons.Default.Lock, null, tint = c.textSecondary, modifier = Modifier.size(24.dp))
        },
        title = { Text("更改仓库可见性", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = !isPrivate,
                        onClick = { isPrivate = false },
                        colors = RadioButtonDefaults.colors(selectedColor = BlueColor),
                    )
                    Column {
                        Text("公开仓库", fontSize = 13.sp, color = c.textPrimary)
                        Text("任何人都可以查看此仓库。", fontSize = 11.sp, color = c.textTertiary)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = isPrivate,
                        onClick = { isPrivate = true },
                        colors = RadioButtonDefaults.colors(selectedColor = RedColor),
                    )
                    Column {
                        Text("私有仓库", fontSize = 13.sp, color = c.textPrimary)
                        Text("仅你和被授予权限的协作者可以访问。", fontSize = 11.sp, color = c.textTertiary)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(isPrivate) },
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    )
}

@Composable
fun RepoTransferDialog(
    owner: String,
    repoName: String,
    userLogin: String,
    userAvatar: String,
    orgs: List<GHOrg>,
    c: GmColors,
    onConfirm: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // ── 构建统一候选列表：正确算法 ──────────────────────────────────────────
    // 全部潜在目标 = [当前用户] + [所有组织]
    // 排除当前仓库 owner（不论是用户名还是组织名）
    data class TransferTarget(val login: String, val avatarUrl: String?, val label: String)

    val transferTargets = remember(userLogin, orgs, owner) {
        buildList {
            if (userLogin.isNotBlank() && userLogin != owner) {
                add(TransferTarget(userLogin, userAvatar, "（你）"))
            }
            orgs.filter { it.login != owner }.forEach { org ->
                add(TransferTarget(org.login, org.avatarUrl, ""))
            }
        }
    }

    var selectedLogin by remember { mutableStateOf(transferTargets.firstOrNull()?.login.orEmpty()) }
    var keepName by remember { mutableStateOf(true) }
    var newName by remember { mutableStateOf(repoName) }
    var nameAvailable by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        icon = {
            Icon(Icons.Default.SwapHoriz, null, tint = BlueColor, modifier = Modifier.size(24.dp))
        },
        title = { Text("转移仓库", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.bgItem, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Info, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                    Text("当前归属：$owner / $repoName", fontSize = 12.sp, color = c.textSecondary)
                }

                if (transferTargets.isEmpty()) {
                    Text(
                        "没有可用的转移目标（当前仓库已在你的唯一命名空间下）",
                        fontSize = 12.sp, color = c.textTertiary,
                    )
                } else {
                    Text("选择新的所有者", fontSize = 12.sp, color = c.textSecondary)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        transferTargets.forEach { target ->
                            RepoTransferTargetRow(
                                login = target.login,
                                avatarUrl = target.avatarUrl,
                                selected = selectedLogin == target.login,
                                c = c,
                                onClick = { selectedLogin = target.login },
                                labelSuffix = target.label,
                            )
                        }
                    }
                }

                if (transferTargets.isNotEmpty()) {
                    Text("仓库名称", fontSize = 12.sp, color = c.textSecondary)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RadioButton(selected = keepName, onClick = { keepName = true }, colors = RadioButtonDefaults.colors(selectedColor = BlueColor))
                        Text("保留原名称（$repoName）", fontSize = 12.sp, color = c.textPrimary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RadioButton(selected = !keepName, onClick = { keepName = false }, colors = RadioButtonDefaults.colors(selectedColor = Coral))
                        Text("在目标下重命名", fontSize = 12.sp, color = c.textPrimary)
                    }
                    if (!keepName) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it; nameAvailable = null },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("输入新的仓库名", color = c.textTertiary) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Coral, unfocusedBorderColor = c.border,
                                focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
                                focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem,
                            ),
                            trailingIcon = {
                                when {
                                    checking -> CircularProgressIndicator(color = Coral, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    nameAvailable == true -> Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(16.dp))
                                    nameAvailable == false -> Icon(Icons.Default.Error, null, tint = RedColor, modifier = Modifier.size(16.dp))
                                }
                            },
                        )
                        Text("目标：$selectedLogin / ${newName.ifBlank { repoName }}", fontSize = 11.sp, color = c.textTertiary)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = if (keepName) null else newName.trim().ifBlank { null }
                    onConfirm(selectedLogin, finalName)
                },
                enabled = selectedLogin.isNotBlank() && (keepName || newName.isNotBlank() && nameAvailable != false),
                colors = ButtonDefaults.buttonColors(containerColor = BlueColor),
            ) { Text(if (selectedLogin.isNotBlank()) "确认转移到 $selectedLogin" else "确认转移") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    )
}

@Composable
fun RepoTransferTargetRow(
    login: String,
    avatarUrl: String?,
    selected: Boolean,
    c: GmColors,
    onClick: () -> Unit,
    labelSuffix: String,
) {
    val bg = if (selected) CoralDim else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        coil3.compose.AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(c.bgItem),
        )
        Text(
            login + labelSuffix,
            fontSize = 13.sp,
            color = if (selected) Coral else c.textPrimary,
        )
        if (selected) {
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.Check, null, tint = Coral, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun RepoArchiveDialog(
    repoName: String,
    owner: String,
    isArchived: Boolean,
    c: GmColors,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        icon = {
            Icon(
                if (isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                null,
                tint = if (isArchived) Coral else BlueColor,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Text(
                if (isArchived) "取消归档仓库" else "归档仓库",
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (isArchived)
                        "取消归档 $owner/$repoName 后，你可以再次编辑仓库、接受拉取请求和打开议题。"
                    else
                        "归档 $owner/$repoName 后，仓库将变为只读，你无法编辑仓库、接受拉取请求或打开议题。",
                    fontSize = 12.sp,
                    color = c.textSecondary,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isArchived) Coral else BlueColor,
                ),
            ) {
                Text(if (isArchived) "取消归档" else "归档")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textSecondary)
            }
        },
    )
}

/**
 * 获取运行时间或状态文本 - 已完成显示时长，未完成显示状态
 */
