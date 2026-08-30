package com.gitmob.app.ui.repocommunity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gitmob.app.R
import com.gitmob.app.ui.common.RepositoryContextTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RepoContributorsScreen(owner: String, name: String, onBack: () -> Unit, onOwnerClick: (String) -> Unit, onRepositoryClick: (String, String) -> Unit, onUserClick: (String) -> Unit, viewModel: RepoContributorsViewModel = hiltViewModel()) { LaunchedEffect(owner, name) { viewModel.init(owner, name) }; val state by viewModel.state.collectAsStateWithLifecycle(); Scaffold(topBar = { TopAppBar(title = { RepositoryContextTitle(owner, name, stringResource(R.string.repo_contributors), onOwnerClick, onRepositoryClick) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) }, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) { padding -> when { state.isLoading && state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; state.loadFailed -> CommunityRetry(viewModel::load); state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.contributors_empty)) }; else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) { items(state.items, key = { "${it.login}-${it.profileUrl}" }) { contributor -> Row(Modifier.fillMaxWidth().clickable(enabled = contributor.login != null) { contributor.login?.let(onUserClick) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(contributor.avatarUrl, null, Modifier.size(44.dp).clip(CircleShape)); Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(contributor.login ?: stringResource(R.string.common_deleted_user), fontWeight = FontWeight.SemiBold); contributor.type?.let { Text(contributorTypeLabel(it), style = MaterialTheme.typography.bodySmall) } }; Text(stringResource(R.string.contributors_count, contributor.contributions)) }; HorizontalDivider() }; if (state.hasNextPage) item { LaunchedEffect(state.items.size) { viewModel.loadMore() }; Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) } } } } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RepoLicenseScreen(owner: String, name: String, ref: String, onBack: () -> Unit, onOwnerClick: (String) -> Unit, onRepositoryClick: (String, String) -> Unit, viewModel: RepoLicenseViewModel = hiltViewModel()) { LaunchedEffect(owner, name, ref) { viewModel.init(owner, name, ref) }; val state by viewModel.state.collectAsStateWithLifecycle(); val context = LocalContext.current; Scaffold(topBar = { TopAppBar(title = { RepositoryContextTitle(owner, name, state.document?.name ?: stringResource(R.string.repo_license), onOwnerClick, onRepositoryClick) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, actions = { state.document?.let { doc -> IconButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(doc.name, doc.content)) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.license_copy)) } } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) }, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) { padding -> when { state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; state.loadFailed -> CommunityRetry(viewModel::load); state.document != null -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) { item { state.document!!.spdxId?.let { AssistChip(onClick = {}, label = { Text(it) }) }; SelectionContainer { Text(state.document!!.content, style = MaterialTheme.typography.bodyMedium) } } } } } }

@Composable private fun CommunityRetry(retry: () -> Unit) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = retry) { Text(stringResource(R.string.common_retry)) } } }
@Composable private fun contributorTypeLabel(value: String): String = when (value) { "User" -> stringResource(R.string.contributors_type_user); "Bot" -> stringResource(R.string.contributors_type_bot); "Organization" -> stringResource(R.string.contributors_type_organization); else -> value }
