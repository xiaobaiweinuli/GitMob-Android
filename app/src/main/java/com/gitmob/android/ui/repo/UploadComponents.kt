@file:OptIn(ExperimentalMaterial3Api::class)

package com.gitmob.android.ui.repo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gitmob.android.api.GHContent
import com.gitmob.android.ui.theme.*
import java.io.File

// ─── 常量 ─────────────────────────────────────────────────────────────────────
private const val MAX_FILE_BYTES = 30L * 1024 * 1024   // 30 MB 上限

// ─── 数据类 ───────────────────────────────────────────────────────────────────
/** 待上传文件条目 */
data class UploadFileEntry(
    val localPath: String,          // 本地绝对路径
    val repoPath: String,           // 仓库目标相对路径（含子目录）
    val sizeBytes: Long,
    val tooLarge: Boolean = sizeBytes > MAX_FILE_BYTES,
)

// ─── 工具函数 ─────────────────────────────────────────────────────────────────
/** 递归扫描目录，返回所有文件的 (绝对路径, 相对于rootDir的相对路径) */
fun scanDirectory(rootDir: File, repoBasePath: String): List<UploadFileEntry> {
    if (!rootDir.exists() || !rootDir.isDirectory) return emptyList()
    val results = mutableListOf<UploadFileEntry>()
    fun walk(dir: File, relativeDir: String) {
        dir.listFiles()?.sortedWith(compareBy({ it.isFile }, { it.name }))?.forEach { f ->
            val relPath = if (relativeDir.isEmpty()) f.name else "$relativeDir/${f.name}"
            if (f.isDirectory) {
                walk(f, relPath)
            } else {
                val repoTarget = if (repoBasePath.isEmpty()) relPath else "$repoBasePath/$relPath"
                results.add(UploadFileEntry(f.absolutePath, repoTarget, f.length()))
            }
        }
    }
    walk(rootDir, "")
    return results
}

fun formatUploadSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024        -> "%.1f KB".format(bytes / 1024.0)
    else                 -> "$bytes B"
}

// ─── UploadSourceSheet ────────────────────────────────────────────────────────
/**
 * 第一步弹窗：选择"上传文件"还是"上传文件夹"
 */
@Composable
fun UploadSourceSheet(
    repoPath: String,       // 当前仓库路径，展示用
    c: GmColors,
    onPickFiles: () -> Unit,       // 触发文件选择器（MULTI_FILE）
    onPickFolder: () -> Unit,      // 触发文件夹选择器（DIRECTORY）
    onDismiss: () -> Unit,
) {
    // 消费所有垂直手势，防止 Sheet 跳动
    val consumeAllVerticalScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                return available.copy(x = 0f)
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                return available.copy(x = 0f)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        dragHandle = null,
        sheetGesturesEnabled = false,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = true,
            shouldDismissOnClickOutside = true
        ),
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden }
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .nestedScroll(consumeAllVerticalScroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 标题和关闭按钮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Upload, null, tint = Coral, modifier = Modifier.size(20.dp))
                Text("上传文件", fontSize = 16.sp, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = c.textSecondary, modifier = Modifier.size(20.dp))
                }
            }
            // 目标路径提示
            val displayPath = if (repoPath.isEmpty()) "仓库根目录" else repoPath
            Text(
                "目标路径：$displayPath",
                fontSize = 12.sp, color = c.textTertiary,
                fontFamily = FontFamily.Monospace,
            )
            HorizontalDivider(color = c.border)
            Spacer(Modifier.height(4.dp))

            // 上传文件按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.bgItem, RoundedCornerShape(10.dp))
                    .clickable { onPickFiles() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(40.dp).background(Coral.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = Coral, modifier = Modifier.size(20.dp)) }
                Column(modifier = Modifier.weight(1f)) {
                    Text("选择文件", fontSize = 14.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
                    Text("多选文件，直接上传到当前目录", fontSize = 11.sp, color = c.textTertiary)
                }
                Icon(Icons.Default.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
            }

            // 上传文件夹按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.bgItem, RoundedCornerShape(10.dp))
                    .clickable { onPickFolder() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(40.dp).background(Yellow.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Folder, null, tint = Yellow, modifier = Modifier.size(20.dp)) }
                Column(modifier = Modifier.weight(1f)) {
                    Text("选择文件夹", fontSize = 14.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
                    Text("保留目录结构，可勾选部分文件上传", fontSize = 11.sp, color = c.textTertiary)
                }
                Icon(Icons.Default.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ─── UploadReviewSheet ────────────────────────────────────────────────────────
/**
 * 第二步弹窗：预览文件列表 + 勾选 + commit message
 * allEntries：扫描/选取得到的完整列表（已携带 repoPath）
 * existingFiles：仓库当前目录中已有的文件列表，用于检测重复
 */
@Composable
fun UploadReviewSheet(
    allEntries: List<UploadFileEntry>,
    existingFiles: List<GHContent>,
    c: GmColors,
    onConfirm: (selected: List<UploadFileEntry>, message: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 检测与仓库已有文件的重复
    val duplicatePaths = remember(allEntries, existingFiles) {
        existingFiles.map { it.path }.toSet()
    }
    
    val duplicateEntries = remember(allEntries, duplicatePaths) {
        allEntries.filter { duplicatePaths.contains(it.repoPath) }
    }
    
    // 勾选状态（tooLarge 的默认不勾选，重复的默认也不勾选）
    val checkedMap = remember(allEntries) {
        mutableStateMapOf<String, Boolean>().apply {
            allEntries.forEach { e -> 
                val isDuplicate = duplicatePaths.contains(e.repoPath)
                put(e.localPath, !e.tooLarge && !isDuplicate)
            }
        }
    }
    var commitMsg by remember { mutableStateOf("") }
    var showPathEdit by remember { mutableStateOf<UploadFileEntry?>(null) }
    // 可编辑的 repoPath（key=localPath）
    val pathEditMap = remember(allEntries) {
        mutableStateMapOf<String, String>().apply {
            allEntries.forEach { e -> put(e.localPath, e.repoPath) }
        }
    }

    val validEntries = allEntries.filter { checkedMap[it.localPath] == true && !it.tooLarge }
    val totalBytes   = validEntries.sumOf { it.sizeBytes }
    val allChecked   = allEntries.filter { !it.tooLarge }.all { checkedMap[it.localPath] == true }

    // 消费所有垂直手势，防止 Sheet 跳动
    val consumeAllVerticalScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                return available.copy(x = 0f)
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                return available.copy(x = 0f)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        dragHandle = null,
        sheetGesturesEnabled = false,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = true,
            shouldDismissOnClickOutside = true
        ),
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden }
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .nestedScroll(consumeAllVerticalScroll),
        ) {
            // ── 头部 ──────────────────────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudUpload, null, tint = Coral, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("确认上传文件", fontSize = 15.sp, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (duplicateEntries.isNotEmpty()) {
                        Text(
                            "${duplicateEntries.size} 个文件重复",
                            fontSize = 11.sp, color = Color(0xFFD32F2F),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        "${validEntries.size}/${allEntries.size} 个文件  ${formatUploadSize(totalBytes)}",
                        fontSize = 11.sp, color = c.textTertiary,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = c.textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 全选/取消
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val newVal = !allChecked
                            allEntries.filter { !it.tooLarge }.forEach { checkedMap[it.localPath] = newVal }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = allChecked,
                        onCheckedChange = { newVal ->
                            allEntries.filter { !it.tooLarge }.forEach { checkedMap[it.localPath] = newVal }
                        },
                        colors = CheckboxDefaults.colors(checkedColor = Coral),
                    )
                    Text("全选", fontSize = 13.sp, color = c.textPrimary)
                }
            }
            HorizontalDivider(color = c.border)

            // ── 文件列表 ──────────────────────────────────────────────────────
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(allEntries, key = { it.localPath }) { entry ->
                    val checked  = checkedMap[entry.localPath] ?: false
                    val repoPath = pathEditMap[entry.localPath] ?: entry.repoPath
                    val isDuplicate = duplicatePaths.contains(repoPath)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    entry.tooLarge || isDuplicate -> Color(0x22D32F2F)
                                    else -> c.bgItem
                                },
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = !entry.tooLarge && !isDuplicate) { checkedMap[entry.localPath] = !checked }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Checkbox(
                            checked = checked && !entry.tooLarge && !isDuplicate,
                            onCheckedChange = if (entry.tooLarge || isDuplicate) null
                                             else { v -> checkedMap[entry.localPath] = v },
                            colors = CheckboxDefaults.colors(checkedColor = Coral),
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                File(entry.localPath).name,
                                fontSize = 13.sp,
                                color = when {
                                    entry.tooLarge || isDuplicate -> Color(0xFFD32F2F)
                                    else -> c.textPrimary
                                },
                                maxLines = 1,
                            )
                            Text(
                                repoPath,
                                fontSize = 10.sp, color = c.textTertiary,
                                fontFamily = FontFamily.Monospace, maxLines = 1,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                formatUploadSize(entry.sizeBytes),
                                fontSize = 10.sp,
                                color = when {
                                    entry.tooLarge || isDuplicate -> Color(0xFFD32F2F)
                                    else -> c.textTertiary
                                },
                            )
                            when {
                                entry.tooLarge -> Text("超出限制", fontSize = 9.sp, color = Color(0xFFD32F2F))
                                isDuplicate -> Text("文件重复", fontSize = 9.sp, color = Color(0xFFD32F2F))
                            }
                        }
                        // 编辑目标路径按钮
                        if (!entry.tooLarge) {
                            IconButton(
                                onClick = { showPathEdit = entry },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Default.Edit, null, tint = c.textTertiary, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = c.border)

            // ── Commit Message + 提交按钮 ─────────────────────────────────────
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = commitMsg,
                    onValueChange = { commitMsg = it },
                    label = { Text("Commit 信息") },
                    placeholder = { Text("Add files via GitMob upload") },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor    = Coral,
                        unfocusedBorderColor  = c.border,
                        focusedTextColor      = c.textPrimary,
                        unfocusedTextColor    = c.textPrimary,
                        focusedContainerColor = c.bgItem,
                        unfocusedContainerColor = c.bgItem,
                        focusedLabelColor     = Coral,
                        unfocusedLabelColor   = c.textTertiary,
                    ),
                )
                Button(
                    onClick = {
                        val finalList = validEntries.map { e ->
                            e.copy(repoPath = pathEditMap[e.localPath] ?: e.repoPath)
                        }
                        val msg = commitMsg.ifBlank { "Add files via GitMob upload" }
                        onConfirm(finalList, msg)
                    },
                    enabled = validEntries.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("上传 ${validEntries.size} 个文件", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    // ── 编辑目标路径弹窗 ──────────────────────────────────────────────────────
    showPathEdit?.let { entry ->
        var editVal by remember(entry.localPath) { mutableStateOf(pathEditMap[entry.localPath] ?: entry.repoPath) }
        AlertDialog(
            onDismissRequest = { showPathEdit = null },
            containerColor = c.bgCard,
            title = { Text("修改上传路径", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = editVal, onValueChange = { editVal = it },
                    label = { Text("仓库目标路径") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor    = Coral,
                        unfocusedBorderColor  = c.border,
                        focusedTextColor      = c.textPrimary,
                        unfocusedTextColor    = c.textPrimary,
                        focusedContainerColor = c.bgItem,
                        unfocusedContainerColor = c.bgItem,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = { pathEditMap[entry.localPath] = editVal.trim(); showPathEdit = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showPathEdit = null }) { Text("取消", color = c.textSecondary) }
            },
        )
    }
}

// ─── UploadProgressDialog ─────────────────────────────────────────────────────
/**
 * 上传进度弹窗（不可被用户手动关闭，等待完成/失败）
 */
@Composable
fun UploadProgressDialog(
    phase: UploadPhase,
    blobProgress: Int,
    blobTotal: Int,
    currentFile: String,
    errorMsg: String?,
    c: GmColors,
    onDone: () -> Unit,     // 成功后点"完成"
    onRetry: () -> Unit,    // 失败后点"重试"（关闭弹窗，让用户重新触发）
) {
    Dialog(
        onDismissRequest = {},   // 禁止点外部关闭
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bgCard, RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (phase) {
                UploadPhase.DONE -> {
                    Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(48.dp))
                    Text("上传成功", fontSize = 16.sp, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text("共提交 $blobTotal 个文件", fontSize = 13.sp, color = c.textSecondary)
                    Button(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Coral),
                    ) { Text("完成") }
                }
                UploadPhase.ERROR -> {
                    Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFD32F2F), modifier = Modifier.size(48.dp))
                    Text("上传失败", fontSize = 16.sp, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(errorMsg ?: "未知错误", fontSize = 12.sp, color = c.textSecondary)
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    ) { Text("关闭") }
                }
                else -> {
                    CircularProgressIndicator(color = Coral, modifier = Modifier.size(48.dp), strokeWidth = 3.dp)
                    val phaseText = when (phase) {
                        UploadPhase.BLOBS  -> if (blobTotal > 0) "正在上传文件 ($blobProgress/$blobTotal)" else "正在准备..."
                        UploadPhase.TREE   -> "正在构建目录树..."
                        UploadPhase.COMMIT -> "正在创建 commit..."
                        UploadPhase.REF    -> "正在更新分支..."
                        else               -> "处理中..."
                    }
                    Text(phaseText, fontSize = 14.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
                    if (phase == UploadPhase.BLOBS && blobTotal > 0) {
                        LinearProgressIndicator(
                            progress = { blobProgress.toFloat() / blobTotal.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = Coral,
                            trackColor = c.border,
                        )
                    }
                    if (currentFile.isNotBlank()) {
                        Text(currentFile, fontSize = 11.sp, color = c.textTertiary,
                            fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                    Text("请勿退出页面", fontSize = 11.sp, color = c.textTertiary)
                }
            }
        }
    }
}
