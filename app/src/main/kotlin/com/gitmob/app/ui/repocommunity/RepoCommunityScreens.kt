package com.gitmob.app.ui.repocommunity

import com.gitmob.app.ui.common.StatusBadge
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gitmob.app.R
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.ListFeedback
import com.gitmob.app.ui.common.ListFeedbackState
import com.gitmob.app.ui.common.RepositoryContextTitle
import com.gitmob.app.ui.common.SoraTextViewer
import com.gitmob.app.ui.common.rememberSoraViewerState
import com.gitmob.app.ui.common.informationItem
import com.gitmob.app.ui.common.pressClickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoContributorsScreen(
    owner: String, name: String, onBack: () -> Unit, onOwnerClick: (String) -> Unit,
    onRepositoryClick: (String, String) -> Unit, onUserClick: (String) -> Unit,
    viewModel: RepoContributorsViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name) { viewModel.init(owner, name) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { RepositoryContextTitle(owner, name, stringResource(R.string.repo_contributors), onOwnerClick, onRepositoryClick) },
                navigationIcon = { ActionIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val feedback = when {
                state.isLoading && state.items.isEmpty() -> ListFeedbackState.LOADING
                state.loadFailed && state.items.isEmpty() -> ListFeedbackState.ERROR
                state.items.isEmpty() -> ListFeedbackState.EMPTY
                else -> ListFeedbackState.CONTENT
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp,
                    bottom = 8.dp + WindowInsets.navigationBars.union(WindowInsets.captionBar)
                        .asPaddingValues().calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(state.items, key = { index, contributor -> contributor.key ?: "index:$index" }) { _, contributor ->
                    Row(
                        Modifier.animateItem().fillMaxWidth().informationItem()
                            .pressClickable(enabled = contributor.login != null) { contributor.login?.let(onUserClick) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(contributor.avatarUrl, null, Modifier.size(44.dp).clip(CircleShape))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(contributor.displayName ?: stringResource(R.string.common_deleted_user), fontWeight = FontWeight.SemiBold)
                            contributor.type?.let { Text(contributorTypeLabel(it), style = MaterialTheme.typography.bodySmall) }
                        }
                        Text(pluralStringResource(R.plurals.contributors_count, contributor.contributions, contributor.contributions))
                    }
                }
                if (feedback == ListFeedbackState.CONTENT && state.hasNextPage) item(key = "more") {
                    LaunchedEffect(state.items.size) { viewModel.loadMore() }
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) }
                }

            }
            ListFeedback(feedback, viewModel::load, emptyMessage = stringResource(R.string.contributors_empty))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoLicenseScreen(
    owner: String,
    name: String,
    ref: String,
    onBack: () -> Unit,
    onOwnerClick: (String) -> Unit,
    onRepositoryClick: (String, String) -> Unit,
    viewModel: RepoLicenseViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, ref) { viewModel.init(owner, name, ref) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val document = state.document
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    RepositoryContextTitle(owner, name, document?.name ?: stringResource(R.string.repo_license), onOwnerClick, onRepositoryClick)
                },
                navigationIcon = {
                    ActionIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    document?.let { doc ->
                        ActionIconButton(onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText(doc.name, doc.content))
                        }) {
                            Icon(Icons.Default.ContentCopy, stringResource(R.string.license_copy))
                        }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.loadFailed -> CommunityRetry(viewModel::load)
                document != null -> Column(Modifier.fillMaxSize()) {
                    document.spdxId?.let { identifier ->
                        StatusBadge(
                            label = { Text(identifier) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    SoraTextViewer(
                        text = document.content,
                        wrapLines = true,
                        state = rememberSoraViewerState("license:$owner/$name/$ref/${document.path}"),
                        showLineNumbers = false,
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable private fun CommunityRetry(retry: () -> Unit) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = retry, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.common_retry)) } } }
@Composable private fun contributorTypeLabel(value: String): String = when (value) { "User" -> stringResource(R.string.contributors_type_user); "Bot" -> stringResource(R.string.contributors_type_bot); "Organization" -> stringResource(R.string.contributors_type_organization); "Anonymous" -> stringResource(R.string.contributors_type_anonymous); else -> value }
