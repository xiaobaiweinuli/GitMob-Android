package com.gitmob.app.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.HistoryItem
import com.gitmob.app.data.model.LibraryContent
import com.gitmob.app.ui.common.GistCard
import com.gitmob.app.ui.common.RepoCard
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(accountLibrary: AccountLibraryViewModel, onBack: () -> Unit, onRepo: (String, String) -> Unit, onHomepage: (String) -> Unit, onGist: (String) -> Unit, viewModel: LibraryViewModel = hiltViewModel()) {
    LaunchedEffect(viewModel) { viewModel.show(LibraryContent.History) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val settings by accountLibrary.settings.collectAsStateWithLifecycle()
    val pending by accountLibrary.pendingSettings.collectAsStateWithLifecycle()
    val clearing = LibrarySettingOperation.Clear in pending
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var remove by remember { mutableStateOf<HistoryItem?>(null) }
    var clear by remember { mutableStateOf(false) }
    LibraryScaffold(stringResource(R.string.history_title), onBack, actions = {
        IconButton(onClick = { clear = true }, enabled = settings.value?.historyEnabled == true && state.history.isNotEmpty() && !busy && !clearing) {
            Icon(Icons.Default.Delete, stringResource(R.string.history_clear), tint = MaterialTheme.colorScheme.error)
        }
    }) { padding ->
        LibraryLoading(settings.value != null && (settings.value?.historyEnabled == false || state.loaded), failed || settings.failed, { viewModel.load(); accountLibrary.retrySettings() }, Modifier.padding(padding)) {
            when {
                settings.value?.historyEnabled == false -> LibraryEmpty(stringResource(R.string.library_disabled))
                state.history.isEmpty() -> LibraryEmpty(stringResource(R.string.library_no_history))
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.history, key = { it.key }) { item ->
                        Column(Modifier.animateItem()) {
                            item.visitedAt.let { timestamp ->
                                Text(remember(timestamp) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp)) },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
                            }
                            val deleteAction: @Composable () -> Unit = {
                                IconButton(onClick = { remove = item }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.history_delete_description, item.title), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            when (item) {
                                is HistoryItem.Repository -> RepoCard(item.card,
                                    onClick = { onRepo(item.card.ownerLogin, item.card.name) }, onForkSourceClick = onRepo,
                                    onHomepageClick = onHomepage, showOwner = true, titleAction = deleteAction)
                                is HistoryItem.Gist -> GistCard(item.gist, onClick = { onGist(item.id) },
                                    onForkSourceClick = onGist, titleAction = deleteAction)
                            }

                        }
                    }
                }
            }
        }
    }
    remove?.let { item -> LibraryConfirm(stringResource(R.string.history_delete), stringResource(R.string.history_delete_confirm, item.title), busy,
        { remove = null }, { viewModel.deleteVisit(item) { remove = null } }) }
    if (clear) LibraryConfirm(stringResource(R.string.history_clear), stringResource(R.string.history_clear_confirm), clearing, { clear = false }, { accountLibrary.clearHistory { clear = false } })
}
