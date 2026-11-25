package com.gitmob.app.ui.repoforks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoForkSort
import com.gitmob.app.data.model.RepoForkSortDirection
import com.gitmob.app.data.model.RepoForkSortField
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.ListFeedback
import com.gitmob.app.ui.common.ListFeedbackState
import com.gitmob.app.ui.common.FilterMenuChip
import com.gitmob.app.ui.common.RepoCard
import com.gitmob.app.ui.common.RepositoryContextTitle
import java.text.NumberFormat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoForksScreen(
    owner: String,
    name: String,
    onBack: () -> Unit,
    onOwnerClick: (String) -> Unit,
    onRepositoryClick: (String, String) -> Unit,
    onForkSourceClick: (String, String) -> Unit,
    onHomepageClick: (String) -> Unit,
    viewModel: RepoForksViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name) { viewModel.init(owner, name) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    RepositoryContextTitle(
                        owner = owner,
                        repository = name,
                        pageTitle = stringResource(R.string.repo_forks),
                        onOwnerClick = onOwnerClick,
                        onRepositoryClick = onRepositoryClick,
                    )
                },
                navigationIcon = {
                    ActionIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    RepoForkSortMenu(state.sort) { sort ->
                        if (sort != state.sort) {
                            viewModel.selectSort(sort)
                            scope.launch { listState.scrollToItem(0) }
                        }
                    }
                }
                val locale = LocalConfiguration.current.locales[0]
                val count = state.totalCount?.let { NumberFormat.getIntegerInstance(locale).format(it) } ?: "—"
                val countDescription = stringResource(R.string.repo_forks_total, count)
                Text(
                    text = count,
                    modifier = Modifier.semantics { contentDescription = countDescription },
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val feedback = when {
                state.isLoading && state.repos.isEmpty() -> ListFeedbackState.LOADING
                state.loadFailed && state.repos.isEmpty() -> ListFeedbackState.ERROR
                state.repos.isEmpty() -> ListFeedbackState.EMPTY
                else -> ListFeedbackState.CONTENT
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp,
                        bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.repos, key = { it.key }) { repo ->
                        RepoCard(
                            repo = repo,
                            modifier = Modifier.animateItem(),
                            onClick = { onRepositoryClick(repo.ownerLogin, repo.name) },
                            onForkSourceClick = onForkSourceClick,
                            onHomepageClick = onHomepageClick,
                            showOwner = true,
                        )
                    }
                    if (feedback == ListFeedbackState.CONTENT && state.hasNextPage) item(key = "load_more") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            if (state.loadFailed) {
                                Button(onClick = viewModel::loadMore, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.common_retry)) }
                            } else {
                                LaunchedEffect(state.endCursor) { viewModel.loadMore() }
                                CircularProgressIndicator(Modifier.size(20.dp))
                            }
                        }
                    }
                }
                ListFeedback(feedback, viewModel::load)
            }
        }
    }
}

@Composable
private fun RepoForkSortMenu(sort: RepoForkSort, onSelect: (RepoForkSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var openedField by remember { mutableStateOf<RepoForkSortField?>(null) }
    val fieldFocus = remember { RepoForkSortField.entries.associateWith { FocusRequester() } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val enterKey = if (rtl) Key.DirectionLeft else Key.DirectionRight
    val exitKey = if (rtl) Key.DirectionRight else Key.DirectionLeft
    val label = stringResource(sort.labelRes)
    val dismiss: () -> Unit = {
        openedField = null
        expanded = false
    }
    Box {
        FilterMenuChip(
            label = label,
            selected = true,
            expanded = expanded,
            selectedText = label,
            filterLabel = stringResource(R.string.work_filter_sort),
            onClick = { expanded = true },
            labelContent = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        )
        DropdownMenuPopup(expanded = expanded, onDismissRequest = dismiss) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(0, 1),
                modifier = Modifier.widthIn(max = 280.dp).verticalScroll(rememberScrollState()),
            ) {
                RepoForkSortField.entries.forEachIndexed { index, field ->
                    Box {
                        val closeSubmenu: () -> Unit = {
                            openedField = null
                        }
                        DropdownMenuItem(
                            onClick = { openedField = field },
                            text = {
                                Text(stringResource(field.labelRes), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = if (field == sort.field) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface)
                            },
                            shape = MenuDefaults.itemShape(index, RepoForkSortField.entries.size).shape,
                            modifier = Modifier.focusRequester(fieldFocus.getValue(field)).onPreviewKeyEvent {
                                if (it.type == KeyEventType.KeyDown && it.key == enterKey) {
                                    openedField = field
                                    true
                                } else false
                            },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.ArrowRight, contentDescription = null,
                                    modifier = Modifier.size(MenuDefaults.TrailingIconSize))
                            },
                        )
                        val directionFocus = remember { RepoForkSortDirection.entries.associateWith { FocusRequester() } }
                        val submenuExpanded = expanded && openedField == field
                        DropdownMenuPopup(
                            expanded = submenuExpanded,
                            onDismissRequest = closeSubmenu,
                            popupPositionProvider = MenuDefaults.rememberDropdownMenuPopupPositionProvider(MenuAnchorPosition.End),
                            modifier = Modifier.onPreviewKeyEvent {
                                if (it.type == KeyEventType.KeyDown && it.key == exitKey) {
                                    closeSubmenu()
                                    true
                                } else false
                            },
                        ) {
                            DropdownMenuGroup(
                                shapes = MenuDefaults.groupShape(0, 1),
                                modifier = Modifier.widthIn(max = 280.dp).verticalScroll(rememberScrollState()).selectableGroup(),
                            ) {
                                RepoForkSortDirection.entries.forEachIndexed { directionIndex, direction ->
                                    val option = RepoForkSort(field, direction)
                                    SelectableDropdownMenuItem(
                                        selected = sort == option,
                                        onClick = { dismiss(); onSelect(option) },
                                        text = { Text(stringResource(option.labelRes), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                        shapes = MenuDefaults.itemShape(directionIndex, RepoForkSortDirection.entries.size),
                                        modifier = Modifier.focusRequester(directionFocus.getValue(direction)),
                                    )
                                }
                            }
                            LaunchedEffect(submenuExpanded) {
                                if (submenuExpanded) {
                                    val direction = if (sort.field == field) sort.direction
                                        else if (field == RepoForkSortField.NAME) RepoForkSortDirection.ASC
                                        else RepoForkSortDirection.DESC
                                    directionFocus.getValue(direction).requestFocus()
                                }
                            }
                        }
                    }
                }
            }
            LaunchedEffect(expanded) {
                if (expanded) fieldFocus.getValue(sort.field).requestFocus()
            }
        }
    }
}

private val RepoForkSortField.labelRes: Int
    get() = when (this) {
        RepoForkSortField.CREATED -> R.string.repo_forks_sort_created
        RepoForkSortField.STARS -> R.string.repo_forks_sort_stars
        RepoForkSortField.NAME -> R.string.repos_filter_name
        RepoForkSortField.UPDATED -> R.string.repo_forks_sort_updated
        RepoForkSortField.PUSHED -> R.string.repo_forks_sort_pushed
    }

private val RepoForkSort.labelRes: Int
    get() = when (field) {
        RepoForkSortField.CREATED -> if (direction == RepoForkSortDirection.DESC)
            R.string.repo_forks_sort_created_desc else R.string.repo_forks_sort_created_asc
        RepoForkSortField.STARS -> if (direction == RepoForkSortDirection.DESC)
            R.string.repo_forks_sort_stars_desc else R.string.repo_forks_sort_stars_asc
        RepoForkSortField.NAME -> if (direction == RepoForkSortDirection.DESC)
            R.string.repo_forks_sort_name_desc else R.string.repo_forks_sort_name_asc
        RepoForkSortField.UPDATED -> if (direction == RepoForkSortDirection.DESC)
            R.string.work_sort_updated_desc else R.string.work_sort_updated_asc
        RepoForkSortField.PUSHED -> if (direction == RepoForkSortDirection.DESC)
            R.string.repo_forks_sort_pushed_desc else R.string.repo_forks_sort_pushed_asc
    }
