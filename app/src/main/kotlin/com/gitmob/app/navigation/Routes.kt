package com.gitmob.app.navigation

import androidx.navigation3.runtime.NavKey
import com.gitmob.app.core.permission.RepoPermission
import kotlinx.serialization.Serializable

/**
 * Navigation 3 的路由用类型安全的 NavKey 实现类表示，不是 Navigation 2 那种字符串路径。
 * 见 references/navigation3.md。
 */
sealed interface Route : NavKey

@Serializable data object HomeRoute : Route
@Serializable data object ReposRoute : Route
@Serializable data object StarsRoute : Route
@Serializable data object GistRoute : Route
@Serializable data object SettingsRoute : Route

/** 关注者 / 关注列表，参数化路由（不是底部 Tab，从 HomeScreen 点击跳转进来） */
@Serializable data class FollowersRoute(val login: String) : Route
@Serializable data class FollowingRoute(val login: String) : Route

/** 用户/组织统一资料页，从"选择组织"弹窗或任意头像点击跳转进来 */
@Serializable data class ProfileRoute(val login: String) : Route

/**
 * 指定 login 用户的仓库列表（不是底部 Tab 的"我的仓库"，从 Home/Profile 点击"仓库"统计项跳转进来）。
 * 指向参数化后的 ReposScreen(login=route.login)，与底部 Tab ReposRoute→ReposScreen(login=null) 共用同一套 Screen/ViewModel，
 * 只在 Repository 层分流走 viewer.repositories vs user(login:).repositories，见 ReposViewModel.init(login?)。
 */
@Serializable data class UserRepoListRoute(val login: String) : Route

/**
 * 指定 login 用户的星标仓库列表（只读、轻量）。
 * 指向新建的轻量 UserStarredReposScreen/ViewModel（不带列表管理的状态机），
 * 卡片复用公共 StarredRepoCard(showViewerActions=false)，隐藏 viewer 专属的"添加到列表/取消星标"按钮。
 * 为什么不复用底部 Tab 的 StarsScreen？StarsScreen 的状态机（建/改/删列表、加入列表 BottomSheet、取消星标）
 * 全部都是 viewer 自己专属的管理交互，硬塞 mode 分支会破坏已有稳定功能的封装边界，
 * 所以单独开一个只负责"加载+展示"的轻量页，遵循"单一职责"落到实处。
 */
@Serializable data class UserStarredReposRoute(val login: String) : Route

/** 个人主页 Gist 统计项进入的参数化列表；null 表示 viewer（包含秘密 Gist）。 */
@Serializable data class GistListRoute(val login: String? = null) : Route

/**
 * 组织主页 → 点击"成员"统计项：指定组织 login 的成员列表。
 * 零新文件复用 UserListScreen 架构：指向 UserListMode.ORG_MEMBERS 模式，
 * 走 UserListViewModel.initForOrgMembers(orgLogin)，内部调用 UserRepository.getOrgMembers(orgLogin, after)，
 * 与 FOLLOWERS/FOLLOWING/WATCHERS 三种模式共用同一套 UI 渲染、分页、加载失败重试逻辑。
 */
@Serializable data class OrgMembersRoute(val login: String) : Route

/** 仓库详情及其子页面 */
@Serializable data class RepoDetailRoute(val owner: String, val name: String) : Route
@Serializable data class RepoIssuesRoute(val owner: String, val name: String, val permission: RepoPermission? = null, val viewerCanCreateIssues: Boolean? = null) : Route
@Serializable data class RepoIssueDetailRoute(val owner: String, val name: String, val number: Int, val permission: RepoPermission? = null) : Route
@Serializable data class RepoIssueEditorRoute(val owner: String, val name: String, val number: Int? = null, val permission: RepoPermission? = null, val templateFilename: String? = null) : Route
@Serializable data class RepoPullRequestsRoute(val owner: String, val name: String, val permission: RepoPermission? = null) : Route
@Serializable data class RepoPullRequestDetailRoute(val owner: String, val name: String, val number: Int, val permission: RepoPermission? = null) : Route
@Serializable data class RepoPullRequestEditorRoute(
    val owner: String,
    val name: String,
    val number: Int? = null,
    val permission: RepoPermission? = null,
    val baseOwner: String? = null,
    val baseName: String? = null,
    val baseRef: String? = null,
    val headOwner: String? = null,
    val headName: String? = null,
    val headRef: String? = null,
    val headRepositoryId: String? = null,
) : Route
@Serializable data class RepoDiscussionsRoute(val owner: String, val name: String, val permission: RepoPermission? = null) : Route
@Serializable data class RepoDiscussionDetailRoute(val owner: String, val name: String, val number: Int, val permission: RepoPermission? = null) : Route
@Serializable data class RepoDiscussionEditorRoute(val owner: String, val name: String, val number: Int? = null, val permission: RepoPermission? = null) : Route
@Serializable
enum class ConversationComposerTarget { ISSUE_COMMENT, PULL_REQUEST_COMMENT, PULL_REQUEST_REVIEW, PULL_REQUEST_THREAD, DISCUSSION_COMMENT }

@Serializable data class ConversationComposerRoute(
    val owner: String,
    val name: String,
    val number: Int,
    val target: ConversationComposerTarget,
    val subjectId: String,
    val initialText: String = "",
    val commentId: String? = null,
    val replyToId: String? = null,
    val reviewEvent: String? = null,
) : Route
@Serializable data class RepoActionsRoute(val owner: String, val name: String, val permission: RepoPermission? = null, val defaultRef: String? = null) : Route
@Serializable data class RepoWorkflowRunRoute(val owner: String, val name: String, val runId: Long, val permission: RepoPermission? = null) : Route
@Serializable data class RepoReleasesRoute(val owner: String, val name: String, val permission: RepoPermission? = null) : Route
@Serializable data class RepoReleaseDetailRoute(val owner: String, val name: String, val tag: String, val permission: RepoPermission? = null) : Route
@Serializable data class RepoReleaseEditorRoute(val owner: String, val name: String, val releaseId: Long? = null, val permission: RepoPermission? = null) : Route
@Serializable data class RepoContributorsRoute(val owner: String, val name: String) : Route
@Serializable data class RepoLicenseRoute(val owner: String, val name: String, val ref: String) : Route
@Serializable data class RepoBranchesRoute(
    val owner: String,
    val name: String,
    val currentRef: String,
    val canPush: Boolean = false,
    val canManageBranchProtection: Boolean = false,
) : Route
@Serializable data class RepoCodeRoute(
    val owner: String,
    val name: String,
    val ref: String,
    val path: String = "",
    val permission: RepoPermission? = null,
) : Route
@Serializable data class RepoCommitsRoute(
    val owner: String,
    val name: String,
    val ref: String,
    val path: String? = null,
    val permission: RepoPermission? = null,
) : Route
@Serializable data class RepoCommitDetailRoute(
    val owner: String,
    val name: String,
    val sha: String,
    val ref: String = "",
    val permission: RepoPermission? = null,
) : Route
@Serializable data class RepoFileDetailRoute(
    val owner: String,
    val name: String,
    val ref: String,
    val path: String,
    val permission: RepoPermission? = null,
) : Route
@Serializable data class RepoFileEditorRoute(
    val owner: String,
    val name: String,
    val ref: String,
    val path: String? = null,
    val permission: RepoPermission? = null,
) : Route
@Serializable data class RepoWatchersRoute(val owner: String, val name: String) : Route
@Serializable data class RepoPlaceholderRoute(val label: String) : Route

/** "关于"页面，从设置 Tab 点击跳转 */
@Serializable data object AboutRoute : Route

/** "外观"页面，从设置 Tab push 进入当前 Settings 返回栈 */
@Serializable data object AppearanceRoute : Route

/** 主页事务入口：议题/PR/讨论（involves:@me 聚合），收件箱（REST 通知） */
@Serializable data object WorkIssuesRoute : Route
@Serializable data object WorkPullRequestsRoute : Route
@Serializable data object WorkDiscussionsRoute : Route
@Serializable data object InboxRoute : Route
