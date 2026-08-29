package com.gitmob.app.ui.icons

/**
 * App 内实际使用的 GitHub Octicons 语义。
 *
 * 这里只声明“图标是什么”，不保存 drawable 或颜色。drawable 映射由
 * [OcticonPainterProvider] 负责；Issue / PR / Discussion / Release 的状态颜色与
 * 状态组合规则由 ui/common/GitHubStateIcon.kt 负责。
 */
enum class OcticonName {
    // 个人资料中的 GitHub 专属徽章
    BADGE_DEVELOPER_PROGRAM,
    BADGE_SECURITY_BOUNTY_HUNTER,
    BADGE_CAMPUS_EXPERT,
    BADGE_GITHUB_STAR,

    // 已稳定使用的资料统计图标
    REPO,
    STAR,
    ORGANIZATION,
    PEOPLE,
    LOCKED,

    // Issue 状态
    ISSUE_OPENED,
    ISSUE_REOPENED,
    ISSUE_CLOSED,
    ISSUE_NOT_PLANNED,
    ISSUE_DUPLICATE,

    // Pull Request 状态
    PULL_REQUEST_OPENED,
    PULL_REQUEST_DRAFT,
    PULL_REQUEST_CLOSED,
    PULL_REQUEST_MERGED,

    // Discussion 状态与“已回答”辅助标记
    DISCUSSION_OPENED,
    DISCUSSION_RESOLVED,
    DISCUSSION_DUPLICATE,
    DISCUSSION_OUTDATED,
    DISCUSSION_ANSWERED,

    // Release 的草稿、预发布和正式发布共用 Tag 图标，由 tint 与文案区分
    RELEASE_TAG,
}
