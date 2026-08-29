package com.gitmob.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gitmob.app.R
import com.gitmob.app.core.diff.DiffLineType
import com.gitmob.app.core.diff.UnifiedDiff
import com.gitmob.app.data.model.RepoChangedFile
import com.gitmob.app.data.model.RepoCommitSummary
import coil3.compose.AsyncImage

@Composable
fun GitCommitRow(
    commit: RepoCommitSummary,
    onClick: () -> Unit,
    onAuthorClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val actor = commit.author ?: commit.committer
    val authorLogin = actor?.login?.takeIf(String::isNotBlank)
    val authorLabel = actor?.login ?: actor?.displayName ?: stringResource(R.string.common_unknown)
    val authorClickModifier = if (authorLogin != null && onAuthorClick != null) {
        Modifier.clickable { onAuthorClick(authorLogin) }
    } else {
        Modifier
    }
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(commit.headline.ifBlank { commit.oid.take(7) }, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = actor?.avatarUrl,
                contentDescription = authorLogin,
                modifier = Modifier.size(28.dp).clip(CircleShape).then(authorClickModifier),
            )
            Text(
                "$authorLabel · ${commit.committedDate ?: commit.authoredDate.orEmpty()} · ${commit.abbreviatedOid}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).then(authorClickModifier),
            )
        }
        if (commit.changedFiles != null || commit.additions != 0 || commit.deletions != 0) {
            Text(stringResource(R.string.git_commit_stats, commit.additions, commit.deletions, commit.changedFiles?.toString() ?: stringResource(R.string.git_commit_files_unknown)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun GitChangedFileRow(
    file: RepoChangedFile,
    onClick: (() -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
        Column(
            Modifier.fillMaxWidth(0.85f).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(file.filename, style = MaterialTheme.typography.bodyLarge)
            file.previousFilename?.let { Text("← $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("${file.status.name} · +${file.additions} · -${file.deletions}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRevert != null) {
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.common_more))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.git_revert_file)) },
                        onClick = { menuExpanded = false; onRevert() },
                    )
                }
            }
        }
    }
}

@Composable
fun CommitStats(additions: Int, deletions: Int, changedFiles: Int?, modifier: Modifier = Modifier) {
    Text(stringResource(R.string.git_commit_stats, additions, deletions, changedFiles?.toString() ?: stringResource(R.string.git_commit_files_unknown)), modifier.padding(16.dp), style = MaterialTheme.typography.labelLarge)
}

@Composable
fun UnifiedDiffViewer(diff: UnifiedDiff?, modifier: Modifier = Modifier) {
    if (diff == null || diff.hunks.isEmpty()) {
        Text(stringResource(R.string.git_commit_no_diff), modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(modifier.fillMaxWidth()) {
        diff.hunks.forEach { hunk ->
            Text(hunk.header, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            hunk.lines.forEach { line ->
                val background = when (line.type) {
                    DiffLineType.ADDITION -> Color(0x332E7D32)
                    DiffLineType.DELETION -> Color(0x33C62828)
                    else -> Color.Transparent
                }
                Row(Modifier.fillMaxWidth().background(background).padding(vertical = 1.dp)) {
                    Text(line.oldLine?.toString().orEmpty(), modifier = Modifier.width(42.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(line.newLine?.toString().orEmpty(), modifier = Modifier.width(42.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(line.text, modifier = Modifier.weight(1f).padding(end = 8.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
