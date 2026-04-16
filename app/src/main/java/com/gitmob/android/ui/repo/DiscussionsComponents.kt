@file:OptIn(ExperimentalMaterial3Api::class)
package com.gitmob.android.ui.repo

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gitmob.android.api.GHDiscussion
import com.gitmob.android.api.GHDiscussionCategory
import com.gitmob.android.ui.common.EmptyBox
import com.gitmob.android.ui.common.GmDivider
import com.gitmob.android.ui.common.LoadingBox
import com.gitmob.android.ui.repos.FilterOptionItem
import com.gitmob.android.ui.repos.RepoFilterBottomSheet
import com.gitmob.android.ui.theme.*
import kotlinx.coroutines.launch

// ─── Discussions Tab 入口 ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscussionsTab(
    state: RepoDetailState,
    c: GmColors,
    vm: RepoDetailViewModel,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onRefresh: () -> Unit,
    onDiscussionClick: (Int) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val isAtBottom by remember { derivedStateOf {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
        last != null && last.index >= listState.layoutInfo.totalItemsCount - 3
    } }
    LaunchedEffect(isAtBottom) {
        if (isAtBottom && state.discussionsHasMore && !state.discussionsLoadingMore) {
            vm.loadMoreDiscussions()
        }
    }

    var showCreateDiscussionDialog by remember { mutableStateOf(false) }

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = state.discussionsRefreshing,
        onRefresh = onRefresh,
    ) {
        Column {
            DiscussionFilterToolbar(state, c, vm, permission, isArchived = isArchived, onAddDiscussionClick = {
                showCreateDiscussionDialog = true
            })

            if (state.discussions.isEmpty() && !state.discussionsRefreshing) {
                EmptyBox("暂无讨论")
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.discussions, key = { it.number }) { discussion ->
                        SwipeableDiscussionCard(
                            discussion = discussion,
                            c = c,
                            permission = permission,
                            onDelete = { vm.deleteDiscussion(discussion.number) },
                            onClick = { onDiscussionClick(discussion.number) }
                        )
                    }
                    if (state.discussionsLoadingMore) item {
                        Box(Modifier.fillMaxWidth().padding(8.dp),
                            contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp, color = Coral)
                        }
                    }
                }
            }
        }
    }

    if (showCreateDiscussionDialog) {
        CreateDiscussionDialog(
            c = c,
            categories = state.discussionCategories,
            onConfirm = { title, body, categoryId ->
                // TODO: 实际的创建讨论API调用
                showCreateDiscussionDialog = false
            },
            onDismiss = { showCreateDiscussionDialog = false }
        )
    }
}

// ─── 筛选工具栏 ──────────────────────────────────────────────────────────────

@Composable
private fun DiscussionFilterToolbar(
    state: RepoDetailState,
    c: GmColors,
    vm: RepoDetailViewModel,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onAddDiscussionClick: () -> Unit = {},
) {
    var showCategorySheet by remember { mutableStateOf(false) }
    var showAnsweredMenu  by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showAuthorsSheet by remember { mutableStateOf(false) }
    var showLabelsSheet by remember { mutableStateOf(false) }

    val hasFilters = state.discussionCategoryFilter != null || 
                    state.discussionAnsweredFilter != null ||
                    state.discussionFilterState.sortBy != DiscussionSortBy.UPDATED_DESC ||
                    state.discussionFilterState.selectedCreator != null ||
                    state.discussionFilterState.selectedLabels.isNotEmpty()
    val allAuthors = vm.getAllDiscussionAuthors().sorted()
    val allLabels = vm.getAllLabels()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgCard)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasFilters) {
                TextButton(
                    onClick = { vm.clearDiscussionFilters() },
                    colors = ButtonDefaults.textButtonColors(contentColor = RedColor)
                ) {
                    Text("清除", fontSize = 13.sp)
                }
            }

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val catName = state.discussionCategories
                    .firstOrNull { it.id == state.discussionCategoryFilter }
                    ?.let { "${it.emoji} ${it.name}" } ?: "分类"
                FilterButton(
                    text = catName,
                    isActive = state.discussionCategoryFilter != null, 
                    c = c,
                    onClick = { showCategorySheet = true }
                )

                Box {
                    val answeredLabel = when (state.discussionAnsweredFilter) {
                        true -> "已回答"; false -> "未回答"; null -> "状态"
                    }
                    FilterButton(
                        text = answeredLabel,
                        isActive = state.discussionAnsweredFilter != null, 
                        c = c,
                        onClick = { showAnsweredMenu = true }
                    )
                    DropdownMenu(
                        expanded = showAnsweredMenu,
                        onDismissRequest = { showAnsweredMenu = false },
                        modifier = Modifier.background(c.bgCard)
                    ) {
                        listOf(null to "全部", true to "已回答", false to "未回答").forEach { (v, label) ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        RadioButton(
                                            selected = state.discussionAnsweredFilter == v,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(selectedColor = Coral)
                                        )
                                        Text(label, fontSize = 13.sp, color = c.textPrimary)
                                    }
                                },
                                onClick = { 
                                    vm.setDiscussionAnsweredFilter(v)
                                    showAnsweredMenu = false 
                                },
                            )
                        }
                    }
                }

                FilterButton(
                    text = state.discussionFilterState.sortBy.displayName,
                    isActive = state.discussionFilterState.sortBy != DiscussionSortBy.UPDATED_DESC,
                    c = c,
                    onClick = { showSortSheet = true }
                )

                FilterButton(
                    text = "作者",
                    isActive = state.discussionFilterState.selectedCreator != null,
                    c = c,
                    onClick = { showAuthorsSheet = true }
                )

                FilterButton(
                    text = "标签",
                    isActive = state.discussionFilterState.selectedLabels.isNotEmpty(),
                    c = c,
                    count = state.discussionFilterState.selectedLabels.size,
                    onClick = { showLabelsSheet = true }
                )
            }

            IconButton(
                onClick = onAddDiscussionClick,
                modifier = Modifier.size(36.dp),
                enabled = !isArchived
            ) {
                Icon(
                    Icons.Default.Add,
                    null,
                    tint = if (isArchived) c.textTertiary else Coral,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }

    if (showCategorySheet) {
        RepoFilterBottomSheet(title = "按分类筛选", c = c,
            onDismiss = { showCategorySheet = false }) {
            FilterOptionItem(text = "全部分类",
                isSelected = state.discussionCategoryFilter == null,
                isRadio = true, c = c,
                onClick = { vm.setDiscussionCategoryFilter(null); showCategorySheet = false })
            state.discussionCategories.forEach { cat ->
                FilterOptionItem(text = "${cat.emoji} ${cat.name}",
                    isSelected = state.discussionCategoryFilter == cat.id,
                    isRadio = true, c = c,
                    onClick = { vm.setDiscussionCategoryFilter(cat.id); showCategorySheet = false })
            }
        }
    }

    if (showSortSheet) {
        RepoFilterBottomSheet(
            title = "排序方式",
            c = c,
            onDismiss = { showSortSheet = false }
        ) {
            DiscussionSortBy.values().forEach { sortBy ->
                val isSelected = state.discussionFilterState.sortBy == sortBy
                FilterOptionItem(
                    text = sortBy.displayName,
                    isSelected = isSelected,
                    isRadio = true,
                    c = c,
                    onClick = {
                        vm.setDiscussionSortBy(sortBy)
                        showSortSheet = false
                    }
                )
            }
        }
    }

    if (showAuthorsSheet) {
        RepoFilterBottomSheet(
            title = "选择作者",
            c = c,
            onDismiss = { showAuthorsSheet = false }
        ) {
            val currentCreator = state.discussionFilterState.selectedCreator
            FilterOptionItem(
                text = "全部",
                isSelected = currentCreator == null,
                isRadio = true,
                c = c,
                onClick = {
                    vm.setDiscussionCreator(null)
                    showAuthorsSheet = false
                }
            )
            if (allAuthors.isNotEmpty()) {
                allAuthors.forEach { author ->
                    val isSelected = currentCreator == author
                    FilterOptionItem(
                        text = author,
                        isSelected = isSelected,
                        isRadio = true,
                        c = c,
                        onClick = {
                            vm.setDiscussionCreator(author)
                            showAuthorsSheet = false
                        }
                    )
                }
            }
        }
    }

    if (showLabelsSheet) {
        RepoFilterBottomSheet(
            title = "选择标签",
            c = c,
            onDismiss = { showLabelsSheet = false }
        ) {
            val selectedLabels = state.discussionFilterState.selectedLabels
            if (allLabels.isNotEmpty()) {
                allLabels.forEach { label ->
                    val isSelected = selectedLabels.contains(label)
                    FilterOptionItem(
                        text = label,
                        isSelected = isSelected,
                        isRadio = false,
                        c = c,
                        onClick = {
                            vm.toggleDiscussionLabel(label)
                        }
                    )
                }
            }
        }
    }
}

// ─── 创建讨论对话框 ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateDiscussionDialog(
    c: GmColors,
    categories: List<GHDiscussionCategory> = emptyList(),
    onConfirm: (title: String, body: String, categoryId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableStateOf(categories.firstOrNull()?.id ?: "") }
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
                "创建新讨论",
                fontSize = 16.sp,
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            GmDivider()

            // 标题
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题 *") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.bgDeep,
                    unfocusedContainerColor = c.bgDeep,
                    focusedBorderColor = Coral,
                    unfocusedBorderColor = c.border,
                ),
            )

            // 分类选择
            if (categories.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("选择分类", fontSize = 13.sp, color = c.textSecondary)
                    categories.forEach { category ->
                        val isSelected = selectedCategoryId == category.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedCategoryId = category.id }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Coral)
                            )
                            Text("${category.emoji} ${category.name}", fontSize = 13.sp, color = c.textPrimary)
                        }
                    }
                }
            }

            // 正文
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("正文") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
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
                TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (title.isNotBlank() && selectedCategoryId.isNotBlank()) {
                            onConfirm(title, body, selectedCategoryId)
                        }
                    },
                    enabled = title.isNotBlank() && selectedCategoryId.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                ) { Text("创建") }
            }
        }
    }
}

// ─── 可滑动删除的讨论卡片 ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableDiscussionCard(
    discussion: GHDiscussion,
    c: GmColors,
    permission: RepoPermission,
    onDelete: () -> Unit,
    onClick: () -> Unit = {},
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    
    if (permission.isOwner) {
        androidx.compose.material3.SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            onDismiss = { value ->
                if (value == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) {
                    showDeleteDialog = true
                }
                scope.launch {
                    dismissState.reset()
                }
            },
            backgroundContent = {
                val color by animateColorAsState(
                    if (dismissState.targetValue == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) RedColor else c.border,
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
            DiscussionCardContent(discussion = discussion, c = c, onClick = onClick)
        }
    } else {
        DiscussionCardContent(discussion = discussion, c = c, onClick = onClick)
    }

    if (showDeleteDialog) {
        DeleteDiscussionConfirmDialog(
            discussionNumber = discussion.number,
            title = discussion.title,
            onConfirm = { onDelete(); showDeleteDialog = false },
            onDismiss = { showDeleteDialog = false },
            c = c,
        )
    }
}

// ─── 讨论卡片内容 ─────────────────────────────────────────────────────────────

@Composable
fun DiscussionCardContent(discussion: GHDiscussion, c: GmColors, onClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (discussion.category?.isAnswerable == true) {
                Icon(
                    if (discussion.isAnswered) Icons.Default.CheckCircle else Icons.Default.Forum,
                    null,
                    tint = if (discussion.isAnswered) Green else c.textTertiary,
                    modifier = Modifier.size(10.dp),
                )
            }
            Text(
                discussion.title,
                fontSize = 13.sp,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            discussion.category?.let { cat ->
                Surface(
                    color = c.bgItem,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "${cat.emoji} ${cat.name}",
                        fontSize = 10.sp,
                        color = c.textSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        maxLines = 1,
                    )
                }
            }
            if (discussion.isAnswered) {
                Surface(
                    color = Green.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "已回答",
                        fontSize = 10.sp,
                        color = Green,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        maxLines = 1,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "#${discussion.number} · ${discussion.author?.login ?: "unknown"}",
                    fontSize = 11.sp,
                    color = c.textTertiary,
                )
                Text(
                    "·",
                    fontSize = 11.sp,
                    color = c.textTertiary,
                )
                Text(
                    repoFormatDate(discussion.createdAt),
                    fontSize = 11.sp,
                    color = c.textTertiary,
                )
            }
            val commentCount = discussion.comments
            if (commentCount > 0) {
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

// ─── 删除讨论确认对话框 ──────────────────────────────────────────────────────

@Composable
fun DeleteDiscussionConfirmDialog(
    discussionNumber: Int,
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    c: GmColors,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        icon = {
            Icon(Icons.Default.DeleteForever, null, tint = RedColor, modifier = Modifier.size(28.dp))
        },
        title = { Text("删除讨论", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.bgItem, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Report,
                        null,
                        tint = RedColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "#$discussionNumber",
                        fontSize = 13.sp,
                        color = c.textPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    title,
                    fontSize = 13.sp,
                    color = c.textSecondary,
                )
                Text(
                    "此操作不可撤销。确认要删除这个讨论吗？",
                    fontSize = 13.sp,
                    color = c.textTertiary,
                    lineHeight = 20.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = RedColor),
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


