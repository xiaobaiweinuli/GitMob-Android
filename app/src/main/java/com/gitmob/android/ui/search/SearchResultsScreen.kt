package com.gitmob.android.ui.search

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun SearchResultsScreen(
    query: String,
    category: SearchCategory,
    onBack: () -> Unit,
    onRepoClick: (String, String) -> Unit,
    onOrgClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    onIssueClick: (String, String, Int) -> Unit,
    onPRClick: (String, String, Int) -> Unit,
    onCodeClick: (String, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
    vm: SearchViewModel = viewModel(),
) {
    val c = LocalGmColors.current
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        vm.searchSingleCategory(query, category)
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                val items = when (category) {
                    SearchCategory.REPOS -> state.repoResults
                    SearchCategory.CODE -> state.codeResults
                    SearchCategory.ISSUES -> state.issueResults
                    SearchCategory.PRS -> state.prResults
                    SearchCategory.USERS -> state.userResults
                    SearchCategory.ORGS -> state.orgResults
                    SearchCategory.ALL -> emptyList()
                }
                if (lastVisibleIndex != null && lastVisibleIndex >= items.size - 3) {
                    when (category) {
                        SearchCategory.REPOS -> vm.loadMoreRepos()
                        SearchCategory.CODE -> vm.loadMoreCode()
                        SearchCategory.ISSUES -> vm.loadMoreIssues()
                        SearchCategory.PRS -> vm.loadMorePrs()
                        SearchCategory.USERS -> vm.loadMoreUsers()
                        SearchCategory.ORGS -> vm.loadMoreOrgs()
                        SearchCategory.ALL -> {}
                    }
                }
            }
    }

    Scaffold(
        modifier = modifier,
        containerColor = c.bgDeep,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "${category.label}: $query",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, null, tint = c.textSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep)
            )
        }
    ) { padding ->
        val items = when (category) {
            SearchCategory.REPOS -> state.repoResults
            SearchCategory.CODE -> state.codeResults
            SearchCategory.ISSUES -> state.issueResults
            SearchCategory.PRS -> state.prResults
            SearchCategory.USERS -> state.userResults
            SearchCategory.ORGS -> state.orgResults
            SearchCategory.ALL -> emptyList()
        }
        val isLoading = when (category) {
            SearchCategory.REPOS -> state.loadingRepos
            SearchCategory.CODE -> state.loadingCode
            SearchCategory.ISSUES -> state.loadingIssues
            SearchCategory.PRS -> state.loadingPrs
            SearchCategory.USERS -> state.loadingUsers
            SearchCategory.ORGS -> state.loadingOrgs
            SearchCategory.ALL -> false
        }
        val isLoadingMore = when (category) {
            SearchCategory.REPOS -> state.repoLoadingMore
            SearchCategory.CODE -> state.codeLoadingMore
            SearchCategory.ISSUES -> state.issueLoadingMore
            SearchCategory.PRS -> state.prLoadingMore
            SearchCategory.USERS -> state.userLoadingMore
            SearchCategory.ORGS -> state.orgLoadingMore
            SearchCategory.ALL -> false
        }
        val total = when (category) {
            SearchCategory.REPOS -> state.repoTotal
            SearchCategory.CODE -> state.codeTotal
            SearchCategory.ISSUES -> state.issueTotal
            SearchCategory.PRS -> state.prTotal
            SearchCategory.USERS -> state.userTotal
            SearchCategory.ORGS -> state.orgTotal
            SearchCategory.ALL -> 0
        }

        if (isLoading && items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                LoadingBox()
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(padding)
            ) {
                if (total > 0) {
                    item {
                        Text(
                            text = "共找到 $total 个结果",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary
                        )
                    }
                }

                if (category == SearchCategory.REPOS) {
                    items(state.repoResults) { repo ->
                        SearchRepoCard(
                            repo = repo,
                            onClick = { onRepoClick(repo.owner.login, repo.name) }
                        )
                    }
                } else if (category == SearchCategory.CODE) {
                    items(state.codeResults) { code ->
                        SearchCodeCard(
                            code = code,
                            query = query,
                            onClick = {
                                val uri = Uri.parse(code.htmlUrl)
                                val dest = GitHubUrlParser.parse(uri)
                                if (dest is GitHubDestination.FileView) {
                                    onCodeClick(dest.owner, dest.repo, dest.path, dest.branch)
                                }
                            }
                        )
                    }
                } else if (category == SearchCategory.ISSUES) {
                    items(state.issueResults) { issue ->
                        SearchIssueCard(
                            issue = issue,
                            onClick = {
                                val uri = Uri.parse(issue.htmlUrl)
                                val dest = GitHubUrlParser.parse(uri)
                                if (dest is GitHubDestination.Issue) {
                                    onIssueClick(dest.owner, dest.repo, dest.number)
                                }
                            }
                        )
                    }
                } else if (category == SearchCategory.PRS) {
                    items(state.prResults) { issue ->
                        SearchIssueCard(
                            issue = issue,
                            onClick = {
                                val uri = Uri.parse(issue.htmlUrl)
                                val dest = GitHubUrlParser.parse(uri)
                                if (dest is GitHubDestination.PR) {
                                    onPRClick(dest.owner, dest.repo, dest.number)
                                }
                            }
                        )
                    }
                } else if (category == SearchCategory.USERS) {
                    items(state.userResults) { user ->
                        SearchUserCard(
                            user = user,
                            onClick = { onUserClick(user.login) }
                        )
                    }
                } else if (category == SearchCategory.ORGS) {
                    items(state.orgResults) { org ->
                        SearchUserCard(
                            user = org,
                            onClick = { onUserClick(org.login) }
                        )
                    }
                }

                if (isLoadingMore) {
                    item { LoadingBox(modifier = Modifier.fillMaxWidth()) }
                }

                if (!isLoading && items.isEmpty() && state.hasSearched) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("未找到相关结果", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        }
                    }
                }
            }
        }
    }
}
