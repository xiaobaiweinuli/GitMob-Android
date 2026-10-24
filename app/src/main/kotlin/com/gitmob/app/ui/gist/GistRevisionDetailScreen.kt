package com.gitmob.app.ui.gist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.GistDiffUnavailableReason
import com.gitmob.app.ui.common.UnifiedDiffViewer

@Composable
fun GistRevisionDetailScreen(
    gistId: String,
    version: String,
    previousVersion: String?,
    committedAt: String,
    additions: Int,
    deletions: Int,
    totalChanges: Int,
    onBack: () -> Unit,
    viewModel: GistRevisionDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(gistId, version, previousVersion, committedAt, additions, deletions, totalChanges) {
        viewModel.init(gistId, version, previousVersion, committedAt, additions, deletions, totalChanges)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { GistTopBar(R.string.gist_revision_detail, onBack) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    ) { innerPadding ->
        when {
            state.isLoading && state.comparison == null -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed && state.comparison == null -> LoadFailed(Modifier.fillMaxSize().padding(innerPadding), viewModel::load)
            else -> state.comparison?.let { comparison ->
                LazyColumn(
                    Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("summary") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(comparison.revision.version, fontWeight = FontWeight.SemiBold)
                            Text(comparison.revision.committedAt.take(19), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.gist_revision_changes, comparison.revision.additions, comparison.revision.deletions, comparison.revision.totalChanges))
                        }
                    }
                    if (comparison.previousVersion == null) item("no-previous") {
                        Text(stringResource(R.string.gist_revision_no_previous), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (comparison.files.isEmpty()) item("unchanged") {
                        Text(stringResource(R.string.gist_revision_no_diff), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    comparison.files.forEach { file ->
                        item("file-${file.filename}") {
                            Column(Modifier.fillMaxWidth()) {
                                Text(file.filename, Modifier.padding(vertical = 8.dp), fontWeight = FontWeight.SemiBold)
                                val reason = file.unavailableReason
                                if (reason != null) Text(
                                    stringResource(reason.labelRes),
                                    Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ) else UnifiedDiffViewer(file.diff)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val GistDiffUnavailableReason.labelRes: Int
    get() = when (this) {
        GistDiffUnavailableReason.BINARY -> R.string.gist_diff_binary
        GistDiffUnavailableReason.TRUNCATED -> R.string.gist_diff_truncated
        GistDiffUnavailableReason.MISSING_CONTENT -> R.string.gist_diff_missing_content
    }
