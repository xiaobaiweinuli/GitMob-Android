package com.gitmob.app.ui.search

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.displayMessageRes
import com.gitmob.app.data.model.*
import com.gitmob.app.navigation.tryOpenBrowser
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.IssueStateIcon
import com.gitmob.app.ui.common.PullRequestStateIcon
import com.gitmob.app.ui.common.RepoCard
import com.gitmob.app.ui.common.informationItem
import com.gitmob.app.ui.common.issueStateVisual
import com.gitmob.app.ui.common.pressClickable
import com.gitmob.app.ui.common.pullRequestStateVisual
import com.gitmob.app.ui.icons.Octicon
import com.gitmob.app.ui.icons.OcticonName
import com.gitmob.app.ui.work.WorkDiscussionRow
import java.text.NumberFormat
import java.time.Instant

class SearchResultActions(
    val repository: (String, String) -> Unit,
    val work: (WorkIssueItem) -> Unit,
    val account: (String) -> Unit,
    val file: (SearchItem.Code) -> Unit,
    val homepage: (String) -> Unit,
    val discussion: (WorkDiscussionItem) -> Unit,
)

internal val SearchCategory.titleRes: Int
    @StringRes get() = when (this) {
        SearchCategory.CODE -> R.string.search_code
        SearchCategory.REPOSITORIES -> R.string.search_repositories
        SearchCategory.ISSUES -> R.string.search_issues
        SearchCategory.PULL_REQUESTS -> R.string.search_pull_requests
        SearchCategory.DISCUSSIONS -> R.string.common_discussions
        SearchCategory.USERS -> R.string.search_users
        SearchCategory.ORGANIZATIONS -> R.string.search_organizations
    }
internal val SearchCategory.icon: OcticonName get() = when (this) {
    SearchCategory.CODE -> OcticonName.CODE
    SearchCategory.REPOSITORIES -> OcticonName.REPO
    SearchCategory.ISSUES -> OcticonName.ISSUE_OPENED
    SearchCategory.PULL_REQUESTS -> OcticonName.PULL_REQUEST_OPENED
    SearchCategory.DISCUSSIONS -> OcticonName.DISCUSSION_OPENED
    SearchCategory.USERS -> OcticonName.PERSON
    SearchCategory.ORGANIZATIONS -> OcticonName.ORGANIZATION
}

@Composable
internal fun SearchCategoryRow(category: SearchCategory, query: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressClickable(onClick = onClick).heightIn(min = 62.dp)
        .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Octicon(category.icon, null, size = 24.dp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(category.titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.search_for_query, query), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SearchSectionHeading(category: SearchCategory, count: Int?) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Octicon(category.icon, null, size = 20.dp)
        Text(stringResource(category.titleRes), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        count?.let { Text(NumberFormat.getIntegerInstance().format(it), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun SearchResult(item: SearchItem, actions: SearchResultActions) {
    when (item) {
        is SearchItem.Repository -> RepoCard(item.repo,
            onClick = { actions.repository(item.repo.ownerLogin, item.repo.name) },
            onForkSourceClick = actions.repository, onHomepageClick = actions.homepage,
            showOwner = true, modifier = Modifier.padding(bottom = 10.dp))
        is SearchItem.Code -> CodeResult(item, actions)
        is SearchItem.Work -> WorkResult(item.item) { actions.work(item.item) }
        is SearchItem.Account -> AccountResult(item) { actions.account(item.login) }
        is SearchItem.Discussion -> {
            Box(Modifier.padding(bottom = 10.dp)) {
                WorkDiscussionRow(item.item, onClick = { actions.discussion(item.item) })
            }
        }
    }
}

@Composable
private fun CodeResult(item: SearchItem.Code, actions: SearchResultActions) {
    val repoLabel = stringResource(R.string.search_open_repo, item.owner + "/" + item.repository)
    val fileLabel = stringResource(R.string.search_open_file, item.path)
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).informationItem().padding(16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .pressClickable(onClickLabel = repoLabel) { actions.repository(item.owner, item.repository) },
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Octicon(OcticonName.REPO, null)
            Text(item.owner + " / " + item.repository, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressClickable(onClickLabel = fileLabel) { actions.file(item) },
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.name, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (item.snippets.isNotEmpty()) CodeSnippets(item.snippets)
            if (item.path != item.name) {
                Text(item.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
internal fun CodeSnippets(snippets: List<SearchSnippet>) {
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val highlightText = MaterialTheme.colorScheme.onTertiaryContainer
    val codeStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer,
        MaterialTheme.shapes.medium).padding(12.dp)) {
        snippets.forEach { snippet ->
            val text = remember(snippet, highlight, highlightText) {
                buildAnnotatedString {
                    append(snippet.text)
                    snippet.matches.forEach {
                        addStyle(SpanStyle(background = highlight, color = highlightText), it.start, it.end)
                    }
                }
            }
            Text(text, Modifier.fillMaxWidth(), style = codeStyle)
        }
    }
}

@Composable
private fun WorkResult(item: WorkIssueItem, onClick: () -> Unit) {
    val visual = if (item.isPullRequest) pullRequestStateVisual(requireNotNull(item.pullRequestState), item.isDraft, item.locked)
        else issueStateVisual(requireNotNull(item.issueState), item.issueStateReason, item.locked)
    val updated = remember(item.updatedAt) { runCatching { Instant.parse(item.updatedAt).toEpochMilli() }.getOrNull() }
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).informationItem().pressClickable(onClick = onClick).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (item.isPullRequest) PullRequestStateIcon(requireNotNull(item.pullRequestState), item.isDraft, item.locked, size = 20.dp)
        else IssueStateIcon(requireNotNull(item.issueState), item.issueStateReason, item.locked, size = 20.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(item.repoOwner + "/" + item.repoName + " #" + item.number,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val stateLabel = stringResource(visual.labelRes)
            val relative = updated?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() }
            Text(if (relative == null) stateLabel else stateLabel + " · " + stringResource(R.string.search_updated, relative),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AccountResult(item: SearchItem.Account, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).informationItem().pressClickable(onClick = onClick).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        AsyncImage(item.avatarUrl, null, Modifier.size(44.dp).clip(CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name?.takeIf(String::isNotBlank) ?: item.login, style = MaterialTheme.typography.titleSmall)
            Text("@" + item.login, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            item.location?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun SearchPlaceholder() {
    val label = stringResource(R.string.search_loading)
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp).semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(0.65f, 1f, 0.8f).forEach { fraction ->
            Spacer(Modifier.fillMaxWidth(fraction).height(14.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
internal fun SearchFailure(error: ApiError, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(error.displayMessageRes()), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (error !is ApiError.UserVisible) TextButton(onClick = retry, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_retry)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodeSearchWebButton(query: String, viewModel: SearchViewModel, owner: String? = null, name: String? = null) {
    val activity = LocalActivity.current
    val label = stringResource(R.string.code_search_on_web)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        ActionIconButton(onClick = {
            val url = Uri.Builder().scheme("https").authority("github.com").apply {
                if (owner != null && name != null) appendPath(owner).appendPath(name)
            }.appendPath("search").appendQueryParameter("q", query).appendQueryParameter("type", "code").build()
            if (activity?.tryOpenBrowser(url) != true) viewModel.browserUnavailable()
        }) {
            Icon(Icons.Default.Language, contentDescription = label)
        }
    }
}
