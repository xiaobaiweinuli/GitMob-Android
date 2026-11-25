package com.gitmob.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gitmob.app.R
import com.gitmob.app.core.diff.DiffLineType
import com.gitmob.app.core.diff.UnifiedDiffParser
import com.gitmob.app.core.diff.UnifiedDiff
import com.gitmob.app.data.model.RepoChangedFile
import com.gitmob.app.data.model.RepoCommitSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.color.ConstColor
import io.github.rosemoe.sora.lang.styling.line.LineBackground
import com.gitmob.app.ui.common.AppDropdownMenu as DropdownMenu
import com.gitmob.app.ui.common.AppDropdownMenuItem as DropdownMenuItem

@Composable
fun GitCommitRow(
    commit: RepoCommitSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAuthorClick: ((String) -> Unit)? = null,
) {
    val actor = commit.author ?: commit.committer
    val authorLogin = actor?.login?.takeIf(String::isNotBlank)
    val authorLabel = actor?.login ?: actor?.displayName ?: stringResource(R.string.common_unknown)
    val authorClickModifier = if (authorLogin != null && onAuthorClick != null) {
        Modifier.pressClickable { onAuthorClick(authorLogin) }
    } else {
        Modifier
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).informationItem().pressClickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).informationItem().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
        Column(
            Modifier.fillMaxWidth(0.85f).then(if (onClick != null) Modifier.pressClickable(onClick = onClick) else Modifier),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(file.filename, style = MaterialTheme.typography.bodyLarge)
            file.previousFilename?.let { Text("← $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("${file.status.name} · +${file.additions} · -${file.deletions}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRevert != null) {
            androidx.compose.foundation.layout.Box {
                ActionIconButton(onClick = { menuExpanded = true }) {
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

/** Raw patches from PR pages are parsed off the UI thread once per payload. */
@Composable
fun UnifiedDiffViewer(patch: String?, modifier: Modifier = Modifier, documentId: String = "diff") {
    val parsed by produceState<UnifiedDiff?>(null, patch) {
        value = withContext(Dispatchers.Default) { UnifiedDiffParser.parse(patch) ?: UnifiedDiff(emptyList()) }
    }
    if (parsed == null) {
        Box(modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else UnifiedDiffViewer(parsed, modifier, documentId)
}

@Composable
fun UnifiedDiffViewer(diff: UnifiedDiff?, modifier: Modifier = Modifier, documentId: String = "diff") {
    if (diff == null || diff.hunks.isEmpty()) {
        Text(stringResource(R.string.git_commit_no_diff), modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    // Identity avoids hashing every patch line on every recomposition.
    val documentKey = remember(diff) { Any() }
    SoraTextViewer(
        documentKey = documentKey,
        wrapLines = true,
        state = rememberSoraViewerState(documentId),
        modifier = modifier.fillMaxWidth(),
        embedded = true,
        lineNumbersAtStart = true,
    ) { diff.toSoraDocument() }
}

/** Build one immutable document; gutters and backgrounds use Sora's visible-row renderer. */
internal fun UnifiedDiff.toSoraDocument(): SoraViewerDocument {
    val labels = mutableListOf<String>()
    val content = Content().apply { isUndoEnabled = false }
    val styles = Styles()
    val digits = hunks.maxOfOrNull { maxOf(it.oldStart + it.oldCount, it.newStart + it.newCount) }
        ?.toString()?.length ?: 1
    val hasOldLines = hunks.any { hunk -> hunk.lines.any { it.oldLine != null } }
    val hasNewLines = hunks.any { hunk -> hunk.lines.any { it.newLine != null } }
    fun append(text: String, oldLine: Int? = null, newLine: Int? = null, background: Int? = null) {
        if (labels.isNotEmpty()) content.insert(content.lineCount - 1, content.getColumnCount(content.lineCount - 1), "\n")
        content.insert(content.lineCount - 1, content.getColumnCount(content.lineCount - 1), text)
        background?.let { styles.addLineStyle(LineBackground(labels.size, ConstColor(it))) }
        labels += if (oldLine == null && newLine == null) "" else buildList {
            if (hasOldLines) add((oldLine?.toString() ?: "–").padEnd(digits))
            if (hasNewLines) add((newLine?.toString() ?: "–").padEnd(digits))
        }.joinToString("  ")
    }
    metadata.forEach { append(it) }
    hunks.forEach { hunk ->
        append(hunk.header, background = 0x18808080)
        hunk.lines.forEach { line ->
            val prefix = when (line.type) {
                DiffLineType.ADDITION -> "+"
                DiffLineType.DELETION -> "-"
                DiffLineType.CONTEXT -> " "
                DiffLineType.META -> ""
            }
            val background = when (line.type) {
                DiffLineType.ADDITION -> 0x332E7D32
                DiffLineType.DELETION -> 0x33C62828
                else -> null
            }
            append(prefix + line.text, line.oldLine, line.newLine, background)
        }
    }
    styles.finishBuilding()
    return SoraViewerDocument(content, labels, styles)
}
