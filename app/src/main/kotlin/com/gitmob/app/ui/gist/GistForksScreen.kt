package com.gitmob.app.ui.gist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gitmob.app.R
import com.gitmob.app.ui.common.ListFeedback
import com.gitmob.app.ui.common.formatDateTime
import com.gitmob.app.ui.common.ListFeedbackState
import com.gitmob.app.ui.common.informationItem
import com.gitmob.app.ui.common.pressClickable

@Composable
fun GistForksScreen(
    gistId: String,
    onBack: () -> Unit,
    onUserClick: (String) -> Unit,
    viewModel: GistForksViewModel = hiltViewModel(),
) {
    LaunchedEffect(gistId) { viewModel.init(gistId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { GistTopBar(R.string.gist_forks, onBack) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            val feedback = when {
                state.isLoading && state.items.isEmpty() -> ListFeedbackState.LOADING
                state.loadFailed && state.items.isEmpty() -> ListFeedbackState.ERROR
                state.items.isEmpty() -> ListFeedbackState.EMPTY
                else -> ListFeedbackState.CONTENT
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, fork ->
                    Row(
                        Modifier.animateItem().fillMaxWidth().informationItem().pressClickable(enabled = fork.owner != null) { fork.owner?.login?.let(onUserClick) }.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(fork.owner?.avatarUrl, fork.owner?.login, Modifier.size(40.dp))
                        Column {
                            Text(fork.owner?.login ?: stringResource(R.string.common_unknown))
                            Text(formatDateTime(fork.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (index == state.items.lastIndex && state.nextPage != null) LaunchedEffect(state.items.size) { viewModel.loadMore() }
                }
                if (state.isLoadingMore) item("loading-more") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
            }
            ListFeedback(feedback, viewModel::load, emptyMessage = stringResource(R.string.gist_no_forks))
        }
    }
}
