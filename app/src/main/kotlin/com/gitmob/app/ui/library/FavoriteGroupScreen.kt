package com.gitmob.app.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.FavoriteItem
import com.gitmob.app.ui.common.GistCard
import com.gitmob.app.ui.common.RepoCard
import kotlinx.coroutines.launch

@Composable
fun FavoriteGroupScreen(
    accountLibrary: AccountLibraryViewModel, groupId: String, onBack: () -> Unit,
    onRepo: (String, String) -> Unit, onHomepage: (String) -> Unit, onGist: (String) -> Unit,
    initialName: String = "",
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel, groupId) { viewModel.show(com.gitmob.app.data.model.LibraryContent.Group(groupId)) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val settings by accountLibrary.settings.collectAsStateWithLifecycle()
    val groupsState by accountLibrary.groups.collectAsStateWithLifecycle()
    val group = groupsState.value?.firstOrNull { it.id == groupId }
    val favorites = state.favorites.filter { it.groupId == groupId }
    var editing by rememberSaveable { mutableStateOf(false) }
    var move by remember { mutableStateOf<FavoriteItem?>(null) }
    var remove by remember { mutableStateOf<FavoriteItem?>(null) }
    BackHandler(editing) { editing = false }
    LibraryScaffold(group?.let { groupLabel(it) } ?: if (groupId == com.gitmob.app.data.model.DEFAULT_FAVORITE_GROUP) stringResource(R.string.favorites_default_group) else initialName, onBack, actions = {
        TextButton(onClick = { editing = !editing }, enabled = group != null && favorites.isNotEmpty() && !busy) {
            Text(stringResource(if (editing) R.string.library_done else R.string.library_edit))
        }
    }) { padding ->
        LibraryLoading(settings.value != null && (settings.value?.favoritesEnabled == false || (state.loaded && groupsState.value != null)),
            failed || settings.failed || groupsState.failed, { viewModel.load(); accountLibrary.retrySettings(); accountLibrary.retryGroups() }, Modifier.padding(padding)) {
            when {
                settings.value?.favoritesEnabled == false -> LibraryEmpty(stringResource(R.string.library_disabled))
                group == null -> LibraryEmpty(stringResource(R.string.library_group_missing))
                favorites.isEmpty() -> LibraryEmpty(stringResource(R.string.library_no_favorites))
                else -> LibraryReorderList(favorites, { it.key }, { true }, editing, busy,
                    Modifier.fillMaxSize(), { ids, done -> viewModel.reorderFavorites(groupId, ids, done) },
                ) { item, modifier ->
                    FavoriteSwipeRow(editing, !busy, { remove = item }, modifier) {
                        val moveAction: (@Composable () -> Unit)? = if (editing) ({
                            TextButton(onClick = { move = item }, enabled = !busy) { Text(stringResource(R.string.favorites_move)) }
                        }) else null
                        when (item) {
                            is FavoriteItem.Repository -> RepoCard(item.card,
                                onClick = { if (!editing) onRepo(item.card.ownerLogin, item.card.name) },
                                onForkSourceClick = { owner, name -> if (!editing) onRepo(owner, name) },
                                onHomepageClick = { if (!editing) onHomepage(it) }, showOwner = true, titleAction = moveAction,
                            )
                            is FavoriteItem.Gist -> GistCard(item.gist,
                                onClick = { if (!editing) onGist(item.id) },
                                onForkSourceClick = { if (!editing) onGist(it) }, titleAction = moveAction,
                            )
                        }
                    }
                }
            }
        }
    }
    move?.takeIf { state.favorites.any { favorite -> favorite.key == it.key } }?.let { item ->
        FavoriteSheet(item, item, groupsState.value, busy, { move = null }, viewModel::createGroup, viewModel::saveFavorite, viewModel::removeFavorite, moveOnly = true, groupsFailed = groupsState.failed, retryGroups = accountLibrary::retryGroups)
    }
    remove?.let { item -> LibraryConfirm(stringResource(R.string.favorites_remove),
        stringResource(R.string.favorites_remove_confirm, item.title), busy, { remove = null },
        { viewModel.removeFavorite(item) { remove = null } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoriteSwipeRow(editing: Boolean, enabled: Boolean, remove: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    if (editing) {
        Box(modifier) { content() }
        return
    }
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(state = swipe, modifier = modifier.clip(CardDefaults.shape),
        enableDismissFromStartToEnd = false, enableDismissFromEndToStart = enabled && !editing,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && enabled && !editing) remove()
            scope.launch { swipe.reset() }
        },
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Default.Delete, stringResource(R.string.favorites_remove), tint = MaterialTheme.colorScheme.onError)
            }
        },
    ) { content() }
}
