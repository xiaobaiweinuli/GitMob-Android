@file:OptIn(ExperimentalMaterial3Api::class)

package com.gitmob.android.ui.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Outbound
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
import com.gitmob.android.ui.filepicker.FilePickerScreen
import com.gitmob.android.ui.filepicker.PickerMode
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
import com.gitmob.android.data.FavoritesManager
import com.gitmob.android.ui.home.AddToFavoritesDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(
    owner: String,
    repoName: String,
    tabStepBackEnabled: Boolean,
    onBack: () -> Unit,
    onFileClick: (String, String, String, String) -> Unit,
    onIssueClick: (Int) -> Unit = {},
    onPRClick: (Int) -> Unit = {},
    onDiscussionClick: (Int) -> Unit = {},
    onForkedRepoClick: ((String, String) -> Unit)? = null,
    onOwnerClick: ((String) -> Unit)? = null,
    onEditFile: (String, String, String, Boolean, Boolean) -> Unit = { _, _, _, _, _ -> },
    vm: RepoDetailViewModel = viewModel {
        val app = checkNotNull(this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
        val handle = androidx.lifecycle.SavedStateHandle(mapOf("owner" to owner, "repo" to repoName))
        RepoDetailViewModel(app, handle)
    },
) {
    val c = LocalGmColors.current
    val state by vm.state.collectAsState()
    val repo = state.repo
    val tabs = buildList {
        add("文件")
        add("提交")
        add("分支")
        add("操作")
        add("发行版")
        add("PR")
        if (repo?.hasIssues != false) {
            add("Issues")
        }
        if (repo?.hasDiscussions == true) {
            add("讨论")
        }
    }
    val permission = rememberRepoPermission(state.repo, state.userLogin, state.userOrgs)
    var showBranchDialog by remember { mutableStateOf(false) }
    var showNewBranchDialog by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showFavoritesDialog by remember { mutableStateOf(false) }
    val favVm: FavoritesManager = viewModel()
    state.userLogin.takeIf { it.isNotBlank() }?.let { login ->
        LaunchedEffect(login) { favVm.init(login) }
    }
    val favState by favVm.state.collectAsState()
    val isCurrentRepoFavorited = state.repo?.fullName?.let { favVm.isFavorited(it) } ?: false

    // 仓库成功加载后：若已收藏则同步最新数据（stars/language/description 等）
    LaunchedEffect(state.repo) {
        val repo = state.repo ?: return@LaunchedEffect
        favVm.updateFavRepoData(repo)
    }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showVisibilityDialog by remember { mutableStateOf(false) }
    var showTransferDialog by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCreateFileTypeMenu by remember { mutableStateOf(false) }
    var showWatchSheet by remember { mutableStateOf(false) }
    var showToggleIssuesDialog by remember { mutableStateOf(false) }
    var showToggleDiscussionsDialog by remember { mutableStateOf(false) }
    var showArchiveDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var newFileContent by remember { mutableStateOf("") }
    var showCommitMessageDialog by remember { mutableStateOf(false) }
    var commitMessage by remember { mutableStateOf("") }
    var selectedFileForMenu by remember { mutableStateOf<GHContent?>(null) }
    
    var showRenameFileDialog by remember { mutableStateOf(false) }
    
    var showDeleteFileDialog by remember { mutableStateOf(false) }
    var renameFileNewName by remember { mutableStateOf("") }
    
    // 复刻仓库同步状态弹窗
    var showForkSyncDialog by remember { mutableStateOf(false) }
    
    val context = LocalContext.current

    // ── 上传功能状态 ──────────────────────────────────────────────────────────
    var showUploadSourceSheet  by remember { mutableStateOf(false) }
    var showUploadReviewSheet  by remember { mutableStateOf(false) }
    // 扫描/选取得到的待上传条目（传入 ReviewSheet）
    var uploadEntries by remember { mutableStateOf<List<UploadFileEntry>>(emptyList()) }
    // 文件夹选择器：等待 FilePickerScreen 返回路径
    var showUploadFolderPicker by remember { mutableStateOf(false) }
    var showUploadFilePicker   by remember { mutableStateOf(false) }

    state.toast?.let { msg ->
        LaunchedEffect(msg) { kotlinx.coroutines.delay(2500); vm.clearToast() }
    }

    /**
     * 左上角返回按钮：行为固定，无论是否开启逐级返回：
     *   1. 设置菜单打开 → 关闭菜单
     *   2. 在文件子目录 → 退回上一级目录
     *   3. 其他情况    → 直接返回上一页（Remote Tab）
     */
    val handleTopBarBack by rememberUpdatedState(newValue = {
        when {
            showSettingsMenu               -> showSettingsMenu = false
            state.currentPath.isNotEmpty() -> vm.navigateUp()
            else                           -> onBack()
        }
    })

    /**
     * 系统返回手势 / 实体返回键：
     *   tabStepBackEnabled=false：非文件Tab先跳回文件Tab，文件Tab退目录或返回
     *   tabStepBackEnabled=true ：逐级退 Tab（tab-1），Tab=0 时退目录或返回
     */
    val handleBackPress by rememberUpdatedState(newValue = {
        when {
            showSettingsMenu -> {
                showSettingsMenu = false
            }
            tabStepBackEnabled -> {
                when {
                    state.tab > 0 -> {
                        vm.setTab(state.tab - 1)
                    }
                    state.currentPath.isNotEmpty() -> {
                        vm.navigateUp()
                    }
                    else -> {
                        onBack()
                    }
                }
            }
            else -> {
                when {
                    state.tab != 0 -> {
                        vm.setTab(0)
                    }
                    state.currentPath.isNotEmpty() -> {
                        vm.navigateUp()
                    }
                    else -> {
                        onBack()
                    }
                }
            }
        }
    })

    BackHandler(enabled = true) {
        handleBackPress()
    }

    val isArchived = state.repo?.archived == true

    Scaffold(
        containerColor = c.bgDeep,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        state.repo?.let { repo ->
                            Text(repo.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.textPrimary)
                            val isOwnRepo = repo.owner.login == state.userLogin
                            if (repo.fork && repo.parent != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.clickable {
                                        onForkedRepoClick?.invoke(repo.parent.owner.login, repo.parent.name)
                                    }
                                ) {
                                    Text(
                                        text = repo.parent.fullName,
                                        fontSize = 12.sp,
                                        color = BlueColor,
                                    )
                                }
                            } else {
                                // 始终显示 owner，点击进入主页（包括自己的仓库也可以点）
                                Text(
                                    repo.owner.login, fontSize = 12.sp,
                                    color = if (isOwnRepo) c.textTertiary else BlueColor,
                                    modifier = if (onOwnerClick != null) Modifier.clickable { onOwnerClick.invoke(repo.owner.login) } else Modifier,
                                )
                            }
                        } ?: Text(vm.state.value.currentRepoName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.textPrimary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = handleTopBarBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.textSecondary) }
                },
                actions = {
                    IconButton(onClick = { vm.loadAll(forceRefresh = true) }) {
                        Icon(Icons.Default.Refresh, null, tint = c.textSecondary)
                    }
                    Box {
                        // 所有人都能看到设置按钮
                        IconButton(onClick = { showSettingsMenu = true }) {
                            Icon(Icons.Default.Settings, null, tint = c.textSecondary)
                        }
                        DropdownMenu(
                            expanded = showSettingsMenu,
                            onDismissRequest = { showSettingsMenu = false },
                            modifier = Modifier.background(c.bgCard),
                        ) {
                            // 需要权限的选项用 PermissionRequired 包裹
                            PermissionRequired(permission = permission, requireOwner = true) {
                                ArchivedAwareDropdownMenuItem(
                                    text = { Text("重命名", fontSize = 14.sp) },
                                    isArchived = isArchived,
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.DriveFileRenameOutline,
                                            null,
                                            tint = c.textSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    onClick = {
                                        showSettingsMenu = false
                                        showRenameDialog = true
                                    },
                                )
                            }
                            PermissionRequired(permission = permission, requireOwner = true) {
                                ArchivedAwareDropdownMenuItem(
                                    text = { Text("编辑信息", fontSize = 14.sp) },
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
                                        showSettingsMenu = false
                                        showEditDialog = true
                                    },
                                )
                            }
                            // 分享不需要权限
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
                                    showSettingsMenu = false
                                    state.repo?.let { repo ->
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, repo.fullName)
                                            putExtra(Intent.EXTRA_TEXT, repo.htmlUrl)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(intent, "分享仓库"),
                                        )
                                    }
                                },
                            )
                            PermissionRequired(permission = permission, requireOwner = true) {
                                ArchivedAwareDropdownMenuItem(
                                    text = {
                                        Text(
                                            if (state.repo?.private == true) "设为公开" else "设为私有",
                                            fontSize = 14.sp,
                                        )
                                    },
                                    isArchived = isArchived,
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Lock,
                                            null,
                                            tint = c.textSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    onClick = {
                                        showSettingsMenu = false
                                        showVisibilityDialog = true
                                    },
                                )
                            }
                            PermissionRequired(permission = permission, requireOwner = true) {
                                ArchivedAwareDropdownMenuItem(
                                    text = { Text("转移", fontSize = 14.sp) },
                                    isArchived = isArchived,
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.AccountCircle,
                                            null,
                                            tint = c.textSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    onClick = {
                                        showSettingsMenu = false
                                        showTransferDialog = true
                                    },
                                )
                            }
                            PermissionRequired(permission = permission, requireOwner = true) {
                                ArchivedAwareDropdownMenuItem(
                                    text = {
                                        Text(
                                            if (state.repo?.archived == true) "取消归档" else "归档",
                                            fontSize = 14.sp,
                                        )
                                    },
                                    isArchived = isArchived,
                                    allowWhenArchived = true,
                                    leadingIcon = {
                                        Icon(
                                            if (state.repo?.archived == true) Icons.Default.Unarchive else Icons.Default.Archive,
                                            null,
                                            tint = c.textSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    onClick = {
                                        showSettingsMenu = false
                                        showArchiveDialog = true
                                    },
                                )
                            }
                            // 收藏不需要权限
                            DropdownMenuItem(
                                text = { Text(if (isCurrentRepoFavorited) "已收藏" else "收藏仓库", fontSize = 14.sp, color = c.textPrimary) },
                                leadingIcon = {
                                    Icon(
                                        if (isCurrentRepoFavorited) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                        null, tint = if (isCurrentRepoFavorited) Coral else c.textSecondary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                onClick = {
                                    showSettingsMenu = false
                                    showFavoritesDialog = true
                                },
                            )
                            // 订阅通知不需要权限
                            DropdownMenuItem(
                                text = { Text("订阅通知", fontSize = 14.sp, color = c.textPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Default.Notifications, null,
                                        tint = c.textSecondary, modifier = Modifier.size(16.dp))
                                },
                                onClick = {
                                    showSettingsMenu = false
                                    vm.loadSubscription()
                                    showWatchSheet = true
                                },
                            )
                            PermissionRequired(permission = permission, requireOwner = true) {
                                ArchivedAwareDropdownMenuItem(
                                    text = {
                                        Text(
                                            if (state.repo?.hasIssues != false) "关闭议题" else "打开议题",
                                            fontSize = 14.sp
                                        )
                                    },
                                    isArchived = isArchived,
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.BugReport,
                                            null,
                                            tint = c.textSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    onClick = {
                                        showSettingsMenu = false
                                        showToggleIssuesDialog = true
                                    },
                                )
                            }
                            PermissionRequired(permission = permission, requireOwner = true) {
                                ArchivedAwareDropdownMenuItem(
                                    text = {
                                        Text(
                                            if (state.repo?.hasDiscussions == true) "关闭讨论" else "打开讨论",
                                            fontSize = 14.sp
                                        )
                                    },
                                    isArchived = isArchived,
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Forum,
                                            null,
                                            tint = c.textSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    onClick = {
                                        showSettingsMenu = false
                                        showToggleDiscussionsDialog = true
                                    },
                                )
                            }
                            PermissionRequired(permission = permission, requireOwner = true) {
                                ArchivedAwareDropdownMenuItem(
                                    text = { Text("删除", fontSize = 14.sp, color = RedColor) },
                                    isArchived = isArchived,
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            null,
                                            tint = RedColor,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    onClick = {
                                        showSettingsMenu = false
                                        showDeleteDialog = true
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep),
            )
        },
        snackbarHost = {
            state.toast?.let {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Snackbar(modifier = Modifier.padding(top = 80.dp, start = 16.dp, end = 16.dp), containerColor = c.bgCard, contentColor = c.textPrimary) {
                        Text(it)
                    }
                }
            }
        },
    ) { padding ->
        if (state.loading) { LoadingBox(Modifier.padding(padding)); return@Scaffold }
        if (state.error != null && state.repo == null) {
            ErrorBox(state.error!!, vm::loadAll)
            // 仓库已删除时弹出提示
            if (state.repoNotFound) {
                AlertDialog(
                    onDismissRequest = {},
                    containerColor = c.bgCard,
                    icon = { Icon(Icons.Default.DeleteForever, null, tint = Color(0xFFF85149), modifier = Modifier.size(32.dp)) },
                    title = { Text("仓库已删除", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
                    text = {
                        Text(
                            "「$owner/$repoName」已不存在（可能已被删除或设为私有）。\n是否同时删除收藏记录？",
                            color = c.textSecondary, fontSize = 14.sp,
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { favVm.removeFavorite("$owner/$repoName"); onBack() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF85149)),
                        ) { Text("删除收藏并返回") }
                    },
                    dismissButton = {
                        TextButton(onClick = { onBack() }) { Text("仅返回", color = c.textSecondary) }
                    },
                )
            }
            return@Scaffold
        }
        val repo = state.repo ?: return@Scaffold

        Column(Modifier.padding(padding).fillMaxSize()) {
            // 仓库信息卡
            Column(
                modifier = Modifier.fillMaxWidth().background(c.bgCard)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (!repo.description.isNullOrBlank()) {
                    CollapsibleText(
                        text = repo.description,
                        maxLines = 2,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (!repo.homepage.isNullOrBlank()) {
                    val urlToOpen = remember(repo.homepage) {
                        val url = repo.homepage
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            "https://$url"
                        } else {
                            url
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlToOpen))
                            context.startActivity(intent)
                        }
                    ) {
                        Icon(Icons.Default.Link, null, tint = BlueColor, modifier = Modifier.size(14.dp))
                        Text(repo.homepage, fontSize = 12.sp, color = BlueColor)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatItem(Icons.Default.Star, "${repo.stars}", Yellow, onClick = { vm.showStargazersSheet() })
                    StatItem(Icons.Default.Share, "${repo.forks}", c.textSecondary, onClick = { vm.showForksSheet() })
                    StatItem(Icons.Default.ErrorOutline, "${repo.openIssues}", Green)
                    
                    IconButton(onClick = vm::toggleStar, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (state.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                            null, tint = if (state.isStarred) Yellow else c.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    IconButton(
                        onClick = { if (!permission.isOwner) vm.showCreateForkDialog() },
                        enabled = !permission.isOwner,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "复刻仓库",
                            tint = if (permission.isOwner) c.textTertiary else c.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    Spacer(Modifier.weight(1f))
                    
                    if (!repo.language.isNullOrBlank()) {
                        Text(repo.language, fontSize = 11.sp, color = c.textTertiary,
                            modifier = Modifier.background(c.bgItem, RoundedCornerShape(20.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    
                    if (repo.private) GmBadge("私有", RedDim, RedColor)
                    
                    if (repo.archived == true) GmBadge("已归档", CoralDim, Coral)
                }
                
                if (repo.topics.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        repo.topics.forEach { topic ->
                            Text(
                                text = topic,
                                fontSize = 10.sp,
                                color = c.textTertiary,
                                modifier = Modifier
                                    .background(BlueDim, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // 分支选择器
            Row(
                modifier = Modifier.fillMaxWidth().background(c.bgDeep)
                    .clickable { showBranchDialog = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.AccountTree, null, tint = BlueColor, modifier = Modifier.size(15.dp))
                Text(state.currentBranch, fontSize = 13.sp, color = BlueColor,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                // 如果是复刻仓库且有落后的提交，显示同步按钮
                if (state.repo?.fork == true && state.forkBehindBy > 0) {
                    IconButton(
                        onClick = { showForkSyncDialog = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "同步上游仓库",
                            tint = Coral,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text("${state.branches.size} 个分支", fontSize = 11.sp, color = c.textTertiary)
                Icon(Icons.Default.ExpandMore, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
            }

            // Tabs
            PrimaryScrollableTabRow(
                selectedTabIndex = state.tab, containerColor = c.bgDeep,
                contentColor = Coral, edgePadding = 16.dp,
                divider = { GmDivider() },
            ) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = state.tab == i, onClick = { vm.setTab(i) },
                        text = { Text(label, fontSize = 13.sp) },
                        selectedContentColor = Coral, unselectedContentColor = c.textSecondary,
                    )
                }
            }

            when (state.tab) {
                0 -> {
                    LaunchedEffect(Unit) { vm.ensureReadmeLoaded() }
                    FilesTab(state, c, permission, isArchived = state.repo?.archived ?: false,
                        onDirClick = { vm.loadContents(it) },
                        onFileClick = { path -> onFileClick(owner, repoName, path, state.currentBranch) },
                        onNavigateUp = vm::navigateUp,
                        onRefresh = { vm.loadContents(state.currentPath, forceRefresh = true) },
                        onUpload = { showUploadSourceSheet = true },
                        onShare = {
                            val url = "https://github.com/$owner/$repoName/blob/${state.currentBranch}/${state.currentPath}"
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(Intent.createChooser(intent, "分享"))
                        },
                        onAddFile = {
                            showCreateFileTypeMenu = true
                        },
                        onHistory = {
                            vm.setTab(1)
                        },
                        onFileRename = { content ->
                            selectedFileForMenu = content
                            renameFileNewName = content.name
                            showRenameFileDialog = true
                        },
                        onFileDelete = { content ->
                            selectedFileForMenu = content
                            showDeleteFileDialog = true
                        },
                        onFileShare = { content ->
                            val url = "https://github.com/$owner/$repoName/blob/${state.currentBranch}/${content.path}"
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(Intent.createChooser(intent, "分享"))
                        },
                        onFileHistory = { content ->
                            vm.showFileHistory(content)
                        },
                    )
                }
                1 -> {
                    LaunchedEffect(Unit) { vm.ensureCommitsLoaded() }
                    CommitsTab(state, c, onCommitClick = { vm.loadCommitDetail(it.sha) },
                        onRefresh = { vm.loadCommits(forceRefresh = true) })
                }
                2 -> BranchesTab(state, c, permission = permission, isArchived = state.repo?.archived ?: false, onSwitch = vm::switchBranch,
                    onNewBranch = { showNewBranchDialog = true },
                    onDelete = vm::deleteBranch, onRename = vm::renameBranch,
                    onSetDefault = vm::setDefaultBranch,
                    onRefresh = vm::refreshBranches)
                3 -> {
                    LaunchedEffect(Unit) { vm.ensureActionsLoaded() }
                    ActionsTab(state, c, vm, owner, repoName, permission = permission,
                        isArchived = state.repo?.archived ?: false,
                        onRefresh = vm::refreshActions)
                }
                4 -> {
                    LaunchedEffect(Unit) { vm.ensureReleasesLoaded() }
                    ReleasesTab(state, vm = vm, c = c, permission = permission,
                        isArchived = state.repo?.archived ?: false,
                        onRefresh = vm::refreshReleases)
                }
                5 -> PRTab(state, c, vm = vm, permission = permission,
                    isArchived = state.repo?.archived ?: false,
                    onRefresh = vm::refreshPRs, onPRClick = onPRClick)
                6 -> {
                    LaunchedEffect(Unit) { vm.ensureIssuesLoaded() }
                    IssuesTab(state, c, vm, permission = permission,
                        isArchived = state.repo?.archived ?: false,
                        onRefresh = vm::refreshIssues,
                        onIssueClick = onIssueClick)
                }
                7 -> {
                    LaunchedEffect(Unit) { vm.ensureDiscussionsLoaded() }
                    DiscussionsTab(state, c, vm, permission = permission,
                        isArchived = state.repo?.archived ?: false,
                        onRefresh = vm::refreshDiscussions,
                        onDiscussionClick = onDiscussionClick)
                }
            }
        }
    }

    // ── 收藏对话框 ────────────────────────────────────────────────────────────
    if (showFavoritesDialog) {
        state.repo?.let { repo ->
            AddToFavoritesDialog(repo = repo, favVm = favVm, c = c, onDismiss = { showFavoritesDialog = false })
        }
    }

    if (showBranchDialog) {
        BranchPickerDialog(
            branches = state.branches, current = state.currentBranch, c = c,
            onSelect = { vm.switchBranch(it); showBranchDialog = false },
            onDismiss = { showBranchDialog = false },
        )
    }
    if (showNewBranchDialog) {
        NewBranchDialog(c = c,
            onConfirm = { vm.createBranch(it); showNewBranchDialog = false },
            onDismiss = { showNewBranchDialog = false },
        )
    }
    val repoForDialogs = state.repo
    if (showRenameDialog && repoForDialogs != null) {
        RepoRenameDialog(
            currentName = repoForDialogs.name,
            owner = repoForDialogs.owner.login,
            c = c,
            onConfirm = { newName ->
                vm.renameRepo(newName) {}
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }
    if (showEditDialog && repoForDialogs != null) {
        RepoEditDialog(
            repo = repoForDialogs,
            c = c,
            onConfirm = { desc, site, topics ->
                vm.editRepo(desc, site, topics)
                showEditDialog = false
            },
            onDismiss = { showEditDialog = false },
        )
    }
    if (showDeleteDialog && repoForDialogs != null) {
        RepoDeleteDialog(
            repoName = repoForDialogs.name,
            owner = repoForDialogs.owner.login,
            c = c,
            onConfirm = {
                vm.deleteRepo { onBack() }
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
    if (showArchiveDialog && repoForDialogs != null) {
        RepoArchiveDialog(
            repoName = repoForDialogs.name,
            owner = repoForDialogs.owner.login,
            isArchived = repoForDialogs.archived == true,
            c = c,
            onConfirm = {
                if (repoForDialogs.archived == true) {
                    vm.unarchiveRepo { /* 刷新后界面自动更新 */ }
                } else {
                    vm.archiveRepo { /* 刷新后界面自动更新 */ }
                }
                showArchiveDialog = false
            },
            onDismiss = { showArchiveDialog = false },
        )
    }
    
    // 复刻仓库同步状态弹窗
    if (showForkSyncDialog) {
        val hasConflict = state.forkAheadBy > 0 && state.forkBehindBy > 0
        val isAnyOperationInProgress = state.forkSyncing || state.forkDiscardingCommits || state.forkCreatingPR
        val isAnySuccess = state.forkSyncSuccess || state.forkDiscardSuccess || state.forkCreatePRSuccess
        
        AlertDialog(
            onDismissRequest = { showForkSyncDialog = false },
            containerColor = c.bgCard,
            title = { Text("同步上游仓库", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 状态信息
                    if (state.forkAheadBy == 0 && state.forkBehindBy == 0) {
                        Text(
                            "此分支已同步",
                            color = Green,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "此分支",
                                color = c.textPrimary,
                                fontSize = 14.sp
                            )
                            if (state.forkAheadBy > 0) {
                                Text(
                                    "领先",
                                    color = c.textPrimary,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "${state.forkAheadBy} 个提交",
                                    color = BlueColor,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .background(BlueColor.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                        .clickable { vm.showForkSyncCommits(ForkSyncCommitsType.AHEAD) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (state.forkBehindBy > 0) {
                                Text(
                                    "落后",
                                    color = c.textPrimary,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "${state.forkBehindBy} 个提交",
                                    color = Coral,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .background(CoralDim, RoundedCornerShape(4.dp))
                                        .clickable { vm.showForkSyncCommits(ForkSyncCommitsType.BEHIND) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(4.dp))
                    
                    // 操作状态显示
                    if (state.forkSyncing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = Coral,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在同步...", color = c.textSecondary, fontSize = 14.sp)
                        }
                    }
                    
                    if (state.forkDiscardingCommits) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = Yellow,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在丢弃提交并同步...", color = c.textSecondary, fontSize = 14.sp)
                        }
                    }
                    
                    if (state.forkCreatingPR) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = BlueColor,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在创建拉取请求...", color = c.textSecondary, fontSize = 14.sp)
                        }
                    }
                    
                    // 成功状态
                    if (state.forkSyncSuccess) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Green,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("同步成功！", color = Green, fontSize = 14.sp)
                        }
                        state.forkSyncResponse?.let { resp ->
                            resp.message?.let { 
                                Text(it, color = c.textSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                    
                    if (state.forkDiscardSuccess) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Green,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("已丢弃提交并同步成功！", color = Green, fontSize = 14.sp)
                        }
                    }
                    
                    if (state.forkCreatePRSuccess && state.forkCreatedPR != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Green,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("拉取请求创建成功！", color = Green, fontSize = 14.sp)
                        }
                        Text(
                            "#${state.forkCreatedPR!!.number} ${state.forkCreatedPR!!.title}",
                            color = c.textSecondary,
                            fontSize = 13.sp
                        )
                    }
                    
                    // 错误状态
                    state.forkSyncError?.let { error ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = RedColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(error, color = RedColor, fontSize = 14.sp)
                        }
                    }
                    
                    Spacer(Modifier.height(4.dp))
                    
                    // 如果有分歧，显示警告和两个选项
                    if (hasConflict && !isAnyOperationInProgress && !isAnySuccess) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = YellowDim),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = Yellow,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        "检测到代码分歧。您的仓库既有自己的提交，又落后于上游。",
                                        color = c.textSecondary,
                                        fontSize = 13.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                            
                            // 选项1：丢弃自己提交
                            Card(
                                colors = CardDefaults.cardColors(containerColor = c.bgItem),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = Yellow,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            "选项一：丢弃自己提交后同步",
                                            color = c.textPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Text(
                                        "⚠ 此操作会永久丢弃您的 ${state.forkAheadBy} 个提交，然后同步上游的 ${state.forkBehindBy} 个提交。",
                                        color = c.textSecondary,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                    Button(
                                        onClick = { vm.discardCommitsAndSync() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Yellow),
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text("丢弃并同步", color = Color.Black)
                                    }
                                }
                            }
                            
                            // 选项2：创建拉取请求
                            Card(
                                colors = CardDefaults.cardColors(containerColor = c.bgItem),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Outbound,
                                            contentDescription = null,
                                            tint = BlueColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            "选项二：创建拉取请求",
                                            color = c.textPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Text(
                                        "向上游仓库创建拉取请求，保留您的 ${state.forkAheadBy} 个提交。上游仓库维护者可以选择是否合并您的变更。",
                                        color = c.textSecondary,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                    Button(
                                        onClick = { vm.createPullRequest() },
                                        colors = ButtonDefaults.buttonColors(containerColor = BlueColor),
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text("创建 PR", color = Color.White)
                                    }
                                }
                            }
                        }
                    } else if (!isAnyOperationInProgress && !isAnySuccess && state.forkBehindBy > 0) {
                        // 只是落后，显示普通同步
                        Card(
                            colors = CardDefaults.cardColors(containerColor = c.bgItem),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "您可以直接同步上游的 ${state.forkBehindBy} 个提交。",
                                    color = c.textSecondary,
                                    fontSize = 13.sp
                                )
                                Button(
                                    onClick = { vm.syncForkBranch() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("同步", color = Color.White)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showForkSyncDialog = false }
                ) {
                    Text("关闭", color = c.textSecondary)
                }
            }
        )
    }

    // 显示同步提交列表的Sheet
    if (state.showForkSyncCommits) {
        val repo = state.repo
        val parent = repo?.parent
        val compareResult = when (state.forkSyncCommitsType) {
            ForkSyncCommitsType.AHEAD -> state.forkSyncCompareResult
            ForkSyncCommitsType.BEHIND -> state.forkSyncReverseCompareResult
        }
        if (compareResult != null) {
            val title = when (state.forkSyncCommitsType) {
                ForkSyncCommitsType.AHEAD -> "您的提交 (${state.forkAheadBy} 个)"
                ForkSyncCommitsType.BEHIND -> "上游提交 (${state.forkBehindBy} 个)"
            }
            val commits = compareResult.commits
            val detailOwner = when (state.forkSyncCommitsType) {
                ForkSyncCommitsType.AHEAD -> owner
                ForkSyncCommitsType.BEHIND -> parent?.owner?.login ?: owner
            }
            val detailRepo = when (state.forkSyncCommitsType) {
                ForkSyncCommitsType.AHEAD -> repoName
                ForkSyncCommitsType.BEHIND -> parent?.name ?: repoName
            }

        ModalBottomSheet(
            onDismissRequest = { vm.hideForkSyncCommits() },
            containerColor = c.bgCard,
            dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 标题
                Text(
                    title,
                    color = c.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                GmDivider()

                if (commits.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无提交记录",
                            color = c.textTertiary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.heightIn(max = 500.dp)
                    ) {
                        items(commits, key = { it.sha }) { commit ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(c.bgCard, RoundedCornerShape(12.dp))
                                    .clickable {
                                        vm.hideForkSyncCommits()
                                        vm.loadForkSyncCommitDetail(
                                            detailOwner,
                                            detailRepo,
                                            commit.sha
                                        )
                                    }
                                    .padding(12.dp),
                            ) {
                                Text(
                                    commit.commit.message.lines().first(),
                                    fontSize = 13.sp,
                                    color = c.textPrimary,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (commit.author != null) {
                                        AvatarImage(commit.author.avatarUrl, 20)
                                    }
                                    Text(
                                        commit.commit.author.name,
                                        fontSize = 11.sp,
                                        color = c.textSecondary
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        commit.shortSha,
                                        fontSize = 10.sp,
                                        color = Coral,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier
                                            .background(CoralDim, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                    Text(
                                        repoFormatDate(commit.commit.author.date),
                                        fontSize = 11.sp,
                                        color = c.textTertiary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 关闭按钮
                Button(
                    onClick = { vm.hideForkSyncCommits() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = c.bgItem)
                ) {
                    Text("关闭", color = c.textSecondary)
                }
            }
        }
        }
    }

    if (showVisibilityDialog && repoForDialogs != null) {
        RepoVisibilityDialog(
            repo = repoForDialogs,
            c = c,
            onConfirm = { makePrivate ->
                vm.updateVisibility(makePrivate)
                showVisibilityDialog = false
            },
            onDismiss = { showVisibilityDialog = false },
        )
    }
    if (showTransferDialog) {
        RepoTransferDialog(
            owner = vm.owner,
            repoName = vm.state.value.currentRepoName,
            userLogin = state.userLogin,
            userAvatar = state.userAvatar,
            orgs = state.userOrgs,
            c = c,
            onConfirm = { target, newName ->
                vm.transferRepo(target, newName) { onBack() }
                showTransferDialog = false
            },
            onDismiss = { showTransferDialog = false },
        )
    }

    if (state.showCreateForkDialog) {
        data class ForkTarget(val login: String, val avatarUrl: String?, val label: String)
        
        val forkTargets = remember(state.userLogin, state.userAvatar, state.userOrgs) {
            buildList {
                if (state.userLogin.isNotBlank()) {
                    add(ForkTarget(state.userLogin, state.userAvatar, "（你）"))
                }
                state.userOrgs.forEach { org ->
                    add(ForkTarget(org.login, org.avatarUrl, ""))
                }
            }
        }
        
        var selectedTarget by remember { mutableStateOf(forkTargets.firstOrNull()?.login.orEmpty()) }
        var forkName by remember { mutableStateOf(vm.state.value.currentRepoName) }
        var forkDefaultBranchOnly by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { vm.hideCreateForkDialog() },
            containerColor = c.bgCard,
            title = { Text("复刻仓库", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "将 ${vm.owner}/${vm.state.value.currentRepoName} 复刻到",
                        color = c.textSecondary,
                        fontSize = 14.sp
                    )
                    
                    Text("选择所有者", fontSize = 12.sp, color = c.textSecondary)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        forkTargets.forEach { target ->
                            val isSelected = selectedTarget == target.login
                            val bg = if (isSelected) CoralDim else Color.Transparent
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bg, RoundedCornerShape(8.dp))
                                    .clickable { selectedTarget = target.login }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                coil3.compose.AsyncImage(
                                    model = target.avatarUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(c.bgItem),
                                )
                                Text(
                                    "${target.login}${target.label}",
                                    color = if (isSelected) Coral else c.textPrimary,
                                    fontSize = 13.sp,
                                )
                                if (isSelected) {
                                    Spacer(Modifier.weight(1f))
                                    Icon(Icons.Default.Check, null, tint = Coral, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    
                    OutlinedTextField(
                        value = forkName,
                        onValueChange = { forkName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("仓库名称") },
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
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = forkDefaultBranchOnly,
                            onCheckedChange = { forkDefaultBranchOnly = it },
                            colors = CheckboxDefaults.colors(checkedColor = Coral)
                        )
                        Text(
                            "仅复制 ${state.repo?.defaultBranch ?: "main"} 分支",
                            color = c.textPrimary,
                            fontSize = 14.sp
                        )
                    }
                    
                    if (state.createForkError != null) {
                        Text(
                            state.createForkError!!,
                            color = RedColor,
                            fontSize = 13.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val organization = if (selectedTarget == state.userLogin) null else selectedTarget
                        vm.createFork(
                            name = forkName,
                            organization = organization,
                            defaultBranchOnly = forkDefaultBranchOnly
                        )
                    },
                    enabled = selectedTarget.isNotBlank() && forkName.isNotBlank() && !state.creatingFork,
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                ) {
                    if (state.creatingFork) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("复刻到 $selectedTarget", color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.hideCreateForkDialog() }) {
                    Text("取消", color = c.textSecondary)
                }
            },
        )
        
        LaunchedEffect(state.createForkSuccess) {
            if (state.createForkSuccess) {
                vm.hideCreateForkDialog()
            }
        }
    }

    if (showCreateFileTypeMenu) {
        AlertDialog(
            onDismissRequest = { showCreateFileTypeMenu = false },
            containerColor = c.bgCard,
            title = { Text("创建", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请选择要创建的类型", fontSize = 14.sp, color = c.textSecondary)
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showCreateFileTypeMenu = false
                            val fullPath = if (state.currentPath.isNotEmpty()) {
                                "${state.currentPath}/"
                            } else {
                                ""
                            }
                            onEditFile(fullPath, "NEW", state.currentBranch, false, false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Coral)
                    ) {
                        Icon(Icons.Default.Description, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("普通文件")
                    }
                    Button(
                        onClick = {
                            showCreateFileTypeMenu = false
                            val fullPath = if (state.currentPath.isNotEmpty()) {
                                "${state.currentPath}/"
                            } else {
                                ""
                            }
                            onEditFile(fullPath, "NEW", state.currentBranch, true, false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = BlueColor)
                    ) {
                        Icon(Icons.Default.Link, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("符号链接")
                    }
                    Button(
                        onClick = {
                            showCreateFileTypeMenu = false
                            val fullPath = if (state.currentPath.isNotEmpty()) {
                                "${state.currentPath}/"
                            } else {
                                ""
                            }
                            onEditFile(fullPath, "NEW", state.currentBranch, false, true)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) {
                        Icon(Icons.Default.AccountTree, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Git 子模块")
                    }
                    TextButton(
                        onClick = { showCreateFileTypeMenu = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("取消", color = c.textSecondary)
                    }
                }
            },
        )
    }

    if (showCreateFileDialog) {
        CreateFileDialog(
            currentPath = state.currentPath,
            c = c,
            onConfirm = { fileName, content ->
                showCreateFileDialog = false
                newFileName = fileName
                newFileContent = content
                commitMessage = "Create $fileName"
                showCommitMessageDialog = true
            },
            onDismiss = { showCreateFileDialog = false },
        )
    }

    if (showCommitMessageDialog) {
        CommitMessageDialog(
            defaultMessage = commitMessage,
            c = c,
            onConfirm = { msg ->
                showCommitMessageDialog = false
                val fullPath = if (state.currentPath.isNotEmpty()) {
                    "${state.currentPath}/$newFileName"
                } else {
                    newFileName
                }
                vm.createOrUpdateFile(
                    path = fullPath,
                    message = msg,
                    content = newFileContent,
                )
            },
            onDismiss = { showCommitMessageDialog = false },
        )
    }

    if (showRenameFileDialog && selectedFileForMenu != null) {
        var newName by remember { mutableStateOf(renameFileNewName) }
        var commitMsg by remember { mutableStateOf("Rename ${selectedFileForMenu!!.name} to ${renameFileNewName}") }
        AlertDialog(
            onDismissRequest = { showRenameFileDialog = false },
            containerColor = c.bgCard,
            title = { Text("重命名文件/文件夹", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { 
                            newName = it
                            commitMsg = "Rename ${selectedFileForMenu!!.name} to $it"
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("新名称") },
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
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = commitMsg,
                        onValueChange = { commitMsg = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("提交信息") },
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
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank() && newName != selectedFileForMenu!!.name && commitMsg.isNotBlank()) {
                            showRenameFileDialog = false
                            val oldPath = selectedFileForMenu!!.path
                            val newPath = if (oldPath.contains("/")) {
                                oldPath.replaceAfterLast("/", newName)
                            } else {
                                newName
                            }
                            
                            vm.renameFile(
                                oldPath = oldPath,
                                newPath = newPath,
                                message = commitMsg,
                            )
                        }
                    },
                    enabled = newName.isNotBlank() && newName != selectedFileForMenu!!.name && commitMsg.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                ) {
                    Text("重命名")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameFileDialog = false }) {
                    Text("取消", color = c.textSecondary)
                }
            },
        )
    }

    if (showDeleteFileDialog && selectedFileForMenu != null) {
        AlertDialog(
            onDismissRequest = { showDeleteFileDialog = false },
            containerColor = c.bgCard,
            icon = {
                Icon(Icons.Default.Delete, null, tint = RedColor, modifier = Modifier.size(28.dp))
            },
            title = { Text("确认删除", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Text("确定要删除 \"${selectedFileForMenu!!.name}\" 吗？此操作无法撤销。", fontSize = 13.sp, color = c.textSecondary)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteFileDialog = false
                        commitMessage = "Delete ${selectedFileForMenu!!.name}"
                        vm.deleteFile(
                            path = selectedFileForMenu!!.path,
                            message = commitMessage,
                            sha = selectedFileForMenu!!.sha,
                            contentType = selectedFileForMenu!!.type,
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedColor),
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFileDialog = false }) {
                    Text("取消", color = c.textSecondary)
                }
            },
        )
    }
    // 订阅设置 Sheet
    if (showWatchSheet) {
        WatchSheet(state = state, vm = vm, onDismiss = { showWatchSheet = false })
    }

    // 切换议题确认弹窗
    if (showToggleIssuesDialog && state.repo != null) {
        AlertDialog(
            onDismissRequest = { showToggleIssuesDialog = false },
            containerColor = c.bgCard,
            icon = {
                Icon(
                    Icons.Default.BugReport,
                    null,
                    tint = if (state.repo!!.hasIssues) RedColor else Green,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    if (state.repo!!.hasIssues) "关闭议题" else "打开议题",
                    color = c.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    if (state.repo!!.hasIssues) "确定要关闭此仓库的议题功能吗？之前的议题仍会保留。" else "确定要打开此仓库的议题功能吗？",
                    fontSize = 13.sp,
                    color = c.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showToggleIssuesDialog = false
                        vm.toggleIssues(!state.repo!!.hasIssues)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.repo!!.hasIssues) RedColor else Coral),
                ) {
                    Text(if (state.repo!!.hasIssues) "关闭" else "打开")
                }
            },
            dismissButton = {
                TextButton(onClick = { showToggleIssuesDialog = false }) {
                    Text("取消", color = c.textSecondary)
                }
            },
        )
    }

    // 切换讨论确认弹窗
    if (showToggleDiscussionsDialog && state.repo != null) {
        AlertDialog(
            onDismissRequest = { showToggleDiscussionsDialog = false },
            containerColor = c.bgCard,
            icon = {
                Icon(
                    Icons.Default.Forum,
                    null,
                    tint = if (state.repo!!.hasDiscussions) RedColor else Green,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    if (state.repo!!.hasDiscussions) "关闭讨论" else "打开讨论",
                    color = c.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    if (state.repo!!.hasDiscussions) "确定要关闭此仓库的讨论功能吗？之前的讨论仍会保留。" else "确定要打开此仓库的讨论功能吗？",
                    fontSize = 13.sp,
                    color = c.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showToggleDiscussionsDialog = false
                        vm.toggleDiscussions(!state.repo!!.hasDiscussions)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.repo!!.hasDiscussions) RedColor else Coral),
                ) {
                    Text(if (state.repo!!.hasDiscussions) "关闭" else "打开")
                }
            },
            dismissButton = {
                TextButton(onClick = { showToggleDiscussionsDialog = false }) {
                    Text("取消", color = c.textSecondary)
                }
            },
        )
    }

    // ── 上传入口 Sheet（选择"文件"还是"文件夹"）────────────────────────────
    if (showUploadSourceSheet) {
        UploadSourceSheet(
            repoPath  = state.currentPath,
            c         = c,
            onPickFiles = {
                showUploadSourceSheet = false
                showUploadFilePicker  = true
            },
            onPickFolder = {
                showUploadSourceSheet = false
                showUploadFolderPicker = true
            },
            onDismiss = { showUploadSourceSheet = false },
        )
    }

    // ── 文件多选 Picker ──────────────────────────────────────────────────────
    if (showUploadFilePicker) {
        FilePickerScreen(
            title       = "选择要上传的文件",
            mode        = PickerMode.MULTI_FILE,
            rootEnabled = false,
            detectGitRepos = false,
            onConfirm   = { _, files ->
                val repoBase = state.currentPath
                uploadEntries = files.map { localPath ->
                    val name      = java.io.File(localPath).name
                    val repoPath  = if (repoBase.isEmpty()) name else "$repoBase/$name"
                    val sizeBytes = java.io.File(localPath).length()
                    UploadFileEntry(localPath, repoPath, sizeBytes)
                }
                showUploadFilePicker  = false
                showUploadReviewSheet = true
            },
            onDismiss = { showUploadFilePicker = false },
        )
    }

    // ── 文件夹 Picker ────────────────────────────────────────────────────────
    if (showUploadFolderPicker) {
        FilePickerScreen(
            title       = "选择要上传的文件夹",
            mode        = PickerMode.DIRECTORY,
            rootEnabled = false,
            detectGitRepos = false,
            onConfirm   = { localDir, _ ->
                val repoBase = state.currentPath
                uploadEntries = scanDirectory(java.io.File(localDir), repoBase)
                showUploadFolderPicker = false
                showUploadReviewSheet  = true
            },
            onDismiss = { showUploadFolderPicker = false },
        )
    }

    // ── 上传预览 Sheet（勾选文件 + commit message）──────────────────────────
    if (showUploadReviewSheet && uploadEntries.isNotEmpty()) {
        UploadReviewSheet(
            allEntries = uploadEntries,
            existingFiles = state.contents,
            c          = c,
            onConfirm  = { selected, message ->
                showUploadReviewSheet = false
                vm.uploadFiles(
                    fileEntries   = selected.map { Pair(it.localPath, it.repoPath) },
                    commitMessage = message,
                    onSuccess     = { /* 进度弹窗自己处理 DONE 状态 */ },
                    onError       = { /* 进度弹窗自己处理 ERROR 状态 */ },
                )
            },
            onDismiss = { showUploadReviewSheet = false },
        )
    }

    // ── 上传进度弹窗（上传进行中 / 完成 / 失败）──────────────────────────
    if (state.uploadPhase != UploadPhase.IDLE) {
        UploadProgressDialog(
            phase        = state.uploadPhase,
            blobProgress = state.uploadBlobProgress,
            blobTotal    = state.uploadBlobTotal,
            currentFile  = state.uploadCurrentFile,
            errorMsg     = state.uploadError,
            c            = c,
            onDone  = { vm.resetUploadState() },
            onRetry = { vm.resetUploadState() },
        )
    }

    // Commit 详情 Modal
    state.selectedCommit?.let { commit ->
        CommitDetailSheet(commit = commit, c = c, permission = permission, isArchived = state.repo?.archived ?: false, vm = vm, onDismiss = vm::clearCommitDetail)
    }
    // 星标用户列表 Modal
    if (state.showStargazersSheet) {
        StargazersSheet(c = c, vm = vm, onDismiss = vm::hideStargazersSheet)
    }
    // 复刻仓库列表 Modal
    if (state.showForksSheet) {
        ForksSheet(c = c, vm = vm, onDismiss = vm::hideForksSheet)
    }
    
    // 文件历史弹窗
    if (state.showFileHistorySheet && state.selectedFileForHistory != null) {
        val selectedFilePatchForFileHistory = remember { mutableStateOf<FilePatchInfo?>(null) }
        
        selectedFilePatchForFileHistory.value?.let { info ->
            FileDiffSheet(info = info, c = c, vm = vm, onDismiss = { selectedFilePatchForFileHistory.value = null })
        }
        
        ModalBottomSheet(
            onDismissRequest = { vm.hideFileHistory() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.bgCard,
            dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("文件历史", color = c.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(state.selectedFileForHistory!!.name, color = c.textTertiary, fontSize = 12.sp)
                    }
                    IconButton(onClick = { vm.hideFileHistory() }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = c.textSecondary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                GmDivider()
                Spacer(Modifier.height(8.dp))
                
                if (state.fileHistoryLoading) {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Coral, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (state.fileHistoryCommits.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("暂无历史记录", fontSize = 13.sp, color = c.textTertiary)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(state.fileHistoryCommits, key = { it.sha }) { commit ->
                            Column(
                                modifier = Modifier.fillMaxWidth().background(c.bgCard, RoundedCornerShape(12.dp))
                                    .clickable { vm.loadFileHistoryCommitDetail(commit.sha) }
                                    .padding(12.dp),
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
    }
    
    // 文件历史中的提交详情弹窗
    state.selectedCommitForFileHistory?.let { commit ->
        val selectedFilePatchForFileHistory = remember { mutableStateOf<FilePatchInfo?>(null) }

        selectedFilePatchForFileHistory.value?.let { info ->
            FileDiffSheet(info = info, c = c, vm = vm, onDismiss = { selectedFilePatchForFileHistory.value = null })
        }

        ModalBottomSheet(
            onDismissRequest = { vm.clearFileHistoryCommitDetail() },
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
            ) {
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
                Spacer(Modifier.height(12.dp))
                GmDivider()
                Spacer(Modifier.height(12.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (commit.author != null) AvatarImage(commit.author.avatarUrl, 32)
                    Column {
                        Text(commit.commit.author.name, fontSize = 13.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
                        Text(
                            repoFormatDate(commit.commit.author.date),
                            fontSize = 11.sp, color = c.textTertiary
                        )
                    }
                }
                
                if (commit.files?.isNotEmpty() == true) {
                    Spacer(Modifier.height(16.dp))
                    GmDivider()
                    Spacer(Modifier.height(12.dp))
                    Text("变更文件", fontSize = 14.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    
                    commit.files.forEach { file ->
                        val status = when (file.status) {
                            "added" -> "新增"
                            "removed" -> "删除"
                            "modified" -> "修改"
                            "renamed" -> "重命名"
                            else -> file.status
                        }
                        val statusColor = when (file.status) {
                            "added" -> Green
                            "removed" -> RedColor
                            else -> c.textSecondary
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(c.bgItem, RoundedCornerShape(8.dp))
                                .clickable {
                                    if (file.patch != null) {
                                        selectedFilePatchForFileHistory.value = FilePatchInfo(
                                            filename         = file.filename,
                                            patch            = file.patch,
                                            additions        = file.additions,
                                            deletions        = file.deletions,
                                            status           = file.status,
                                            parentSha        = commit.parentSha,
                                            previousFilename = file.previousFilename,
                                            owner            = owner,
                                            repoName         = repoName,
                                            currentSha       = commit.sha,
                                        )
                                    }
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (file.status == "added") Icons.Default.Add else if (file.status == "removed") Icons.Default.Remove else Icons.Default.Edit,
                                null,
                                tint = statusColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.filename, fontSize = 12.sp, color = c.textPrimary, maxLines = 1)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(status, fontSize = 11.sp, color = statusColor)
                                    if (file.additions > 0) Text("+${file.additions}", fontSize = 11.sp, color = Green)
                                    if (file.deletions > 0) Text("-${file.deletions}", fontSize = 11.sp, color = RedColor)
                                }
                            }
                            if (file.patch != null) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector, 
    text: String, 
    tint: Color,
    onClick: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically, 
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = if (onClick != null) {
            Modifier
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp, horizontal = 8.dp)
        } else {
            Modifier
        }
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 13.sp, color = LocalGmColors.current.textSecondary)
    }
}

// ─── Tabs ────────────────────────────────────────────────────────

/**
 * 文件标签页组件
 * 
 * @param state 仓库详情状态
 * @param c 颜色主题
 * @param permission 仓库权限
 * @param isArchived 仓库是否已归档
 * @param onDirClick 目录点击回调
 * @param onFileClick 文件点击回调
 * @param onNavigateUp 返回上一级目录回调
 * @param onRefresh 刷新回调
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FilesTab(
    state: RepoDetailState,
    c: GmColors,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onDirClick: (String) -> Unit,
    onFileClick: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit = {},
    onUpload: () -> Unit = {},
    onShare: () -> Unit = {},
    onAddFile: () -> Unit = {},
    onHistory: () -> Unit = {},
    onFileRename: (GHContent) -> Unit = {},
    onFileDelete: (GHContent) -> Unit = {},
    onFileShare: (GHContent) -> Unit = {},
    onFileHistory: (GHContent) -> Unit = {},
) {
    PullToRefreshBox(
        isRefreshing = state.filesRefreshing,
        onRefresh = onRefresh,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(c.bgCard, RoundedCornerShape(8.dp))
                        .let { if (state.currentPath.isNotEmpty()) it.clickable(onClick = onNavigateUp) else it }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.currentPath.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
                            tint = c.textTertiary, modifier = Modifier.size(17.dp))
                        Text("..", fontSize = 13.sp, color = c.textTertiary)
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.currentPath.isNotEmpty()) {
                        Text(state.currentPath, fontSize = 11.sp,
                            color = c.textTertiary, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(8.dp))
                    }
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PermissionRequired(permission = permission, requireOwner = true, isArchived = isArchived) {
                            IconButton(
                                onClick = onUpload,
                                modifier = Modifier.size(32.dp),
                                enabled = !isArchived
                            ) {
                                Icon(
                                    Icons.Default.Upload,
                                    contentDescription = "上传文件",
                                    tint = if (isArchived) c.textTertiary else Coral,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = onShare,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "分享",
                                tint = c.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        PermissionRequired(permission = permission, requireOwner = true, isArchived = isArchived) {
                            IconButton(
                                onClick = onAddFile,
                                modifier = Modifier.size(32.dp),
                                enabled = !isArchived
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "添加文件",
                                    tint = if (isArchived) c.textTertiary else c.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = onHistory,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = "历史记录",
                                tint = c.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            // 文件列表加载中：仅在非下拉刷新时显示内联 spinner
            // （下拉刷新时顶部已有 PullToRefreshBox 的指示器，无需重复显示）
            if (state.contentsLoading && !state.filesRefreshing) {
                item {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Coral, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (!state.contentsLoading) {
                items(state.contents, key = { it.path }) { content ->
                    val isDir = content.type == "dir"
                    val isSymlink = content.type == "symlink"
                    val isSubmodule = content.submoduleGitUrl != null
                    var showMenu by remember { mutableStateOf(false) }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(c.bgCard, RoundedCornerShape(8.dp))
                            .clickable { if (isDir) onDirClick(content.path) else onFileClick(content.path) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            when {
                                isDir -> Icons.Default.Folder
                                isSymlink -> Icons.Default.Link
                                isSubmodule -> Icons.Default.AccountTree
                                else -> Icons.Default.Description
                            },
                            null,
                            tint = when {
                                isDir -> Yellow
                                isSymlink -> BlueColor
                                isSubmodule -> Green
                                else -> c.textSecondary
                            },
                            modifier = Modifier.size(17.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(content.name, fontSize = 13.sp, color = c.textPrimary)
                            if (isSymlink) {
                                Text("符号链接", fontSize = 10.sp, color = c.textTertiary)
                            }
                            if (isSubmodule) {
                                Text("子模块", fontSize = 10.sp, color = c.textTertiary)
                            }
                        }
                        if (!isDir && !isSubmodule) Text(formatSize(content.size), fontSize = 11.sp, color = c.textTertiary)
                        
                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "更多",
                                    tint = c.textTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                PermissionRequired(permission = permission, requireOwner = true, isArchived = isArchived) {
                                    ArchivedAwareDropdownMenuItem(
                                        text = { Text("重命名", fontSize = 13.sp) },
                                        isArchived = isArchived,
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.DriveFileRenameOutline,
                                                null,
                                                tint = c.textSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            onFileRename(content)
                                        },
                                    )
                                }
                                PermissionRequired(permission = permission, requireOwner = true, isArchived = isArchived) {
                                    ArchivedAwareDropdownMenuItem(
                                        text = { Text("删除", fontSize = 13.sp, color = if (isArchived) c.textTertiary else RedColor) },
                                        isArchived = isArchived,
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Delete,
                                                null,
                                                tint = if (isArchived) c.textTertiary else RedColor,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            onFileDelete(content)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("分享", fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Share,
                                            null,
                                            tint = c.textSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        onFileShare(content)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("历史记录", fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.History,
                                            null,
                                            tint = c.textSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        onFileHistory(content)
                                    },
                                )
                            }
                        }
                        
                        if (isDir) Icon(Icons.AutoMirrored.Filled.ArrowForward, null,
                            tint = c.textTertiary, modifier = Modifier.size(14.dp))
                    }
                }
            }
            // README 区域：仅在根目录 且 文件列表已加载完成时显示，避免双 loading
            if (state.currentPath.isEmpty() && !state.contentsLoading && !state.filesRefreshing) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.bgCard, RoundedCornerShape(8.dp))
                            .padding(16.dp)
                    ) {
                        Text("README", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                        Spacer(Modifier.height(8.dp))
                        GmDivider()
                        Spacer(Modifier.height(8.dp))
                        when {
                            state.readmeLoading -> {
                                // 骨架占位条，不再使用独立 CircularProgressIndicator
                                repeat(3) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(if (it == 2) 0.6f else 1f)
                                            .height(14.dp)
                                            .padding(bottom = 2.dp)
                                            .background(c.border, RoundedCornerShape(4.dp))
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            state.readmeContent != null -> {
                GmMarkdownWebView(
                    markdown = state.readmeContent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
                            else -> {
                                Text("暂无 README 文件", fontSize = 13.sp, color = c.textTertiary)
                            }
                        }
                    }
                }
            }
        }

    }
}

/**
 * Issues标签页组件
 * 
 * @param state 仓库详情状态
 * @param c 颜色主题
 * @param vm 仓库详情ViewModel
 * @param onRefresh 刷新回调
 */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024L        -> "${bytes}B"
    bytes < 1024L * 1024 -> "${bytes / 1024}KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / 1024 / 1024}MB"
    else -> "${bytes / 1024 / 1024 / 1024}GB"
}

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal fun repoFormatDate(iso: String): String = try {
    val odt = OffsetDateTime.parse(iso)
    // 转换为北京时间（UTC+8），GitHub API 返回 UTC/带偏移时间
    val beijing = odt.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
    beijing.format(dateFormatter)
} catch (_: Exception) {
    iso
}

internal fun isColorLight(color: Color): Boolean {
    val luminance = 0.299 * color.red + 0.587 * color.green + 0.114 * color.blue
    return luminance > 0.5
}

// ─── 星标用户列表弹窗 ────────────────────────────────────────────────────────

/**
 * 星标用户列表弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StargazersSheet(
    c: GmColors,
    vm: RepoDetailViewModel,
    onDismiss: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect { layoutInfo ->
                val visibleItems = layoutInfo.visibleItemsInfo
                val lastVisibleItem = visibleItems.lastOrNull()
                if (lastVisibleItem != null) {
                    val totalItems = layoutInfo.totalItemsCount
                    if (lastVisibleItem.index >= totalItems - 3 && !state.stargazersLoadingMore && state.stargazersHasMore) {
                        vm.loadMoreStargazers()
                    }
                }
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            // 标题
            Text(
                "星标用户",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.textPrimary
            )
            Spacer(Modifier.height(8.dp))
            GmDivider()
            Spacer(Modifier.height(8.dp))

            if (state.stargazersLoading) {
                Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Coral)
                }
            } else if (state.stargazers.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("暂无星标用户", fontSize = 14.sp, color = c.textTertiary) }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(state.stargazers, key = { it.id }) { stargazer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(c.bgItem, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AvatarImage(stargazer.avatarUrl, 40)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    stargazer.login,
                                    fontSize = 14.sp,
                                    color = c.textPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                if (stargazer.starredAt != null) {
                                    Text(
                                        repoFormatDate(stargazer.starredAt),
                                        fontSize = 11.sp,
                                        color = c.textTertiary
                                    )
                                }
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                null,
                                tint = c.textTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    item {
                        if (state.stargazersLoadingMore) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Coral,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── 复刻仓库列表弹窗 ────────────────────────────────────────────────────────

/**
 * 复刻仓库列表弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForksSheet(
    c: GmColors,
    vm: RepoDetailViewModel,
    onDismiss: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var showSortDropdown by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect { layoutInfo ->
                val visibleItems = layoutInfo.visibleItemsInfo
                val lastVisibleItem = visibleItems.lastOrNull()
                if (lastVisibleItem != null) {
                    val totalItems = layoutInfo.totalItemsCount
                    if (lastVisibleItem.index >= totalItems - 3 && !state.forksLoadingMore && state.forksHasMore) {
                        vm.loadMoreForks()
                    }
                }
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            // 标题和筛选区域
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 清除按钮（仅在非默认排序时显示）
                if (state.forkSortBy != ForkSortBy.NEWEST) {
                    FilterButton(
                        text = "清除",
                        isActive = true,
                        c = c,
                        onClick = { vm.clearForkFilters() }
                    )
                }
                
                Text(
                    "复刻仓库",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                
                // 排序（仅保留 API 支持的选项）
                Box {
                    FilterButton(
                        text = state.forkSortBy.displayName,
                        isActive = state.forkSortBy != ForkSortBy.NEWEST,
                        c = c,
                        onClick = { showSortDropdown = true }
                    )
                    DropdownMenu(
                        expanded = showSortDropdown,
                        onDismissRequest = { showSortDropdown = false },
                        modifier = Modifier.background(c.bgCard)
                    ) {
                        ForkSortBy.values().forEach { sortBy ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        RadioButton(
                                            selected = state.forkSortBy == sortBy,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(selectedColor = Coral)
                                        )
                                        Text(sortBy.displayName, fontSize = 13.sp, color = c.textPrimary)
                                    }
                                },
                                onClick = {
                                    vm.setForkSortBy(sortBy)
                                    showSortDropdown = false
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            GmDivider()
            Spacer(Modifier.height(8.dp))

            if (state.forksLoading) {
                Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Coral)
                }
            } else if (state.forks.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("暂无复刻仓库", fontSize = 14.sp, color = c.textTertiary) }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(state.forks, key = { it.id }) { fork ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(c.bgItem, RoundedCornerShape(12.dp))
                                .clickable {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(fork.htmlUrl)
                                    )
                                    context.startActivity(intent)
                                }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AvatarImage(fork.owner.avatarUrl, 36)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        fork.fullName,
                                        fontSize = 14.sp,
                                        color = c.textPrimary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (!fork.description.isNullOrBlank()) {
                                        Text(
                                            fork.description,
                                            fontSize = 12.sp,
                                            color = c.textSecondary,
                                            maxLines = 2
                                        )
                                    }
                                }
                                Icon(
                                    Icons.Default.ChevronRight,
                                    null,
                                    tint = c.textTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Star, null, tint = Yellow, modifier = Modifier.size(14.dp))
                                    Text("${fork.stars}", fontSize = 11.sp, color = c.textTertiary)
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Share, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                                    Text("${fork.forks}", fontSize = 11.sp, color = c.textTertiary)
                                }
                                if (!fork.language.isNullOrBlank()) {
                                    Text(
                                        fork.language,
                                        fontSize = 11.sp,
                                        color = c.textTertiary,
                                        modifier = Modifier
                                            .background(c.bgCard, RoundedCornerShape(12.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                if (fork.updatedAt != null) {
                                    Text(
                                        repoFormatDate(fork.updatedAt),
                                        fontSize = 11.sp,
                                        color = c.textTertiary
                                    )
                                }
                            }
                        }
                    }
                    item {
                        if (state.forksLoadingMore) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Coral,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── 分支相关弹窗 ─────────────────────────────────────────────────────────────

