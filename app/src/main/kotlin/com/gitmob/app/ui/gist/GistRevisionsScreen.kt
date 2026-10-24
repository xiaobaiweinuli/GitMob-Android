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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.GistRevision

@Composable
fun GistRevisionsScreen(
    gistId: String,
    onBack: () -> Unit,
    onRevisionClick: (revision: GistRevision, previousVersion: String?) -> Unit,
    onUserClick: (String) -> Unit,
    viewModel: GistRevisionsViewModel = hiltViewModel(),
) {
    LaunchedEffect(gistId) { viewModel.init(gistId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { GistTopBar(R.string.gist_revisions, onBack) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    ) { innerPadding ->
        when {
            state.isLoading && state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed && state.items.isEmpty() -> LoadFailed(Modifier.fillMaxSize().padding(innerPadding), viewModel::load)
            state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.gist_no_revisions)) }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                itemsIndexed(state.items, key = { _, item -> item.version }) { index, revision ->
                    val previous = state.items.getOrNull(index + 1)?.version
                    val canOpen = previous != null || (index == state.items.lastIndex && state.nextPage == null)
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            if (canOpen) onRevisionClick(revision, previous) else viewModel.loadMore()
                        }.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(revision.version.take(10), fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                revision.user?.login ?: stringResource(R.string.common_unknown),
                                modifier = revision.user?.login?.let { login -> Modifier.clickable { onUserClick(login) } } ?: Modifier,
                            )
                            Text(revision.committedAt.take(19), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            stringResource(R.string.gist_revision_changes, revision.additions, revision.deletions, revision.totalChanges),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    if (index == state.items.lastIndex && state.nextPage != null) LaunchedEffect(state.items.size) { viewModel.loadMore() }
                }
                if (state.isLoadingMore) item("loading-more") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun GistTopBar(titleRes: Int, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(titleRes)) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    )
}
