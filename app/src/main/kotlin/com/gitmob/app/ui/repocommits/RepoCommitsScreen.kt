package com.gitmob.app.ui.repocommits

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.RepoContentSource
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.GitCommitRow
import com.gitmob.app.ui.common.ListFeedback
import com.gitmob.app.ui.common.ListFeedbackState
import com.gitmob.app.ui.common.RepositoryContextTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoCommitsScreen(
    owner: String,
    name: String,
    source: RepoContentSource,
    permission: RepoPermission?,
    onBack: () -> Unit,
    onOwnerClick: (String) -> Unit,
    onRepositoryClick: (String, String) -> Unit,
    onCommitClick: (String, String) -> Unit,
    onUserClick: (String) -> Unit,
    viewModel: RepoCommitsViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, source) { viewModel.init(owner, name, source, permission) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    RepositoryContextTitle(
                        owner = owner,
                        repository = name,
                        pageTitle = state.target?.let { stringResource(R.string.nav_commit_history, it.ref) }
                            ?: stringResource(R.string.repo_commits),
                        onOwnerClick = onOwnerClick,
                        onRepositoryClick = onRepositoryClick,
                    )
                },
                navigationIcon = { ActionIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isLoading && state.items.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val feedback = when {
                state.isLoading && state.items.isEmpty() -> ListFeedbackState.LOADING
                state.loadFailed && state.items.isEmpty() -> ListFeedbackState.ERROR
                state.items.isEmpty() -> ListFeedbackState.EMPTY
                else -> ListFeedbackState.CONTENT
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
                items(state.items, key = { it.oid }) { commit ->
                    GitCommitRow(
                        commit,
                        { state.target?.let { onCommitClick(commit.oid, it.ref) } },
                        modifier = Modifier.animateItem(),
                        onAuthorClick = onUserClick,
                    )
                }
                if (feedback == ListFeedbackState.CONTENT && state.hasNextPage) item(key = "more") {
                    LaunchedEffect(state.items.size) { viewModel.loadMore() }
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }

            }
            ListFeedback(feedback, viewModel::retry)
        }
    }
}
