package com.gitmob.app.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.gitmob.app.R

/** Octicons 语义到 drawable 的唯一映射层。 */
object OcticonPainterProvider {

    /**
     * 使用穷尽 when，不提供 fallback。新增枚举却忘记映射时直接编译失败，避免把错误图标
     * 悄悄显示成盾牌等无关资源。
     */
    @DrawableRes
    private fun resolveDrawableId(name: OcticonName): Int = when (name) {
        OcticonName.BADGE_DEVELOPER_PROGRAM -> R.drawable.oct_cpu_16
        OcticonName.BADGE_SECURITY_BOUNTY_HUNTER -> R.drawable.oct_lock_16
        OcticonName.BADGE_CAMPUS_EXPERT -> R.drawable.oct_mortar_board_16
        OcticonName.BADGE_GITHUB_STAR -> R.drawable.oct_star_fill_16

        OcticonName.REPO -> R.drawable.oct_repo_16
        OcticonName.STAR -> R.drawable.oct_star_16
        OcticonName.ORGANIZATION -> R.drawable.oct_organization_16
        OcticonName.PEOPLE -> R.drawable.oct_people_16
        OcticonName.LOCKED -> R.drawable.oct_lock_16

        OcticonName.ISSUE_OPENED -> R.drawable.oct_issue_opened_16
        OcticonName.ISSUE_REOPENED -> R.drawable.oct_issue_reopened_16
        OcticonName.ISSUE_CLOSED -> R.drawable.oct_issue_closed_16
        OcticonName.ISSUE_NOT_PLANNED -> R.drawable.oct_skip_16
        OcticonName.ISSUE_DUPLICATE -> R.drawable.oct_duplicate_16

        OcticonName.PULL_REQUEST_OPENED -> R.drawable.oct_git_pull_request_16
        OcticonName.PULL_REQUEST_DRAFT -> R.drawable.oct_git_pull_request_draft_16
        OcticonName.PULL_REQUEST_CLOSED -> R.drawable.oct_git_pull_request_closed_16
        OcticonName.PULL_REQUEST_MERGED -> R.drawable.oct_git_merge_16

        OcticonName.DISCUSSION_OPENED -> R.drawable.oct_comment_discussion_16
        OcticonName.DISCUSSION_RESOLVED -> R.drawable.oct_discussion_closed_16
        OcticonName.DISCUSSION_DUPLICATE -> R.drawable.oct_discussion_duplicate_16
        OcticonName.DISCUSSION_OUTDATED -> R.drawable.oct_discussion_outdated_16
        OcticonName.DISCUSSION_ANSWERED -> R.drawable.oct_check_circle_fill_16

        OcticonName.RELEASE_TAG -> R.drawable.oct_tag_16
    }

    @Composable
    fun rememberOcticonPainter(name: OcticonName): Painter =
        painterResource(id = resolveDrawableId(name))
}
