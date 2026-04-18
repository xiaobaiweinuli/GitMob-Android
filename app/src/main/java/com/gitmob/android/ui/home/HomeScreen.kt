@file:OptIn(ExperimentalMaterial3Api::class)
package com.gitmob.android.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import coil3.compose.AsyncImage
import com.gitmob.android.api.GHOrg
import com.gitmob.android.api.GHRepo
import com.gitmob.android.api.GHUser
import com.gitmob.android.data.FavGroup
import com.gitmob.android.data.FavRepo
import com.gitmob.android.data.FavoritesManager
import com.gitmob.android.ui.common.GmDivider
import com.gitmob.android.ui.common.GmBadge
import com.gitmob.android.ui.common.LoadingBox
import com.gitmob.android.ui.theme.*
import com.gitmob.android.ui.update.UpdateDialog
import com.gitmob.android.GitMobApp
import com.gitmob.android.util.GmDownloadManager
import com.gitmob.android.util.LogManager
import com.gitmob.android.util.UpdateManager

@Composable
fun HomeScreen(
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRepoClick: (String, String) -> Unit,
    onReposClick: () -> Unit,
    onStarredClick: () -> Unit,
    onOrgClick: (GHOrg) -> Unit,
    onUserClick: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    targetUserLogin: String? = null,
    onUserReposClick: (String) -> Unit = {},
    onUserStarredClick: (String) -> Unit = {},
    onUserOrgsClick: (String) -> Unit = {},
    vm: HomeViewModel = viewModel(),
    favVm: FavoritesManager = viewModel(),
) {
    val c = LocalGmColors.current
    val state by vm.state.collectAsState()
    val favState by favVm.state.collectAsState()
    val ctx = LocalContext.current

    LaunchedEffect(targetUserLogin) {
        if (targetUserLogin != null) {
            vm.loadUser(targetUserLogin)
        } else {
            vm.load()
        }
    }

    // 初始化收藏夹（按登录用户隔离）
    state.user?.login?.let { login -> LaunchedEffect(login) { favVm.init(login) } }

    var showAddGroupDialog by remember { mutableStateOf(false) }
    val showFavGroupDetail = state.showFavGroupDetail
    val showUngroupedDetail = state.showUngroupedDetail
    var showUpdateDialog by remember { mutableStateOf(false) }
    var latestRelease by remember { mutableStateOf<UpdateManager.Release?>(null) }
    val scope = rememberCoroutineScope()

    val app = ctx.applicationContext as GitMobApp
    val pendingUpdate by app.pendingUpdate.collectAsState(initial = null)

    fun downloadAndInstall(release: UpdateManager.Release) {
        val apkUrl = release.apkUrl ?: return
        LogManager.i("Home", "开始下载更新: ${release.tagName}")
        GmDownloadManager.download(
            ctx = ctx,
            url = apkUrl,
            filename = "gitmob-${release.tagName}.apk"
        )
    }

    LaunchedEffect(pendingUpdate) {
        if (targetUserLogin == null && pendingUpdate != null && UpdateManager.shouldShowUpdateDialog(ctx, pendingUpdate!!)) {
            LogManager.i("Home", "显示更新弹窗: ${pendingUpdate!!.tagName}")
            latestRelease = pendingUpdate
            showUpdateDialog = true
        }
    }

    if (showUpdateDialog && latestRelease != null) {
        UpdateDialog(
            release = latestRelease!!,
            onDismiss = { 
                showUpdateDialog = false
                app.markUpdateShown()
            },
            onIgnore = {
                UpdateManager.ignoreVersion(ctx, latestRelease!!.tagName)
                showUpdateDialog = false
                app.markUpdateShown()
            },
            onUpdate = {
                showUpdateDialog = false
                app.markUpdateShown()
                downloadAndInstall(latestRelease!!)
            }
        )
    }

    // 分组详情页（BackHandler 拦截系统返回键，避免关闭 APP）
    showFavGroupDetail?.let { group ->
        BackHandler { vm.hideFavGroupDetail() }
        FavGroupDetailScreen(
            group = group, favVm = favVm, c = c,
            onRepoClick = onRepoClick,
            onBack = { vm.hideFavGroupDetail() },
        )
        return
    }
    if (showUngroupedDetail) {
        BackHandler { vm.hideUngroupedDetail() }
        FavUngroupedDetailScreen(
            favVm = favVm, c = c, onRepoClick = onRepoClick,
            onBack = { vm.hideUngroupedDetail() },
        )
        return
    }

    Scaffold(
        containerColor = c.bgDeep,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.textSecondary)
                        }
                    }
                },
                actions = {
                    if (state.isCurrentUser) {
                        IconButton(onClick = onSearchClick) {
                            Icon(Icons.Default.Search, "搜索", tint = c.textSecondary)
                        }
                        IconButton(onClick = {
                            val url = state.user?.htmlUrl ?: "https://github.com/${state.user?.login ?: ""}"
                            ctx.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) },
                                "分享 GitHub 主页",
                            ))
                        }) { Icon(Icons.Default.Share, "分享", tint = c.textSecondary) }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, "设置", tint = c.textSecondary)
                        }
                    } else {
                        IconButton(onClick = onSearchClick) {
                            Icon(Icons.Default.Search, "搜索", tint = c.textSecondary)
                        }
                        IconButton(onClick = {
                            val url = state.user?.htmlUrl ?: "https://github.com/${state.user?.login ?: ""}"
                            ctx.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) },
                                "分享 GitHub 主页",
                            ))
                        }) { Icon(Icons.Default.Share, "分享", tint = c.textSecondary) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep),
            )
        },
    ) { paddingValues ->
        when {
            state.loading -> LoadingBox(Modifier.fillMaxSize().padding(paddingValues))
            state.error != null && state.user == null -> Box(
                Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("加载失败", color = c.textPrimary, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { 
                        if (targetUserLogin != null) {
                            vm.loadUser(targetUserLogin)
                        } else {
                            vm.load(forceRefresh = true)
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Coral)) {
                        Text("重试")
                    }
                }
            }
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { vm.refresh(targetUserLogin) },
                modifier = Modifier.fillMaxSize().padding(paddingValues)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // ── 用户资料 ──────────────────────────────────────────────────
                    item { state.user?.let { UserProfileSection(it, c, ctx, vm::showFollowers, vm::showFollowing) } }

                // ── 统计入口 ──────────────────────────────────────────────────
                item {
                    Spacer(Modifier.height(8.dp)); GmDivider(); Spacer(Modifier.height(4.dp))
                    StatsRow(
                        repoCount = state.repoCount, orgCount = state.orgCount,
                        starredCount = state.starredCount, c = c,
                        onReposClick = if (state.isCurrentUser) {
                            onReposClick
                        } else {
                            { targetUserLogin?.let(onUserReposClick) ?: Unit }
                        },
                        onOrgsClick = if (state.isCurrentUser) {
                            { vm.showOrgPicker() }
                        } else {
                            { targetUserLogin?.let(onUserOrgsClick) ?: Unit }
                        },
                        onStarredClick = if (state.isCurrentUser) {
                            onStarredClick
                        } else {
                            { targetUserLogin?.let(onUserStarredClick) ?: Unit }
                        },
                        isCurrentUser = state.isCurrentUser,
                    )
                    Spacer(Modifier.height(4.dp)); GmDivider()
                }

                // ── 置顶仓库 ──────────────────────────────────────────────────
                if (state.pinnedRepos.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        SectionLabel("已置顶", Icons.Default.PushPin, c)
                        Spacer(Modifier.height(10.dp))
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(state.pinnedRepos) { repo ->
                                PinnedRepoCard(repo, c) { onRepoClick(repo.owner.login, repo.name) }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        GmDivider()
                    }
                }

                // ── 收藏夹（仅当前用户可见）──────────────────────────────────
                if (state.isCurrentUser) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            SectionLabel("收藏夹", Icons.Default.Bookmark, c, inline = true)
                            IconButton(onClick = { showAddGroupDialog = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.AddCircleOutline, "添加分组", tint = Coral, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // 未分组收藏
                    if (favState.ungroupedRepos.isNotEmpty()) {
                        item {
                            FavGroupRow(
                                name = "未分组", description = null, c = c,
                                repoCount = favState.ungroupedRepos.size,
                                onDeleteGroupOnly = null, onDeleteAll = null,
                                onClick = { vm.showUngroupedDetail() },
                            )
                        }
                    }

                    // 各分组
                    items(favState.groups) { group ->
                        FavGroupRow(
                            name = group.name, description = group.description.ifBlank { null },
                            repoCount = group.repoIds.size, c = c,
                            onDeleteGroupOnly = { favVm.deleteGroup(group.id, "group_only") },
                            onDeleteAll = { favVm.deleteGroup(group.id, "all") },
                            onClick = { vm.showFavGroupDetail(group) },
                        )
                    }

                    if (favState.groups.isEmpty() && favState.ungroupedRepos.isEmpty()) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("暂无收藏，在仓库详情中点击设置→收藏", fontSize = 13.sp, color = c.textTertiary)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
            }
        }
    }

    // ── 关注者列表弹窗 ─────────────────────────────────────────────────────────
    if (state.showFollowers) {
        ModalBottomSheet(
            onDismissRequest = { vm.hideFollowers() },
            containerColor = c.bgCard,
        ) {
            Text(
                "关注者",
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                color = c.textPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            if (state.followersLoading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Coral)
                }
            } else if (state.followers.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("暂无关注者", color = c.textTertiary, fontSize = 14.sp)
                }
            } else {
                state.followers.forEach { user ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { vm.hideFollowers(); onUserClick(user.login) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AsyncImage(
                            model = user.avatarUrl?.let { if (it.contains("?")) "$it&s=60" else "$it?s=60" },
                            contentDescription = null,
                            modifier = Modifier.size(36.dp).clip(CircleShape).background(c.bgItem),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(user.name ?: user.login, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.textPrimary)
                            Text(user.login, fontSize = 12.sp, color = c.textTertiary)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
                    }
                    GmDivider()
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    // ── 关注列表弹窗 ──────────────────────────────────────────────────────────
    if (state.showFollowing) {
        ModalBottomSheet(
            onDismissRequest = { vm.hideFollowing() },
            containerColor = c.bgCard,
        ) {
            Text(
                "关注",
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                color = c.textPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            if (state.followingLoading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Coral)
                }
            } else if (state.following.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("暂无关注", color = c.textTertiary, fontSize = 14.sp)
                }
            } else {
                state.following.forEach { user ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { vm.hideFollowing(); onUserClick(user.login) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AsyncImage(
                            model = user.avatarUrl?.let { if (it.contains("?")) "$it&s=60" else "$it?s=60" },
                            contentDescription = null,
                            modifier = Modifier.size(36.dp).clip(CircleShape).background(c.bgItem),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(user.name ?: user.login, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.textPrimary)
                            Text(user.login, fontSize = 12.sp, color = c.textTertiary)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
                    }
                    GmDivider()
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    // ── 组织选择弹窗 ──────────────────────────────────────────────────────────
    if (state.showOrgPicker) {
        ModalBottomSheet(
            onDismissRequest = { vm.hideOrgPicker() },
            containerColor = c.bgCard,
        ) {
            Text(
                "选择组织",
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                color = c.textPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            if (state.orgs.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("暂无组织", color = c.textTertiary, fontSize = 14.sp)
                }
            } else {
                state.orgs.forEach { org ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { vm.hideOrgPicker(); onOrgClick(org) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AsyncImage(
                            model = org.avatarUrl?.let { if (it.contains("?")) "$it&s=60" else "$it?s=60" },
                            contentDescription = null,
                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(c.bgItem),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(org.login, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.textPrimary)
                            if (!org.description.isNullOrBlank()) {
                                Text(org.description, fontSize = 12.sp, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
                    }
                    GmDivider()
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    // ── 添加分组对话框 ────────────────────────────────────────────────────────
    if (showAddGroupDialog) {
        AddFavGroupDialog(c = c, onConfirm = { name, desc -> favVm.addGroup(name, desc); showAddGroupDialog = false }, onDismiss = { showAddGroupDialog = false })
    }
}

// ─── 收藏夹分组行（SwipeToDismissBox，与远程仓库卡片一致）──────────────────
@Composable
private fun FavGroupRow(
    name: String, description: String?, repoCount: Int, c: GmColors,
    /** null = 无法删除（未分组行） */
    onDeleteGroupOnly: (() -> Unit)?,
    onDeleteAll: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }
    val canSwipe = onDeleteGroupOnly != null

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && canSwipe) {
                showDeleteDialog = true
            }
            scope.launch { dismissState.reset() }
        },
        backgroundContent = {
            if (!canSwipe) return@SwipeToDismissBox
            val color by animateColorAsState(
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                    Color(0xFFF85149) else c.border,
                label = "fav_swipe",
            )
            Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.CenterEnd) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(end = 20.dp)) {
                    Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Text("删除", fontSize = 11.sp, color = Color.White, modifier = Modifier.padding(top = 3.dp))
                }
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(c.bgDeep)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.FolderSpecial, null, tint = Coral, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.textPrimary)
                if (description != null) {
                    Text(description, fontSize = 12.sp, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("$repoCount 个", fontSize = 12.sp, color = c.textTertiary)
            Icon(Icons.Default.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
        }
    }
    GmDivider()

    if (showDeleteDialog && canSwipe) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = c.bgCard,
            title = { Text("删除分组「$name」", color = c.textPrimary) },
            text = { Text("请选择删除方式", color = c.textSecondary) },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onDeleteGroupOnly(); showDeleteDialog = false }) {
                        Text("删除分组，内容移至未分组", color = Coral)
                    }
                    TextButton(onClick = { onDeleteAll?.invoke(); showDeleteDialog = false }) {
                        Text("同时删除分组内的收藏", color = Color(0xFFF85149))
                    }
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("取消", color = c.textSecondary)
                    }
                }
            },
            dismissButton = {},
        )
    }
}

// ─── 收藏夹分组详情页 ─────────────────────────────────────────────────────────
@Composable
fun FavGroupDetailScreen(
    group: FavGroup, favVm: FavoritesManager, c: GmColors,
    onRepoClick: (String, String) -> Unit, onBack: () -> Unit,
) {
    val state by favVm.state.collectAsState()
    val repos = remember(state, group.id) { favVm.getReposInGroup(group.id) }

    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().background(c.bgDeep)) {
        TopAppBar(
            title = { Text(group.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.textPrimary) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.textSecondary) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep),
        )
        if (repos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无收藏仓库", color = c.textTertiary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(repos, key = { it.fullName }) { repo ->
                    FavRepoCard(
                        repo = repo, c = c,
                        onRepoClick = { o, n ->
                            onRepoClick(o, n)
                        },
                        onSwipe = { favVm.removeFavorite(repo.fullName) },
                    )
                }
            }
        }
    }
}

@Composable
fun FavUngroupedDetailScreen(
    favVm: FavoritesManager, c: GmColors,
    onRepoClick: (String, String) -> Unit, onBack: () -> Unit,
) {
    val state by favVm.state.collectAsState()

    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().background(c.bgDeep)) {
        TopAppBar(
            title = { Text("未分组", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.textPrimary) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.textSecondary) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep),
        )
        if (state.ungroupedRepos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无收藏仓库", color = c.textTertiary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.ungroupedRepos, key = { it.fullName }) { repo ->
                    FavRepoCard(
                        repo = repo, c = c,
                        onRepoClick = onRepoClick,
                        onSwipe = { favVm.removeFavorite(repo.fullName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavRepoCard(
    repo: FavRepo, c: GmColors,
    onRepoClick: (String, String) -> Unit,
    onSwipe: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var showRemoveDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) showRemoveDialog = true
            scope.launch { dismissState.reset() }
        },
        backgroundContent = {
            val color by animateColorAsState(
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                    Color(0xFFF85149) else c.border,
                label = "fav_repo_swipe",
            )
            Box(Modifier.fillMaxSize().background(color, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.CenterEnd) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(end = 20.dp)) {
                    Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Text("移除", fontSize = 11.sp, color = Color.White, modifier = Modifier.padding(top = 3.dp))
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(c.bgCard, RoundedCornerShape(12.dp))
                .border(0.5.dp, c.border, RoundedCornerShape(12.dp))
                .clickable { onRepoClick(repo.ownerLogin, repo.name) }
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, null,
                    tint = c.textTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(repo.fullName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = Coral, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!repo.description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(repo.description, fontSize = 12.sp, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!repo.website.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                val urlToOpen = remember(repo.website) {
                    val url = repo.website
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
                    Icon(Icons.Default.Link, null, tint = BlueColor, modifier = Modifier.size(12.dp))
                    Text(repo.website, fontSize = 11.sp, color = BlueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!repo.language.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(10.dp).background(Coral, CircleShape))
                            Text(repo.language, fontSize = 11.sp, color = c.textTertiary)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Default.StarBorder, null, tint = Yellow, modifier = Modifier.size(13.dp))
                        Text("${repo.stars}", fontSize = 11.sp, color = c.textTertiary)
                    }
                    if (repo.isPrivate) {
                        GmBadge("私有", RedDim, RedColor)
                    }
                    if (repo.archived) {
                        GmBadge("已归档", CoralDim, Coral)
                    }
                }
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
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false }, containerColor = c.bgCard,
            title = { Text("移除收藏", color = c.textPrimary) },
            text = { Text("确定将「${repo.fullName}」从收藏中移除？", color = c.textSecondary) },
            confirmButton = {
                Button(onClick = { onSwipe(); showRemoveDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF85149))) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { showRemoveDialog = false }) { Text("取消", color = c.textSecondary) } },
        )
    }
}

// ─── 添加分组对话框 ───────────────────────────────────────────────────────────
@Composable
fun AddFavGroupDialog(c: GmColors, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.bgCard,
        title = { Text("新建分组", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("分组名称", color = c.textTertiary) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Coral, unfocusedBorderColor = c.border, focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary, focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem, focusedLabelColor = Coral, unfocusedLabelColor = c.textTertiary))
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("描述（可选）", color = c.textTertiary) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Coral, unfocusedBorderColor = c.border, focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary, focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem, focusedLabelColor = Coral, unfocusedLabelColor = c.textTertiary))
            }
        },
        confirmButton = { Button(onClick = { onConfirm(name, desc) }, enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Coral)) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) } },
    )
}

// ─── 收藏选择对话框（从仓库详情调用）────────────────────────────────────────
@Composable
fun AddToFavoritesDialog(
    repo: GHRepo,
    favVm: FavoritesManager,
    c: GmColors,
    onDismiss: () -> Unit,
) {
    val state by favVm.state.collectAsState()
    val isAlreadyFavorited = favVm.isFavorited(repo.fullName)
    val currentGroupId = favVm.getRepoGroup(repo.fullName)
    var selectedGroupId by remember(currentGroupId) { mutableStateOf(currentGroupId) }
    var showNewGroup by remember { mutableStateOf(false) }

    if (showNewGroup) {
        AddFavGroupDialog(c = c, onConfirm = { name, desc ->
            val id = favVm.addGroup(name, desc); selectedGroupId = id; showNewGroup = false
        }, onDismiss = { showNewGroup = false })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.bgCard,
        title = {
            Text(if (isAlreadyFavorited) "修改收藏" else "添加收藏",
                color = c.textPrimary, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("选择分组（可不选直接添加至未分组）", fontSize = 12.sp, color = c.textTertiary)
                // 未分组选项
                Row(modifier = Modifier.fillMaxWidth()
                    .background(if (selectedGroupId == null) Coral.copy(alpha = 0.1f) else c.bgItem, RoundedCornerShape(8.dp))
                    .clickable { selectedGroupId = null }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedGroupId == null, onClick = { selectedGroupId = null },
                        colors = RadioButtonDefaults.colors(selectedColor = Coral))
                    Spacer(Modifier.width(8.dp))
                    Text("未分组", fontSize = 13.sp, color = c.textPrimary)
                }
                // 已有分组
                state.groups.forEach { group ->
                    Row(modifier = Modifier.fillMaxWidth()
                        .background(if (selectedGroupId == group.id) Coral.copy(alpha = 0.1f) else c.bgItem, RoundedCornerShape(8.dp))
                        .clickable { selectedGroupId = group.id }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedGroupId == group.id, onClick = { selectedGroupId = group.id },
                            colors = RadioButtonDefaults.colors(selectedColor = Coral))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(group.name, fontSize = 13.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
                            if (group.description.isNotBlank()) Text(group.description, fontSize = 11.sp, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                TextButton(onClick = { showNewGroup = true }) {
                    Icon(Icons.Default.AddCircleOutline, null, tint = Coral, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("新建分组", color = Coral, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 已收藏时提供"移出收藏"选项
                if (isAlreadyFavorited) {
                    TextButton(onClick = { favVm.removeFavorite(repo.fullName); onDismiss() }) {
                        Text("移出收藏", color = Color(0xFFF85149), fontSize = 13.sp)
                    }
                }
                Button(onClick = { favVm.addFavorite(repo, selectedGroupId); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral)) {
                    Text(if (isAlreadyFavorited) "确认修改" else "确认添加")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) } },
    )
}

// ─── 共用小组件 ───────────────────────────────────────────────────────────────
@Composable
private fun SectionLabel(title: String, icon: ImageVector, c: GmColors, inline: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = if (!inline) Modifier.padding(horizontal = 16.dp) else Modifier) {
        Icon(icon, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary)
    }
}

@Composable
private fun UserProfileSection(user: GHUser, c: GmColors, ctx: android.content.Context, onFollowersClick: () -> Unit, onFollowingClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = user.avatarUrl?.let { if (it.contains("?")) "$it&s=120" else "$it?s=120" },
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(CircleShape).background(c.bgItem).border(2.dp, c.border, CircleShape),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(user.name ?: user.login, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.textPrimary)
                Text(user.login, fontSize = 14.sp, color = c.textTertiary, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${user.followers} 关注者", fontSize = 12.sp, color = Coral, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { onFollowersClick() })
                    Text(" · ", fontSize = 12.sp, color = c.textTertiary)
                    Text("${user.following} 关注", fontSize = 12.sp, color = Coral, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { onFollowingClick() })
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (!user.bio.isNullOrBlank()) {
            Text("\"${user.bio}\"", fontSize = 14.sp, color = c.textSecondary, lineHeight = 20.sp)
            Spacer(Modifier.height(10.dp))
        }
        buildList {
            if (!user.company.isNullOrBlank())         add(Triple(Icons.Default.Business,      user.company,          null as String?))
            if (!user.location.isNullOrBlank())        add(Triple(Icons.Default.LocationOn,    user.location,         null))
            if (!user.blog.isNullOrBlank())            add(Triple(Icons.Default.Link,          user.blog,             if (!user.blog.startsWith("http")) "https://${user.blog}" else user.blog))
            if (!user.twitterUsername.isNullOrBlank()) add(Triple(Icons.Default.AlternateEmail, "@${user.twitterUsername}", "https://twitter.com/${user.twitterUsername}"))
        }.forEach { (icon, label, url) ->
            Row(
                modifier = Modifier.fillMaxWidth().then(if (url != null) Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } else Modifier).padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = c.textTertiary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 13.sp, color = if (url != null) Coral else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun StatsRow(
    repoCount: Int, orgCount: Int, starredCount: Int, c: GmColors,
    onReposClick: () -> Unit, onOrgsClick: (() -> Unit)?, onStarredClick: () -> Unit,
    isCurrentUser: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        StatItem(Icons.Default.Folder,   repoCount,    "仓库",   onReposClick,  c)
        Box(Modifier.width(0.5.dp).height(52.dp).background(c.border))
        StatItem(Icons.Default.Business, orgCount,     "组织",   onOrgsClick,   c)
        Box(Modifier.width(0.5.dp).height(52.dp).background(c.border))
        StatItem(Icons.Default.Star,     starredCount, "已星标", onStarredClick, c)
    }
}

@Composable
private fun RowScope.StatItem(icon: ImageVector, count: Int, label: String, onClick: (() -> Unit)?, c: GmColors) {
    Column(
        modifier = Modifier.weight(1f)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = if (onClick != null) Coral else c.textTertiary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(count.toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (onClick != null) c.textPrimary else c.textTertiary)
        Text(label, fontSize = 11.sp, color = c.textTertiary)
    }
}

@Composable
private fun PinnedRepoCard(repo: GHRepo, c: GmColors, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(220.dp).background(c.bgCard, RoundedCornerShape(12.dp))
            .border(0.5.dp, c.border, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (repo.private) Icons.Default.Lock else Icons.Default.Folder, null, tint = c.textTertiary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text(repo.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Coral, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(5.dp))
        Text(repo.description ?: "暂无描述", fontSize = 12.sp, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp, modifier = Modifier.height(34.dp))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (!repo.language.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Coral, CircleShape)); Spacer(Modifier.width(4.dp))
                Text(repo.language, fontSize = 11.sp, color = c.textTertiary, maxLines = 1)
            } else Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.StarBorder, null, tint = c.textTertiary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(3.dp)); Text("${repo.stars}", fontSize = 11.sp, color = c.textTertiary)
            }
        }
    }
}
