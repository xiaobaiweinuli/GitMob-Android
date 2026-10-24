package com.gitmob.app.ui.gist

import androidx.compose.foundation.clickable
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
        when {
            state.isLoading && state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed && state.items.isEmpty() -> LoadFailed(Modifier.fillMaxSize().padding(innerPadding), viewModel::load)
            state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.gist_no_forks)) }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, fork ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = fork.owner != null) { fork.owner?.login?.let(onUserClick) }.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(fork.owner?.avatarUrl, fork.owner?.login, Modifier.size(40.dp))
                        Column {
                            Text(fork.owner?.login ?: stringResource(R.string.common_unknown))
                            Text(fork.createdAt.take(19), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (index == state.items.lastIndex && state.nextPage != null) LaunchedEffect(state.items.size) { viewModel.loadMore() }
                }
                if (state.isLoadingMore) item("loading-more") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
            }
        }
    }
}
