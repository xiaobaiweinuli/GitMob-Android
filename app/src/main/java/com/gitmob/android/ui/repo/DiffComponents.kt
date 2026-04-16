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
import androidx.compose.material.icons.automirrored.filled.Undo
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
import kotlinx.coroutines.launch
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

/** 解析后的 diff 行 */
data class DiffLine(
    val type: DiffLineType,
    val content: String,
    val oldLine: Int?,
    val newLine: Int?,
)
enum class DiffLineType { CONTEXT, ADD, DEL, HUNK, META }

/** 从 unified diff patch 解析为带行号的 DiffLine 列表 */
fun parsePatch(patch: String): List<DiffLine> {
    val result = mutableListOf<DiffLine>()
    var oldLine = 0
    var newLine = 0
    
    if (patch.isBlank()) {
        return result
    }
    
    try {
        for (raw in patch.lines()) {
            when {
                raw.startsWith("@@") -> {
                    // @@ -a,b +c,d @@ 提取起始行号
                    val m = Regex("""-(\d+)(?:,\d+)?\s+\+(\d+)""").find(raw)
                    if (m != null) {
                        oldLine = m.groupValues[1].toIntOrNull()?.minus(1) ?: 0
                        newLine = m.groupValues[2].toIntOrNull()?.minus(1) ?: 0
                    }
                    result += DiffLine(DiffLineType.HUNK, raw, null, null)
                }
                raw.startsWith("+") && !raw.startsWith("+++") -> {
                    newLine++
                    result += DiffLine(DiffLineType.ADD, raw.drop(1), null, newLine)
                }
                raw.startsWith("-") && !raw.startsWith("---") -> {
                    oldLine++
                    result += DiffLine(DiffLineType.DEL, raw.drop(1), oldLine, null)
                }
                raw.startsWith("---") || raw.startsWith("+++") -> {
                    result += DiffLine(DiffLineType.META, raw, null, null)
                }
                else -> {
                    oldLine++; newLine++
                    result += DiffLine(DiffLineType.CONTEXT, raw.drop(1).ifEmpty { raw }, oldLine, newLine)
                }
            }
        }
    } catch (e: Exception) {
    }
    
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDiffSheet(
    info: FilePatchInfo,
    c: GmColors,
    vm: RepoDetailViewModel,
    isArchived: Boolean = false,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lines = remember(info.patch) { parsePatch(info.patch) }

    var showRevertConfirm by remember { mutableStateOf(false) }

    // GitHub 暗色主题（与 GitHub 网页一致）
    val ghBg      = Color(0xFF0D1117)
    val ghCard    = Color(0xFF161B22)
    val ghBorder  = Color(0xFF30363D)
    val ghText    = Color(0xFFE6EDF3)
    val ghMuted   = Color(0xFF8B949E)
    val addBg     = Color(0xFF0D4A29); val addFg = Color(0xFF85E89D)
    val delBg     = Color(0xFF430D18); val delFg = Color(0xFFFFA198)
    val hunkBg    = Color(0xFF1B3A5E); val hunkFg = Color(0xFF79C0FF)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ghBg,
        dragHandle = { BottomSheetDefaults.DragHandle(color = ghBorder) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f),
        ) {
            // ── 头部：文件名 + 统计 + 操作按钮 ─────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ghCard)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Description, null,
                        tint = ghMuted, modifier = Modifier.size(15.dp))
                    Text(
                        if (info.status == "renamed" && info.previousFilename != null)
                            "${info.previousFilename} → ${info.filename}"
                        else info.filename,
                        fontSize = 12.sp, color = ghText,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f), maxLines = 2,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 统计：+additions -deletions
                    if (info.additions > 0)
                        Text("+${info.additions}", fontSize = 12.sp,
                            color = Color(0xFF3FB950), fontWeight = FontWeight.SemiBold)
                    if (info.deletions > 0)
                        Text("-${info.deletions}", fontSize = 12.sp,
                            color = Color(0xFFF85149), fontWeight = FontWeight.SemiBold)
                    // 状态徽章
                    val (badgeBg, badgeFg, badgeText) = when (info.status) {
                        "added"   -> Triple(Color(0xFF0D4A29), Color(0xFF3FB950), "新增")
                        "removed" -> Triple(Color(0xFF430D18), Color(0xFFF85149), "删除")
                        "renamed" -> Triple(Color(0xFF1B3A5E), Color(0xFF79C0FF), "重命名")
                        else      -> Triple(Color(0xFF2D2A1E), Color(0xFFE3B341), "修改")
                    }
                    Text(badgeText, fontSize = 10.sp, color = badgeFg,
                        modifier = Modifier.background(badgeBg, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp))

                    Spacer(Modifier.weight(1f))

                    // 撤销回滚按钮（仅 modified/added/removed 时显示）
                    if (info.parentSha != null && info.currentBranch.isNotBlank()) {
                        val buttonBg = if (isArchived) Color(0xFF21262D) else Color(0xFF3D1F1F)
                        val buttonTint = if (isArchived) Color(0xFF484F58) else Color(0xFFF85149)
                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .background(buttonBg, RoundedCornerShape(6.dp))
                                .clickable(enabled = !isArchived) { showRevertConfirm = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Undo, null,
                                    tint = buttonTint, modifier = Modifier.size(14.dp))
                                Text("回滚提交", fontSize = 11.sp, color = buttonTint)
                            }
                        }
                    }

                    // 关闭按钮
                    IconButton(onClick = onDismiss,
                        modifier = Modifier.size(30.dp)
                            .background(Color(0xFF21262D), RoundedCornerShape(6.dp))) {
                        Icon(Icons.Default.Close, null,
                            tint = ghMuted, modifier = Modifier.size(14.dp))
                    }
                }
            }
            HorizontalDivider(color = ghBorder, thickness = 0.5.dp)

            // ── Diff 主体 ──────────────────────────────────────────────────
            if (lines.isEmpty()) {
                // 没有变更内容时显示提示
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "无变更内容",
                        color = ghMuted,
                        fontSize = 14.sp,
                    )
                }
            } else {
                // 使用 LazyColumn 替代 Column，防止渲染大文件时卡顿
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(lines.filter { it.type != DiffLineType.META }) { dl ->
                        when (dl.type) {
                            DiffLineType.HUNK -> {
                                Row(modifier = Modifier
                                    .fillMaxWidth()
                                    .background(hunkBg)
                                    .padding(vertical = 1.dp)) {
                                    Text(dl.content, fontSize = 10.sp, color = hunkFg,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                }
                            }
                            DiffLineType.ADD, DiffLineType.DEL, DiffLineType.CONTEXT -> {
                                val bg = when (dl.type) {
                                    DiffLineType.ADD  -> addBg
                                    DiffLineType.DEL  -> delBg
                                    else              -> Color.Transparent
                                }
                                val fg = when (dl.type) {
                                    DiffLineType.ADD  -> addFg
                                    DiffLineType.DEL  -> delFg
                                    else              -> ghText
                                }
                                val prefix = when (dl.type) {
                                    DiffLineType.ADD  -> "+"
                                    DiffLineType.DEL  -> "-"
                                    else              -> " "
                                }
                                Row(modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bg)
                                    .height(IntrinsicSize.Min)) {
                                    Text(
                                        text = "$prefix${dl.content}",
                                        fontSize = 11.sp, color = fg,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 16.sp,
                                        softWrap = false,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    // ── 撤销回滚确认弹窗 ────────────────────────────────────────────────────
    if (showRevertConfirm) {
        AlertDialog(
            onDismissRequest = { showRevertConfirm = false },
            containerColor   = Color(0xFF161B22),
            icon  = { Icon(Icons.AutoMirrored.Filled.Undo, null, tint = Color(0xFFF85149)) },
            title = { Text("撤销这次提交？", color = Color(0xFFE6EDF3),
                fontWeight = FontWeight.SemiBold) },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将在分支「${info.currentBranch}」上创建一个新的 revert 提交，\n" +
                         "撤销 ${info.currentSha.take(7)} 的所有变更。\n\n" +
                         "此操作不重写历史，可安全用于保护分支。",
                        fontSize = 13.sp, color = Color(0xFF8B949E), lineHeight = 20.sp)
                    Text("提交 SHA：${info.currentSha.take(7)}",
                        fontSize = 11.sp, color = Color(0xFF79C0FF),
                        fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRevertConfirm = false
                        vm.revertCommit(info.currentSha,
                            "Revert \"${info.filename}\" changes from ${info.currentSha.take(7)}")
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF85149)),
                ) { Text("确认撤销") }
            },
            dismissButton = {
                TextButton(onClick = { showRevertConfirm = false }) {
                    Text("取消", color = Color(0xFF8B949E))
                }
            },
        )
    }
}

// ─── 工具方法 ────────────────────────────────────────────────────────────────

