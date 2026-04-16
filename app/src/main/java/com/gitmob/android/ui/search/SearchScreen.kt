@file:OptIn(ExperimentalMaterial3Api::class)
package com.gitmob.android.ui.search

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gitmob.android.api.GHCodeResult
import com.gitmob.android.api.GHIssue
import com.gitmob.android.api.GHRepo
import com.gitmob.android.api.GHSearchUser
import com.gitmob.android.ui.common.*
import com.gitmob.android.ui.theme.*

// ─── 高亮工具 ─────────────────────────────────────────────────────────────────
@Composable
private fun HighlightText(
    text: String, query: String, baseColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    modifier: Modifier = Modifier,
) {
    if (query.isBlank()) {
        androidx.compose.material3.Text(text, color = baseColor, fontSize = fontSize,
            fontWeight = fontWeight, maxLines = maxLines, overflow = overflow, modifier = modifier)
        return
    }
    val annotated = buildAnnotatedString {
        pushStyle(SpanStyle(color = baseColor, fontWeight = fontWeight))
        var cursor = 0
        val lower = text.lowercase()
        val lowerQ = query.lowercase()
        while (cursor < text.length) {
            val idx = lower.indexOf(lowerQ, cursor)
            if (idx < 0) { 
                append(text.substring(cursor))
                break 
            }
            if (idx > cursor) {
                append(text.substring(cursor, idx))
            }
            pushStyle(SpanStyle(color = Coral, fontWeight = FontWeight.Bold, background = Coral.copy(alpha = 0.12f)))
            append(text.substring(idx, idx + lowerQ.length))
            pop()
            cursor = idx + lowerQ.length
        }
        pop()
    }
    androidx.compose.material3.Text(annotated, fontSize = fontSize,
        maxLines = maxLines, overflow = overflow, modifier = modifier)
}

// ─── SearchScreen ─────────────────────────────────────────────────────────────
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onRepoClick: (String, String) -> Unit,
    onOrgClick: ((String) -> Unit)? = null,
    onUserClick: ((String) -> Unit)? = null,
    vm: SearchViewModel = viewModel(),
) {
    val c = LocalGmColors.current
    val state by vm.state.collectAsState()
    val history by vm.searchHistory.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxSize().background(c.bgDeep)) {
        // ── 搜索栏 ──────────────────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth().statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.textSecondary)
            }
            TextField(
                value = state.query, onValueChange = { vm.setQuery(it) },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text("搜索 GitHub", color = c.textTertiary, fontSize = 15.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary, cursorColor = Coral,
                ),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    if (state.query.isNotEmpty())
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Default.Close, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
                        }
                },
            )
            if (state.query.isNotEmpty()) {
                TextButton(onClick = { keyboard?.hide(); vm.search() }) {
                    Text("搜索", color = Coral, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        when {
            !state.hasSearched -> HistoryPanel(history, state.query, c,
                onClear = { vm.clearHistory() },
                onHistoryClick = { keyboard?.hide(); vm.searchFromHistory(it) },
                onCategoryClick = { cat -> keyboard?.hide(); vm.setCategory(cat); vm.search() })
            else -> ResultPanel(state, c, onCategorySelect = { vm.setCategory(it) },
                onRepoClick = onRepoClick, onOrgClick = onOrgClick, onUserClick = onUserClick,
                onLoadMoreRepos = { vm.loadMoreRepos() }, onLoadMoreCode = { vm.loadMoreCode() },
                onLoadMoreIssues = { vm.loadMoreIssues() }, onLoadMorePrs = { vm.loadMorePrs() },
                onLoadMoreUsers = { vm.loadMoreUsers() }, onLoadMoreOrgs = { vm.loadMoreOrgs() })
        }
    }
}

// ─── 历史面板 ─────────────────────────────────────────────────────────────────
@Composable
private fun HistoryPanel(history: List<String>, query: String, c: GmColors,
    onClear: () -> Unit, onHistoryClick: (String) -> Unit,
    onCategoryClick: (SearchCategory) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (history.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("近期搜索", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.textPrimary)
                    TextButton(onClick = onClear) { Text("清除", color = Coral, fontSize = 13.sp) }
                }
            }
            items(history) { q ->
                Row(modifier = Modifier.fillMaxWidth().clickable { onHistoryClick(q) }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.History, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(q, color = c.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Filled.CallMade, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
                }
            }
            item { GmDivider() }
        }
        if (query.isNotEmpty()) item {
            Column {
                listOf(
                    Triple(SearchCategory.REPOS,  Icons.Default.Folder,                   "包含 \"$query\" 的仓库"),
                    Triple(SearchCategory.CODE,   Icons.Default.Code,                     "具有 \"$query\" 的代码"),
                    Triple(SearchCategory.ISSUES, Icons.Default.Circle,                   "包含 \"$query\" 的议题"),
                    Triple(SearchCategory.PRS,    Icons.AutoMirrored.Filled.MergeType,    "包含 \"$query\" 的拉取请求"),
                    Triple(SearchCategory.USERS,  Icons.Default.Person,                   "包含 \"$query\" 的人员"),
                    Triple(SearchCategory.ORGS,   Icons.Default.Business,                 "包含 \"$query\" 的组织"),
                ).forEach { (cat, icon, label) ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { onCategoryClick(cat) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(label, color = c.textPrimary, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

// ─── 结果面板 ─────────────────────────────────────────────────────────────────
@Composable
private fun ResultPanel(
    state: SearchState, c: GmColors,
    onCategorySelect: (SearchCategory) -> Unit,
    onRepoClick: (String, String) -> Unit, onOrgClick: ((String) -> Unit)?, onUserClick: ((String) -> Unit)?,
    onLoadMoreRepos: () -> Unit, onLoadMoreCode: () -> Unit,
    onLoadMoreIssues: () -> Unit, onLoadMorePrs: () -> Unit,
    onLoadMoreUsers: () -> Unit, onLoadMoreOrgs: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 分类 Chip
        Row(modifier = Modifier.fillMaxWidth().background(c.bgDeep)
            .padding(horizontal = 12.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchCategory.entries.forEach { cat ->
                val isSelected = cat == state.category
                val count = when (cat) {
                    SearchCategory.REPOS  -> state.repoTotal;  SearchCategory.CODE  -> state.codeTotal
                    SearchCategory.ISSUES -> state.issueTotal; SearchCategory.PRS   -> state.prTotal
                    SearchCategory.USERS  -> state.userTotal;  SearchCategory.ORGS  -> state.orgTotal
                    SearchCategory.ALL    -> 0
                }
                val loading = when (cat) {
                    SearchCategory.REPOS  -> state.loadingRepos;  SearchCategory.CODE  -> state.loadingCode
                    SearchCategory.ISSUES -> state.loadingIssues; SearchCategory.PRS   -> state.loadingPrs
                    SearchCategory.USERS  -> state.loadingUsers;  SearchCategory.ORGS  -> state.loadingOrgs
                    SearchCategory.ALL    -> state.run { loadingRepos || loadingCode || loadingIssues || loadingPrs || loadingUsers || loadingOrgs }
                }
                val label = if (cat == SearchCategory.ALL || loading) cat.label
                            else if (count > 0) "${cat.label} $count" else cat.label
                FilterChip(selected = isSelected, onClick = { onCategorySelect(cat) },
                    label = { Text(label, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Coral.copy(alpha = 0.18f), selectedLabelColor = Coral,
                        containerColor = c.bgItem, labelColor = c.textSecondary),
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = isSelected,
                        selectedBorderColor = Coral.copy(alpha = 0.5f), borderColor = c.border))
            }
        }
        GmDivider()

        when (state.category) {
            SearchCategory.ALL    -> AllResultsView(state, c, onRepoClick, onOrgClick, onUserClick, onCategorySelect)
            SearchCategory.REPOS  -> PagingList(state.repoResults, state.loadingRepos, state.repoLoadingMore, state.repoHasMore, onLoadMoreRepos, "未找到相关仓库") { RepoItem(it, c, state.query, onRepoClick) }
            SearchCategory.CODE   -> PagingList(state.codeResults, state.loadingCode, state.codeLoadingMore, state.codeHasMore, onLoadMoreCode, "未找到相关代码") { CodeItem(it, c, state.query) }
            SearchCategory.ISSUES -> PagingList(state.issueResults, state.loadingIssues, state.issueLoadingMore, state.issueHasMore, onLoadMoreIssues, "未找到相关议题") { IssueItem(it, c, false, state.query) }
            SearchCategory.PRS    -> PagingList(state.prResults, state.loadingPrs, state.prLoadingMore, state.prHasMore, onLoadMorePrs, "未找到相关拉取请求") { IssueItem(it, c, true, state.query) }
            SearchCategory.USERS  -> PagingList(state.userResults, state.loadingUsers, state.userLoadingMore, state.userHasMore, onLoadMoreUsers, "未找到相关用户") { UserItem(it, c, false, onClick = { onUserClick?.invoke(it.login) }) }
            SearchCategory.ORGS   -> PagingList(state.orgResults, state.loadingOrgs, state.orgLoadingMore, state.orgHasMore, onLoadMoreOrgs, "未找到相关组织") { UserItem(it, c, true, onClick = { onOrgClick?.invoke(it.login) }) }
        }
    }
}

@Composable
private fun <T> PagingList(items: List<T>, loading: Boolean, loadingMore: Boolean, hasMore: Boolean,
    onLoadMore: () -> Unit, emptyMsg: String, itemContent: @Composable (T) -> Unit) {
    if (loading && items.isEmpty()) { LoadingBox(); return }
    if (!loading && items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emptyMsg, color = LocalGmColors.current.textTertiary, fontSize = 14.sp) }; return
    }
    val listState = rememberLazyListState()
    val isAtBottom by remember { derivedStateOf {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
        last != null && last.index >= listState.layoutInfo.totalItemsCount - 3
    } }
    LaunchedEffect(isAtBottom) { if (isAtBottom && hasMore && !loadingMore) onLoadMore() }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { itemContent(it) }
        if (loadingMore) item {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Coral)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ─── 全部结果视图 ─────────────────────────────────────────────────────────────
@Composable
private fun AllResultsView(state: SearchState, c: GmColors, onRepoClick: (String, String) -> Unit,
    onOrgClick: ((String) -> Unit)?, onUserClick: ((String) -> Unit)?,
    onCategorySelect: (SearchCategory) -> Unit) {
    val anyLoading = state.run { loadingRepos || loadingCode || loadingIssues || loadingPrs || loadingUsers || loadingOrgs }
    val total = state.repoResults.size + state.codeResults.size + state.issueResults.size +
        state.prResults.size + state.userResults.size + state.orgResults.size
    if (anyLoading && total == 0) { LoadingBox(); return }
    if (!anyLoading && total == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("未找到相关结果", color = c.textTertiary, fontSize = 14.sp) }; return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.repoResults.isNotEmpty()) {
            item { SectionHeader("仓库", state.repoTotal, Icons.Default.Folder, c) }
            items(state.repoResults.take(5)) { RepoItem(it, c, state.query, onRepoClick) }
            if (state.repoTotal > 5) item { LoadMoreButton("查看全部 ${state.repoTotal} 个仓库结果", c) { onCategorySelect(SearchCategory.REPOS) } }
        }
        if (state.codeResults.isNotEmpty()) {
            item { SectionHeader("代码", state.codeTotal, Icons.Default.Code, c) }
            items(state.codeResults.take(5)) { CodeItem(it, c, state.query) }
            if (state.codeTotal > 5) item { LoadMoreButton("查看全部 ${state.codeTotal} 个代码结果", c) { onCategorySelect(SearchCategory.CODE) } }
        }
        if (state.issueResults.isNotEmpty()) {
            item { SectionHeader("议题", state.issueTotal, Icons.Default.Circle, c) }
            items(state.issueResults.take(5)) { IssueItem(it, c, false, state.query) }
            if (state.issueTotal > 5) item { LoadMoreButton("查看全部 ${state.issueTotal} 个议题结果", c) { onCategorySelect(SearchCategory.ISSUES) } }
        }
        if (state.prResults.isNotEmpty()) {
            item { SectionHeader("拉取请求", state.prTotal, Icons.AutoMirrored.Filled.MergeType, c) }
            items(state.prResults.take(5)) { IssueItem(it, c, true, state.query) }
            if (state.prTotal > 5) item { LoadMoreButton("查看全部 ${state.prTotal} 个拉取请求结果", c) { onCategorySelect(SearchCategory.PRS) } }
        }
        if (state.userResults.isNotEmpty()) {
            item { SectionHeader("人员", state.userTotal, Icons.Default.Person, c) }
            items(state.userResults.take(5)) { UserItem(it, c, false, onClick = { onUserClick?.invoke(it.login) }) }
            if (state.userTotal > 5) item { LoadMoreButton("查看全部 ${state.userTotal} 个人员结果", c) { onCategorySelect(SearchCategory.USERS) } }
        }
        if (state.orgResults.isNotEmpty()) {
            item { SectionHeader("组织", state.orgTotal, Icons.Default.Business, c) }
            items(state.orgResults.take(5)) { UserItem(it, c, true, onClick = { onOrgClick?.invoke(it.login) }) }
            if (state.orgTotal > 5) item { LoadMoreButton("查看全部 ${state.orgTotal} 个组织结果", c) { onCategorySelect(SearchCategory.ORGS) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, icon: ImageVector, c: GmColors) {
    Row(Modifier.fillMaxWidth().background(c.bgCard, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Coral, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = c.textPrimary)
        }
        if (count > 0) Text("$count 个结果", fontSize = 12.sp, color = c.textTertiary)
    }
}

@Composable
private fun LoadMoreButton(label: String, c: GmColors, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.ExpandMore, null, tint = Coral, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Coral, fontSize = 13.sp)
    }
}

// ─── Item 卡片 ────────────────────────────────────────────────────────────────
@Composable
private fun RepoItem(repo: GHRepo, c: GmColors, query: String, onRepoClick: (String, String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(14.dp))
            .clickable(onClick = { onRepoClick(repo.owner.login, repo.name) })
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (repo.private) Icons.Default.Lock else Icons.Default.Folder, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                    HighlightText(repo.name, query, Coral, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (repo.archived == true) {
                        com.gitmob.android.ui.common.GmBadge("已归档", com.gitmob.android.ui.theme.CoralDim, com.gitmob.android.ui.theme.Coral)
                    }
                }
                if (!repo.description.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    HighlightText(repo.description, query, c.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (repo.private) GmBadge("私有", RedDim, RedColor)
        }

        Spacer(Modifier.height(10.dp))

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
            Text(
                text = repo.defaultBranch,
                fontSize = 10.5.sp, color = BlueColor,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.background(BlueDim, RoundedCornerShape(20.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun CodeItem(code: GHCodeResult, c: GmColors, query: String) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(code.repository.fullName, fontSize = 11.sp, color = c.textTertiary, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Code, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            HighlightText(code.path, query, c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        // 代码片段预览（text_matches 中的 fragment）
        code.textMatches.firstOrNull()?.fragment?.trim()?.takeIf { it.isNotBlank() }?.let { fragment ->
            Spacer(Modifier.height(6.dp))
            HighlightText(fragment.take(200), query, c.textSecondary, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(c.bgItem, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun IssueItem(issue: GHIssue, c: GmColors, isPr: Boolean, query: String) {
    val isOpen = issue.state == "open"
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(if (isPr) Icons.AutoMirrored.Filled.MergeType else Icons.Default.Circle,
                null, tint = if (isOpen) Green else c.textTertiary, modifier = Modifier.size(16.dp).padding(top = 2.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                val repoPath = issue.htmlUrl.removePrefix("https://github.com/")
                    .let { if (isPr) it.substringBefore("/pull") else it.substringBefore("/issues") }
                if (repoPath.isNotBlank()) Text(repoPath, fontSize = 11.sp, color = c.textTertiary, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(2.dp))
                HighlightText(issue.title, query, c.textPrimary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("#${issue.number}", fontSize = 11.sp, color = c.textTertiary)
                    if (issue.user.login.isNotBlank()) Text("by ${issue.user.login}", fontSize = 11.sp, color = c.textTertiary)
                    if ((issue.comments ?: 0) > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.Comment, null, tint = c.textTertiary, modifier = Modifier.size(11.dp))
                            Spacer(Modifier.width(2.dp)); Text("${issue.comments}", fontSize = 11.sp, color = c.textTertiary)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun UserItem(user: GHSearchUser, c: GmColors, isOrg: Boolean, onClick: (() -> Unit)? = null) {
    val avatarUrl = user.avatarUrl?.let { if (it.contains("?")) "$it&s=60" else "$it?s=60" }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(model = avatarUrl, contentDescription = null,
            modifier = Modifier.size(36.dp)
                .clip(if (isOrg) RoundedCornerShape(8.dp) else CircleShape).background(c.bgItem))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(user.login, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            Text(if (isOrg) "组织" else "用户", fontSize = 11.sp, color = c.textTertiary)
        }
        if (onClick != null)
            Icon(Icons.Default.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.height(8.dp))
}
