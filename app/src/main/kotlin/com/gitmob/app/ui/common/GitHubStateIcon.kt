package com.gitmob.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gitmob.app.R
import com.gitmob.app.data.model.DiscussionStateReason
import com.gitmob.app.data.model.IssueState
import com.gitmob.app.data.model.IssueStateReason
import com.gitmob.app.data.model.PullRequestState
import com.gitmob.app.ui.icons.Octicon
import com.gitmob.app.ui.icons.OcticonName

/** GitHub 状态语义色。它独立于应用主题主色，但会针对浅色/深色背景选择可读变体。 */
enum class GitHubStateColor { OPEN, DANGER, DONE, NEUTRAL, ATTENTION }

enum class GitHubStateBadge { LOCKED, ANSWERED, LATEST }

data class GitHubStateVisual(
    val icon: OcticonName,
    val color: GitHubStateColor,
    @StringRes val labelRes: Int,
    val badges: Set<GitHubStateBadge> = emptySet(),
)

fun issueStateVisual(
    state: IssueState,
    stateReason: IssueStateReason?,
    locked: Boolean = false,
): GitHubStateVisual {
    val visual = when {
        state == IssueState.OPEN && stateReason == IssueStateReason.REOPENED -> GitHubStateVisual(
            OcticonName.ISSUE_REOPENED,
            GitHubStateColor.OPEN,
            R.string.state_reopened,
        )
        state == IssueState.OPEN -> GitHubStateVisual(
            OcticonName.ISSUE_OPENED,
            GitHubStateColor.OPEN,
            R.string.state_open,
        )
        stateReason == IssueStateReason.NOT_PLANNED -> GitHubStateVisual(
            OcticonName.ISSUE_NOT_PLANNED,
            GitHubStateColor.NEUTRAL,
            R.string.state_not_planned,
        )
        stateReason == IssueStateReason.DUPLICATE -> GitHubStateVisual(
            OcticonName.ISSUE_DUPLICATE,
            GitHubStateColor.NEUTRAL,
            R.string.state_duplicate,
        )
        else -> GitHubStateVisual(
            OcticonName.ISSUE_CLOSED,
            GitHubStateColor.DONE,
            R.string.state_completed,
        )
    }
    return visual.withBadgeIf(GitHubStateBadge.LOCKED, locked)
}

fun pullRequestStateVisual(
    state: PullRequestState,
    isDraft: Boolean,
    locked: Boolean = false,
): GitHubStateVisual {
    val visual = when {
        state == PullRequestState.MERGED -> GitHubStateVisual(
            OcticonName.PULL_REQUEST_MERGED,
            GitHubStateColor.DONE,
            R.string.state_merged,
        )
        state == PullRequestState.CLOSED -> GitHubStateVisual(
            OcticonName.PULL_REQUEST_CLOSED,
            GitHubStateColor.DANGER,
            R.string.common_state_closed,
        )
        isDraft -> GitHubStateVisual(
            OcticonName.PULL_REQUEST_DRAFT,
            GitHubStateColor.NEUTRAL,
            R.string.state_draft,
        )
        else -> GitHubStateVisual(
            OcticonName.PULL_REQUEST_OPENED,
            GitHubStateColor.OPEN,
            R.string.state_open,
        )
    }
    return visual.withBadgeIf(GitHubStateBadge.LOCKED, locked)
}

fun discussionStateVisual(
    stateReason: DiscussionStateReason?,
    isAnswered: Boolean,
    locked: Boolean = false,
    isClosed: Boolean = false,
): GitHubStateVisual {
    val visual = when (stateReason) {
        null -> if (isClosed) {
            GitHubStateVisual(
                OcticonName.DISCUSSION_RESOLVED,
                GitHubStateColor.DONE,
                R.string.common_state_closed,
            )
        } else {
            GitHubStateVisual(
                OcticonName.DISCUSSION_OPENED,
                GitHubStateColor.OPEN,
                R.string.state_open,
            )
        }
        DiscussionStateReason.REOPENED -> GitHubStateVisual(
            OcticonName.DISCUSSION_OPENED,
            GitHubStateColor.OPEN,
            R.string.state_reopened,
        )
        DiscussionStateReason.RESOLVED -> GitHubStateVisual(
            OcticonName.DISCUSSION_RESOLVED,
            GitHubStateColor.DONE,
            R.string.state_resolved,
        )
        DiscussionStateReason.DUPLICATE -> GitHubStateVisual(
            OcticonName.DISCUSSION_DUPLICATE,
            GitHubStateColor.NEUTRAL,
            R.string.state_duplicate,
        )
        DiscussionStateReason.OUTDATED -> GitHubStateVisual(
            OcticonName.DISCUSSION_OUTDATED,
            GitHubStateColor.NEUTRAL,
            R.string.state_outdated,
        )
    }
    return visual
        .withBadgeIf(GitHubStateBadge.ANSWERED, isAnswered)
        .withBadgeIf(GitHubStateBadge.LOCKED, locked)
}

fun releaseStateVisual(
    isDraft: Boolean,
    isPrerelease: Boolean,
    isLatest: Boolean = false,
): GitHubStateVisual {
    val visual = when {
        isDraft -> GitHubStateVisual(OcticonName.RELEASE_TAG, GitHubStateColor.NEUTRAL, R.string.state_draft)
        isPrerelease -> GitHubStateVisual(OcticonName.RELEASE_TAG, GitHubStateColor.ATTENTION, R.string.state_prerelease)
        else -> GitHubStateVisual(OcticonName.RELEASE_TAG, GitHubStateColor.OPEN, R.string.state_published)
    }
    return visual.withBadgeIf(GitHubStateBadge.LATEST, isLatest)
}

private fun GitHubStateVisual.withBadgeIf(
    badge: GitHubStateBadge,
    condition: Boolean,
): GitHubStateVisual = if (condition) copy(badges = badges + badge) else this

@Composable
fun IssueStateIcon(
    state: IssueState,
    stateReason: IssueStateReason?,
    locked: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    GitHubStateIcon(issueStateVisual(state, stateReason, locked), modifier, size)
}

@Composable
fun PullRequestStateIcon(
    state: PullRequestState,
    isDraft: Boolean,
    locked: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    GitHubStateIcon(pullRequestStateVisual(state, isDraft, locked), modifier, size)
}

@Composable
fun DiscussionStateIcon(
    stateReason: DiscussionStateReason?,
    isAnswered: Boolean,
    locked: Boolean,
    isClosed: Boolean = false,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    GitHubStateIcon(discussionStateVisual(stateReason, isAnswered, locked, isClosed), modifier, size)
}

@Composable
fun ReleaseStateIcon(
    isDraft: Boolean,
    isPrerelease: Boolean,
    isLatest: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    GitHubStateIcon(releaseStateVisual(isDraft, isPrerelease, isLatest), modifier, size)
}

/** 状态主图标和辅助属性。locked / answered 不会覆盖主生命周期状态。 */
@Composable
private fun GitHubStateIcon(
    visual: GitHubStateVisual,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val stateColor = visual.color.resolveColor()
        Octicon(
            name = visual.icon,
            contentDescription = stringResource(visual.labelRes),
            size = size,
            tint = stateColor,
        )
        if (GitHubStateBadge.ANSWERED in visual.badges) {
            Octicon(
                name = OcticonName.DISCUSSION_ANSWERED,
                contentDescription = stringResource(R.string.state_answered),
                size = 12.dp,
                tint = GitHubStateColor.OPEN.resolveColor(),
            )
        }
        if (GitHubStateBadge.LOCKED in visual.badges) {
            Octicon(
                name = OcticonName.LOCKED,
                contentDescription = stringResource(R.string.state_locked),
                modifier = Modifier.padding(start = 1.dp).size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (GitHubStateBadge.LATEST in visual.badges) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.state_latest),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** 与状态图标共用同一套语义与固定色的紧凑文字标签。 */
@Composable
fun GitHubStateChip(
    visual: GitHubStateVisual,
    modifier: Modifier = Modifier,
) {
    val stateColor = visual.color.resolveColor()
    Surface(
        modifier = modifier,
        color = stateColor.copy(alpha = 0.12f),
        contentColor = stateColor,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = stringResource(visual.labelRes),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun GitHubStateColor.resolveColor(): Color {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (this) {
        GitHubStateColor.OPEN -> if (isDark) Color(0xFF3FB950) else Color(0xFF1A7F37)
        GitHubStateColor.DANGER -> if (isDark) Color(0xFFF85149) else Color(0xFFCF222E)
        GitHubStateColor.DONE -> if (isDark) Color(0xFFA371F7) else Color(0xFF8250DF)
        GitHubStateColor.NEUTRAL -> if (isDark) Color(0xFF8C959F) else Color(0xFF656D76)
        GitHubStateColor.ATTENTION -> if (isDark) Color(0xFFD29922) else Color(0xFF9A6700)
    }
}
