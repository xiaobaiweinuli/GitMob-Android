package com.gitmob.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.model.SearchCategory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onCategory: (String, SearchCategory) -> Unit,
    actions: SearchResultActions,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var showingResults by rememberSaveable { mutableStateOf(false) }
    var initialFocusRequested by rememberSaveable { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) { viewModel.init() }
    LaunchedEffect(Unit) {
        if (!initialFocusRequested) {
            initialFocusRequested = true
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    fun submit() {
        if (input.text.isBlank()) return
        keyboard?.hide()
        focusManager.clearFocus()
        showingResults = true
        viewModel.submit(input.text)
        scope.launch { listState.scrollToItem(0) }
    }
    fun openCategory(query: String, category: SearchCategory, record: Boolean) {
        if (query.isBlank()) return
        keyboard?.hide()
        focusManager.clearFocus()
        if (record) viewModel.recordQuery(query)
        onCategory(query.trim(), category)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = input,
                        onValueChange = {
                            if (it.text != input.text) showingResults = false
                            input = it
                        },
                        placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        trailingIcon = if (input.text.isNotEmpty()) {{
                            IconButton(onClick = { input = TextFieldValue(); showingResults = false; focusRequester.requestFocus(); keyboard?.show() }) {
                                Icon(Icons.Default.Close, stringResource(R.string.repos_clear_search))
                            }
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
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                if (showingResults && state.submittedQuery.isNotEmpty()) {
                    SearchCategory.entries.forEach { category ->
                        val section = state.sections[category] ?: SearchSectionState(loading = true)
                        item(key = "heading:$category") {
                            Box(Modifier.padding(horizontal = 16.dp)) { SearchSectionHeading(category, section.page?.totalCount) }
                        }
                        val page = section.page
                        if (page == null && section.loading) item(key = "loading:$category") {
                            Box(Modifier.padding(horizontal = 16.dp)) { SearchPlaceholder() }
                        }
                        items(page?.items.orEmpty(), key = { "$category:" + it.key }) { result ->
                            Box(Modifier.padding(horizontal = 16.dp)) { SearchResult(result, actions) }
                        }
                        item(key = "footer:$category") {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                section.error?.let { SearchFailure(it) { viewModel.retry(category) } }
                                if (page != null) {
                                    if (page.items.isEmpty()) Text(stringResource(R.string.search_empty), Modifier.padding(vertical = 12.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (page.incomplete) Text(stringResource(R.string.search_incomplete), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val remaining = (page.totalCount - page.items.size).coerceAtLeast(0)
                                    if (remaining > 0 || page.next != null || page.incomplete) TextButton(
                                        onClick = { openCategory(state.submittedQuery, category, record = false) }, modifier = Modifier.fillMaxWidth(),
                                    ) { Text(if (page.incomplete) stringResource(R.string.search_more_unknown) else stringResource(R.string.search_more, remaining)) }
                                }
                                HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                } else {
                    if (input.text.isNotBlank()) {
                        items(SearchCategory.entries, key = { it }) { category ->
                            SearchCategoryRow(category, input.text) { openCategory(input.text, category, record = true) }
                        }
                        item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                    }
                    item(key = "history-heading") {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.search_recent), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (state.history.isNotEmpty()) TextButton(onClick = { confirmClear = true }, enabled = !state.clearingHistory) {
                                Text(stringResource(R.string.search_clear))
                            }
                        }
                    }
                    if (state.historyFailed) item {
                        TextButton(onClick = viewModel::observeHistory, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.common_retry)) }
                    } else if (state.history.isEmpty()) item {
                        Text(stringResource(R.string.search_no_history), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val visibleHistory = if (input.text.isBlank()) state.history else state.history.take(PageSize.SEARCH_RECENT_PREVIEW)
                    items(visibleHistory, key = { "history:$it" }) { query ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable {
                            input = TextFieldValue(query, TextRange(query.length)); showingResults = false
                            focusRequester.requestFocus(); keyboard?.show()
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(query, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.AutoMirrored.Filled.CallMade, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (focused && WindowInsets.isImeVisible) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(syntaxSuggestions(input), key = { it.token }) { syntax ->
                        val separator = stringResource(R.string.search_list_separator)
                        val categories = syntax.categories.map { stringResource(it.titleRes) }.joinToString(separator)
                        val description = stringResource(R.string.search_syntax_description, syntax.token, categories)
                        Surface(onClick = { input = insertSyntax(input, syntax); showingResults = false },
                            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description }) {
                            Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                Text(syntax.token, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text(stringResource(R.string.search_clear_history)) },
        text = { Text(stringResource(R.string.search_clear_history_body)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; viewModel.clearHistory() }) { Text(stringResource(R.string.search_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
}
