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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchSheet(
    state: RepoDetailState,
    vm: RepoDetailViewModel,
    onDismiss: () -> Unit,
) {
    val c = LocalGmColors.current

    /**
     * GitHub REST API PUT /repos/{owner}/{repo}/subscription 仅支持两个字段：
     *   subscribed（bool）和 ignored（bool），无法设置细粒度事件类型。
     *
     * 三档对应规则：
     *   subscription == null → PARTICIPATING（未设置，GitHub 默认）
     *   ignored == true      → IGNORE
     *   subscribed == true   → ALL_ACTIVITY
     */
    val currentMode: WatchMode = when {
        state.subscription == null      -> WatchMode.PARTICIPATING
        state.subscription.ignored      -> WatchMode.IGNORE
        state.subscription.subscribed   -> WatchMode.ALL_ACTIVITY
        else                            -> WatchMode.PARTICIPATING
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("订阅通知", fontSize = 16.sp, color = c.textPrimary,
                    fontWeight = FontWeight.SemiBold)
                if (state.subscriptionLoading)
                    CircularProgressIndicator(modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp, color = Coral)
            }
            GmDivider()
            Spacer(Modifier.height(4.dp))

            WatchOptionRow(
                icon     = Icons.Default.NotificationsNone,
                title    = "参与后 @提及",
                desc     = "仅在参与或被提及时收到通知",
                selected = currentMode == WatchMode.PARTICIPATING,
                c        = c,
                onClick  = { vm.setWatchMode(WatchMode.PARTICIPATING); onDismiss() },
            )
            WatchOptionRow(
                icon     = Icons.Default.Notifications,
                title    = "所有活动",
                desc     = "接收该仓库的所有通知",
                selected = currentMode == WatchMode.ALL_ACTIVITY,
                c        = c,
                onClick  = { vm.setWatchMode(WatchMode.ALL_ACTIVITY); onDismiss() },
            )
            WatchOptionRow(
                icon     = Icons.Default.NotificationsOff,
                title    = "忽略",
                desc     = "永不收到该仓库的通知（除非被 @提及）",
                selected = currentMode == WatchMode.IGNORE,
                c        = c,
                onClick  = { vm.setWatchMode(WatchMode.IGNORE); onDismiss() },
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "通知粒度设置请前往 GitHub 网页版进行配置",
                fontSize = 11.sp,
                color = c.textTertiary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 订阅选项行（圆圈单选 + 图标 + 标题 + 描述） */
@Composable
fun WatchOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    selected: Boolean,
    c: GmColors,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(
            selected = selected, onClick = null,
            colors   = RadioButtonDefaults.colors(selectedColor = Coral),
        )
        Icon(icon, null,
            tint = if (selected) Coral else c.textSecondary,
            modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
            Text(desc,  fontSize = 12.sp, color = c.textSecondary)
        }
        if (selected)
            Icon(Icons.Default.Check, null, tint = Coral, modifier = Modifier.size(18.dp))
    }
}
