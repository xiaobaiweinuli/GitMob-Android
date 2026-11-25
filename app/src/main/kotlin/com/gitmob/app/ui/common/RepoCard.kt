package com.gitmob.app.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoCardData
import com.gitmob.app.data.model.RepoListItem
import com.gitmob.app.data.model.cardData

/** Repository summary shared by lists, search, history, and saved groups. */
@Composable
fun RepoCard(
    repo: RepoListItem,
    onClick: () -> Unit,
    onForkSourceClick: (owner: String, name: String) -> Unit,
    onHomepageClick: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    showOwner: Boolean = false,
) {
    RepoCard(repo.cardData(), onClick, onForkSourceClick, onHomepageClick, modifier, showOwner)
}

@Composable
fun RepoCard(
    repo: RepoCardData,
    onClick: () -> Unit,
    onForkSourceClick: (owner: String, name: String) -> Unit,
    onHomepageClick: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    showOwner: Boolean = false,
    titleAction: (@Composable () -> Unit)? = null,
) {
    PressableCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showOwner) "${repo.ownerLogin}/${repo.name}" else repo.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (titleAction != null) 1 else Int.MAX_VALUE,
                    modifier = if (titleAction != null) Modifier.weight(1f).horizontalScroll(rememberScrollState()) else Modifier,
                )
                titleAction?.invoke()
            }
            val forkOwner = repo.forkedFromOwner
            val forkName = repo.forkedFromName
            if (repo.isFork && forkOwner != null && forkName != null) {
                RepoMetadataLinkRow(
                    icon = Icons.AutoMirrored.Default.CallSplit,
                    text = stringResource(R.string.common_forked_from, forkOwner, forkName),
                    contentDescription = stringResource(R.string.common_open_source_repo),
                    onClick = { onForkSourceClick(forkOwner, forkName) },
                )
            }
            repo.description?.takeIf { it.isNotBlank() }?.let {
                GitHubEmojiText(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
            }
            repo.homepageUrl?.takeIf { it.isNotBlank() }?.let { homepageUrl ->
                RepoMetadataLinkRow(
                    icon = Icons.Default.Link,
                    text = homepageUrl,
                    contentDescription = stringResource(R.string.common_open_homepage),
                    onClick = { onHomepageClick(homepageUrl) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        repo.languageName?.let { lang ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                val dotColor = repo.languageColor
                                    ?.let { runCatching { Color(it.toColorInt()) }.getOrNull() }
                                    ?: MaterialTheme.colorScheme.outline
                                Surface(color = dotColor, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
                                Text(lang, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(14.dp))
                            Text("${repo.stargazerCount}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.AutoMirrored.Default.CallSplit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Text("${repo.forkCount}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (repo.openIssueCount > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Adjust, contentDescription = null, modifier = Modifier.size(14.dp))
                                Text("${repo.openIssueCount}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                repo.defaultBranchName?.let {
                    StatusChip(
                        it,
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                }
            }
            if (repo.visibility != "PUBLIC" || repo.isArchived) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (repo.visibility != "PUBLIC") {
                        StatusChip(stringResource(
                            if (repo.visibility == "INTERNAL") R.string.work_visibility_internal else R.string.common_private,
                        ))
                    }
                    if (repo.isArchived) {
                        StatusChip(stringResource(R.string.common_archived))
                    }
                }
            }
            if (repo.topics.isNotEmpty()) {
                RepositoryTopicsRow(topics = repo.topics)
            }
        }
    }
}
