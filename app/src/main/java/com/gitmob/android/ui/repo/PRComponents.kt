@file:OptIn(ExperimentalMaterial3Api::class)

package com.gitmob.android.ui.repo

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gitmob.android.api.*
import com.gitmob.android.ui.common.*
import com.gitmob.android.ui.theme.*
import com.gitmob.android.util.LogManager
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

// ─── 颜色常量 ─────────────────────────────────────────────────────────────────
private val PurpleColor = Color(0xFF8957E5)
private val PurpleDim   = Color(0x1A8957E5)
private val MergedColor = Color(0xFF8957E5)
private val DraftColor  = Color(0xFF6E7681)
private val DraftDim    = Color(0x1A6E7681)

// ─────────────────────────────────────────────────────────────────────────────
// PR Tab 入口
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PRTab(
    state: RepoDetailState,
    c: GmColors,
    vm: RepoDetailViewModel,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onRefresh: () -> Unit = {},
    onPRClick: (Int) -> Unit = {},
) {
    // 懒加载：第一次进入 Tab 时触发
    LaunchedEffect(Unit) { vm.ensurePRsLoaded() }

    val listState = rememberLazyListState()
    val isAtBottom = remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last != null && last.index >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(isAtBottom.value) {
        if (isAtBottom.value && state.prsHasMore && !state.prsLoadingMore) vm.loadMorePRs()
    }

    // 操作结果 Toast
    state.prOpResult?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2500)
            vm.clearPROpResult()
        }
    }

    PullToRefreshBox(isRefreshing = state.prsRefreshing, onRefresh = onRefresh) {
        Column {
            // ── 筛选工具栏 ────────────────────────────────────────────────
            PRFilterToolbar(state = state, c = c, vm = vm, permission = permission, isArchived = isArchived)

            if (state.prs.isEmpty() && !state.prsRefreshing) {
                EmptyBox("暂无 Pull Request")
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.prs, key = { it.number }) { pr ->
                        PRCard(pr = pr, c = c, onClick = { onPRClick(pr.number) })
                    }
                    if (state.prsLoadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Coral)
                            }
                        }
                    }
                }
            }
        }
    }

    // 悬浮 Toast
    state.prOpResult?.let {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Snackbar(
                modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp),
                containerColor = c.bgCard, contentColor = c.textPrimary,
            ) { Text(it) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 筛选工具栏
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PRFilterToolbar(
    state: RepoDetailState,
    c: GmColors,
    vm: RepoDetailViewModel,
    permission: RepoPermission,
    isArchived: Boolean = false,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showStatusMenu   by remember { mutableStateOf(false) }
    var showSortSheet    by remember { mutableStateOf(false) }
    var showLabelSheet   by remember { mutableStateOf(false) }
    var showAuthorSheet  by remember { mutableStateOf(false) }
    var showReviewSheet  by remember { mutableStateOf(false) }

    val hasFilters = state.prFilterState.hasAnyFilters()
    val allLabels  = vm.getAllLabels().sorted()
    val allAuthors = vm.getAllAuthors().sorted()

    Column(
        modifier = Modifier.fillMaxWidth().background(c.bgCard)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 清除按钮（有任何非默认筛选时显示）
            if (hasFilters) {
                TextButton(
                    onClick = { vm.clearPRFilters() },
                    colors = ButtonDefaults.textButtonColors(contentColor = RedColor),
                ) { Text("清除", fontSize = 13.sp) }
            }

            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 状态筛选按钮（下拉菜单）
                Box {
                    com.gitmob.android.ui.repos.FilterButton(
                        text = state.prFilterState.status.displayName,
                        isActive = state.prFilterState.status != PRStatusFilter.OPEN,
                        c = c, onClick = { showStatusMenu = true },
                    )
                    DropdownMenu(expanded = showStatusMenu,
                        onDismissRequest = { showStatusMenu = false },
                        modifier = Modifier.background(c.bgCard)) {
                        PRStatusFilter.entries.forEach { status ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        RadioButton(
                                            selected = state.prFilterState.status == status,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(selectedColor = Coral),
                                        )
                                        Text(status.displayName, fontSize = 13.sp, color = c.textPrimary)
                                    }
                                },
                                onClick = { vm.setPRStatusFilter(status); showStatusMenu = false },
                            )
                        }
                    }
                }

                // 排序按钮
                com.gitmob.android.ui.repos.FilterButton(
                    text = state.prFilterState.sortBy.displayName,
                    isActive = state.prFilterState.sortBy != PRSortBy.CREATED_DESC,
                    c = c, onClick = { showSortSheet = true },
                )

                // 标签筛选
                com.gitmob.android.ui.repos.FilterButton(
                    text = "标签",
                    isActive = state.prFilterState.labelFilter != null,
                    c = c, onClick = { showLabelSheet = true },
                )

                // 作者筛选
                com.gitmob.android.ui.repos.FilterButton(
                    text = "作者",
                    isActive = state.prFilterState.authorFilter != null,
                    c = c, onClick = { showAuthorSheet = true },
                )

                // 审查者筛选
                com.gitmob.android.ui.repos.FilterButton(
                    text = "审查",
                    isActive = state.prFilterState.reviewerFilter != null,
                    c = c, onClick = { showReviewSheet = true },
                )
            }

            // 创建 PR
            if (permission.canWrite) {
                IconButton(
                    onClick = { showCreateDialog = true }, 
                    modifier = Modifier.size(36.dp),
                    enabled = !isArchived
                ) {
                    Icon(
                        Icons.Default.Add, 
                        null, 
                        tint = if (isArchived) c.textTertiary else Coral, 
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    GmDivider()

    // ── 排序 Sheet ────────────────────────────────────────────────────────────
    if (showSortSheet) {
        com.gitmob.android.ui.repos.RepoFilterBottomSheet(title = "排序方式", c = c,
            onDismiss = { showSortSheet = false }) {
            PRSortBy.entries.forEach { sort ->
                com.gitmob.android.ui.repos.FilterOptionItem(
                    text = sort.displayName,
                    isSelected = state.prFilterState.sortBy == sort,
                    isRadio = true, c = c,
                    onClick = { vm.setPRSortBy(sort); showSortSheet = false },
                )
            }
        }
    }

    // ── 标签 Sheet ────────────────────────────────────────────────────────────
    if (showLabelSheet) {
        com.gitmob.android.ui.repos.RepoFilterBottomSheet(title = "按标签筛选", c = c,
            onDismiss = { showLabelSheet = false }) {
            com.gitmob.android.ui.repos.FilterOptionItem(
                text = "全部标签", isSelected = state.prFilterState.labelFilter == null,
                isRadio = true, c = c,
                onClick = { vm.setPRLabelFilter(null); showLabelSheet = false },
            )
            allLabels.forEach { label ->
                com.gitmob.android.ui.repos.FilterOptionItem(
                    text = label, isSelected = state.prFilterState.labelFilter == label,
                    isRadio = true, c = c,
                    onClick = { vm.setPRLabelFilter(label); showLabelSheet = false },
                )
            }
        }
    }

    // ── 作者 Sheet ────────────────────────────────────────────────────────────
    if (showAuthorSheet) {
        com.gitmob.android.ui.repos.RepoFilterBottomSheet(title = "按作者筛选", c = c,
            onDismiss = { showAuthorSheet = false }) {
            com.gitmob.android.ui.repos.FilterOptionItem(
                text = "全部作者", isSelected = state.prFilterState.authorFilter == null,
                isRadio = true, c = c,
                onClick = { vm.setPRAuthorFilter(null); showAuthorSheet = false },
            )
            allAuthors.forEach { author ->
                com.gitmob.android.ui.repos.FilterOptionItem(
                    text = author, isSelected = state.prFilterState.authorFilter == author,
                    isRadio = true, c = c,
                    onClick = { vm.setPRAuthorFilter(author); showAuthorSheet = false },
                )
            }
        }
    }

    // ── 审查者 Sheet ──────────────────────────────────────────────────────────
    if (showReviewSheet) {
        com.gitmob.android.ui.repos.RepoFilterBottomSheet(title = "按审查者筛选", c = c,
            onDismiss = { showReviewSheet = false }) {
            com.gitmob.android.ui.repos.FilterOptionItem(
                text = "全部审查者", isSelected = state.prFilterState.reviewerFilter == null,
                isRadio = true, c = c,
                onClick = { vm.setPRReviewerFilter(null); showReviewSheet = false },
            )
            allAuthors.forEach { reviewer ->
                com.gitmob.android.ui.repos.FilterOptionItem(
                    text = reviewer, isSelected = state.prFilterState.reviewerFilter == reviewer,
                    isRadio = true, c = c,
                    onClick = { vm.setPRReviewerFilter(reviewer); showReviewSheet = false },
                )
            }
        }
    }

    if (showCreateDialog) {
        CreatePRDialog(
            branches = state.branches, c = c,
            onConfirm = { title, body, head, base, draft ->
                vm.createPR(title, body, head, base, draft); showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 创建 PR 对话框
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CreatePRDialog(
    branches: List<GHBranch>,
    c: GmColors,
    onConfirm: (title: String, body: String, head: String, base: String, draft: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var title   by remember { mutableStateOf("") }
    var body    by remember { mutableStateOf("") }
    var draft   by remember { mutableStateOf(false) }
    var headBranch by remember { mutableStateOf(branches.firstOrNull()?.name ?: "") }
    var baseBranch by remember { mutableStateOf(branches.firstOrNull()?.name ?: "") }
    var showHeadMenu by remember { mutableStateOf(false) }
    var showBaseMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("创建 Pull Request", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 标题
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("标题", color = c.textTertiary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = prTextFieldColors(c),
                )
                // head 分支选择器
                Text("源分支（head）", fontSize = 12.sp, color = c.textTertiary)
                Box {
                    OutlinedTextField(
                        value = headBranch, onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { showHeadMenu = true }) {
                                Icon(Icons.Default.ArrowDropDown, null, tint = c.textSecondary)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = prTextFieldColors(c),
                    )
                    DropdownMenu(
                        expanded = showHeadMenu, onDismissRequest = { showHeadMenu = false },
                        modifier = Modifier.background(c.bgCard).heightIn(max = 240.dp),
                    ) {
                        branches.forEach { b ->
                            DropdownMenuItem(
                                text = { Text(b.name, color = c.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace) },
                                onClick = { headBranch = b.name; showHeadMenu = false },
                            )
                        }
                    }
                }
                // base 分支选择器
                Text("目标分支（base）", fontSize = 12.sp, color = c.textTertiary)
                Box {
                    OutlinedTextField(
                        value = baseBranch, onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { showBaseMenu = true }) {
                                Icon(Icons.Default.ArrowDropDown, null, tint = c.textSecondary)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = prTextFieldColors(c),
                    )
                    DropdownMenu(
                        expanded = showBaseMenu, onDismissRequest = { showBaseMenu = false },
                        modifier = Modifier.background(c.bgCard).heightIn(max = 240.dp),
                    ) {
                        branches.forEach { b ->
                            DropdownMenuItem(
                                text = { Text(b.name, color = c.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace) },
                                onClick = { baseBranch = b.name; showBaseMenu = false },
                            )
                        }
                    }
                }
                // 描述
                OutlinedTextField(
                    value = body, onValueChange = { body = it },
                    label = { Text("描述（可选）", color = c.textTertiary) },
                    minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    colors = prTextFieldColors(c),
                )
                // 草稿开关
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { draft = !draft }) {
                    Checkbox(
                        checked = draft, onCheckedChange = { draft = it },
                        colors = CheckboxDefaults.colors(checkedColor = Coral),
                    )
                    Text("创建为草稿 PR", fontSize = 13.sp, color = c.textSecondary)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title, body, headBranch, baseBranch, draft) },
                enabled = title.isNotBlank() && headBranch != baseBranch,
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    )
}

@Composable
private fun prTextFieldColors(c: GmColors) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = Coral,
    unfocusedBorderColor = c.border,
    focusedTextColor     = c.textPrimary,
    unfocusedTextColor   = c.textPrimary,
    focusedContainerColor   = c.bgItem,
    unfocusedContainerColor = c.bgItem,
    focusedLabelColor    = Coral,
    unfocusedLabelColor  = c.textTertiary,
)

// ─────────────────────────────────────────────────────────────────────────────
// PR 列表卡片
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PRCard(pr: GHPullRequest, c: GmColors, onClick: () -> Unit) {
    val (statusColor, statusBg, statusIcon, statusLabel) = when {
        pr.isMerged -> Quadruple(MergedColor, PurpleDim, Icons.AutoMirrored.Filled.MergeType, "已合并")
        pr.isClosed -> Quadruple(c.textTertiary, c.bgItem,  Icons.Default.Cancel,              "已关闭")
        pr.isDraft  -> Quadruple(DraftColor,  DraftDim,  Icons.Default.Edit,                "草稿")
        else        -> Quadruple(Green,       GreenDim,  Icons.AutoMirrored.Filled.CallMerge,            "开放")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(12.dp))
            .border(0.5.dp, c.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        // 第一行：状态徽章 + 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 状态徽章
            Row(
                modifier = Modifier
                    .background(statusBg, RoundedCornerShape(20.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(12.dp))
                Text(statusLabel, fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Medium)
            }
            // 标题
            Text(
                pr.title,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(8.dp))

        // 第二行：head → base 分支
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BranchChip(pr.head.ref, c)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
            BranchChip(pr.base.ref, c)
        }

        Spacer(Modifier.height(8.dp))

        // 第三行：标签（如果有）
        if (pr.labels.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                pr.labels.forEach { label ->
                    val bg = try { Color(android.graphics.Color.parseColor("#${label.color}")) }
                             catch (_: Exception) { c.bgItem }
                    val fg = if (isColorLight(bg)) Color(0xFF24292F) else Color.White
                    Text(
                        label.name,
                        fontSize = 10.sp, color = fg, fontWeight = FontWeight.Medium,
                        modifier = Modifier.background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 第四行：数字编号、作者、日期、评论数量
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 数字编号
            Text(
                "#${pr.number}",
                fontSize = 11.sp,
                color = c.textTertiary,
                fontFamily = FontFamily.Monospace,
            )
            // · 分隔符
            Text(
                "·",
                fontSize = 11.sp,
                color = c.textTertiary,
            )
            // 作者
            Text(
                pr.user.login,
                fontSize = 11.sp,
                color = c.textTertiary,
            )
            // · 分隔符
            Text(
                "·",
                fontSize = 11.sp,
                color = c.textTertiary,
            )
            // 日期
            Text(
                repoFormatDate(pr.createdAt),
                fontSize = 11.sp,
                color = c.textTertiary,
            )
            // 评论数量徽章（与 GitHub 网页一致，0条时不显示）
            val commentCount = pr.comments
            if (commentCount > 0) {
                // · 分隔符
                Text(
                    "·",
                    fontSize = 11.sp,
                    color = c.textTertiary,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Comment,
                        contentDescription = "评论数",
                        tint = c.textTertiary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = commentCount.toString(),
                        fontSize = 11.sp,
                        color = c.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun BranchChip(ref: String, c: GmColors) {
    Text(
        ref,
        fontSize = 11.sp,
        color = BlueColor,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(BlueDim, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

// ─────────────────────────────────────────────────────────────────────────────
// PR 详情页
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PRDetailScreen(
    owner: String,
    repoName: String,
    prNumber: Int,
    onBack: () -> Unit,
    vm: PRDetailViewModel = viewModel(
        factory = PRDetailViewModel.factory(owner, repoName, prNumber)
    ),
) {
    val c = LocalGmColors.current
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val repoForPermission = remember(owner) {
        GHRepo(
            id = 0,
            name = repoName,
            fullName = "$owner/$repoName",
            description = null,
            homepage = null,
            private = false,
            htmlUrl = "",
            sshUrl = "",
            cloneUrl = "",
            defaultBranch = "",
            updatedAt = null,
            owner = GHOwner(
                login = owner,
                avatarUrl = null
            ),
            pushedAt = null,
            language = null
        )
    }
    val permission = rememberRepoPermission(repoForPermission, state.userLogin)

    var showMenu by remember { mutableStateOf(false) }
    var showEditTitleDialog by remember { mutableStateOf(false) }
    var showEditBodyDialog by remember { mutableStateOf(false) }
    var showChangeBaseBranchDialog by remember { mutableStateOf(false) }
    var showMergeDialog   by remember { mutableStateOf(false) }
    var showReviewDialog  by remember { mutableStateOf(false) }
    var showReviewerDialog by remember { mutableStateOf(false) }
    var showCommentEditor by remember { mutableStateOf<GHComment?>(null) }
    var showDeleteCommentDialog by remember { mutableStateOf<GHComment?>(null) }
    var commentText       by remember { mutableStateOf("") }
    var showCloseConfirm  by remember { mutableStateOf(false) }
    var showCommentInputDialog by remember { mutableStateOf(false) }
    var replyingToComment by remember { mutableStateOf<GHComment?>(null) }
    var replyingToPR by remember { mutableStateOf(false) }
    var replyingToReviewText by remember { mutableStateOf<String?>(null) }
    var showEditHistory by remember { mutableStateOf<PREditHistoryType?>(null) }

    fun isEdited(createdAt: String, updatedAt: String?): Boolean {
        if (updatedAt == null) return false
        return try {
            val created = java.time.OffsetDateTime.parse(createdAt)
            val updated = java.time.OffsetDateTime.parse(updatedAt)
            val duration = java.time.Duration.between(created, updated)
            duration.toMinutes() > 1
        } catch (_: Exception) {
            false
        }
    }

    state.toast?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2500)
            vm.clearToast()
        }
    }

    val currentPR = state.pr

    Scaffold(
        containerColor = c.bgDeep,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PR #$prNumber", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.textPrimary)
                        Text("$owner/$repoName", fontSize = 12.sp, color = c.textTertiary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.textSecondary)
                    }
                },
                actions = {
                    Row {
                        currentPR?.let { pr ->
                            IconButton(onClick = { sharePR(context, pr) }) {
                                Icon(Icons.Default.Share, null, tint = c.textSecondary)
                            }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, null, tint = c.textSecondary)
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    modifier = Modifier.background(c.bgCard),
                                ) {
                                    val isSubscribed = state.isSubscribed
                                    val isPRAuthor = pr.user.login == state.userLogin
                                    val canEditOrClose = permission.isOwner || isPRAuthor
                                    val canLock = permission.isOwner || permission.canWrite
                                    val isArchived = state.isArchived

                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (isSubscribed) "取消订阅" else "订阅",
                                                fontSize = 14.sp,
                                                color = c.textPrimary
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (isSubscribed) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                                                null,
                                                tint = c.textSecondary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            vm.toggleSubscription()
                                        },
                                    )

                                    if (canEditOrClose) {
                                        ArchivedAwareDropdownMenuItem(
                                            text = { Text("编辑标题", fontSize = 14.sp) },
                                            isArchived = isArchived,
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    null,
                                                    tint = c.textSecondary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                showEditTitleDialog = true
                                            },
                                        )
                                    }

                                    if (canEditOrClose) {
                                        ArchivedAwareDropdownMenuItem(
                                            text = { Text("更改基本分支", fontSize = 14.sp) },
                                            isArchived = isArchived,
                                            leadingIcon = {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.CallMerge,
                                                    null,
                                                    tint = c.textSecondary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                showChangeBaseBranchDialog = true
                                            },
                                        )
                                    }

                                    if (canLock) {
                                        ArchivedAwareDropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (pr.locked) "解锁对话" else "锁定对话",
                                                    fontSize = 14.sp,
                                                )
                                            },
                                            isArchived = isArchived,
                                            leadingIcon = {
                                                Icon(
                                                    if (pr.locked) Icons.Default.LockOpen else Icons.Default.Lock,
                                                    null,
                                                    tint = c.textSecondary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                if (pr.locked) {
                                                    vm.unlockPR()
                                                } else {
                                                    vm.lockPR()
                                                }
                                            },
                                        )
                                    }

                                    if (canEditOrClose && pr.isOpen) {
                                        ArchivedAwareDropdownMenuItem(
                                            text = { Text("关闭拉取请求", fontSize = 14.sp) },
                                            isArchived = isArchived,
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Close,
                                                    null,
                                                    tint = c.textSecondary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                showCloseConfirm = true
                                            },
                                        )
                                    } else if (canEditOrClose && pr.isClosed) {
                                        ArchivedAwareDropdownMenuItem(
                                            text = { Text("重新打开拉取请求", fontSize = 14.sp) },
                                            isArchived = isArchived,
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Check,
                                                    null,
                                                    tint = c.textSecondary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                showCloseConfirm = true
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep),
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.loadPRDetail(forceRefresh = true) },
            modifier = Modifier.padding(paddingValues),
        ) {
            if (state.loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Coral)
                }
            } else if (state.error != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error!!, color = c.textSecondary)
                }
            } else if (currentPR != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { PRHeader(pr = currentPR, c = c) }

                    item {
                        PRActionPanel(
                            pr = currentPR,
                            c  = c,
                            permission = permission,
                            isArchived = state.isArchived,
                            onMergeClick        = { showMergeDialog = true },
                            onReviewClick       = { showReviewDialog = true },
                            onReviewerClick     = { showReviewerDialog = true },
                            onUpdateBranchClick = { vm.updatePRBranch() },
                            onMarkReadyClick    = { vm.markPRReadyForReview() },
                            onToggleStateClick  = { showCloseConfirm = true },
                        )
                    }

                    item {
                        PRBodyCard(
                            pr = currentPR,
                            c = c,
                            userLogin = state.userLogin,
                            permission = permission,
                            isArchived = state.isArchived,
                            onReply = {
                                replyingToPR = true
                                showCommentInputDialog = true
                            },
                            onEdit = { showEditBodyDialog = true },
                            onShare = { sharePR(context, currentPR) },
                            onShowEditHistory = {
                                showEditHistory = PREditHistoryType.PRBody(currentPR)
                            }
                        )
                    }

                    items(state.reviews, key = { it.id }) { review ->
                        ReviewItemAsComment(
                            review = review,
                            c = c,
                            userLogin = state.userLogin,
                            permission = permission,
                            isArchived = state.isArchived,
                            onReply = { text ->
                                replyingToReviewText = text
                                showCommentInputDialog = true
                            },
                            onShare = { shareReview(context, it) },
                        )
                    }
                    
                    items(state.comments, key = { it.id }) { comment ->
                        PRCommentItem(
                            comment = comment,
                            c = c,
                            userLogin = state.userLogin,
                            permission = permission,
                            isArchived = state.isArchived,
                            onReply = { 
                                replyingToComment = it
                                showCommentInputDialog = true
                            },
                            onEdit = { showCommentEditor = it },
                            onDelete = { showDeleteCommentDialog = it },
                            onShare = { shareComment(context, it) },
                            onShowEditHistory = { showEditHistory = PREditHistoryType.Comment(it) },
                        )
                    }

                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.BottomEnd,
        ) {
            FloatingActionButton(
                onClick = { 
                    if (!state.isArchived) {
                        showCommentInputDialog = true 
                    }
                },
                containerColor = if (state.isArchived) Coral.copy(alpha = 0.38f) else Coral,
                contentColor = if (state.isArchived) Color.White.copy(alpha = 0.38f) else Color.White,
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Comment, null)
            }
        }
    }

    // ── 合并对话框 ────────────────────────────────────────────────────────────
    if (showMergeDialog && currentPR != null) {
        MergePRDialog(
            pr = currentPR, c = c,
            onConfirm = { method, title, msg -> vm.mergePR(method, title, msg); showMergeDialog = false },
            onDismiss = { showMergeDialog = false },
        )
    }

    // ── 审查对话框 ────────────────────────────────────────────────────────────
    if (showReviewDialog) {
        ReviewDialog(
            c = c,
            onConfirm = { event, body -> vm.submitReview(event, body); showReviewDialog = false },
            onDismiss = { showReviewDialog = false },
        )
    }

    // ── 请求审查者对话框 ──────────────────────────────────────────────────────
    if (showReviewerDialog && currentPR != null) {
        RequestReviewerDialog(
            currentReviewers = currentPR.requestedReviewers.map { it.login },
            c = c,
            onConfirm = { reviewers -> vm.requestReviewers(reviewers); showReviewerDialog = false },
            onDismiss = { showReviewerDialog = false },
        )
    }

    // ── 编辑标题对话框 ──────────────────────────────────────────────────────────
    if (showEditTitleDialog && currentPR != null) {
        var titleText by remember { mutableStateOf(currentPR.title) }
        AlertDialog(
            onDismissRequest = { showEditTitleDialog = false },
            containerColor = c.bgCard,
            title = { Text("编辑标题", color = c.textPrimary) },
            text = {
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    modifier = Modifier.fillMaxWidth(),
                    colors = prTextFieldColors(c),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.updatePR(title = titleText)
                        showEditTitleDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showEditTitleDialog = false }) { Text("取消", color = c.textSecondary) } },
        )
    }

    // ── 编辑描述对话框 ──────────────────────────────────────────────────────────
    if (showEditBodyDialog && currentPR != null) {
        var bodyText by remember { mutableStateOf(currentPR.body ?: "") }
        AlertDialog(
            onDismissRequest = { showEditBodyDialog = false },
            containerColor = c.bgCard,
            title = { Text("编辑描述", color = c.textPrimary) },
            text = {
                OutlinedTextField(
                    value = bodyText,
                    onValueChange = { bodyText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp),
                    minLines = 5,
                    maxLines = 20,
                    colors = prTextFieldColors(c),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.updatePR(body = bodyText)
                        showEditBodyDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showEditBodyDialog = false }) { Text("取消", color = c.textSecondary) } },
        )
    }

    // ── 编辑评论对话框 ──────────────────────────────────────────────────────────
    if (showCommentEditor != null) {
        var commentBody by remember { mutableStateOf(showCommentEditor!!.body) }
        AlertDialog(
            onDismissRequest = { showCommentEditor = null },
            containerColor = c.bgCard,
            title = { Text("编辑评论", color = c.textPrimary) },
            text = {
                OutlinedTextField(
                    value = commentBody,
                    onValueChange = { commentBody = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp),
                    minLines = 5,
                    maxLines = 20,
                    colors = prTextFieldColors(c),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.updateComment(showCommentEditor!!.id, commentBody)
                        showCommentEditor = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showCommentEditor = null }) { Text("取消", color = c.textSecondary) } },
        )
    }

    // ── 删除评论确认对话框 ─────────────────────────────────────────────────────────
    if (showDeleteCommentDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteCommentDialog = null },
            containerColor = c.bgCard,
            title = { Text("删除评论", color = c.textPrimary) },
            text = { Text("确定要删除这条评论吗？此操作无法撤销。", color = c.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteComment(showDeleteCommentDialog!!.id)
                        showDeleteCommentDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF85149)),
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteCommentDialog = null }) { Text("取消", color = c.textSecondary) } },
        )
    }

    if (showEditHistory != null) {
        LaunchedEffect(showEditHistory) {
            when (val type = showEditHistory!!) {
                is PREditHistoryType.PRBody -> {
                    vm.loadPRBodyEditHistory()
                }
                is PREditHistoryType.Comment -> {
                    vm.loadPRCommentEditHistory(type.comment.nodeId)
                }
            }
        }
        EditHistorySheet(
            type = showEditHistory!!,
            editHistory = state.editHistory,
            loading = state.editHistoryLoading,
            c = c,
            onDismiss = { showEditHistory = null }
        )
    }

    // ── 更改基本分支对话框 ────────────────────────────────────────────────────────
    if (showChangeBaseBranchDialog && currentPR != null) {
        var selectedBranch by remember { mutableStateOf(currentPR.base.ref) }
        val availableBranches = state.branches.filter { it.name != currentPR.head.ref }
        
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.PartiallyExpanded }
        )
        
        ModalBottomSheet(
            onDismissRequest = { showChangeBaseBranchDialog = false },
            containerColor = c.bgCard,
            dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "选择基本分支",
                    fontSize = 18.sp,
                    color = c.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                GmDivider()
                if (availableBranches.isEmpty()) {
                    Text(
                        "没有可用的其他分支",
                        fontSize = 14.sp,
                        color = c.textSecondary,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(availableBranches, key = { it.name }) { branch ->
                            val isSelected = branch.name == selectedBranch
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) Coral.copy(alpha = 0.1f) else c.bgItem,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedBranch = branch.name }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedBranch = branch.name },
                                    colors = RadioButtonDefaults.colors(selectedColor = Coral)
                                )
                                Column {
                                    Text(branch.name, fontSize = 14.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
                                    if (branch.name == currentPR.head.ref) {
                                        Text("当前PR的源分支", fontSize = 12.sp, color = c.textTertiary)
                                    }
                                }
                            }
                        }
                    }
                }
                GmDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showChangeBaseBranchDialog = false }) {
                        Text("取消", color = c.textSecondary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            vm.updatePR(base = selectedBranch)
                            showChangeBaseBranchDialog = false
                        },
                        enabled = selectedBranch != currentPR.base.ref,
                        colors = ButtonDefaults.buttonColors(containerColor = Coral)
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }

    // ── 关闭/重新打开确认 ─────────────────────────────────────────────────────
    if (showCloseConfirm && currentPR != null) {
        val isOpen = currentPR.state == "open"
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            containerColor = c.bgCard,
            title = { Text(if (isOpen) "关闭 PR" else "重新打开 PR", color = c.textPrimary) },
            text = { Text(if (isOpen) "确定关闭这个 Pull Request？" else "确定重新打开这个 Pull Request？", color = c.textSecondary) },
            confirmButton = {
                Button(
                    onClick = { vm.togglePRState(); showCloseConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isOpen) Color(0xFFF85149) else Coral),
                ) { Text(if (isOpen) "关闭" else "重新打开") }
            },
            dismissButton = { TextButton(onClick = { showCloseConfirm = false }) { Text("取消", color = c.textSecondary) } },
        )
    }

    if (showCommentInputDialog) {
        CommentInputDialog(
            currentText = commentText,
            replyingTo = replyingToComment,
            replyingToPR = replyingToPR,
            replyingToReviewText = replyingToReviewText,
            prUser = currentPR?.user,
            c = c,
            onConfirm = { text ->
                if (text.isNotBlank()) {
                    val finalText = if (replyingToComment != null) {
                        val quoted = replyingToComment!!.body
                            .lines()
                            .joinToString("\n") { "> $it" }
                        "$quoted\n\n$text"
                    } else if (replyingToPR) {
                        val prBody = currentPR?.body?.orEmpty() ?: ""
                        val quoted = prBody
                            .lines()
                            .take(10)
                            .joinToString("\n") { "> $it" }
                        "$quoted\n\n$text"
                    } else if (replyingToReviewText != null) {
                        val quoted = replyingToReviewText!!
                            .lines()
                            .joinToString("\n") { "> $it" }
                        "$quoted\n\n$text"
                    } else {
                        text
                    }
                    vm.addPRComment(finalText)
                    commentText = ""
                    replyingToComment = null
                    replyingToPR = false
                    replyingToReviewText = null
                    showCommentInputDialog = false
                }
            },
            onDismiss = {
                showCommentInputDialog = false
            },
            onCancelReply = {
                replyingToComment = null
                replyingToPR = false
                replyingToReviewText = null
            },
        )
    }

    // Toast
    state.toast?.let { msg ->
        LaunchedEffect(msg) { kotlinx.coroutines.delay(2500); vm.clearToast() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Snackbar(
                modifier = Modifier.padding(top = 72.dp, start = 16.dp, end = 16.dp),
                containerColor = c.bgCard, contentColor = c.textPrimary,
            ) { Text(msg) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PR 头部信息
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PRHeader(pr: GHPullRequest, c: GmColors) {
    val (statusColor, statusBg, statusIcon, statusLabel) = when {
        pr.isMerged -> Quadruple(MergedColor, PurpleDim, Icons.AutoMirrored.Filled.MergeType, "已合并")
        pr.isClosed -> Quadruple(c.textTertiary, c.bgItem,  Icons.Default.Cancel,              "已关闭")
        pr.isDraft  -> Quadruple(DraftColor,  DraftDim,  Icons.Default.Edit,                "草稿")
        else        -> Quadruple(Green,       GreenDim,  Icons.AutoMirrored.Filled.CallMerge, "开放")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 标题 + 状态
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                pr.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .background(statusBg, RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(14.dp))
                Text(statusLabel, fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.SemiBold)
            }
        }

        // 分支信息
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.AutoMirrored.Filled.CallMerge, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
            BranchChip(pr.head.ref, c)
            Text("→", fontSize = 12.sp, color = c.textTertiary)
            BranchChip(pr.base.ref, c)
        }

        // 作者 + 时间
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AsyncImage(
                model = pr.user.avatarUrl?.let { if (it.contains("?")) "$it&s=32" else "$it?s=32" },
                contentDescription = null,
                modifier = Modifier.size(20.dp).clip(CircleShape).background(c.bgItem),
            )
            Text(pr.user.login, fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.Medium)
            Text("·", fontSize = 12.sp, color = c.textTertiary)
            Text(
                try { repoFormatDate(pr.createdAt) } catch (_: Exception) { pr.createdAt },
                fontSize = 12.sp, color = c.textTertiary,
            )
        }

        // mergeable 状态提示
        if (pr.isOpen && !pr.isDraft) {
            val (mergeStatusColor, mergeStatusText) = when (pr.mergeableState) {
                "clean", "MERGEABLE" -> Pair(Green, "✓ 可以合并")
                "unstable" -> Pair(Color(0xFFD29922), "⚠ 检查未通过")
                "dirty", "CONFLICTING" -> Pair(Color(0xFFF85149), "✗ 存在合并冲突")
                "blocked" -> Pair(Color(0xFFD29922), "⚠ 合并被阻止")
                else -> Pair(c.textTertiary, "检查合并状态中…")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Info, null, tint = mergeStatusColor, modifier = Modifier.size(14.dp))
                Text(mergeStatusText, fontSize = 12.sp, color = mergeStatusColor)
            }
        }

        // 标签
        if (pr.labels.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                pr.labels.forEach { label ->
                    val bg = try { Color(android.graphics.Color.parseColor("#${label.color}")) }
                             catch (_: Exception) { c.bgItem }
                    val fg = if (isColorLight(bg)) Color(0xFF24292F) else Color.White
                    Text(
                        label.name,
                        fontSize = 11.sp, color = fg, fontWeight = FontWeight.Medium,
                        modifier = Modifier.background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * PR描述卡片
 */
@Composable
private fun PRBodyCard(
    pr: com.gitmob.android.api.GHPullRequest,
    c: GmColors,
    userLogin: String,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onShowEditHistory: () -> Unit,
) {
    val isOwnPR = pr.user.login == userLogin
    val canEdit = isOwnPR || permission.isOwner
    var showMenu by remember { mutableStateOf(false) }
    
    LogManager.d("PRDetailScreen", "PR详情 - createdAt: ${pr.createdAt}, updatedAt: ${pr.updatedAt}, lastEditedAt: ${pr.lastEditedAt}")
    val isPREdited = pr.lastEditedAt != null
    LogManager.d("PRDetailScreen", "PR是否已编辑: $isPREdited")
    
    Surface(
        color = c.bgCard,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AsyncImage(
                    model = pr.user.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            pr.user.login,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = c.textPrimary,
                        )
                        if (isPREdited) {
                            TextButton(
                                onClick = onShowEditHistory,
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = c.textSecondary
                                ),
                                enabled = true
                            ) {
                                Text(
                                    "已编辑",
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    Text(
                        formatDate(pr.createdAt),
                        fontSize = 12.sp,
                        color = c.textTertiary,
                    )
                }
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            null,
                            tint = c.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(c.bgCard),
                    ) {
                        DropdownMenuItem(
                            text = { Text("分享", fontSize = 14.sp, color = c.textPrimary) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Share,
                                    null,
                                    tint = c.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                showMenu = false
                                onShare()
                            },
                        )
                        ArchivedAwareDropdownMenuItem(
                            text = { Text("引用回复", fontSize = 14.sp) },
                            isArchived = isArchived,
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.Reply,
                                    null,
                                    tint = c.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                showMenu = false
                                onReply()
                            },
                        )
                        if (canEdit) {
                            ArchivedAwareDropdownMenuItem(
                                text = { Text("编辑描述", fontSize = 14.sp) },
                                isArchived = isArchived,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Edit,
                                        null,
                                        tint = c.textSecondary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                },
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (pr.body.isNullOrBlank()) {
                Text(
                    "暂无描述",
                    fontSize = 14.sp,
                    color = c.textTertiary,
                    lineHeight = 20.sp,
                )
            } else {
                com.gitmob.android.ui.common.GmMarkdownWebView(
                    markdown = pr.body,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 操作面板
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PRActionPanel(
    pr: GHPullRequest,
    c: GmColors,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onMergeClick: () -> Unit,
    onReviewClick: () -> Unit,
    onReviewerClick: () -> Unit,
    onUpdateBranchClick: () -> Unit,
    onMarkReadyClick: () -> Unit,
    onToggleStateClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("操作", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary)

        // 合并按钮（只在可合并时显示）
        if (pr.isOpen && !pr.isDraft && permission.canWrite && pr.mergeableState != "dirty") {
            PRActionButton(
                label = "合并 PR",
                icon = Icons.AutoMirrored.Filled.MergeType,
                color = PurpleColor,
                onClick = onMergeClick,
                enabled = !isArchived,
            )
        }

        // 草稿 → 就绪（只有 PR 作者或管理员才能操作）
        if (pr.isOpen && pr.isDraft) {
            PRActionButton(
                label = "标记为就绪以供审查",
                icon = Icons.Default.CheckCircle,
                color = Green,
                onClick = onMarkReadyClick,
                enabled = !isArchived,
            )
        }

        // 提交审查（有权限的人可以审查）
        if (pr.isOpen && !pr.isDraft) {
            PRActionButton(
                label = "提交审查（批准 / 请求修改 / 评论）",
                icon = Icons.Default.RateReview,
                color = Coral,
                onClick = onReviewClick,
                enabled = !isArchived,
            )
        }

        // 管理审查请求
        if (pr.isOpen && permission.canWrite) {
            PRActionButton(
                label = "管理审查请求",
                icon = Icons.Default.PersonAdd,
                color = c.textSecondary,
                onClick = onReviewerClick,
                enabled = !isArchived,
            )
        }

        // 更新分支（只在 head 落后于 base 时才有意义）
        if (pr.isOpen && permission.canWrite && pr.mergeableState == "behind") {
            PRActionButton(
                label = "更新分支",
                icon = Icons.Default.Sync,
                color = BlueColor,
                onClick = onUpdateBranchClick,
                enabled = !isArchived,
            )
        }

        // 关闭 / 重新打开
        if (!pr.isMerged) {
            PRActionButton(
                label = if (pr.state == "open") "关闭 PR" else "重新打开 PR",
                icon = if (pr.state == "open") Icons.Default.Cancel else Icons.Default.Refresh,
                color = if (pr.state == "open") Color(0xFFF85149) else Green,
                onClick = onToggleStateClick,
                enabled = !isArchived,
            )
        }
    }
}

@Composable
private fun PRActionButton(
    label: String, 
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    color: Color, 
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val c = LocalGmColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (enabled) color.copy(alpha = 0.08f) else c.bgItem, 
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick, enabled = enabled)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = if (enabled) color else c.textTertiary, modifier = Modifier.size(18.dp))
        Text(label, fontSize = 13.sp, color = if (enabled) color else c.textTertiary, fontWeight = FontWeight.Medium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 审查条目
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReviewItem(review: GHReview, c: GmColors) {
    val (color, label) = when (review.state) {
        "APPROVED"          -> Pair(Green, "批准")
        "CHANGES_REQUESTED" -> Pair(Color(0xFFF85149), "请求修改")
        "DISMISSED"         -> Pair(c.textTertiary, "已撤销")
        else                -> Pair(c.textSecondary, "评论")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AsyncImage(
            model = review.user.avatarUrl?.let { if (it.contains("?")) "$it&s=32" else "$it?s=32" },
            contentDescription = null,
            modifier = Modifier.size(28.dp).clip(CircleShape).background(c.bgItem),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(review.user.login, fontSize = 12.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
                Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp))
            }
            if (!review.body.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(review.body, fontSize = 12.sp, color = c.textSecondary, lineHeight = 18.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 评论条目
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 审查条目（按评论样式显示）
 */
@Composable
private fun ReviewItemAsComment(
    review: GHReview,
    c: GmColors,
    userLogin: String,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onReply: (String) -> Unit,
    onShare: (GHReview) -> Unit,
) {
    val (stateColor, stateLabel) = when (review.state) {
        "APPROVED"          -> Pair(Green, "批准")
        "CHANGES_REQUESTED" -> Pair(Color(0xFFF85149), "请求修改")
        "DISMISSED"         -> Pair(c.textTertiary, "已撤销")
        else                -> Pair(c.textSecondary, "评论")
    }
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        color = c.bgCard,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AsyncImage(
                    model = review.user.avatarUrl?.let { if (it.contains("?")) "$it&s=32" else "$it?s=32" },
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        review.user.login,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.textPrimary,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            try { repoFormatDate(review.submittedAt ?: "") } catch (_: Exception) { "" },
                            fontSize = 12.sp,
                            color = c.textTertiary,
                        )
                        Surface(
                            color = c.textSecondary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                "审查",
                                fontSize = 11.sp,
                                color = c.textSecondary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Surface(
                            color = stateColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                stateLabel,
                                fontSize = 11.sp,
                                color = stateColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            null,
                            tint = c.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(c.bgCard),
                    ) {
                        DropdownMenuItem(
                            text = { Text("分享", fontSize = 14.sp, color = c.textPrimary) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Share,
                                    null,
                                    tint = c.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                showMenu = false
                                onShare(review)
                            },
                        )
                        ArchivedAwareDropdownMenuItem(
                            text = { Text("引用回复", fontSize = 14.sp) },
                            isArchived = isArchived,
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.Reply,
                                    null,
                                    tint = c.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                showMenu = false
                                onReply(review.body ?: "")
                            },
                        )
                    }
                }
            }
            if (!review.body.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                com.gitmob.android.ui.common.GmMarkdownWebView(
                    markdown = review.body,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 评论条目
 */
@Composable
private fun PRCommentItem(
    comment: GHComment,
    c: GmColors,
    userLogin: String,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onReply: (GHComment) -> Unit,
    onEdit: (GHComment) -> Unit,
    onDelete: (GHComment) -> Unit,
    onShare: (GHComment) -> Unit,
    onShowEditHistory: (GHComment) -> Unit,
) {
    val isOwnComment = comment.user.login == userLogin
    val canEditOrDelete = isOwnComment || permission.isOwner
    val isCommentEdited = comment.lastEditedAt != null
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        color = c.bgCard,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AsyncImage(
                    model = comment.user.avatarUrl?.let { if (it.contains("?")) "$it&s=32" else "$it?s=32" },
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            comment.user.login,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = c.textPrimary,
                        )
                        if (isCommentEdited) {
                            TextButton(
                                onClick = { onShowEditHistory(comment) },
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = c.textSecondary
                                ),
                                enabled = true
                            ) {
                                Text(
                                    "已编辑",
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    Text(
                        try { repoFormatDate(comment.createdAt) } catch (_: Exception) { "" },
                        fontSize = 12.sp,
                        color = c.textTertiary,
                    )
                }
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            null,
                            tint = c.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(c.bgCard),
                    ) {
                        DropdownMenuItem(
                            text = { Text("分享", fontSize = 14.sp, color = c.textPrimary) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Share,
                                    null,
                                    tint = c.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                showMenu = false
                                onShare(comment)
                            },
                        )
                        ArchivedAwareDropdownMenuItem(
                            text = { Text("引用回复", fontSize = 14.sp) },
                            isArchived = isArchived,
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.Reply,
                                    null,
                                    tint = c.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                showMenu = false
                                onReply(comment)
                            },
                        )
                        if (canEditOrDelete) {
                            ArchivedAwareDropdownMenuItem(
                                text = { Text("编辑", fontSize = 14.sp) },
                                isArchived = isArchived,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Edit,
                                        null,
                                        tint = c.textSecondary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onEdit(comment)
                                },
                            )
                            ArchivedAwareDropdownMenuItem(
                                text = { Text("删除", fontSize = 14.sp) },
                                isArchived = isArchived,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        null,
                                        tint = Color(0xFFF85149),
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onDelete(comment)
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            com.gitmob.android.ui.common.GmMarkdownWebView(
                markdown = comment.body,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 合并对话框
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MergePRDialog(
    pr: GHPullRequest,
    c: GmColors,
    onConfirm: (MergeMethod, String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedMethod by remember { mutableStateOf(MergeMethod.MERGE) }
    var commitTitle    by remember { mutableStateOf("") }
    var commitMessage  by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("合并 Pull Request", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 合并方式选择
                MergeMethod.entries.forEach { method ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selectedMethod == method) Coral.copy(alpha = 0.1f) else c.bgItem,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { selectedMethod = method }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedMethod == method,
                            onClick = { selectedMethod = method },
                            colors = RadioButtonDefaults.colors(selectedColor = Coral),
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(method.displayName, fontSize = 13.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
                            val desc = when (method) {
                                MergeMethod.MERGE  -> "保留所有提交记录"
                                MergeMethod.SQUASH -> "将所有提交合并为一个"
                                MergeMethod.REBASE -> "线性地应用提交"
                            }
                            Text(desc, fontSize = 11.sp, color = c.textTertiary)
                        }
                    }
                }
                if (selectedMethod != MergeMethod.REBASE) {
                    OutlinedTextField(
                        value = commitTitle,
                        onValueChange = { commitTitle = it },
                        label = { Text("提交标题（可选）", color = c.textTertiary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = prTextFieldColors(c),
                    )
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        label = { Text("提交描述（可选）", color = c.textTertiary) },
                        minLines = 2, maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        colors = prTextFieldColors(c),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedMethod, commitTitle.ifBlank { null }, commitMessage.ifBlank { null }) },
                colors = ButtonDefaults.buttonColors(containerColor = PurpleColor),
            ) { Text("确认合并") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) } },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 审查对话框
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReviewDialog(
    c: GmColors,
    onConfirm: (event: String, body: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedEvent by remember { mutableStateOf("COMMENT") }
    var body          by remember { mutableStateOf("") }

    val events = listOf(
        Triple("APPROVE", "批准", Green),
        Triple("REQUEST_CHANGES", "请求修改", Color(0xFFF85149)),
        Triple("COMMENT", "仅评论", Coral),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("提交审查", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                events.forEach { (event, label, color) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selectedEvent == event) color.copy(alpha = 0.1f) else c.bgItem,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { selectedEvent = event }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedEvent == event,
                            onClick = { selectedEvent = event },
                            colors = RadioButtonDefaults.colors(selectedColor = color),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 13.sp, color = if (selectedEvent == event) color else c.textPrimary)
                    }
                }
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("审查意见", color = c.textTertiary) },
                    placeholder = { Text(if (selectedEvent == "COMMENT") "必填" else "可选", color = c.textTertiary) },
                    minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    colors = prTextFieldColors(c),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedEvent, body) },
                enabled = selectedEvent != "COMMENT" || body.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
            ) { Text("提交") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) } },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 请求审查者对话框
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RequestReviewerDialog(
    currentReviewers: List<String>,
    c: GmColors,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    val reviewers = remember { mutableStateListOf<String>().also { it.addAll(currentReviewers) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("管理审查请求", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("输入 GitHub 用户名添加审查者", fontSize = 12.sp, color = c.textTertiary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        singleLine = true,
                        placeholder = { Text("用户名", color = c.textTertiary) },
                        modifier = Modifier.weight(1f),
                        colors = prTextFieldColors(c),
                    )
                    IconButton(
                        onClick = {
                            val trimmed = inputText.trim()
                            if (trimmed.isNotBlank() && !reviewers.contains(trimmed)) {
                                reviewers.add(trimmed); inputText = ""
                            }
                        }
                    ) { Icon(Icons.Default.Add, null, tint = Coral) }
                }
                reviewers.forEach { reviewer ->
                    Row(
                        modifier = Modifier.fillMaxWidth().background(c.bgItem, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(reviewer, fontSize = 13.sp, color = c.textPrimary)
                        IconButton(onClick = { reviewers.remove(reviewer) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reviewers.toList()) },
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
            ) { Text("发送请求") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) } },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 辅助 Composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, c: GmColors) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = c.textTertiary, modifier = Modifier.size(15.dp))       
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary)
    }
}

private fun sharePR(context: Context, pr: GHPullRequest) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, pr.htmlUrl)
        putExtra(Intent.EXTRA_SUBJECT, "GitHub Pull Request")
    }
    context.startActivity(Intent.createChooser(intent, "分享 PR"))
}

private fun shareComment(context: Context, comment: GHComment) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, comment.htmlUrl)
        putExtra(Intent.EXTRA_SUBJECT, "GitHub Comment")
    }
    context.startActivity(Intent.createChooser(intent, "分享评论"))
}

private fun shareReview(context: Context, review: GHReview) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, review.htmlUrl)
        putExtra(Intent.EXTRA_SUBJECT, "GitHub Review")
    }
    context.startActivity(Intent.createChooser(intent, "分享审查"))
}

private fun formatDate(dateStr: String): String {
    return try {
        val date = java.time.OffsetDateTime.parse(dateStr)
        val localDateTime = date.atZoneSameInstant(java.time.ZoneId.systemDefault())
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm")
        localDateTime.format(formatter)
    } catch (_: Exception) {
        dateStr
    }
}

/**
 * 评论输入底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentInputDialog(
    currentText: String,
    replyingTo: GHComment?,
    replyingToPR: Boolean,
    replyingToReviewText: String?,
    prUser: GHOwner?,
    c: GmColors,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onCancelReply: () -> Unit,
) {
    var text by remember { mutableStateOf(currentText) }
    
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.PartiallyExpanded }
    )
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "发表评论",
                fontSize = 16.sp,
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            GmDivider()
            if (replyingTo != null || replyingToPR || replyingToReviewText != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Reply,
                        null,
                        tint = c.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        if (replyingTo != null) "回复 @${replyingTo.user.login}" 
                        else if (replyingToPR) "回复 @${prUser?.login}"
                        else "回复",
                        fontSize = 12.sp,
                        color = c.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onCancelReply,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            null,
                            tint = c.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("评论内容") },
                modifier = Modifier.fillMaxWidth().weight(1f),
                minLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.bgDeep,
                    unfocusedContainerColor = c.bgDeep,
                    focusedBorderColor = Coral,
                    unfocusedBorderColor = c.border,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = c.textSecondary)
                }
                TextButton(
                    onClick = { onConfirm(text) },
                    enabled = text.isNotBlank(),
                ) {
                    Text("发表", color = Coral)
                }
            }
        }
    }
}

/** 编辑历史类型 */
sealed class PREditHistoryType {
    data class PRBody(val pr: GHPullRequest) : PREditHistoryType()
    data class Comment(val comment: GHComment) : PREditHistoryType()
}

/**
 * 编辑历史底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditHistorySheet(
    type: PREditHistoryType,
    editHistory: List<GHUserContentEdit>,
    loading: Boolean,
    c: GmColors,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.PartiallyExpanded }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "编辑历史",
                fontSize = 16.sp,
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            GmDivider()
            
            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Coral)
                }
            } else if (editHistory.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂无编辑历史",
                        fontSize = 14.sp,
                        color = c.textTertiary
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(editHistory.reversed(), key = { it.id }) { edit ->
                        EditHistoryItem(edit = edit, c = c)
                    }
                }
            }
        }
    }
}

/**
 * 编辑历史单项组件
 */
@Composable
private fun EditHistoryItem(
    edit: GHUserContentEdit,
    c: GmColors,
) {
    var showDiff by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = c.bgItem),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (edit.editor != null) {
                        AsyncImage(
                            model = edit.editor.avatarUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                        )
                        Text(
                            edit.editor.login,
                            fontSize = 13.sp,
                            color = c.textPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Text(
                    formatDate(edit.createdAt),
                    fontSize = 12.sp,
                    color = c.textTertiary
                )
            }
            
            val diff = edit.diff
            if (diff != null && diff.isNotBlank()) {
                GmDivider()
                Surface(
                    onClick = { showDiff = !showDiff },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (showDiff) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            tint = Coral,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            if (showDiff) "隐藏差异" else "查看差异",
                            fontSize = 13.sp,
                            color = Coral,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (showDiff) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = c.bgDeep,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            diff,
                            fontSize = 12.sp,
                            color = c.textPrimary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp),
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * 编辑历史内容
 */
@Composable
private fun EditHistoryContent(
    title: String,
    createdAt: String,
    updatedAt: String?,
    c: GmColors,
) {
    val createdDate = try { OffsetDateTime.parse(createdAt) } catch (_: Exception) { null }
    val updatedDate = updatedAt?.let { try { OffsetDateTime.parse(it) } catch (_: Exception) { null } }
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            fontSize = 14.sp,
            color = c.textPrimary,
            fontWeight = FontWeight.Medium
        )
        
        if (updatedDate != null && createdDate != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "当前版本",
                fontSize = 13.sp,
                color = c.textSecondary,
                fontWeight = FontWeight.Medium
            )
            Text(
                "最后编辑于 ${updatedDate.format(formatter)}",
                fontSize = 12.sp,
                color = c.textTertiary
            )
            Spacer(Modifier.height(8.dp))
            GmDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "初始版本",
                fontSize = 13.sp,
                color = c.textSecondary,
                fontWeight = FontWeight.Medium
            )
            Text(
                "创建于 ${createdDate.format(formatter)}",
                fontSize = 12.sp,
                color = c.textTertiary
            )
        } else {
            Text(
                "无法解析时间",
                fontSize = 12.sp,
                color = c.textTertiary
            )
        }
        
        Spacer(Modifier.height(16.dp))
        Text(
            "GitHub API 不支持获取完整的编辑历史记录。\n目前只能显示创建时间和最后修改时间。",
            fontSize = 12.sp,
            color = c.textTertiary,
            lineHeight = 18.sp
        )
    }
}
