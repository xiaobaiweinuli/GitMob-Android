package com.gitmob.android.ui.search

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gitmob.android.ui.common.LoadingBox
import com.gitmob.android.ui.theme.Coral
import com.gitmob.android.ui.theme.LocalGmColors
import com.gitmob.android.util.GitHubDestination
import com.gitmob.android.util.GitHubUrlParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onRepoClick: (String, String) -> Unit,
    onOrgClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    onViewMoreClick: (String, SearchCategory) -> Unit,
    onIssueClick: (String, String, Int) -> Unit,
    onPRClick: (String, String, Int) -> Unit,
    onCodeClick: (String, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
    vm: SearchViewModel = viewModel(),
) {
    val c = LocalGmColors.current
    val state by vm.state.collectAsState()
    val history by vm.searchHistory.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    fun doSearch(category: SearchCategory) {
        val query = state.query.trim()
        if (query.isBlank()) return
        vm.search(query)
        if (category != SearchCategory.ALL) {
            onViewMoreClick(query, category)
        }
        keyboard?.hide()
    }

    val showSearchResults = state.query.isNotBlank() && state.hasSearched && state.category == SearchCategory.ALL

    Scaffold(
        modifier = modifier,
        containerColor = c.bgDeep,
        topBar = {
            TopAppBar(
                title = { Text("搜索", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, null, tint = c.textSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { vm.updateQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .focusRequester(focusRequester),
                leadingIcon = { Icon(Icons.Default.Search, null, tint = c.textSecondary) },
                trailingIcon = {
                    Row {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { vm.updateQuery("") }) {
                                Icon(Icons.Default.Close, null, tint = c.textSecondary)
                            }
                        }
                        IconButton(onClick = { doSearch(state.category) }) {
                            Icon(Icons.Default.Search, null, tint = c.textSecondary)
                        }
                    }
                },
                placeholder = { Text("搜索仓库、代码、用户…", color = c.textTertiary) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.bgCard,
                    unfocusedContainerColor = c.bgCard,
                    focusedBorderColor = Coral,
                    unfocusedBorderColor = Color.Transparent,
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { doSearch(state.category) }
                )
            )

            if (showSearchResults) {
                SearchResultsContent(
                    state = state,
                    onRepoClick = onRepoClick,
                    onUserClick = onUserClick,
                    onViewMoreClick = { category ->
                    onViewMoreClick(state.query, category)
                },
                onIssueClick = onIssueClick,
                onPRClick = onPRClick,
                onCodeClick = onCodeClick
            )
            } else if (history.isNotEmpty() && state.query.isBlank()) {
                SearchHistorySection(
                    history = history,
                    onItemClick = { query ->
                        vm.fillFromHistory(query)
                    },
                    onClear = { vm.clearHistory() }
                )
            } else {
                CategorySelector(
                    categories = SearchCategory.values().toList(),
                    selected = state.category,
                    onSelect = { cat ->
                        vm.setCategory(cat)
                        if (state.query.isNotBlank()) {
                            doSearch(cat)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CategorySelector(
    categories: List<SearchCategory>,
    selected: SearchCategory,
    onSelect: (SearchCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalGmColors.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(categories) { cat ->
            val isSelected = cat == selected
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(cat) },
                color = if (isSelected) Coral else c.bgCard,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when (cat) {
                            SearchCategory.ALL -> Icons.Default.AllInclusive
                            SearchCategory.REPOS -> Icons.Default.BookmarkBorder
                            SearchCategory.CODE -> Icons.Default.Code
                            SearchCategory.ISSUES -> Icons.Default.Report
                            SearchCategory.PRS -> Icons.Default.Merge
                            SearchCategory.USERS -> Icons.Default.Person
                            SearchCategory.ORGS -> Icons.Default.Apartment
                        },
                        null,
                        tint = if (isSelected) Color.White else c.textSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = cat.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) Color.White else c.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchHistorySection(
    history: List<String>,
    onItemClick: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalGmColors.current

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "搜索历史",
                style = MaterialTheme.typography.titleMedium,
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = onClear) {
                Text("清除", color = Coral)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(history) { item ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onItemClick(item) },
                    color = c.bgCard,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.History,
                            null,
                            tint = c.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = item,
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultsContent(
    state: SearchState,
    onRepoClick: (String, String) -> Unit,
    onUserClick: (String) -> Unit,
    onViewMoreClick: (SearchCategory) -> Unit,
    onIssueClick: (String, String, Int) -> Unit,
    onPRClick: (String, String, Int) -> Unit,
    onCodeClick: (String, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalGmColors.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (state.repoResults.isNotEmpty() || state.loadingRepos) {
            item {
                CategorySectionHeader(
                    SearchCategory.REPOS,
                    state.repoTotal
                )
            }
            if (state.loadingRepos) {
                item { LoadingBox(modifier = Modifier.fillMaxWidth()) }
            } else {
                items(state.repoResults.take(5)) { repo ->
                    SearchRepoCard(
                        repo = repo,
                        onClick = { onRepoClick(repo.owner.login, repo.name) }
                    )
                }
                if (state.repoTotal > 5 || state.repoHasMore) {
                    item { ViewMoreButton(onClick = { onViewMoreClick(SearchCategory.REPOS) }) }
                }
            }
        }

        if (state.codeResults.isNotEmpty() || state.loadingCode) {
            item {
                CategorySectionHeader(
                    SearchCategory.CODE,
                    state.codeTotal
                )
            }
            if (state.loadingCode) {
                item { LoadingBox(modifier = Modifier.fillMaxWidth()) }
            } else {
                items(state.codeResults.take(5)) { code ->
                    SearchCodeCard(code = code, query = state.query, onClick = {
                        val uri = Uri.parse(code.htmlUrl)
                        val dest = GitHubUrlParser.parse(uri)
                        if (dest is GitHubDestination.FileView) {
                            onCodeClick(dest.owner, dest.repo, dest.path, dest.branch)
                        }
                    })
                }
                if (state.codeTotal > 5 || state.codeHasMore) {
                    item { ViewMoreButton(onClick = { onViewMoreClick(SearchCategory.CODE) }) }
                }
            }
        }

        if (state.issueResults.isNotEmpty() || state.loadingIssues) {
            item {
                CategorySectionHeader(
                    SearchCategory.ISSUES,
                    state.issueTotal
                )
            }
            if (state.loadingIssues) {
                item { LoadingBox(modifier = Modifier.fillMaxWidth()) }
            } else {
                items(state.issueResults.take(5)) { issue ->
                    SearchIssueCard(issue = issue, onClick = {
                        val uri = Uri.parse(issue.htmlUrl)
                        val dest = GitHubUrlParser.parse(uri)
                        if (dest is GitHubDestination.Issue) {
                            onIssueClick(dest.owner, dest.repo, dest.number)
                        }
                    })
                }
                if (state.issueTotal > 5 || state.issueHasMore) {
                    item { ViewMoreButton(onClick = { onViewMoreClick(SearchCategory.ISSUES) }) }
                }
            }
        }

        if (state.prResults.isNotEmpty() || state.loadingPrs) {
            item {
                CategorySectionHeader(
                    SearchCategory.PRS,
                    state.prTotal
                )
            }
            if (state.loadingPrs) {
                item { LoadingBox(modifier = Modifier.fillMaxWidth()) }
            } else {
                items(state.prResults.take(5)) { pr ->
                    SearchIssueCard(issue = pr, onClick = {
                        val uri = Uri.parse(pr.htmlUrl)
                        val dest = GitHubUrlParser.parse(uri)
                        if (dest is GitHubDestination.PR) {
                            onPRClick(dest.owner, dest.repo, dest.number)
                        }
                    })
                }
                if (state.prTotal > 5 || state.prHasMore) {
                    item { ViewMoreButton(onClick = { onViewMoreClick(SearchCategory.PRS) }) }
                }
            }
        }

        if (state.userResults.isNotEmpty() || state.loadingUsers) {
            item {
                CategorySectionHeader(
                    SearchCategory.USERS,
                    state.userTotal
                )
            }
            if (state.loadingUsers) {
                item { LoadingBox(modifier = Modifier.fillMaxWidth()) }
            } else {
                items(state.userResults.take(5)) { user ->
                    SearchUserCard(
                        user = user,
                        onClick = { onUserClick(user.login) }
                    )
                }
                if (state.userTotal > 5 || state.userHasMore) {
                    item { ViewMoreButton(onClick = { onViewMoreClick(SearchCategory.USERS) }) }
                }
            }
        }

        if (state.orgResults.isNotEmpty() || state.loadingOrgs) {
            item {
                CategorySectionHeader(
                    SearchCategory.ORGS,
                    state.orgTotal
                )
            }
            if (state.loadingOrgs) {
                item { LoadingBox(modifier = Modifier.fillMaxWidth()) }
            } else {
                items(state.orgResults.take(5)) { org ->
                    SearchUserCard(
                        user = org,
                        onClick = { onUserClick(org.login) }
                    )
                }
                if (state.orgTotal > 5 || state.orgHasMore) {
                    item { ViewMoreButton(onClick = { onViewMoreClick(SearchCategory.ORGS) }) }
                }
            }
        }
    }
}

@Composable
private fun CategorySectionHeader(
    category: SearchCategory,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val c = LocalGmColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (category) {
                    SearchCategory.ALL -> Icons.Default.AllInclusive
                    SearchCategory.REPOS -> Icons.Default.BookmarkBorder
                    SearchCategory.CODE -> Icons.Default.Code
                    SearchCategory.ISSUES -> Icons.Default.Report
                    SearchCategory.PRS -> Icons.Default.Merge
                    SearchCategory.USERS -> Icons.Default.Person
                    SearchCategory.ORGS -> Icons.Default.Apartment
                },
                null,
                tint = Coral,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = category.label,
                style = MaterialTheme.typography.titleMedium,
                color = c.textPrimary,
                fontWeight = FontWeight.Bold
            )
            if (total > 0) {
                Text(
                    text = "$total",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textTertiary
                )
            }
        }
    }
}

@Composable
private fun ViewMoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalGmColors.current
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("查看更多", color = Coral, fontWeight = FontWeight.SemiBold)
            Icon(Icons.AutoMirrored.Default.ArrowForward, null, tint = Coral, modifier = Modifier.size(16.dp))
        }
    }
}
