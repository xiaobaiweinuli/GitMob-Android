package com.gitmob.app.core.event

/**
 * 仓库相关的跨屏幕状态同步事件。解决的问题：Nav3 下不同路由（entry）各自持有
 * 独立生命周期的 ViewModel，互不相通——分支切换页面、仓库详情页面、仓库列表卡片，
 * 任何一处改了"这个仓库的某个状态"，其它正在显示同一个仓库信息的地方都需要知道。
 *
 * 用法：谁改了状态就 emit 一个事件；谁的界面上展示了这个仓库的相关信息，
 * 就在自己的 init 逻辑里 collect 这个事件、按 owner+name 过滤、更新自己的本地状态。
 * 不是"数据库"，不做持久化，纯粹是内存里的"广播通知"，跟 ErrorEventBus 是同一个模式。
 */
sealed class RepoUpdateEvent {
    abstract val owner: String
    abstract val name: String

    /** 当前查看的分支变了（仓库详情 Header、代码/提交入口都要联动） */
    data class BranchSwitched(
        override val owner: String,
        override val name: String,
        val ref: String,
    ) : RepoUpdateEvent()

    /** 星标状态变化（仓库详情页 Star 按钮、"仓库"/"星标" 两个 Tab 的卡片列表都要联动） */
    data class StarChanged(
        override val owner: String,
        override val name: String,
        val isStarred: Boolean,
        val stargazerCount: Int,
    ) : RepoUpdateEvent()

    /** 未关闭 Issue 数变化（新建/关闭/重开一个 Issue 后，详情页菜单上的数字要联动） */
    data class IssueCountChanged(
        override val owner: String,
        override val name: String,
        val openIssueCount: Int,
    ) : RepoUpdateEvent()

    /** 未合并 PR 数变化，同上 */
    data class PullRequestCountChanged(
        override val owner: String,
        override val name: String,
        val openPrCount: Int,
    ) : RepoUpdateEvent()

    data class DiscussionCountChanged(
        override val owner: String,
        override val name: String,
        val openDiscussionCount: Int,
    ) : RepoUpdateEvent()

    data class ReleaseChanged(
        override val owner: String,
        override val name: String,
        val releaseCount: Int,
        val latestReleaseName: String?,
        val latestReleaseTag: String?,
    ) : RepoUpdateEvent()

    data class ActionRunChanged(
        override val owner: String,
        override val name: String,
        val runId: Long,
    ) : RepoUpdateEvent()

    data class CodeChanged(
        override val owner: String,
        override val name: String,
        val ref: String,
        val commitOid: String,
        val changedPaths: List<String>,
    ) : RepoUpdateEvent()

    data class IssueCommentsChanged(
        override val owner: String,
        override val name: String,
        val number: Int,
    ) : RepoUpdateEvent()

    data class PullRequestCommentsChanged(
        override val owner: String,
        override val name: String,
        val number: Int,
    ) : RepoUpdateEvent()

    data class DiscussionCommentsChanged(
        override val owner: String,
        override val name: String,
        val number: Int,
    ) : RepoUpdateEvent()

    /** Watch 订阅状态变化（SUBSCRIBED/UNSUBSCRIBED/IGNORED） */
    data class WatchStateChanged(
        override val owner: String,
        override val name: String,
        val viewerSubscription: String,
        val watcherCount: Int,
    ) : RepoUpdateEvent()
}
