package com.gitmob.app.ui.repopullrequests

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.diff.UnifiedDiffParser
import com.gitmob.app.data.model.ExistingRepoPullRequest
import com.gitmob.app.data.model.RepoPullRequestCreateSelection
import com.gitmob.app.data.model.toListItem
import com.gitmob.app.ui.common.GitChangedFileRow
import com.gitmob.app.ui.common.GitCommitRow
import com.gitmob.app.ui.common.UnifiedDiffViewer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoPullRequestCreateScreen(
    owner: String,
    name: String,
    onDismiss: () -> Unit,
    onCreate: (RepoPullRequestCreateSelection) -> Unit,
    onCommitClick: (owner: String, name: String, ref: String, sha: String) -> Unit,
    onExistingPullRequestClick: (owner: String, name: String, number: Int) -> Unit,
    viewModel: RepoPullRequestCreateViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name) { viewModel.init(owner, name) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    fun dismissSheet() {
        viewModel.resetCreateSession()
        onDismiss()
    }
    ModalBottomSheet(
        onDismissRequest = ::dismissSheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().navigationBarsPadding()) {
            when (state.page) {
                RepoPullRequestCreatePage.TARGET -> TargetPage(state, ::dismissSheet, viewModel::selectForkTarget)
                RepoPullRequestCreatePage.COMPARE -> ComparePage(state, viewModel, ::dismissSheet, onCreate = { selection -> viewModel.resetCreateSession(); onCreate(selection) }, onExistingPullRequestClick = onExistingPullRequestClick)
                RepoPullRequestCreatePage.SELECT_BASE -> BranchPage(state, stringResource(R.string.pr_base_branch), viewModel, onBack = viewModel::showCompare)
                RepoPullRequestCreatePage.SELECT_HEAD -> BranchPage(state, stringResource(R.string.pr_head_branch), viewModel, onBack = viewModel::showCompare)
                RepoPullRequestCreatePage.FILES -> FilesPage(state, viewModel::showCompare)
                RepoPullRequestCreatePage.COMMITS -> CommitsPage(
                    state,
                    viewModel::showCompare,
                    viewModel::loadMoreCommits,
                    onCommitClick = { commitOwner, commitName, ref, sha ->
                        viewModel.resetCreateSession()
                        onCommitClick(commitOwner, commitName, ref, sha)
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, onBack: (() -> Unit)? = null, onClose: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } else Spacer(Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.common_cancel)) } else Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun TargetPage(state: RepoPullRequestCreateUiState, onDismiss: () -> Unit, onTarget: (Boolean) -> Unit) {
    SheetHeader(stringResource(R.string.pr_create_target_title), onClose = onDismiss)
    state.currentRepository?.let { repository ->
        Text("${repository.ownerLogin}/${repository.name}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TargetRepositoryCard(stringResource(R.string.pr_create_target_current), stringResource(R.string.pr_create_target_current_description)) { onTarget(false) }
        TargetRepositoryCard(stringResource(R.string.pr_create_target_upstream), stringResource(R.string.pr_create_target_upstream_description)) { onTarget(true) }
    }
}

@Composable
private fun TargetRepositoryCard(title: String, description: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ColumnScope.ComparePage(
    state: RepoPullRequestCreateUiState,
    viewModel: RepoPullRequestCreateViewModel,
    onDismiss: () -> Unit,
    onCreate: (RepoPullRequestCreateSelection) -> Unit,
    onExistingPullRequestClick: (owner: String, name: String, number: Int) -> Unit,
) {
    CompareHeader(state, onDismiss)
    if (state.isLoading || state.isComparing) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Column(Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text(stringResource(R.string.pr_create_select_branches), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
            item { BranchSelectorRow(stringResource(R.string.pr_create_base_label), state.baseBranch?.name ?: "-", viewModel::openBaseBranches) }
            item { BranchSelectorRow(stringResource(R.string.pr_create_head_label), state.headBranch?.name ?: stringResource(R.string.pr_create_select_head), viewModel::openHeadBranches, state.headBranch == null) }
            item { HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp)) }
            when {
                state.sameBranch -> item { CompareEmptyState(R.string.pr_create_same_branch_title, R.string.pr_create_same_branch_description) }
                state.headBranch == null -> item { CompareEmptyState(R.string.pr_create_select_head_hint, R.string.pr_create_select_head_description) }
                state.noCommonAncestor -> item { CompareEmptyState(R.string.pr_create_no_common_ancestor_title, R.string.pr_create_no_common_ancestor_description) }
                state.compareFailed -> item { Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.common_load_failed), Modifier.weight(1f)); TextButton(onClick = viewModel::retryCompare) { Text(stringResource(R.string.common_retry)) } } }
                state.comparison?.aheadBy == 0 -> item { CompareEmptyState(R.string.pr_create_no_ahead_title, R.string.pr_create_no_ahead_description) }
                state.comparison != null -> {
                    val comparison = state.comparison
                    item { Text(stringResource(R.string.pr_create_changes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                    item {
                        CompareSummaryRow(
                            icon = { Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            title = stringResource(R.string.pr_create_files_changed, comparison.files.size, if (comparison.filesTruncated) "+" else ""),
                            onClick = viewModel::showFiles,
                        ) { DiffStats(comparison.additions, comparison.deletions) }
                    }
                    item { HorizontalDivider() }
                    item {
                        CompareSummaryRow(
                            icon = { Icon(Icons.Default.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            title = stringResource(R.string.pr_create_commits_count, comparison.totalCommits),
                            onClick = viewModel::showCommits,
                        ) { Text(comparison.commits.firstOrNull()?.committedDate?.take(10).orEmpty(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (state.existingPullRequest != null) item {
                        ExistingPullRequestCard(
                            state.existingPullRequest,
                            onClick = {
                                state.baseRepository?.let { base ->
                                    viewModel.resetCreateSession()
                                    onExistingPullRequestClick(base.ownerLogin, base.name, state.existingPullRequest.number)
                                }
                            },
                        )
                    }
                }
            }
            // A matching open PR can still be returned when compare has no usable
            // summary. Keep the same list-row presentation, but emit it only once.
            if (state.existingPullRequest != null && state.comparison == null && state.headBranch != null) {
                item {
                    ExistingPullRequestCard(
                        state.existingPullRequest,
                        onClick = {
                            state.baseRepository?.let { base ->
                                viewModel.resetCreateSession()
                                onExistingPullRequestClick(base.ownerLogin, base.name, state.existingPullRequest.number)
                            }
                        },
                    )
                }
            }
        }
        if (state.canCreate && state.existingPullRequest == null) {
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { state.selection?.let(onCreate) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pr_create_submit)) }
                Text(stringResource(R.string.pr_create_compare_footer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun CompareHeader(state: RepoPullRequestCreateUiState, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, stringResource(R.string.common_cancel)) }
        Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
            Text(state.baseRepository?.let { "${it.ownerLogin}/${it.name}" } ?: stringResource(R.string.pr_new), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.pr_create_compare_title), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun BranchSelectorRow(label: String, value: String, onClick: () -> Unit, placeholder: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(top = 6.dp).semantics { contentDescription = "$label: $value" },
        ) {
            Row(Modifier.widthIn(max = 320.dp).padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(value, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = if (placeholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
    }
}

@Composable
private fun CompareEmptyState(titleRes: Int, descriptionRes: Int) {
    Card(Modifier.fillMaxWidth().padding(vertical = 24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(descriptionRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CompareSummaryRow(icon: @Composable () -> Unit, title: String, onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        icon()
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp).weight(1f))
        trailing()
    }
}

@Composable
private fun DiffStats(additions: Int, deletions: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("+$additions", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("-$deletions", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ExistingPullRequestCard(pr: ExistingRepoPullRequest, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
        Text(stringResource(R.string.pr_create_active_pull_requests), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        Text(stringResource(R.string.pr_create_existing_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        PullRequestListItemRow(pr.toListItem(), onClick)
    }
}

@Composable
private fun ColumnScope.BranchPage(state: RepoPullRequestCreateUiState, title: String, viewModel: RepoPullRequestCreateViewModel, onBack: () -> Unit) {
    SheetHeader(title, onBack = onBack)
    OutlinedTextField(state.branchSearch, viewModel::setBranchSearch, label = { Text(stringResource(R.string.pr_create_search_branch)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    if (state.isLoadingBranches) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else if (state.branchLoadFailed) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { TextButton(onClick = viewModel::retryBranches) { Text(stringResource(R.string.common_retry)) } }
    else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(state.filteredBranches, key = { it.id }) { branch ->
            Text(branch.name, Modifier.fillMaxWidth().clickable { viewModel.selectBranch(branch) }.padding(horizontal = 20.dp, vertical = 14.dp))
        }
        if (state.branchesHasNextPage) item { LaunchedEffect(state.filteredBranches.size) { viewModel.loadMoreBranches() }; Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) } }
    }
}

@Composable
private fun ColumnScope.FilesPage(state: RepoPullRequestCreateUiState, onBack: () -> Unit) {
    SheetHeader(stringResource(R.string.pr_create_changed_files), onBack = onBack)
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    LazyColumn(Modifier.weight(1f)) {
        items(state.comparison?.files.orEmpty(), key = { it.filename }) { file ->
            GitChangedFileRow(file, onClick = { expanded[file.filename] = expanded[file.filename] != true })
            if (expanded[file.filename] == true) {
                file.patch?.let { UnifiedDiffViewer(UnifiedDiffParser.parse(it), Modifier.padding(horizontal = 16.dp)) }
                    ?: Text(stringResource(R.string.pr_create_no_text_diff), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ColumnScope.CommitsPage(state: RepoPullRequestCreateUiState, onBack: () -> Unit, onLoadMore: () -> Unit, onCommitClick: (String, String, String, String) -> Unit) {
    SheetHeader(stringResource(R.string.pr_create_compare_commits), onBack = onBack)
    val comparison = state.comparison
    LazyColumn(Modifier.weight(1f)) {
        items(comparison?.commits.orEmpty(), key = { it.oid }) { commit ->
            GitCommitRow(commit, onClick = { comparison?.let { onCommitClick(it.refs.headOwner, it.refs.headRepository, it.refs.headRef, commit.oid) } })
        }
        if (comparison?.commitsHasNextPage == true) item { LaunchedEffect(comparison.commits.size) { onLoadMore() }; CircularProgressIndicator(Modifier.padding(16.dp)) }
    }
}
