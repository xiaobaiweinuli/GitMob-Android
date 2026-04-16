package com.gitmob.android.ui.repos

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gitmob.android.api.GHOrg
import com.gitmob.android.api.GHRepo
import com.gitmob.android.ui.common.*
import com.gitmob.android.ui.repo.ArchivedAwareDropdownMenuItem
import com.gitmob.android.ui.theme.*
import com.gitmob.android.util.LogManager
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FolderDelete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3Api::class)
@Composable
fun RepoListScreen(
    onRepoClick: (String, String) -> Unit,
    onCreateRepo: () -> Unit,
    onCloneRepo: (String) -> Unit = {},
    onBack: () -> Unit = {},
    vm: RepoListViewModel = viewModel(),
    starVm: StarListViewModel = viewModel(),
    onSearchClick: () -> Unit = {},
) {
    val c = LocalGmColors.current
    val state by vm.state.collectAsState()
    val repos = remember(state.repos, state.searchQuery, state.filterState) {
        filterAndSortRepos(state.repos, state.searchQuery, state.filterState)
    }
    val starState by starVm.state.collectAsState()
    var showOrgMenu by remember { mutableStateOf(false) }
    // 星标模式弹窗状态
    var showCreateListDialog by remember { mutableStateOf(false) }
    var editingList by remember { mutableStateOf<UserList?>(null) }
    var classifyRepo by remember { mutableStateOf<StarredRepo?>(null) }
    var createListFromClassify by remember { mutableStateOf(false) }
    
    val isViewingOtherUser = state.targetUserLogin != null
    
    // BackHandler: 拦截系统返回键
    BackHandler(enabled = isViewingOtherUser) {
        when {
            state.viewMode == state.initialViewMode -> {
                // 如果当前视图与初始视图一致，直接返回上一页
                onBack()
            }
            else -> {
                // 否则切换回初始视图
                when (state.initialViewMode) {
                    ViewMode.STARRED -> vm.switchToUserStarred(state.targetUserLogin!!, state.targetUserAvatar)
                    ViewMode.REPOS -> vm.switchToUserRepos(state.targetUserLogin!!, state.targetUserAvatar)
                }
            }
        }
    }

    // 列表权限（控制添加按钮、卡片删除/重命名/编辑）
    val listPermission = remember(state.currentContext, state.userLogin, state.userOrgs) {
        com.gitmob.android.ui.repo.calcRepoListPermission(
            currentContextLogin = state.currentContext?.login,
            userLogin           = state.userLogin,
            userOrgs            = state.userOrgs,
        )
    }

    // Toast
    state.toast?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2500)
            vm.clearToast()
        }
    }

    Scaffold(
        containerColor = c.bgDeep,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // 显示返回按钮（当查看其他用户时）
                        if (isViewingOtherUser) {
                            IconButton(onClick = { 
                                LogManager.d("RepoListScreen", "点击返回按钮，targetUserLogin=${state.targetUserLogin}, viewMode=${state.viewMode}, initialViewMode=${state.initialViewMode}")
                                when {
                                    state.viewMode == state.initialViewMode -> {
                                        // 如果当前视图与初始视图一致，直接返回上一页
                                        LogManager.d("RepoListScreen", "当前视图与初始视图一致，调用 onBack() 返回上一个页面")
                                        onBack()
                                    }
                                    else -> {
                                        // 否则切换回初始视图
                                        LogManager.d("RepoListScreen", "当前视图与初始视图不一致，切换回初始视图")
                                        when (state.initialViewMode) {
                                            ViewMode.STARRED -> vm.switchToUserStarred(state.targetUserLogin!!, state.targetUserAvatar)
                                            ViewMode.REPOS -> vm.switchToUserRepos(state.targetUserLogin!!, state.targetUserAvatar)
                                        }
                                    }
                                }
                            }) {
                                Icon(Icons.AutoMirrored.Default.ArrowBack, null, tint = c.textPrimary)
                            }
                        }
                        Box {
                            // 头像：显示当前组织/用户的头像（追加 s=40 缩略图）
                            val displayAvatar = thumbUrl(
                                if (isViewingOtherUser) {
                                    state.targetUserAvatar ?: state.currentContext?.avatarUrl ?: state.userAvatar
                                } else {
                                    state.currentContext?.avatarUrl ?: state.userAvatar
                                }
                            )
                            AsyncImage(
                                model = displayAvatar,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp).clip(CircleShape)
                                    .background(c.bgItem).clickable { 
                                        vm.ensureOrgsLoaded()
                                        showOrgMenu = true 
                                    },
                            )
                            DropdownMenu(
                                expanded = showOrgMenu,
                                onDismissRequest = { showOrgMenu = false },
                                modifier = Modifier.background(c.bgCard),
                            ) {
                                if (isViewingOtherUser) {
                                    // 查看其他用户时，显示该用户的仓库选项和组织选项
                                    OrgMenuItem(
                                        login = state.targetUserLogin!!,
                                        avatarUrl = thumbUrl(state.targetUserAvatar ?: state.currentContext?.avatarUrl),
                                        selected = state.viewMode == ViewMode.REPOS && (state.currentContext?.isUser ?: true),
                                        onClick = { 
                                            vm.switchToUserRepos(state.targetUserLogin!!, state.targetUserAvatar)
                                            showOrgMenu = false 
                                        },
                                        c = c,
                                    )
                                    if (state.userOrgs.isNotEmpty()) {
                                        HorizontalDivider(color = c.border, thickness = 0.5.dp,
                                            modifier = Modifier.padding(vertical = 4.dp))
                                        state.userOrgs.forEach { org ->
                                            val sel = state.currentContext?.login == org.login && !(state.currentContext?.isUser ?: true)
                                            OrgMenuItem(
                                                login = org.login,
                                                avatarUrl = org.avatarUrl,
                                                selected = sel,
                                                onClick = {
                                                    vm.switchContext(OrgContext(org.login, org.avatarUrl, false))
                                                    showOrgMenu = false
                                                },
                                                c = c,
                                            )
                                        }
                                    }
                                } else {
                                    // 查看自己时，显示原来的选项
                                    OrgMenuItem(
                                        login = state.userLogin,
                                        avatarUrl = thumbUrl(state.userAvatar),
                                        selected = state.currentContext == null,
                                        onClick = { vm.switchContext(null); showOrgMenu = false },
                                        c = c,
                                    )
                                    if (state.userOrgs.isNotEmpty()) {
                                        HorizontalDivider(color = c.border, thickness = 0.5.dp,
                                            modifier = Modifier.padding(vertical = 4.dp))
                                        state.userOrgs.forEach { org ->
                                            val sel = state.currentContext?.login == org.login
                                            OrgMenuItem(
                                                login = org.login,
                                                avatarUrl = org.avatarUrl,
                                                selected = sel,
                                                onClick = {
                                                    vm.switchContext(OrgContext(org.login, org.avatarUrl, false))
                                                    showOrgMenu = false
                                                },
                                                c = c,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        val titleText = when {
                            isViewingOtherUser && state.viewMode == ViewMode.STARRED -> "${state.targetUserLogin} 的星标"
                            isViewingOtherUser -> state.currentContext?.login ?: state.targetUserLogin ?: "GitMob"
                            else -> state.currentContext?.login ?: "GitMob"
                        }
                        Text(
                            text = titleText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Coral,
                        )
                    }
                },
                actions = {
                    if (isViewingOtherUser) {
                        // 查看其他用户时，显示切换仓库/星标按钮
                        IconButton(
                            onClick = {
                                LogManager.d("RepoListScreen", "点击切换按钮，当前状态: targetUserLogin=${state.targetUserLogin}, viewMode=${state.viewMode}")
                                if (state.viewMode == ViewMode.REPOS) {
                                    LogManager.d("RepoListScreen", "从仓库切换到星标，login=${state.targetUserLogin}")
                                    vm.switchToUserStarred(state.targetUserLogin!!, state.targetUserAvatar)
                                } else {
                                    LogManager.d("RepoListScreen", "从星标切换到仓库，login=${state.targetUserLogin}")
                                    vm.switchToUserRepos(state.targetUserLogin!!, state.targetUserAvatar)
                                }
                            }
                        ) {
                            Icon(
                                if (state.viewMode == ViewMode.STARRED) Icons.Default.Folder else Icons.Default.Star,
                                null,
                                tint = Coral
                            )
                        }
                    } else {
                        // 查看自己时，显示原来的星标管理按钮
                        if (state.viewMode == ViewMode.REPOS) {
                            StarModeToggleButton(
                                active = starState.starModeActive,
                                onClick = { starVm.toggleStarMode() },
                            )
                        }
                        // 只有自己的列表或所属组织列表才显示添加按钮
                        if (!starState.starModeActive && state.viewMode == ViewMode.REPOS) {
                            if (listPermission.canManageRepos) {
                                IconButton(onClick = onCreateRepo) {
                                    Icon(Icons.Default.Add, null, tint = Coral)
                                }
                            }
                        }
                    }
                    if (state.loading || starState.reposLoading) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = Coral
                            )
                        }
                    } else {
                        IconButton(onClick = { 
                            if (starState.starModeActive) {
                                starVm.loadStarredRepos(force = true)
                            } else {
                                vm.loadRepos(forceRefresh = true)
                            }
                        }) {
                            Icon(Icons.Default.Refresh, null, tint = c.textSecondary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep),
            )
        },
        snackbarHost = {
            state.toast?.let {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Snackbar(modifier = Modifier.padding(top = 80.dp, start = 16.dp, end = 16.dp),
                        containerColor = c.bgCard, contentColor = c.textPrimary) {
                        Text(it)
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 搜索框（星标模式和普通模式各自独立）
            val shouldShowStarMode = starState.starModeActive && !isViewingOtherUser
            val searchValue = if (shouldShowStarMode) starState.starSearchQuery else state.searchQuery
            val onSearchChange: (String) -> Unit = if (shouldShowStarMode) starVm::setStarSearch else vm::setSearch
            OutlinedTextField(
                value = searchValue,
                onValueChange = onSearchChange,
                placeholder = { Text(if (shouldShowStarMode) "搜索星标仓库…" else "搜索仓库…", color = c.textTertiary, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = c.textTertiary, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchValue.isNotEmpty())
                        IconButton(onClick = { onSearchChange("") }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
                        }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.bgCard, unfocusedContainerColor = c.bgCard,
                    focusedBorderColor = Coral, unfocusedBorderColor = c.border,
                    focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
                ),
            )
            // 过滤 / 星标模式 Header
            if (shouldShowStarMode) {
                StarFilterToolbar(
                    state = starState,
                    c = c,
                    vm = starVm,
                )
                UserListsHeader(
                    lists = starState.userLists,
                    loading = starState.listsLoading,
                    expanded = starState.listsExpanded,
                    selectedListId = starState.selectedListId,
                    onToggleExpand = starVm::toggleListsExpanded,
                    onSelectList = { id ->
                        starVm.selectList(id)
                        if (starState.listsExpanded) starVm.toggleListsExpanded()
                    },
                    onCreate = if (starState.isOwnList) { { showCreateListDialog = true } } else null,
                    onEdit   = if (starState.isOwnList) { { editingList = it } } else null,
                    onDelete = if (starState.isOwnList) starVm::deleteList else { _ -> },
                    c = c,
                )
            } else {
                RepoFilterToolbar(
                    state = state,
                    c = c,
                    vm = vm,
                )
            }

            if (shouldShowStarMode) {
                // ── 星标模式：显示星标仓库 ────────────────────────────────────
                val displayedRepos by starVm.filteredStarredRepos.collectAsState()
                PullToRefreshBox(
                    isRefreshing = false,
                    onRefresh = { starVm.loadStarredRepos(force = true) },
                ) {
                    when {
                        starState.reposLoading && starState.starredRepos.isEmpty() -> LoadingBox()
                        displayedRepos.isEmpty() && starState.starSearchQuery.isNotBlank() -> EmptyBox("无匹配的星标仓库")
                        starState.starredRepos.isEmpty() -> EmptyBox("该列表暂无仓库")
                        else -> {
                            val listState = rememberLazyListState()
                            val scope = rememberCoroutineScope()
                            // 滚到底部时自动加载更多（只在无搜索词时触发分页）
                            val lastIndex = displayedRepos.lastIndex
                            LaunchedEffect(listState.firstVisibleItemIndex) {
                                if (starState.hasNextPage && !starState.reposLoading &&
                                    starState.starSearchQuery.isBlank() &&
                                    listState.firstVisibleItemIndex >= lastIndex - 5) {
                                    starVm.loadStarredRepos(loadMore = true)
                                }
                            }
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(displayedRepos, key = { it.nodeId }) { starred ->
                                    SwipeableStarredRepoCard(
                                        repo = starred,
                                        onClick = {
                                            val parts = starred.nameWithOwner.split("/")
                                            onRepoClick(parts[0], parts[1])
                                        },
                                        onRemoveStar = if (starState.isOwnList) { { starVm.removeStar(starred) } } else null,
                                        onClassify = if (starState.isOwnList) { { classifyRepo = starred } } else null,
                                        c = c,
                                    )
                                }
                                if (starState.hasNextPage) {
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
            } else {
                // ── 普通模式：显示仓库或星标（根据 viewMode） ────────────────────────────────────
                val isStarredMode = state.viewMode == ViewMode.STARRED
                PullToRefreshBox(
                    isRefreshing = false,
                    onRefresh = { vm.loadRepos(forceRefresh = true) },
                ) {
                    when {
                        state.loading && repos.isEmpty() -> LoadingBox()
                        state.error != null && repos.isEmpty() -> ErrorBox(state.error!!) { vm.loadRepos(true) }
                        repos.isEmpty() -> EmptyBox(if (isViewingOtherUser) "暂无仓库" else "暂无仓库，点击右上角 + 创建")
                        else -> {
                            val listState = rememberLazyListState()
                            val isAtBottom = remember {
                                derivedStateOf {
                                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                                    last != null && last.index >= listState.layoutInfo.totalItemsCount - 3
                                }
                            }
                            LaunchedEffect(isAtBottom.value) {
                                if (isAtBottom.value && state.hasNextPage && !state.loadingMore) {
                                    vm.loadMoreRepos()
                                }
                            }
                            
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(repos, key = { it.id }) { repo ->
                                    SwipeableRepoCard(
                                        repo = repo,
                                        onClick = { onRepoClick(repo.owner.login, repo.name) },
                                        onDelete = { vm.deleteRepo(repo.owner.login, repo.name) },
                                        onRename = { newName -> vm.renameRepo(repo.owner.login, repo.name, newName) },
                                        onEdit = { desc, site, topics -> vm.editRepo(repo.owner.login, repo.name, desc, site, topics) },
                                        onClone = { url -> onCloneRepo(url) },
                                        c = c,
                                        canManage = listPermission.canManageRepos,
                                        onForkedRepoClick = { owner, name -> onRepoClick(owner, name) },
                                        onArchive = if (listPermission.canManageRepos) { { vm.archiveRepo(repo.owner.login, repo.name) } } else null,
                                        onUnarchive = if (listPermission.canManageRepos) { { vm.unarchiveRepo(repo.owner.login, repo.name) } } else null,
                                    )
                                }
                                if (state.loadingMore) {
                                    item {
                                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                            androidx.compose.material3.CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = com.gitmob.android.ui.theme.Coral
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 星标模式弹窗 ───────────────────────────────────────────────────────────
    starState.toast?.let { msg ->
        LaunchedEffect(msg) { kotlinx.coroutines.delay(2500); starVm.clearToast() }
    }

    // 创建列表弹窗
    if (showCreateListDialog || createListFromClassify) {
        UserListDialog(
            title = "新建列表",
            c = c,
            onConfirm = { name, desc, priv ->
                starVm.createList(name, desc, priv)
                showCreateListDialog = false
                createListFromClassify = false
            },
            onDismiss = { showCreateListDialog = false; createListFromClassify = false },
        )
    }

    // 编辑列表弹窗
    editingList?.let { list ->
        UserListDialog(
            title = "编辑列表",
            initialName = list.name,
            initialDescription = list.description,
            initialIsPrivate = list.isPrivate,
            c = c,
            onConfirm = { name, desc, priv ->
                starVm.updateList(list, name, desc, priv)
                editingList = null
            },
            onDismiss = { editingList = null },
        )
    }

    // 仓库分类管理 Sheet
    classifyRepo?.let { repo ->
        RepoListClassifySheet(
            repo = repo,
            userLists = starState.userLists,
            c = c,
            onUpdate = { starVm.updateRepoLists(repo, it) },
            onCreateList = { createListFromClassify = true },
            onDismiss = { classifyRepo = null },
        )
    }
}

// ── 星标仓库卡片（左滑确认取消星标，书签图标打开分类Sheet）──────────────────
@Composable
private fun SwipeableStarredRepoCard(
    repo: StarredRepo,
    onClick: () -> Unit,
    onRemoveStar: (() -> Unit)?,
    onClassify: (() -> Unit)?,
    c: GmColors,
) {
    var showRemoveConfirm by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.45f },
    )
    val scope = rememberCoroutineScope()

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && onRemoveStar != null) {
                showRemoveConfirm = true
            }
            scope.launch { dismissState.reset() }
        },
        backgroundContent = {
            // 无论是否有权限都显示背景（只是颜色不同），对齐远程仓库卡片体验
            val color by animateColorAsState(
                when {
                    onRemoveStar != null && dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
                        -> Yellow.copy(alpha = 0.85f)
                    else -> c.border
                },
                label = "swipe_star",
            )
            Box(
                Modifier.fillMaxSize().background(color, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (onRemoveStar != null && dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(end = 20.dp),
                    ) {
                        Icon(Icons.Default.StarBorder, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Text("取消", fontSize = 11.sp, color = Color.White, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        },
    ) {
        StarredRepoCard(repo = repo, onClick = onClick, onClassify = onClassify, c = c)
    }

    // 取消星标确认弹窗
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            containerColor = c.bgCard,
            icon = { Icon(Icons.Default.StarBorder, null, tint = Yellow, modifier = Modifier.size(26.dp)) },
            title = { Text("取消星标？", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "确定要取消对「${repo.name}」的星标吗？",
                    color = c.textSecondary, fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = { onRemoveStar?.invoke(); showRemoveConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Yellow),
                ) { Text("取消星标", color = Color.Black) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text("保留", color = c.textSecondary)
                }
            },
        )
    }
}

// ── 星标仓库独立卡片（不复用RepoCardContent，三点按钮打开分类Sheet）──────────
@Composable
private fun StarredRepoCard(
    repo: StarredRepo,
    onClick: () -> Unit,
    onClassify: (() -> Unit)?,
    c: GmColors,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = repo.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = c.textPrimary,
                        maxLines = 1,
                    )
                    if (repo.archived) {
                        GmBadge("已归档", CoralDim, Coral)
                    }
                }
                if (!repo.description.isNullOrBlank()) {
                    Text(
                        text = repo.description,
                        fontSize = 12.sp,
                        color = c.textSecondary,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                if (!repo.homepage.isNullOrBlank()) {
                    val context = LocalContext.current
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable {
                                val url = if (repo.homepage.startsWith("http")) repo.homepage else "https://${repo.homepage}"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(intent)
                            }
                    ) {
                        Icon(Icons.Default.Link, null, tint = BlueColor, modifier = Modifier.size(12.dp))
                        Text(repo.homepage, fontSize = 11.sp, color = BlueColor, maxLines = 1)
                    }
                }
            }
            if (repo.isPrivate) GmBadge("私有", RedDim, RedColor)
            Spacer(Modifier.width(4.dp))
            // 书签按钮：仅自己的星标列表才显示
            if (onClassify != null) {
                IconButton(onClick = onClassify, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.BookmarkAdd,
                        contentDescription = "分类管理",
                        tint = if (repo.listIds.isNotEmpty()) Yellow else c.textTertiary,
                        modifier = Modifier.size(18.dp),
                )
            }
            } // end if onClassify != null
        }
        // 底部信息行
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 语言
            if (!repo.language.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).background(Yellow, androidx.compose.foundation.shape.CircleShape))
                    Text(repo.language, fontSize = 11.sp, color = c.textTertiary)
                }
            }
            // topics
            if (repo.topics.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repo.topics.take(3).forEach { topic ->
                        Text(
                            text = topic,
                            fontSize = 9.sp,
                            color = c.textTertiary,
                            modifier = Modifier
                                .background(BlueDim, RoundedCornerShape(6.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            maxLines = 1
                        )
                    }
                    if (repo.topics.size > 3) {
                        Text(
                            text = "+${repo.topics.size - 3}",
                            fontSize = 9.sp,
                            color = c.textTertiary
                        )
                    }
                }
            }
            // 星标数
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(Icons.Default.Star, null, tint = Yellow, modifier = Modifier.size(12.dp))
                Text(repo.stars.toString(), fontSize = 11.sp, color = c.textTertiary)
            }
            // forks数
            if (repo.forks > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Default.Share, null, tint = c.textTertiary, modifier = Modifier.size(12.dp))
                    Text(repo.forks.toString(), fontSize = 11.sp, color = c.textTertiary)
                }
            }
            // 所属列表数量提示
            if (repo.listIds.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Default.Bookmark, null, tint = Yellow.copy(alpha = 0.7f), modifier = Modifier.size(11.dp))
                    Text("${repo.listIds.size}个列表", fontSize = 10.sp, color = c.textTertiary)
                }
            }
            Spacer(Modifier.weight(1f))
            // 打开的 Issues 数（> 0 才显示，与远程仓库卡片一致）
            if (repo.openIssues > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Green, modifier = Modifier.size(12.dp))
                    Text("${repo.openIssues}", fontSize = 11.sp, color = Green)
                }
            }
            // 默认分支标签（与远程仓库卡片一致）
            if (repo.defaultBranch.isNotBlank()) {
                Text(
                    text = repo.defaultBranch,
                    fontSize = 10.5.sp, color = BlueColor,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(BlueDim, RoundedCornerShape(20.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun OrgMenuItem(login: String, avatarUrl: String?, selected: Boolean, onClick: () -> Unit, c: GmColors) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(model = thumbUrl(avatarUrl), contentDescription = null,
                    modifier = Modifier.size(22.dp).clip(CircleShape).background(c.bgItem))
                Text(login, fontSize = 14.sp, color = if (selected) Coral else c.textPrimary)
                if (selected) Spacer(Modifier.weight(1f))
                if (selected) Icon(Icons.Default.Check, null, tint = Coral, modifier = Modifier.size(14.dp))
            }
        },
        onClick = onClick,
        colors = MenuDefaults.itemColors(textColor = c.textPrimary),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableRepoCard(
    repo: GHRepo,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onEdit: (String, String, List<String>) -> Unit,
    onClone: (String) -> Unit,
    c: GmColors,
    canManage: Boolean = true,
    onForkedRepoClick: ((String, String) -> Unit)? = null,
    onArchive: (() -> Unit)? = null,
    onUnarchive: (() -> Unit)? = null,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && canManage) {
                showDeleteDialog = true
            }
            scope.launch { dismissState.reset() }
        },
        backgroundContent = {
            // 无管理权限时不显示删除背景
            if (!canManage) return@SwipeToDismissBox
            val color by animateColorAsState(
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) RedColor else c.border,
                label = "swipe_bg",
            )
            Box(
                modifier = Modifier.fillMaxSize().background(color, RoundedCornerShape(14.dp)),
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
        RepoCardContent(
            repo = repo, onClick = onClick,
            onRename = if (canManage) { { showRenameDialog = true } } else null,
            onEdit   = if (canManage) { { showEditDialog = true } } else null,
            onArchive = if (canManage) onArchive else null,
            onUnarchive = if (canManage) onUnarchive else null,
            onClone  = { onClone(repo.cloneUrl) },
            c = c,
            onForkedRepoClick = onForkedRepoClick,
        )
    }

    if (showDeleteDialog) {
        DeleteRepoDialog(
            repoName = repo.name,
            owner    = repo.owner.login,
            onConfirm = { onDelete(); showDeleteDialog = false },
            onDismiss = { showDeleteDialog = false },
            c = c,
        )
    }
    if (showRenameDialog) {
        RenameRepoDialog(
            currentName = repo.name,
            owner = repo.owner.login,
            onConfirm = { newName -> onRename(newName); showRenameDialog = false },
            onDismiss = { showRenameDialog = false },
            c = c,
        )
    }
    if (showEditDialog) {
        EditRepoDialog(
            repo = repo,
            onConfirm = { desc, site, topics -> onEdit(desc, site, topics); showEditDialog = false },
            onDismiss = { showEditDialog = false },
            c = c,
        )
    }
}

@Composable
private fun RepoCardContent(
    repo: GHRepo,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onArchive: (() -> Unit)?,
    onUnarchive: (() -> Unit)?,
    onClone: () -> Unit,
    c: GmColors,
    extraActions: @Composable (() -> Unit)? = null,
    onForkedRepoClick: ((String, String) -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showArchiveDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth().background(c.bgCard, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(repo.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.textPrimary)
                }
                if (repo.fork && repo.parent != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp).clickable {
                            onForkedRepoClick?.invoke(repo.parent.owner.login, repo.parent.name)
                        }
                    ) {
                        Icon(Icons.Default.Share, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                        Text(
                            text = "复刻自: ${repo.parent.fullName}",
                            fontSize = 11.sp,
                            color = BlueColor,
                        )
                    }
                }
                if (!repo.description.isNullOrBlank()) {
                    Text(repo.description, fontSize = 12.sp, color = c.textSecondary, maxLines = 2,
                        modifier = Modifier.padding(top = 3.dp))
                }
                if (!repo.homepage.isNullOrBlank()) {
                    val context = LocalContext.current
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable {
                                // 打开website链接
                                val url = if (repo.homepage.startsWith("http")) repo.homepage else "https://${repo.homepage}"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(intent)
                            }) {
                        Icon(Icons.Default.Link, null, tint = BlueColor, modifier = Modifier.size(12.dp))
                        Text(repo.homepage, fontSize = 11.sp, color = BlueColor, maxLines = 1)
                    }
                }
            }
            // 星标模式下注入自定义按钮（如分类管理三点菜单）
            extraActions?.invoke()
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.MoreVert, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(c.bgCard)) {
                    val isArchived = repo.archived == true
                    if (onRename != null) {
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
                            onClick = { onRename(); showMenu = false },
                        )
                    }
                    if (onEdit != null) {
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
                            onClick = { onEdit(); showMenu = false },
                        )
                    }
                    if (repo.archived == true && onUnarchive != null) {
                        DropdownMenuItem(
                            text = { Text("取消归档", fontSize = 14.sp, color = c.textPrimary) },
                            leadingIcon = { Icon(Icons.Default.Unarchive, null, tint = Coral, modifier = Modifier.size(16.dp)) },
                            onClick = { showMenu = false; showArchiveDialog = true },
                        )
                    } else if (repo.archived != true && onArchive != null) {
                        DropdownMenuItem(
                            text = { Text("归档", fontSize = 14.sp, color = c.textPrimary) },
                            leadingIcon = { Icon(Icons.Default.Archive, null, tint = BlueColor, modifier = Modifier.size(16.dp)) },
                            onClick = { showMenu = false; showArchiveDialog = true },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("克隆到本地", fontSize = 14.sp, color = c.textPrimary) },
                        leadingIcon = { Icon(Icons.Default.Download, null, tint = BlueColor, modifier = Modifier.size(16.dp)) },
                        onClick = { onClone(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("复制 SSH 地址", fontSize = 14.sp, color = c.textPrimary) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = c.textSecondary, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("ssh_url", repo.sshUrl))
                            showMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("复制 HTTPS 地址", fontSize = 14.sp, color = c.textPrimary) },
                        leadingIcon = { Icon(Icons.Default.Link, null, tint = c.textSecondary, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("clone_url", repo.cloneUrl))
                            showMenu = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!repo.language.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(8.dp).background(Coral, CircleShape))
                        Text(repo.language, fontSize = 11.sp, color = c.textTertiary)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Default.Star, null, tint = Yellow, modifier = Modifier.size(12.dp))
                    Text("${repo.stars}", fontSize = 11.sp, color = c.textTertiary)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Default.Share, null, tint = c.textTertiary, modifier = Modifier.size(12.dp))
                    Text("${repo.forks}", fontSize = 11.sp, color = c.textTertiary)
                }
                if (repo.openIssues > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Default.ErrorOutline, null, tint = Green, modifier = Modifier.size(12.dp))
                        Text("${repo.openIssues}", fontSize = 11.sp, color = Green)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (repo.private) GmBadge("私有", RedDim, RedColor)
                if (repo.archived == true) GmBadge("已归档", CoralDim, Coral)
                Text(
                    text = repo.defaultBranch,
                    fontSize = 10.5.sp, color = BlueColor,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.background(BlueDim, RoundedCornerShape(20.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            
            // topics
            if (repo.topics.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    repo.topics.forEach { topic ->
                        Text(
                            text = topic,
                            fontSize = 9.sp,
                            color = c.textTertiary,
                            modifier = Modifier
                                .background(BlueDim, RoundedCornerShape(6.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }

    if (showArchiveDialog) {
        ArchiveRepoDialog(
            repoName = repo.name,
            owner = repo.owner.login,
            isArchived = repo.archived == true,
            onConfirm = {
                if (repo.archived == true) {
                    onUnarchive?.invoke()
                } else {
                    onArchive?.invoke()
                }
                showArchiveDialog = false
            },
            onDismiss = { showArchiveDialog = false },
            c = c,
        )
    }
}

// ─── Dialogs ───────────────────────────────────────────────────

@Composable
private fun ArchiveRepoDialog(
    repoName: String,
    owner: String,
    isArchived: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    c: GmColors,
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

@Composable
private fun DeleteRepoDialog(
    repoName: String,
    owner: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    c: GmColors,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var input by remember { mutableStateOf("") }
    val fullName = "$owner/$repoName"

    // 复制到剪贴板的工具函数
    fun copyToClipboard(text: String, label: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        icon = {
            Icon(Icons.Default.DeleteForever, null, tint = RedColor, modifier = Modifier.size(28.dp))
        },
        title = { Text("删除仓库", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 仓库标识，点击全名复制「仓库名」并自动填入下面的输入框
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.bgItem, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.FolderDelete,
                        null,
                        tint = RedColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        fullName,
                        fontSize = 13.sp,
                        color = c.textPrimary,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                // 点击整行 owner/repo 文本，复制「仓库名」并自动填充输入框
                                copyToClipboard(repoName, "repoName")
                                input = repoName
                            },
                    )
                }

                Text(
                    "此操作不可撤销。请在下方输入仓库名确认删除：",
                    fontSize = 13.sp,
                    color = c.textSecondary,
                    lineHeight = 20.sp,
                )

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(repoName, color = c.textTertiary) },
                    trailingIcon = {
                        if (input == repoName)
                            Icon(Icons.Default.CheckCircle, null,
                                tint = Green, modifier = Modifier.size(18.dp))
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = if (input == repoName) Green else RedColor,
                        unfocusedBorderColor = c.border,
                        focusedTextColor     = c.textPrimary,
                        unfocusedTextColor   = c.textPrimary,
                        focusedContainerColor   = c.bgItem,
                        unfocusedContainerColor = c.bgItem,
                    ),
                )

                if (input.isNotEmpty() && input != repoName) {
                    Text(
                        "输入不匹配，请检查大小写",
                        fontSize = 11.sp, color = RedColor,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = input == repoName,
                colors  = ButtonDefaults.buttonColors(
                    containerColor         = RedColor,
                    disabledContainerColor = c.border,
                ),
            ) {
                Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("确认删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    )
}

@Composable
private fun RenameRepoDialog(
    currentName: String, owner: String,
    onConfirm: (String) -> Unit, onDismiss: () -> Unit, c: GmColors,
) {
    var name by remember { mutableStateOf(currentName) }
    val isValid = name.isNotBlank() && name != currentName && name.matches(Regex("[a-zA-Z0-9._-]+"))

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("重命名仓库", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$owner / $currentName → $owner / $name",
                    fontSize = 12.sp, color = c.textTertiary, fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("新名称") },
                    supportingText = {
                        if (name.isNotBlank() && !name.matches(Regex("[a-zA-Z0-9._-]+")))
                            Text("只允许字母、数字、点、连字符和下划线", color = RedColor, fontSize = 11.sp)
                    },
                    isError = name.isNotBlank() && !name.matches(Regex("[a-zA-Z0-9._-]+")),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Coral, unfocusedBorderColor = c.border,
                        focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
                        focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem,
                    ),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = isValid,
                colors = ButtonDefaults.buttonColors(containerColor = Coral, disabledContainerColor = c.border)) {
                Text("重命名")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) } },
    )
}

@Composable
private fun EditRepoDialog(
    repo: GHRepo,
    onConfirm: (String, String, List<String>) -> Unit,
    onDismiss: () -> Unit,
    c: GmColors,
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
                GmTextField(value = desc, onValueChange = { desc = it },
                    label = "About", maxLines = 3, c = c)
                GmTextField(value = website, onValueChange = { website = it },
                    label = "Website", c = c)
                GmTextField(value = topicsText, onValueChange = { topicsText = it },
                    label = "Topics（空格分隔）", c = c)
                Text("Topics 示例：kotlin android github",
                    fontSize = 11.sp, color = c.textTertiary)
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) } },
    )
}

@Composable
private fun GmTextField(
    value: String, onValueChange: (String) -> Unit,
    label: String, c: GmColors, maxLines: Int = 1,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = maxLines == 1, maxLines = maxLines,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Coral, unfocusedBorderColor = c.border,
            focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
            focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem,
            focusedLabelColor = Coral, unfocusedLabelColor = c.textTertiary,
        ),
    )
}

/** 为 GitHub avatars URL 追加 s=40 缩略图参数 */
private fun thumbUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    return if (url.contains("?")) "$url&s=40" else "$url?s=40"
}
