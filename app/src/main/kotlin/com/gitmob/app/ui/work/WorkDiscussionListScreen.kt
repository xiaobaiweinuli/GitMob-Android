package com.gitmob.app.ui.work

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.UserDiscussionAnswerFilter
import com.gitmob.app.data.model.UserDiscussionRelationFilter
import com.gitmob.app.data.model.UserDiscussionSortFilter
import com.gitmob.app.data.model.UserDiscussionStateFilter
import com.gitmob.app.data.model.UserDiscussionVisibilityFilter
import com.gitmob.app.data.model.WorkDiscussionItem
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.DiscussionStateIcon
import com.gitmob.app.ui.common.FilterCapsuleMenu
import com.gitmob.app.ui.common.ListFeedback
import com.gitmob.app.ui.common.ListFeedbackState
import com.gitmob.app.ui.common.informationItem
import com.gitmob.app.ui.common.pressClickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkDiscussionListScreen(
    onBack: () -> Unit,
    onItemClick: (owner: String, name: String, number: Int) -> Unit,
    viewModel: WorkDiscussionListViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_discussions)) },
                navigationIcon = {
                    ActionIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            DiscussionFilterControls(
                state = state.filter.state,
                relation = state.filter.relation,
                answer = state.filter.answer,
                visibility = state.filter.visibility,
                sort = state.filter.sort,
                totalCount = state.totalCount,
                onStateSelected = viewModel::setStateFilter,
                onRelationSelected = viewModel::setRelationFilter,
                onAnswerSelected = viewModel::setAnswerFilter,
                onVisibilitySelected = viewModel::setVisibilityFilter,
                onSortSelected = viewModel::setSortFilter,
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val feedback = when {
                    state.isLoading && state.items.isEmpty() -> ListFeedbackState.LOADING
                    state.loadFailed && state.items.isEmpty() -> ListFeedbackState.ERROR
                    state.items.isEmpty() && !state.hasNextPage -> ListFeedbackState.EMPTY
                    else -> ListFeedbackState.CONTENT
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp,
                        bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        Box(Modifier.animateItem()) {
                            WorkDiscussionRow(
                                item = item,
                                onClick = { onItemClick(item.repoOwner, item.repoName, item.number) },
                            )
                        }
                    }
                    if (state.hasNextPage && feedback == ListFeedbackState.CONTENT) {
                        item {
                            LaunchedEffect(state.items.size) { viewModel.loadMore() }
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp))
                            }
                        }
                    }
                }
                ListFeedback(feedback, viewModel::retry)
            }
        }
    }
}

@Composable
private fun DiscussionFilterControls(
    state: UserDiscussionStateFilter,
    relation: UserDiscussionRelationFilter,
    answer: UserDiscussionAnswerFilter,
    visibility: UserDiscussionVisibilityFilter,
    sort: UserDiscussionSortFilter,
    totalCount: Int,
    onStateSelected: (UserDiscussionStateFilter) -> Unit,
    onRelationSelected: (UserDiscussionRelationFilter) -> Unit,
    onAnswerSelected: (UserDiscussionAnswerFilter) -> Unit,
    onVisibilitySelected: (UserDiscussionVisibilityFilter) -> Unit,
    onSortSelected: (UserDiscussionSortFilter) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterCapsuleMenu(
                selected = state,
                options = UserDiscussionStateFilter.entries,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = onStateSelected,
                filterLabel = stringResource(R.string.work_filter_state),
                neutralLabel = stringResource(R.string.work_filter_state),
                isNeutral = { it == UserDiscussionStateFilter.ALL },
            )
            FilterCapsuleMenu(
                selected = relation,
                options = UserDiscussionRelationFilter.entries,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = onRelationSelected,
                filterLabel = stringResource(R.string.work_filter_relation),
            )
            FilterCapsuleMenu(
                selected = answer,
                options = UserDiscussionAnswerFilter.entries,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = onAnswerSelected,
                filterLabel = stringResource(R.string.work_filter_answer),
                neutralLabel = stringResource(R.string.work_filter_answer),
                isNeutral = { it == UserDiscussionAnswerFilter.ALL },
            )
            FilterCapsuleMenu(
                selected = visibility,
                options = UserDiscussionVisibilityFilter.entries,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = onVisibilitySelected,
                filterLabel = stringResource(R.string.work_filter_visibility),
                neutralLabel = stringResource(R.string.work_filter_visibility),
                isNeutral = { it == UserDiscussionVisibilityFilter.ALL },
            )
            FilterCapsuleMenu(
                selected = sort,
                options = UserDiscussionSortFilter.entries,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = onSortSelected,
                filterLabel = stringResource(R.string.work_filter_sort),
            )
        }
        Text(
            text = pluralStringResource(R.plurals.work_items_count, totalCount, totalCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        HorizontalDivider()
    }
}

@get:StringRes
private val UserDiscussionStateFilter.labelRes: Int
    get() = when (this) {
        UserDiscussionStateFilter.ALL -> R.string.common_all
        UserDiscussionStateFilter.OPEN -> R.string.work_filter_open
        UserDiscussionStateFilter.CLOSED -> R.string.common_state_closed
    }

@get:StringRes
private val UserDiscussionRelationFilter.labelRes: Int
    get() = when (this) {
        UserDiscussionRelationFilter.INVOLVED -> R.string.work_relation_involved
        UserDiscussionRelationFilter.AUTHORED -> R.string.work_relation_authored
        UserDiscussionRelationFilter.COMMENTED -> R.string.work_relation_commented
    }

@get:StringRes
private val UserDiscussionAnswerFilter.labelRes: Int
    get() = when (this) {
        UserDiscussionAnswerFilter.ALL -> R.string.common_all
        UserDiscussionAnswerFilter.ANSWERED -> R.string.state_answered
        UserDiscussionAnswerFilter.UNANSWERED -> R.string.work_answer_unanswered
    }

@get:StringRes
private val UserDiscussionVisibilityFilter.labelRes: Int
    get() = when (this) {
        UserDiscussionVisibilityFilter.ALL -> R.string.common_all
        UserDiscussionVisibilityFilter.PUBLIC -> R.string.work_visibility_public
        UserDiscussionVisibilityFilter.PRIVATE -> R.string.common_private
        UserDiscussionVisibilityFilter.INTERNAL -> R.string.work_visibility_internal
    }

@get:StringRes
private val UserDiscussionSortFilter.labelRes: Int
    get() = when (this) {
        UserDiscussionSortFilter.CREATED_DESC -> R.string.work_sort_created_desc
        UserDiscussionSortFilter.CREATED_ASC -> R.string.work_sort_created_asc
        UserDiscussionSortFilter.UPDATED_DESC -> R.string.work_sort_updated_desc
        UserDiscussionSortFilter.UPDATED_ASC -> R.string.work_sort_updated_asc
    }

@Composable
internal fun WorkDiscussionRow(
    item: WorkDiscussionItem,
    onClick: () -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .informationItem()
            .pressClickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiscussionStateIcon(
            stateReason = item.stateReason,
            isAnswered = item.isAnswered,
            locked = item.locked,
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Text(
                "${item.repoOwner}/${item.repoName} · #${item.number}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
