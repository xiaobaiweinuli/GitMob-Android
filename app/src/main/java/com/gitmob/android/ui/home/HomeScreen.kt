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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun HomeScreen(
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRepoClick: (String, String) -> Unit,
    onReposClick: () -> Unit,
    onStarredClick: () -> Unit,
    onOrgClick: (GHOrg, List<GHOrg>) -> Unit,
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
        LogManager.i("HomeScreen", "🎯 LaunchedEffect 触发! targetUserLogin=$targetUserLogin")
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

    // 收藏夹编辑模式（分组列表层）
    var favEditMode by remember { mutableStateOf(false) }
    // 记录进入编辑时的分组顺序，用于判断是否有改动
    var favEditOriginalOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var showFavEditExitDialog by remember { mutableStateOf(false) }
    // 提升到顶层，BackHandler 和 LazyColumn 共享同一份列表
    val groupList = remember(favState.groups) { favState.groups.toMutableStateList() }
    LaunchedEffect(favState.groups) {
        if (!favEditMode) {
            groupList.clear()
            groupList.addAll(favState.groups)
        }
    }

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

    // 主页分组编辑模式返回拦截
    if (favEditMode) {
        BackHandler {
            val currentOrder = groupList.map { it.id }
            if (currentOrder != favEditOriginalOrder) {
                showFavEditExitDialog = true
            } else {
                favEditMode = false
            }
        }
    }

    if (showFavEditExitDialog) {
        AlertDialog(
            onDismissRequest = { showFavEditExitDialog = false },
            containerColor = c.bgCard,
            title = { Text("放弃排序更改？", color = c.textPrimary) },
            text = { Text("当前的排序尚未保存，是否放弃更改？", color = c.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showFavEditExitDialog = false
                        favEditMode = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF85149)),
                ) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { showFavEditExitDialog = false }) {
                    Text("继续编辑", color = Coral)
                }
            },
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
                val lazyListState = rememberLazyListState()
                val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
                    // key 是分组 id，找到对应下标移动
                    val fromIdx = groupList.indexOfFirst { it.id == from.key }
                    val toIdx   = groupList.indexOfFirst { it.id == to.key }
                    if (fromIdx >= 0 && toIdx >= 0) {
                        groupList.add(toIdx, groupList.removeAt(fromIdx))
                    }
                }

                LazyColumn(modifier = Modifier.fillMaxSize(), state = lazyListState) {
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
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 编辑/完成 按钮（只在有分组时显示）
                                    if (favState.groups.isNotEmpty()) {
                                        if (favEditMode) {
                                            TextButton(
                                                onClick = {
                                                    // 写回排序结果
                                                    favVm.reorderGroups(groupList.map { it.id })
                                                    favEditMode = false
                                                },
                                                modifier = Modifier.height(32.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                            ) {
                                                Text("完成", fontSize = 13.sp, color = Coral, fontWeight = FontWeight.SemiBold)
                                            }
                                        } else {
                                            IconButton(
                                                onClick = {
                                                    favEditOriginalOrder = favState.groups.map { it.id }
                                                    favEditMode = true
                                                },
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(Icons.Default.Edit, "编辑排序", tint = c.textTertiary, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                    // 添加分组按钮（编辑模式隐藏，保持界面简洁）
                                    if (!favEditMode) {
                                        IconButton(onClick = { showAddGroupDialog = true }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.AddCircleOutline, "添加分组", tint = Coral, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        // 未分组收藏（固定置顶，不参与拖拽）
                        if (favState.ungroupedRepos.isNotEmpty()) {
                            item {
                                FavGroupRow(
                                    name = "未分组", description = null, c = c,
                                    repoCount = favState.ungroupedRepos.size,
                                    editMode = false,
                                    onDeleteGroupOnly = null, onDeleteAll = null,
                                    draggableModifier = Modifier,
                                    onClick = { if (!favEditMode) vm.showUngroupedDetail() },
                                )
                            }
                        }

                        // 各分组（编辑模式时支持拖拽排序）
                        if (favEditMode) {
                            items(groupList, key = { it.id }) { group ->
                                ReorderableItem(reorderState, key = group.id) { isDragging ->
                                    val haptic = LocalHapticFeedback.current
                                    val elevation by animateColorAsState(
                                        if (isDragging) c.bgCard else Color.Transparent,
                                        label = "drag_bg"
                                    )
                                    Box(Modifier.background(elevation)) {
                                        FavGroupRow(
                                            name = group.name,
                                            description = group.description.ifBlank { null },
                                            repoCount = group.repoIds.size,
                                            c = c,
                                            editMode = true,
                                            onDeleteGroupOnly = {
                                                favVm.deleteGroup(group.id, "group_only")
                                            },
                                            onDeleteAll = {
                                                favVm.deleteGroup(group.id, "all")
                                            },
                                            draggableModifier = Modifier.draggableHandle(
                                                onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                                onDragStopped = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                            ),
                                            onClick = {},
                                        )
                                    }
                                }
                            }
                        } else {
                            items(favState.groups, key = { it.id }) { group ->
                                FavGroupRow(
                                    name = group.name,
                                    description = group.description.ifBlank { null },
                                    repoCount = group.repoIds.size,
                                    c = c,
                                    editMode = false,
                                    onDeleteGroupOnly = { favVm.deleteGroup(group.id, "group_only") },
                                    onDeleteAll = { favVm.deleteGroup(group.id, "all") },
                                    draggableModifier = Modifier,
                                    onClick = { vm.showFavGroupDetail(group) },
                                )
                            }
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
                        modifier = Modifier.fillMaxWidth().clickable { vm.hideOrgPicker(); onOrgClick(org, state.orgs) }
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
        AddFavGroupDialog(c = c, onConfirm = { name, desc, _ -> favVm.addGroup(name, desc); showAddGroupDialog = false }, onDismiss = { showAddGroupDialog = false })
    }
}

// ─── 收藏夹分组行 ──────────────────────────────────────────────────────────────
// editMode=false: 正常模式，左滑删除可用
// editMode=true:  编辑模式，⠿ 把手在行内左侧 + Delete 图标在行内右侧，左滑禁用
@Composable
private fun FavGroupRow(
    name: String,
    description: String?,
    repoCount: Int,
    c: GmColors,
    editMode: Boolean,
    /** null = 无法删除（未分组行） */
    onDeleteGroupOnly: (() -> Unit)?,
    onDeleteAll: (() -> Unit)?,
    /** 编辑模式下由 ReorderableItem 作用域传入的 draggableHandle Modifier */
    draggableModifier: Modifier,
    onClick: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val canDelete = onDeleteGroupOnly != null

    if (editMode) {
        // ── 编辑模式行 ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bgDeep)
                .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 拖拽把手图标，draggableModifier 使整个把手区域可拖
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "拖拽排序",
                tint = c.textTertiary,
                modifier = Modifier.size(22.dp).then(draggableModifier),
            )
            Icon(Icons.Default.FolderSpecial, null, tint = Coral, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.textPrimary)
                if (description != null) {
                    Text(description, fontSize = 12.sp, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("$repoCount 个", fontSize = 12.sp, color = c.textTertiary)
            // 右侧删除按钮，无背景，直接用 Delete 图标融合在行内
            if (canDelete) {
                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除分组",
                        tint = Color(0xFFF85149),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        GmDivider()
    } else {
        // ── 正常模式行（SwipeToDismissBox）──────────────────────────────────────
        val dismissState = rememberSwipeToDismissBoxState()
        val scope = rememberCoroutineScope()

        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            onDismiss = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart && canDelete) {
                    showDeleteDialog = true
                }
                scope.launch { dismissState.reset() }
            },
            backgroundContent = {
                if (!canDelete) return@SwipeToDismissBox
                val color by animateColorAsState(
                    if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                        Color(0xFFF85149) else c.border,
                    label = "fav_swipe",
                )
                Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.CenterEnd) {
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.bgDeep)
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
    }

    // 删除确认弹窗（两种模式共用）
    if (showDeleteDialog && canDelete) {
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

// ─── 编辑分组配置 ─────────────────────────────────────────────────────────────
private data class EditGroupConfig(
    val getCurrentGroup: () -> FavGroup,
    val onUpdateGroup: (String, String) -> Unit,
    val editIcon: ImageVector,
    val editDescription: String,
)

// ─── 基础收藏详情页组件 ───────────────────────────────────────────────────────
@Composable
private fun BaseFavDetailScreen(
    title: String,
    getRepos: () -> List<FavRepo>,
    onSaveOrder: (List<String>) -> Unit,
    favVm: FavoritesManager,
    c: GmColors,
    onRepoClick: (String, String) -> Unit,
    onBack: () -> Unit,
    editGroupConfig: EditGroupConfig? = null,
) {
    val repos = getRepos()

    // 详情页编辑模式（排序）
    var editMode by remember { mutableStateOf(false) }
    var editOriginalOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var showExitDialog by remember { mutableStateOf(false) }

    // 编辑分组信息对话框
    var showEditGroupDialog by remember { mutableStateOf(false) }

    // Toast 状态
    var toastMessage by remember { mutableStateOf<String?>(null) }
    toastMessage?.let {
        LaunchedEffect(it) {
            kotlinx.coroutines.delay(2500)
            toastMessage = null
        }
    }

    // 用于拖拽排序的可变列表
    val repoList = remember(repos) { repos.toMutableStateList() }
    LaunchedEffect(repos) {
        if (!editMode) {
            repoList.clear()
            repoList.addAll(repos)
        }
    }

    // 编辑模式下拦截返回键，判断有无改动
    if (editMode) {
        BackHandler {
            if (repoList.map { it.fullName } != editOriginalOrder) {
                showExitDialog = true
            } else {
                editMode = false
            }
        }
    } else {
        BackHandler(onBack = onBack)
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = c.bgCard,
            title = { Text("放弃排序更改？", color = c.textPrimary) },
            text = { Text("当前的排序尚未保存，是否放弃更改？", color = c.textSecondary) },
            confirmButton = {
                Button(
                    onClick = { showExitDialog = false; editMode = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF85149)),
                ) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("继续编辑", color = Coral)
                }
            },
        )
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIdx = repoList.indexOfFirst { it.fullName == from.key }
        val toIdx   = repoList.indexOfFirst { it.fullName == to.key }
        if (fromIdx >= 0 && toIdx >= 0) {
            repoList.add(toIdx, repoList.removeAt(fromIdx))
        }
    }

    Scaffold(
        containerColor = c.bgDeep,
        topBar = {
            TopAppBar(
                title = {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.textPrimary)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (editMode) {
                            if (repoList.map { it.fullName } != editOriginalOrder) {
                                showExitDialog = true
                            } else {
                                editMode = false
                            }
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.textSecondary)
                    }
                },
                actions = {
                    if (!editMode && editGroupConfig != null) {
                        IconButton(onClick = { showEditGroupDialog = true }) {
                            Icon(editGroupConfig.editIcon, editGroupConfig.editDescription, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    if (repos.isNotEmpty()) {
                    if (editMode) {
                        TextButton(
                            onClick = {
                                val hasChanged = repoList.map { it.fullName } != editOriginalOrder
                                if (hasChanged) {
                                    onSaveOrder(repoList.map { it.fullName })
                                    toastMessage = "排序已保存"
                                }
                                editMode = false
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) {
                            Text("完成", color = Coral, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        IconButton(onClick = {
                            editOriginalOrder = repoList.map { it.fullName }
                            editMode = true
                        }) {
                            val icon = if (editGroupConfig != null) Icons.Default.Reorder else Icons.Default.Edit
                            Icon(icon, "编辑排序", tint = c.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep),
            )
        },
        snackbarHost = {
            toastMessage?.let {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Snackbar(modifier = Modifier.padding(top = 80.dp, start = 16.dp, end = 16.dp), containerColor = c.bgCard, contentColor = c.textPrimary) {
                        Text(it)
                    }
                }
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // 编辑分组对话框
            if (showEditGroupDialog && editGroupConfig != null) {
                val currentGroup = editGroupConfig.getCurrentGroup()
                AddFavGroupDialog(
                    c = c,
                    initialName = currentGroup.name,
                    initialDescription = currentGroup.description,
                    isEditMode = true,
                    onConfirm = { name, desc, hasChanged ->
                        if (hasChanged) {
                            editGroupConfig.onUpdateGroup(name, desc)
                            toastMessage = "分组信息已更新"
                        }
                        showEditGroupDialog = false
                    },
                    onDismiss = { showEditGroupDialog = false }
                )
            }
            if (repos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无收藏仓库", color = c.textTertiary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = lazyListState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (editMode) {
                        items(repoList, key = { it.fullName }) { repo ->
                            ReorderableItem(reorderState, key = repo.fullName) { isDragging ->
                                val haptic = LocalHapticFeedback.current
                                // 整张卡片作为拖拽把手：长按任意位置即可拖动
                                FavRepoCard(
                                    repo = repo, c = c,
                                    editMode = true,
                                    isDragging = isDragging,
                                    draggableModifier = Modifier.draggableHandle(
                                        onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                        onDragStopped = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    ),
                                    onRepoClick = { _, _ -> },
                                    onSwipe = { favVm.removeFavorite(repo.fullName) },
                                )
                            }
                        }
                    } else {
                        items(repos, key = { it.fullName }) { repo ->
                            FavRepoCard(
                                repo = repo, c = c,
                                editMode = false,
                                isDragging = false,
                                draggableModifier = Modifier,
                                onRepoClick = { o, n -> onRepoClick(o, n) },
                                onSwipe = { favVm.removeFavorite(repo.fullName) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── 收藏夹分组详情页 ─────────────────────────────────────────────────────────
@Composable
fun FavGroupDetailScreen(
    group: FavGroup, favVm: FavoritesManager, c: GmColors,
    onRepoClick: (String, String) -> Unit, onBack: () -> Unit,
) {
    val state by favVm.state.collectAsState()
    // 获取最新的分组信息
    val getCurrentGroup = { state.groups.find { it.id == group.id } ?: group }
    val getRepos = { favVm.getReposInGroup(getCurrentGroup().id) }

    BaseFavDetailScreen(
        title = getCurrentGroup().name,
        getRepos = getRepos,
        onSaveOrder = { order -> favVm.reorderReposInGroup(getCurrentGroup().id, order) },
        favVm = favVm,
        c = c,
        onRepoClick = onRepoClick,
        onBack = onBack,
        editGroupConfig = EditGroupConfig(
            getCurrentGroup = getCurrentGroup,
            onUpdateGroup = { name, desc -> favVm.updateGroup(getCurrentGroup().id, name, desc) },
            editIcon = Icons.Default.EditNote,
            editDescription = "编辑分组",
        ),
    )
}

@Composable
fun FavUngroupedDetailScreen(
    favVm: FavoritesManager, c: GmColors,
    onRepoClick: (String, String) -> Unit, onBack: () -> Unit,
) {
    val state by favVm.state.collectAsState()

    BaseFavDetailScreen(
        title = "未分组",
        getRepos = { state.ungroupedRepos },
        onSaveOrder = { order -> favVm.reorderReposInGroup(null, order) },
        favVm = favVm,
        c = c,
        onRepoClick = onRepoClick,
        onBack = onBack,
        editGroupConfig = null,
    )
}

// ─── 收藏仓库卡片 ─────────────────────────────────────────────────────────────
// editMode=false: 正常模式，左滑删除
// editMode=true:  编辑模式，卡片全宽+整体拖拽+左侧竖条+删除按钮悬浮右侧居中
@Composable
private fun FavRepoCard(
    repo: FavRepo,
    c: GmColors,
    editMode: Boolean,
    isDragging: Boolean,
    /** 编辑模式下由 ReorderableItem 作用域传入的 draggableHandle Modifier */
    draggableModifier: Modifier,
    onRepoClick: (String, String) -> Unit,
    onSwipe: () -> Unit,
) {
    var showRemoveDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (editMode) {
        // ── 编辑模式卡片 ─────────────────────────────────────────────────────────
        val cardBg by animateColorAsState(
            if (isDragging) c.bgItem else c.bgCard,
            label = "card_drag_bg"
        )
        // 卡片全宽，整体可拖，删除按钮悬浮在右侧
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .then(draggableModifier)
                .border(0.5.dp, c.border, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .drawBehind {
                    val barWidth = 8.dp.toPx()
                    
                    // 绘制卡片背景
                    drawRect(color = cardBg)
                    // 绘制左侧 Coral 竖条
                    drawRect(
                        color = Coral,
                        size = Size(barWidth, size.height),
                    )
                },
        ) {
            FavRepoCardContent(
                repo = repo,
                c = c,
                onWebsiteClick = { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
            )
            // 删除按钮：悬浮在卡片右上角，不挤压内容
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(40.dp)
                    .clickable { showRemoveDialog = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "移除收藏",
                    tint = Color(0xFFF85149),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    } else {
        // ── 正常模式卡片（SwipeToDismissBox）────────────────────────────────────
        val dismissState = rememberSwipeToDismissBoxState()
        val scope = rememberCoroutineScope()

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
                Box(
                    Modifier.fillMaxSize().background(color, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(end = 20.dp),
                    ) {
                        Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Text("移除", fontSize = 11.sp, color = Color.White, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.bgCard, RoundedCornerShape(12.dp))
                    .border(0.5.dp, c.border, RoundedCornerShape(12.dp))
                    .clickable { onRepoClick(repo.ownerLogin, repo.name) },
            ) {
                FavRepoCardContent(
                repo = repo,
                c = c,
                onWebsiteClick = { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
            )
            }
        }
    }

    // 移除确认弹窗（两种模式共用）
    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            containerColor = c.bgCard,
            title = { Text("移除收藏", color = c.textPrimary) },
            text = { Text("确定将「${repo.fullName}」从收藏中移除？", color = c.textSecondary) },
            confirmButton = {
                Button(
                    onClick = { onSwipe(); showRemoveDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF85149)),
                ) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text("取消", color = c.textSecondary)
                }
            },
        )
    }
}

/**
 * 收藏仓库卡片内容（正常模式和编辑模式共用）
 */
@Composable
private fun FavRepoCardContent(
    repo: FavRepo,
    c: GmColors,
    onWebsiteClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                repo.fullName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                color = Coral, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (!repo.description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(repo.description, fontSize = 12.sp, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val websiteUrl = repo.website?.takeIf { it.isNotBlank() && it != "null" }
        if (!websiteUrl.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            val urlToOpen = remember(websiteUrl) {
                if (!websiteUrl.startsWith("http://") && !websiteUrl.startsWith("https://")) "https://$websiteUrl"
                else websiteUrl
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.clickable { onWebsiteClick(urlToOpen) },
            ) {
                Icon(Icons.Default.Link, null, tint = BlueColor, modifier = Modifier.size(12.dp))
                Text(websiteUrl, fontSize = 11.sp, color = BlueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 左侧可滚动区域
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                ) {
                    if (!repo.language.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(8.dp).background(Coral, CircleShape))
                            Text(repo.language, fontSize = 11.sp, color = c.textTertiary)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Default.StarBorder, null, tint = Yellow, modifier = Modifier.size(13.dp))
                        Text("${repo.stars}", fontSize = 11.sp, color = c.textTertiary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Default.Share, null, tint = c.textTertiary, modifier = Modifier.size(13.dp))
                        Text("${repo.forks}", fontSize = 11.sp, color = c.textTertiary)
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 右侧固定区域
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = repo.defaultBranch,
                        fontSize = 10.5.sp, color = BlueColor,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(BlueDim, RoundedCornerShape(20.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                    if (repo.isPrivate) GmBadge("私有", RedDim, RedColor)
                    if (repo.archived) GmBadge("已归档", CoralDim, Coral)
                }
            }
            if (repo.topics.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    repo.topics.forEach { topic ->
                        Text(
                            text = topic,
                            fontSize = 9.sp, color = c.textTertiary,
                            modifier = Modifier
                                .background(BlueDim, RoundedCornerShape(6.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// ─── 添加/编辑分组对话框 ───────────────────────────────────────────────────────
@Composable
fun AddFavGroupDialog(
    c: GmColors,
    initialName: String = "",
    initialDescription: String = "",
    isEditMode: Boolean = false,
    onConfirm: (String, String, Boolean) -> Unit,  // 添加 hasChanged 参数
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(initialDescription) }
    val hasChanged = name != initialName || desc != initialDescription
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.bgCard,
        title = { Text(if (isEditMode) "编辑分组" else "新建分组", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("分组名称", color = c.textTertiary) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Coral, unfocusedBorderColor = c.border, focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary, focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem, focusedLabelColor = Coral, unfocusedLabelColor = c.textTertiary),
                )
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text("描述（可选）", color = c.textTertiary) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Coral, unfocusedBorderColor = c.border, focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary, focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem, focusedLabelColor = Coral, unfocusedLabelColor = c.textTertiary),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, desc, hasChanged) }, enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
            ) { Text(if (isEditMode) "保存" else "创建") }
        },
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
        AddFavGroupDialog(c = c, onConfirm = { name, desc, _ ->
            val id = favVm.addGroup(name, desc); selectedGroupId = id; showNewGroup = false
        }, onDismiss = { showNewGroup = false })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.bgCard,
        title = {
            Text(
                if (isAlreadyFavorited) "修改收藏" else "添加收藏",
                color = c.textPrimary, fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("选择分组（可不选直接添加至未分组）", fontSize = 12.sp, color = c.textTertiary)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(if (selectedGroupId == null) Coral.copy(alpha = 0.1f) else c.bgItem, RoundedCornerShape(8.dp))
                        .clickable { selectedGroupId = null }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedGroupId == null, onClick = { selectedGroupId = null }, colors = RadioButtonDefaults.colors(selectedColor = Coral))
                    Spacer(Modifier.width(8.dp))
                    Text("未分组", fontSize = 13.sp, color = c.textPrimary)
                }
                state.groups.forEach { group ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(if (selectedGroupId == group.id) Coral.copy(alpha = 0.1f) else c.bgItem, RoundedCornerShape(8.dp))
                            .clickable { selectedGroupId = group.id }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedGroupId == group.id, onClick = { selectedGroupId = group.id }, colors = RadioButtonDefaults.colors(selectedColor = Coral))
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
                if (isAlreadyFavorited) {
                    TextButton(onClick = { favVm.removeFavorite(repo.fullName); onDismiss() }) {
                        Text("移出收藏", color = Color(0xFFF85149), fontSize = 13.sp)
                    }
                }
                Button(
                    onClick = { favVm.addFavorite(repo, selectedGroupId); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                ) {
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = if (!inline) Modifier.padding(horizontal = 16.dp) else Modifier,
    ) {
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
                modifier = Modifier.fillMaxWidth()
                    .then(if (url != null) Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } else Modifier)
                    .padding(vertical = 3.dp),
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
        horizontalAlignment = Alignment.CenterHorizontally,
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
        modifier = Modifier.width(220.dp)
            .background(c.bgCard, RoundedCornerShape(12.dp))
            .border(0.5.dp, c.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
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
                Box(Modifier.size(8.dp).background(Coral, CircleShape)); Spacer(Modifier.width(4.dp))
                Text(repo.language, fontSize = 11.sp, color = c.textTertiary, maxLines = 1)
            } else Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.StarBorder, null, tint = c.textTertiary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp)); Text("${repo.stars}", fontSize = 11.sp, color = c.textTertiary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Share, null, tint = c.textTertiary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp)); Text("${repo.forks}", fontSize = 11.sp, color = c.textTertiary)
                }
            }
        }
    }
}
