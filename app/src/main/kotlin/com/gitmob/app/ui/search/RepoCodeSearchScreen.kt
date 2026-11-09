package com.gitmob.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.model.SearchCategory
import com.gitmob.app.data.model.SearchItem
import com.gitmob.app.data.repository.repositoryCodeSearchInput
import com.gitmob.app.data.repository.repositoryCodeSearchQuery
import com.gitmob.app.data.repository.unquotedSearchTokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RepoCodeSearchScreen(
    owner: String,
    name: String,
    path: String,
    isFork: Boolean,
    onBack: () -> Unit,
    onFile: (SearchItem.Code) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val initialInput = remember(path) { repositoryCodeSearchInput(path) }
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialInput, TextRange(initialInput.length)))
    }
    var submittedInput by rememberSaveable { mutableStateOf(initialInput) }
    var initialFocusRequested by rememberSaveable { mutableStateOf(false) }
    var scopeConflict by rememberSaveable { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val section = state.sections[SearchCategory.CODE] ?: SearchSectionState(loading = submittedInput.isNotEmpty())
    val page = section.page
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val showingResults = submittedInput.isNotEmpty()
    val pathTerms = remember(submittedInput) { repoCodePathTerms(submittedInput) }

    LaunchedEffect(viewModel) {
        viewModel.init(repositoryCodeSearchQuery(submittedInput, owner, name, isFork), SearchCategory.CODE)
    }
    LaunchedEffect(Unit) {
        if (!initialFocusRequested) {
            initialFocusRequested = true
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    var lastAutoRequest by remember { mutableStateOf<String?>(null) }
    val paginationKey = "repo-code-pagination:" + state.generation + ":" + section.completedPages
    LaunchedEffect(listState, paginationKey, section.canLoadMore, showingResults) {
        if (!showingResults || !section.canLoadMore || lastAutoRequest == paginationKey) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == paginationKey } }.first { it }
        lastAutoRequest = paginationKey
        viewModel.loadMore()
    }
    fun submit() {
        if (input.composition != null || input.text.isBlank()) return
        val query = repositoryCodeSearchQuery(input.text, owner, name, isFork)
        scopeConflict = query == null
        if (query == null) return
        submittedInput = input.text.trim()
        keyboard?.hide()
        focusManager.clearFocus()
        viewModel.submit(query, SearchCategory.CODE, recordHistory = false)
        scope.launch { listState.scrollToItem(0) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = input,
                        onValueChange = { input = it; scopeConflict = false },
                        placeholder = { Text(stringResource(R.string.repo_code_search), maxLines = 1) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge,
                        isError = scopeConflict,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        trailingIcon = if (input.text.isNotEmpty()) {{
                            IconButton(onClick = {
                                input = TextFieldValue()
                                submittedInput = ""
                                scopeConflict = false
                                focusRequester.requestFocus()
                                keyboard?.show()
                            }) { Icon(Icons.Default.Close, stringResource(R.string.repos_clear_search)) }
                        }} else null,
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp).focusRequester(focusRequester)
                            .onFocusChanged { focused = it.isFocused }
                            .onPreviewKeyEvent {
                                if (input.composition == null && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                                    if (it.type == KeyEventType.KeyUp) submit()
                                    true
                                } else false
                            },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Column(Modifier.fillMaxSize()) {
                if (scopeConflict) Text(
                    stringResource(R.string.repo_code_search_scope_conflict),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
                if (!showingResults) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(stringResource(R.string.repo_code_search), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.repo_code_search_hint), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        if (page == null && section.loading) item(key = "initial-loading") { SearchPlaceholder() }
                        items(page?.items.orEmpty(), key = { it.key }) { result ->
                            if (result is SearchItem.Code) RepoCodeResult(result, pathTerms) {
                                keyboard?.hide()
                                focusManager.clearFocus()
                                onFile(result)
                            }
                        }
                        if (page != null && page.items.isEmpty()) item(key = "empty") {
                            Text(stringResource(R.string.search_empty), Modifier.padding(vertical = 24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item(key = paginationKey) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                when {
                                    section.error != null -> SearchFailure(section.error) { viewModel.retry(SearchCategory.CODE) }
                                    section.loading && page != null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    page?.limitReached == true -> Text(stringResource(R.string.search_limit, PageSize.SEARCH_ACCESSIBLE_LIMIT),
                                        style = MaterialTheme.typography.bodySmall)
                                    page != null && page.next == null && page.items.isNotEmpty() -> Text(stringResource(R.string.search_end),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (page?.incomplete == true) Text(stringResource(R.string.search_incomplete),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (focused && WindowInsets.isImeVisible) {
                Surface(Modifier.align(Alignment.BottomCenter).imePadding(), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(repoCodeSyntax, key = { it.token }) { syntax ->
                                TextButton(onClick = {
                                    input = insertSyntax(input, syntax, bare = true)
                                    scopeConflict = false
                                    focusRequester.requestFocus()
                                    keyboard?.show()
                                }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(syntax.token, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoCodeResult(item: SearchItem.Code, terms: List<String>, onClick: () -> Unit) {
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val highlightText = MaterialTheme.colorScheme.onTertiaryContainer
    val path = remember(item.path, terms, highlight, highlightText) {
        buildAnnotatedString {
            append(item.path)
            terms.forEach { term ->
                var start = item.path.indexOf(term, ignoreCase = true)
                while (start >= 0) {
                    addStyle(SpanStyle(background = highlight, color = highlightText), start, start + term.length)
                    start = item.path.indexOf(term, start + term.length, ignoreCase = true)
                }
            }
        }
    }
    val label = stringResource(R.string.search_open_file, item.path)
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = label, onClick = onClick)
        .padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(path, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        if (item.snippets.isNotEmpty()) CodeSnippets(item.snippets)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Literal path emphasis only; code highlights always come from the server's snippet ranges. */
internal fun repoCodePathTerms(query: String): List<String> {
    var excluded = false
    return unquotedSearchTokens(query).mapNotNull { token ->
        when {
            token == "NOT" -> { excluded = true; null }
            token == "AND" || token == "OR" -> null
            else -> {
                val include = !excluded && ':' !in token && !token.startsWith('-') && '(' !in token && ')' !in token
                excluded = false
                token.takeIf { include }
            }
        }
    }.distinct()
}
