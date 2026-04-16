package com.gitmob.android.ui.local



import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.gitmob.android.ui.theme.*


sealed class PushWizardStep {
    object None          : PushWizardStep()
    object SelectRemote  : PushWizardStep()
    data class Running(val log: List<String> = emptyList()) : PushWizardStep()
    data class Done(val log: List<String>, val success: Boolean) : PushWizardStep()
}


/**
 * 一键上云向导 BottomSheet
 *
 * 流程：填写远程地址 + commit 信息 → init → add → commit → push
 * 底层使用 JGit，无需外部 git 可执行文件，无需 Termux/Root。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitOperationSheet(
    repo: LocalRepo,
    wizardStep: PushWizardStep,
    onPush: (remoteUrl: String, commitMsg: String, branch: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalGmColors.current

    var remoteUrl by remember {
        mutableStateOf(
            repo.remoteUrl?.replace(Regex("https://[^@]+@github\\.com/"), "https://github.com/") ?: ""
        )
    }
    var commitMsg by remember { mutableStateOf("Initial commit via GitMob") }
    var branch    by remember { mutableStateOf(repo.currentBranch ?: "main") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        sheetMaxWidth = 600.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (wizardStep) {
                is PushWizardStep.None, is PushWizardStep.SelectRemote -> {
                    PushFormContent(
                        repo        = repo,
                        remoteUrl   = remoteUrl,
                        commitMsg   = commitMsg,
                        branch      = branch,
                        c           = c,
                        onRemoteUrl = { remoteUrl = it },
                        onCommitMsg = { commitMsg = it },
                        onBranch    = { branch = it },
                        onPush      = {
                            if (remoteUrl.isNotBlank())
                                onPush(remoteUrl.trim(), commitMsg.trim(), branch.trim())
                        },
                    )
                }
                is PushWizardStep.Running -> {
                    RunningLog(wizardStep.log, c, isDone = false)
                }
                is PushWizardStep.Done -> {
                    RunningLog(wizardStep.log, c, isDone = true)
                    Spacer(Modifier.height(4.dp))
                    PushResultBanner(wizardStep.success, c)
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = c.bgItem),
                    ) { Text("关闭", color = c.textPrimary) }
                }
            }
        }
    }
}

// ─── 推送表单 ──────────────────────────────────────────────────────────────────

@Composable
private fun PushFormContent(
    repo: LocalRepo,
    remoteUrl: String,
    commitMsg: String,
    branch: String,
    c: GmColors,
    onRemoteUrl: (String) -> Unit,
    onCommitMsg: (String) -> Unit,
    onBranch:    (String) -> Unit,
    onPush:      () -> Unit,
) {
    // 标题行
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.CloudUpload, null, tint = Coral, modifier = Modifier.size(22.dp))
        Column {
            Text("一键上云", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.textPrimary)
            Text(
                repo.path,
                fontSize = 11.sp, color = c.textTertiary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }

    // JGit 徽章（无需外部 git）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A2A1A), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(14.dp))
        Text(
            "由 JGit 驱动 · 无需安装 git 或 Termux",
            fontSize = 12.sp, color = Green,
        )
    }

    // 步骤指示器
    WizardStepIndicator(repo.status, c)

    // 表单字段
    GmInputField(
        label       = "GitHub 仓库地址",
        value       = remoteUrl,
        placeholder = "https://github.com/username/repo",
        c           = c,
        onValueChange = onRemoteUrl,
    )
    GmInputField(
        label       = "提交信息",
        value       = commitMsg,
        placeholder = "Initial commit",
        c           = c,
        onValueChange = onCommitMsg,
    )
    GmInputField(
        label       = "目标分支",
        value       = branch,
        placeholder = "main",
        c           = c,
        onValueChange = onBranch,
    )

    // 待初始化提示
    if (repo.status == LocalRepoStatus.PENDING_INIT) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(YellowDim, RoundedCornerShape(8.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, null, tint = Yellow, modifier = Modifier.size(16.dp))
            Text(
                "将自动执行：git init → add → commit → push",
                fontSize = 12.sp, color = Yellow,
            )
        }
    }

    // 推送按钮
    Button(
        onClick  = onPush,
        enabled  = remoteUrl.isNotBlank() && commitMsg.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape    = RoundedCornerShape(14.dp),
        colors   = ButtonDefaults.buttonColors(containerColor = Coral),
    ) {
        Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("开始推送", fontWeight = FontWeight.SemiBold)
    }
}

// ─── 步骤指示器 ────────────────────────────────────────────────────────────────

@Composable
private fun WizardStepIndicator(status: LocalRepoStatus, c: GmColors) {
    val steps = listOf("git init", "git add .", "git commit", "git push")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgItem, RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { i, step ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val isActive = status == LocalRepoStatus.PENDING_INIT || i > 0
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            if (isActive) CoralDim else c.bgActive,
                            RoundedCornerShape(50),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${i + 1}", fontSize = 11.sp,
                        color = if (isActive) Coral else c.textTertiary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(step, fontSize = 9.sp, color = c.textTertiary, fontFamily = FontFamily.Monospace)
            }
            if (i < steps.lastIndex) {
                // Material 3 非废弃版本
                HorizontalDivider(
                    modifier  = Modifier.width(16.dp),
                    color     = c.border,
                    thickness = 0.5.dp,
                )
            }
        }
    }
}

// ─── 执行日志 ──────────────────────────────────────────────────────────────────

@Composable
private fun RunningLog(log: List<String>, c: GmColors, isDone: Boolean) {
    Text("执行日志", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.textPrimary)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 300.dp)
            .background(Color(0xFF0A0E14), RoundedCornerShape(10.dp))
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        log.forEach { line ->
            val color = when {
                line.startsWith("✓") -> Color(0xFF4ADE80)   // 绿色：成功
                line.startsWith("✗") -> Color(0xFFF87171)   // 红色：失败
                line.startsWith("→") -> Color(0xFF60A5FA)   // 蓝色：步骤
                else                 -> Color(0xFFB0BAD0)   // 灰色：信息
            }
            Text(line, fontSize = 12.sp, color = color, fontFamily = FontFamily.Monospace)
        }
        if (!isDone) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color       = Coral,
                    modifier    = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                )
                Text("执行中…", fontSize = 11.sp,
                    color = Color(0xFF5C6580), fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ─── 结果横幅 ──────────────────────────────────────────────────────────────────

@Composable
private fun PushResultBanner(success: Boolean, c: GmColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (success) GreenDim else RedDim,
                RoundedCornerShape(10.dp),
            )
            .padding(12.dp),
        verticalAlignment  = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (success) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint     = if (success) Green else RedColor,
            modifier = Modifier.size(20.dp),
        )
        Column {
            Text(
                if (success) "推送成功！" else "推送失败",
                color          = if (success) Green else RedColor,
                fontWeight     = FontWeight.SemiBold,
                fontSize       = 14.sp,
            )
            if (!success) {
                Text(
                    "请检查仓库地址、网络连接和 GitHub Token 权限",
                    color    = RedColor.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

// ─── 输入框 ────────────────────────────────────────────────────────────────────

@Composable
private fun GmInputField(
    label: String,
    value: String,
    placeholder: String,
    c: GmColors,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value         = value,
            onValueChange = onValueChange,
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            placeholder   = { Text(placeholder, color = c.textTertiary, fontSize = 13.sp) },
            shape         = RoundedCornerShape(10.dp),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Coral,
                unfocusedBorderColor = c.border,
                focusedTextColor     = c.textPrimary,
                unfocusedTextColor   = c.textPrimary,
                focusedContainerColor   = c.bgItem,
                unfocusedContainerColor = c.bgItem,
            ),
        )
    }
}
