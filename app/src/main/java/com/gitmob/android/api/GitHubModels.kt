package com.gitmob.android.api

import com.google.gson.annotations.SerializedName

data class GHUser(
    val login: String,
    val name: String?,
    val email: String?,
    @SerializedName("avatar_url") val avatarUrl: String?,
    @SerializedName("public_repos") val publicRepos: Int = 0,
    val followers: Int = 0,
    val following: Int = 0,
    val bio: String?,
    val blog: String? = null,
    val location: String? = null,
    val company: String? = null,
    @SerializedName("twitter_username") val twitterUsername: String? = null,
    @SerializedName("html_url") val htmlUrl: String? = null,
)

data class GHRepo(
    val id: Long,
    @SerializedName("node_id") val nodeId: String = "",
    val name: String,
    @SerializedName("full_name") val fullName: String,
    val description: String?,
    val homepage: String?,
    val private: Boolean,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("ssh_url") val sshUrl: String,
    @SerializedName("clone_url") val cloneUrl: String,
    @SerializedName("default_branch") val defaultBranch: String,
    @SerializedName("stargazers_count") val stars: Int = 0,
    @SerializedName("forks_count") val forks: Int = 0,
    @SerializedName("open_issues_count") val openIssues: Int = 0,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String?,
    @SerializedName("pushed_at") val pushedAt: String?,
    val language: String?,
    val owner: GHOwner,
    val fork: Boolean = false,
    val archived: Boolean = false,
    @SerializedName("is_template") val isTemplate: Boolean = false,
    @SerializedName("mirror_url") val mirrorUrl: String? = null,
    val parent: GHRepo? = null,
    @SerializedName("has_issues") val hasIssues: Boolean = true,
    @SerializedName("has_discussions") val hasDiscussions: Boolean = false,
    val topics: List<String> = emptyList(),
)

data class GHOwner(
    val login: String,
    @SerializedName("avatar_url") val avatarUrl: String?,
)

data class GHBranch(
    val name: String,
    val commit: GHBranchCommit,
    val protected: Boolean = false,
)

data class GHBranchCommit(val sha: String, val url: String)

data class GHCommit(
    val sha: String,
    val commit: GHCommitDetail,
    val author: GHOwner?,
    @SerializedName("html_url") val htmlUrl: String,
) {
    val shortSha get() = sha.take(7)
}

data class GHCommitDetail(
    val message: String,
    val author: GHCommitAuthor,
)

data class GHCommitAuthor(
    val name: String,
    val email: String,
    val date: String,
)

data class GHContent(
    val type: String,   // file | dir | symlink | submodule
    val name: String,
    val path: String,
    val sha: String,
    val size: Long = 0,
    val content: String? = null,   // base64, only when type==file or symlink
    val encoding: String? = null,
    @SerializedName("download_url") val downloadUrl: String?,
    @SerializedName("html_url") val htmlUrl: String?,
    @SerializedName("submodule_git_url") val submoduleGitUrl: String? = null,
)

data class GHCreateRepoRequest(
    val name: String,
    val description: String? = null,
    val private: Boolean = false,
    @SerializedName("auto_init") val autoInit: Boolean = false,
)

data class GHCreateFileRequest(
    val message: String,
    val content: String,  // base64
    val branch: String? = null,
    val sha: String? = null,  // required for update
)

data class GHCreateFileResponse(
    val content: GHContent,
    val commit: GHCommit,
)

data class GHDeleteFileRequest(
    val message: String,
    val sha: String,
    val branch: String? = null,
)

data class GHCreateBranchRequest(
    val ref: String,
    val sha: String,
)

data class GHRef(
    val ref: String,
    val url: String,
    @SerializedName("object") val obj: GHRefObject,
)

data class GHRefObject(val sha: String, val type: String, val url: String)

data class GHTopic(val names: List<String>)

data class GHPullRequest(
    val number: Int,
    val title: String,
    val state: String,           // "open" | "closed"
    val body: String?,
    @SerializedName("html_url")   val htmlUrl: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("merged_at")  val mergedAt: String? = null,
    @SerializedName("closed_at")  val closedAt: String? = null,
    @SerializedName("last_edited_at") val lastEditedAt: String? = null,
    val user: GHOwner,
    val head: GHPRBranch,
    val base: GHPRBranch,
    val draft: Boolean = false,
    val merged: Boolean = false,
    val mergeable: Boolean? = null,
    @SerializedName("mergeable_state") val mergeableState: String? = null,
    val comments: Int = 0,
    @SerializedName("review_comments") val reviewComments: Int = 0,
    val labels: List<GHLabel> = emptyList(),
    @SerializedName("requested_reviewers") val requestedReviewers: List<GHOwner> = emptyList(),
    val assignees: List<GHOwner> = emptyList(),
    @SerializedName("node_id") val nodeId: String = "",
    val locked: Boolean = false,
    val viewerSubscription: String? = null,
) {
    val isOpen    get() = state == "open"
    val isMerged  get() = merged
    val isDraft   get() = draft
    val isClosed  get() = state == "closed" && !isMerged
}

data class GHCreatePullRequestRequest(
    val title: String,
    val body: String? = null,
    val head: String,
    val base: String,
    val draft: Boolean = false,
)

data class GHUpdatePullRequestRequest(
    val title: String? = null,
    val body: String? = null,
    val state: String? = null,   // "open" | "closed"
    val base: String? = null,
)

data class GHPRBranch(
    val label: String,
    val ref: String,
    val sha: String,
    val repo: GHRepo? = null,
)

/** 合并 PR 请求体 */
data class GHMergePRRequest(
    @SerializedName("commit_title")   val commitTitle: String? = null,
    @SerializedName("commit_message") val commitMessage: String? = null,
    @SerializedName("merge_method")   val mergeMethod: String = "merge", // merge|squash|rebase
)

/** 合并 PR 响应 */
data class GHMergePRResponse(
    val sha: String? = null,
    val merged: Boolean = false,
    val message: String? = null,
)

/** PR 审查（review）*/
data class GHReview(
    val id: Long,
    val user: GHOwner,
    val body: String?,
    val state: String,           // APPROVED | CHANGES_REQUESTED | COMMENTED | DISMISSED
    @SerializedName("submitted_at") val submittedAt: String? = null,
    @SerializedName("html_url")     val htmlUrl: String = "",
)

/** 提交审查请求体 */
data class GHCreateReviewRequest(
    val body: String? = null,
    val event: String,           // APPROVE | REQUEST_CHANGES | COMMENT
    val comments: List<GHReviewComment> = emptyList(),
)

data class GHReviewComment(
    val path: String,
    val position: Int? = null,
    val body: String,
)

/** 管理审查请求 */
data class GHReviewersRequest(
    val reviewers: List<String> = emptyList(),
    @SerializedName("team_reviewers") val teamReviewers: List<String> = emptyList(),
)

/** 更新分支（基分支有新提交时同步） */
data class GHUpdateBranchRequest(
    @SerializedName("expected_head_sha") val expectedHeadSha: String? = null,
)

data class GHUpdateBranchResponse(
    val message: String? = null,
    val url: String? = null,
)

/** PR 筛选状态 */
data class PRFilterState(
    val status: PRStatusFilter = PRStatusFilter.OPEN,
    val sortBy: PRSortBy = PRSortBy.CREATED_DESC,
    val labelFilter: String? = null,
    val authorFilter: String? = null,
    val reviewerFilter: String? = null,
    val reviewStateFilter: PRReviewState? = null,
) {
    /** 是否需要切换到 Search API（REST /pulls 不支持 label/author/reviewer/merged） */
    val useSearchApi: Boolean get() =
        labelFilter != null || authorFilter != null ||
        reviewerFilter != null || reviewStateFilter != null ||
        status == PRStatusFilter.MERGED

    val hasFilters: Boolean get() =
        status != PRStatusFilter.OPEN || sortBy != PRSortBy.CREATED_DESC ||
        labelFilter != null || authorFilter != null ||
        reviewerFilter != null || reviewStateFilter != null
}

fun PRFilterState.hasAnyFilters(): Boolean = hasFilters

enum class PRStatusFilter(val displayName: String, val apiValue: String, val searchQualifier: String) {
    OPEN(   "打开",   "open",   "state:open is:pr"),
    MERGED( "已合并", "closed", "state:closed is:merged is:pr"),
    CLOSED( "已关闭", "closed", "state:closed is:unmerged is:pr"),
    ALL(    "所有",   "all",    "is:pr"),
}

enum class PRSortBy(val displayName: String, val sort: String, val direction: String) {
    CREATED_DESC( "最新",     "created",     "desc"),
    CREATED_ASC(  "最早",     "created",     "asc"),
    UPDATED_DESC( "最近更新", "updated",     "desc"),
    UPDATED_ASC(  "最早更新", "updated",     "asc"),
    COMMENTS_DESC("最多评论", "popularity",  "desc"),
    COMMENTS_ASC( "最少评论", "popularity",  "asc"),
}

/** 审查状态筛选（走 Search API review: qualifier） */
enum class PRReviewState(val displayName: String, val qualifier: String) {
    APPROVED(           "已批准",   "review:approved"),
    CHANGES_REQUESTED(  "需要修改", "review:changes_requested"),
    REQUIRED(           "待审查",   "review:required"),
}

enum class MergeMethod(val displayName: String, val apiValue: String) {
    MERGE("创建合并提交",  "merge"),
    SQUASH("压缩合并",     "squash"),
    REBASE("变基合并",     "rebase"),
}

data class GHIssue(
    val number: Int,
    val title: String,
    val state: String,
    val body: String?,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String?,
    @SerializedName("closed_at") val closedAt: String? = null,
    @SerializedName("last_edited_at") val lastEditedAt: String? = null,
    val user: GHOwner,
    val labels: List<GHLabel> = emptyList(),
    val assignees: List<GHOwner> = emptyList(),
    val comments: Int? = null,
    @SerializedName("pull_request") val pullRequest: Any? = null,
    @SerializedName("node_id") val nodeId: String = "",
    val locked: Boolean = false,
    val viewerSubscription: String? = null,
) {
    val isPR get() = pullRequest != null
}

data class GHLabel(
    val id: Long,
    val name: String,
    val color: String,
    val description: String?,
)

data class GHComment(
    val id: Long,
    val body: String,
    val user: GHOwner,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("last_edited_at") val lastEditedAt: String? = null,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("author_association") val authorAssociation: String?,
    @SerializedName("node_id") val nodeId: String = "",
)

data class GHCreateCommentRequest(
    val body: String,
)

data class GHUpdateCommentRequest(
    val body: String,
)

data class GHUpdateIssueRequest(
    val title: String? = null,
    val body: String? = null,
    val state: String? = null,
    @SerializedName("state_reason") val stateReason: String? = null,
)

data class GHCreateIssueRequest(
    val title: String,
    val body: String? = null,
)

// ─── GitHub Discussions（GraphQL only）───────────────────────────────────────
data class GHDiscussion(
    val id: String,
    val number: Int,
    val title: String,
    val body: String?,
    val createdAt: String,
    val updatedAt: String,
    val lastEditedAt: String? = null,
    val url: String,
    val author: GHOwner?,
    val category: GHDiscussionCategory?,
    val comments: Int,           // totalCount
    val upvoteCount: Int,
    val isAnswered: Boolean,
    val answeredBy: GHOwner?,    // 回答者
    val viewerSubscription: String? = null,
    val viewerCanUpdate: Boolean = false,
    val viewerCanDelete: Boolean = false,
    val labels: List<GHLabel> = emptyList(),
)

data class GHDiscussionComment(
    val id: String,
    val body: String,
    val author: GHOwner?,
    val createdAt: String,
    val updatedAt: String,
    val lastEditedAt: String? = null,
    val url: String,
    val viewerCanUpdate: Boolean = false,
    val viewerCanDelete: Boolean = false,
    val nodeId: String = "",
)

data class GHDiscussionCategory(
    val id: String,
    val name: String,
    val emoji: String,
    val description: String?,
    val isAnswerable: Boolean = false,
)

data class GHIssueSubscription(
    val subscribed: Boolean,
    val ignored: Boolean,
    val reason: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    val url: String? = null,
    @SerializedName("repository_url") val repositoryUrl: String? = null,
)

data class GHIssueEvent(
    val id: Long,
    val event: String,
    val actor: GHOwner?,
    @SerializedName("created_at") val createdAt: String,
    val commit_id: String? = null,
)

data class GHRelease(
    val id: Long,
    @SerializedName("tag_name") val tagName: String,
    val name: String?,
    val body: String?,
    val draft: Boolean,
    val prerelease: Boolean,
    @SerializedName("published_at") val publishedAt: String?,
    @SerializedName("html_url") val htmlUrl: String,
    val assets: List<GHAsset>,
)

data class UpdateReleaseRequest(
    @SerializedName("tag_name")   val tagName: String,
    val name: String,
    val body: String,
    val draft: Boolean,
    val prerelease: Boolean,
)

data class GHAsset(
    val id: Long = 0,
    val name: String,
    val size: Long,
    val url: String = "",                                           // API URL（Bearer 下载用）
    @SerializedName("browser_download_url") val downloadUrl: String,
    @SerializedName("content_type") val contentType: String = "application/octet-stream",
    @SerializedName("download_count") val downloadCount: Int = 0,
)

data class GHSearchResult<T>(
    @SerializedName("total_count") val totalCount: Int,
    val items: List<T>,
)

// ── Org ──
data class GHOrg(
    val login: String,
    @SerializedName("avatar_url") val avatarUrl: String?,
    val description: String?,
)

// ── Repo update ──
data class GHUpdateRepoRequest(
    val name: String? = null,
    val description: String? = null,
    val homepage: String? = null,
    @SerializedName("private") val private: Boolean? = null,
    @SerializedName("default_branch") val defaultBranch: String? = null,
    @SerializedName("has_issues") val hasIssues: Boolean? = null,
    @SerializedName("has_discussions") val hasDiscussions: Boolean? = null,
    val archived: Boolean? = null,
)

/** 仓库转移请求体 */
data class GHTransferRepoRequest(
    @SerializedName("new_owner") val newOwner: String,
    @SerializedName("new_name") val newName: String? = null,
)

// ── Topics ──
data class GHTopics(
    val names: List<String>,
)

// ── Branch rename ──
data class GHRenameBranchRequest(
    @SerializedName("new_name") val newName: String,
)

// ── Sync a fork branch ──
data class GHSyncForkRequest(
    val branch: String,
)

data class GHSyncForkResponse(
    val message: String? = null,
    val merge_type: String? = null,
    val base_branch: String? = null,
)

// ── Create Fork ──
data class GHCreateForkRequest(
    @SerializedName("organization") val organization: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("default_branch_only") val defaultBranchOnly: Boolean? = null,
)

// ── Commit detail (files) ──
data class GHCommitFull(
    val sha: String,
    val commit: GHCommitDetail,
    val author: GHOwner?,
    @SerializedName("html_url") val htmlUrl: String,
    val stats: GHCommitStats?,
    val files: List<GHCommitFile>?,
    val parents: List<GHCommitParent> = emptyList(),
) {
    val shortSha get() = sha.take(7)
    val parentSha: String? get() = parents.firstOrNull()?.sha
}

data class GHCommitParent(
    val sha: String,
    val url: String = "",
)

data class GHCommitStats(
    val additions: Int,
    val deletions: Int,
    val total: Int,
)

data class GHCommitFile(
    val filename: String,
    val status: String,    // added | removed | modified | renamed
    val additions: Int,
    val deletions: Int,
    val changes: Int,
    val patch: String?,
    @SerializedName("previous_filename") val previousFilename: String? = null,
    @SerializedName("blob_url") val blobUrl: String? = null,
    @SerializedName("raw_url") val rawUrl: String? = null,
    @SerializedName("contents_url") val contentsUrl: String? = null,
)

// ── Revert / Merge request ──
data class GHMergeRequest(
    val base: String,
    val head: String,
    @SerializedName("commit_message") val commitMessage: String,
)

// ─── Git Data API (用于服务端 Reset / Revert) ───────────────────────────────

/** PATCH /repos/{owner}/{repo}/git/refs/heads/{branch} —— 强制移动分支指针（回滚） */
data class GHUpdateRefRequest(
    val sha: String,
    val force: Boolean = false,
)

/** POST /repos/{owner}/{repo}/git/commits —— 创建新 commit（撤销 revert） */
data class GHCreateCommitRequest(
    val message: String,
    val tree: String,           // tree SHA（parent 提交的 tree）
    val parents: List<String>,  // 父提交 SHA 列表
)

/** 创建 commit 的响应 */
data class GHCreateCommitResponse(
    val sha: String,
    val message: String,
    @com.google.gson.annotations.SerializedName("html_url") val htmlUrl: String,
)

/** GET /repos/{owner}/{repo}/git/commits/{sha} —— 获取 Git 提交对象（含 tree） */
data class GHGitCommit(
    val sha: String,
    val message: String,
    val tree: GHGitTree,
    val parents: List<GHGitParent>,
)

data class GHGitTree(val sha: String, val url: String)
data class GHGitParent(val sha: String, val url: String)

// ── GitHub Actions ─────────────────────────────────────────────

data class WorkflowInput(
    val name: String,
    val type: String,
    val description: String?,
    val required: Boolean,
    val default: Any?,
    val options: List<String>? = null,
)

// ── 编辑历史相关 ─────────────────────────────────────────────

/** 用户内容编辑记录 */
data class GHUserContentEdit(
    val id: String,
    val createdAt: String,
    val diff: String?,
    val editor: GHOwner?,
    val deletedBy: GHOwner? = null,
)

/** 编辑历史连接（用于分页） */
data class GHUserContentEditConnection(
    val totalCount: Int,
    val pageInfo: GHPageInfo,
    val edges: List<GHUserContentEditEdge>?,
    val nodes: List<GHUserContentEdit>?,
)

data class GHUserContentEditEdge(
    val cursor: String,
    val node: GHUserContentEdit,
)

data class GHPageInfo(
    val startCursor: String?,
    val endCursor: String?,
    val hasNextPage: Boolean,
    val hasPreviousPage: Boolean,
)


data class GHWorkflow(
    val id: Long,
    val name: String,
    val path: String,
    val state: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("html_url") val htmlUrl: String,
)

data class GHWorkflowRun(
    val id: Long,
    val name: String?,
    @SerializedName("display_title") val displayTitle: String?,
    val status: String?,
    val conclusion: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String?,
    @SerializedName("run_started_at") val runStartedAt: String?,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("workflow_id") val workflowId: Long,
    @SerializedName("head_branch") val headBranch: String?,
    @SerializedName("head_sha") val headSha: String?,
    val actor: GHOwner?,
    val path: String?,
    @SerializedName("run_number") val runNumber: Int?,
    val event: String?,
)

data class GHWorkflowJob(
    val id: Long,
    val name: String?,
    val status: String?,
    val conclusion: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("started_at") val startedAt: String?,
    @SerializedName("completed_at") val completedAt: String?,
    @SerializedName("html_url") val htmlUrl: String,
    val steps: List<GHWorkflowStep>?,
)

data class GHWorkflowStep(
    val name: String?,
    val status: String?,
    val conclusion: String?,
    val number: Int,
    @SerializedName("started_at") val startedAt: String?,
    @SerializedName("completed_at") val completedAt: String?,
)

data class GHWorkflowDispatchRequest(
    val ref: String,
    val inputs: Map<String, Any>? = null,
)

data class GHWorkflowInput(
    val type: String,
    val description: String?,
    val default: Any?,
    val required: Boolean = false,
    val options: List<String>? = null,
)

data class GHWorkflowFile(
    val name: String,
    val content: String,
)

data class GHActionsListResponse<T>(
    @SerializedName("total_count") val totalCount: Int,
    val workflowRuns: List<T>? = null,
    val workflows: List<T>? = null,
)

data class GHWorkflowsResponse(
    @SerializedName("total_count") val totalCount: Int,
    val workflows: List<GHWorkflow>,
)

data class GHWorkflowRunsResponse(
    @SerializedName("total_count") val totalCount: Int,
    @SerializedName("workflow_runs") val workflowRuns: List<GHWorkflowRun>,
)

data class GHWorkflowJobsResponse(
    @SerializedName("total_count") val totalCount: Int,
    val jobs: List<GHWorkflowJob>,
)

data class GHWorkflowArtifact(
    val id: Long,
    @SerializedName("node_id") val nodeId: String,
    val name: String,
    @SerializedName("size_in_bytes") val sizeInBytes: Long,
    val url: String,
    @SerializedName("archive_download_url") val archiveDownloadUrl: String,
    val expired: Boolean,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    val digest: String,
    @SerializedName("workflow_run") val workflowRun: GHWorkflowRunRef,
)

data class GHWorkflowRunRef(
    val id: Long,
    @SerializedName("repository_id") val repositoryId: Long,
    @SerializedName("head_branch") val headBranch: String,
    @SerializedName("head_sha") val headSha: String,
)

data class GHWorkflowArtifactsResponse(
    @SerializedName("total_count") val totalCount: Int,
    val artifacts: List<GHWorkflowArtifact>,
)

// ── 仓库订阅（Watch）─────────────────────────────────────────────

/**
 * GitHub 仓库订阅状态
 * PUT /repos/{owner}/{repo}/subscription
 *   subscribed=true,  ignored=false → 所有活动
 *   subscribed=false, ignored=true  → 忽略
 *   DELETE /repos/{owner}/{repo}/subscription  → 仅参与后@提及（默认）
 *
 * "自定义"的细粒度订阅 GitHub REST API 不支持，
 * 用本地 SharedPreferences 存储用户偏好，并对服务端设为 subscribed=true。
 */
data class GHRepoSubscription(
    val subscribed: Boolean,
    val ignored: Boolean,
    @SerializedName("created_at") val createdAt: String? = null,
    val reason: String? = null,
)

data class GHRepoSubscriptionRequest(
    val subscribed: Boolean,
    val ignored: Boolean,
)

// ── Issue Template（YAML Forms / Markdown front matter）──────────

/**
 * 解析后的 Issue 模板
 */
data class IssueTemplate(
    val name: String,         // 模板名称（供用户选择）
    val about: String = "",   // 简介
    val title: String = "",   // 预填标题
    val labels: List<String> = emptyList(),
    val body: String = "",    // Markdown 模板正文（.md 格式）
    val fields: List<IssueField> = emptyList(), // YAML Forms 字段（.yml 格式）
    val isForm: Boolean = false, // true=YAML Forms，false=Markdown
)

/** YAML Forms 字段类型 */
sealed class IssueField {
    abstract val id: String
    abstract val label: String
    abstract val description: String
    abstract val required: Boolean

    data class InputField(
        override val id: String,
        override val label: String,
        override val description: String = "",
        override val required: Boolean = false,
        val placeholder: String = "",
        val value: String = "",
    ) : IssueField()

    data class TextareaField(
        override val id: String,
        override val label: String,
        override val description: String = "",
        override val required: Boolean = false,
        val placeholder: String = "",
        val value: String = "",
        val render: String = "",
    ) : IssueField()

    data class DropdownField(
        override val id: String,
        override val label: String,
        override val description: String = "",
        override val required: Boolean = false,
        val options: List<String> = emptyList(),
        val multiple: Boolean = false,
        val selectedIndices: Set<Int> = emptySet(),
    ) : IssueField()

    data class CheckboxesField(
        override val id: String,
        override val label: String,
        override val description: String = "",
        override val required: Boolean = false,
        val options: List<CheckboxOption> = emptyList(),
    ) : IssueField()

    data class MarkdownField(
        override val id: String,
        override val label: String = "",
        override val description: String = "",
        override val required: Boolean = false,
        val value: String = "",  // 展示用纯文本
    ) : IssueField()
}

data class CheckboxOption(
    val label: String,
    val required: Boolean = false,
    val checked: Boolean = false,
)

// ─── Git Data API (upload / commit multiple files) ────────────────────────────

/** POST /git/blobs */
data class GHCreateBlobRequest(
    val content: String,   // base64
    val encoding: String = "base64",
)
data class GHBlobResponse(val sha: String, val url: String)

/** POST /git/trees — 每个文件一个 TreeItem */
data class GHTreeItem(
    val path: String,
    val mode: String = "100644",   // 普通文件
    val type: String = "blob",
    val sha: String? = null,        // 子模块用
    val content: String? = null,     // 普通文件/符号链接/.gitmodules用（原始内容，非Base64）
)
data class GHCreateTreeRequest(
    val tree: List<GHTreeItem>,
    @SerializedName("base_tree") val baseTree: String? = null,
)
data class GHTreeResponse(val sha: String, val url: String)

/**
 * GitHub Stargazer - 星标用户信息
 * 包含用户信息和星标时间
 */
data class GHStargazer(
    val login: String,
    val id: Long,
    @SerializedName("node_id") val nodeId: String,
    @SerializedName("avatar_url") val avatarUrl: String?,
    @SerializedName("gravatar_id") val gravatarId: String?,
    val url: String,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("followers_url") val followersUrl: String,
    @SerializedName("following_url") val followingUrl: String,
    @SerializedName("gists_url") val gistsUrl: String,
    @SerializedName("starred_url") val starredUrl: String,
    @SerializedName("subscriptions_url") val subscriptionsUrl: String,
    @SerializedName("organizations_url") val organizationsUrl: String,
    @SerializedName("repos_url") val reposUrl: String,
    @SerializedName("events_url") val eventsUrl: String,
    @SerializedName("received_events_url") val receivedEventsUrl: String,
    val type: String,
    @SerializedName("site_admin") val siteAdmin: Boolean,
    @SerializedName("starred_at") val starredAt: String? = null,
)

/**
 * 复刻列表排序方式
 */
enum class ForkSortBy(val displayName: String, val apiValue: String) {
    NEWEST("最新", "newest"),
    OLDEST("最早", "oldest"),
    STARGAZERS("最多星标", "stargazers");
}

// ── Search: Code ──────────────────────────────────────────────────────────────

/**
 * 代码搜索结果中的仓库信息（精简版，避免 GHRepo 必填字段反序列化失败）
 */
data class GHCodeRepository(
    val id: Long,
    val name: String,
    @SerializedName("full_name") val fullName: String,
    @SerializedName("html_url") val htmlUrl: String,
    val description: String? = null,
    @SerializedName("private") val isPrivate: Boolean = false,
    val owner: GHOwner,
)

/**
 * 代码搜索结果中的文本匹配段落（需要 Accept: text-match 头）
 */
data class GHTextMatch(
    val fragment: String = "",
    val matches: List<GHCodeMatch> = emptyList(),
)

data class GHCodeMatch(
    val text: String = "",
    val indices: List<Int> = emptyList(),
)

/**
 * /search/code 接口返回的单条代码文件结果
 */
data class GHCodeResult(
    val name: String,
    val path: String,
    val sha: String,
    @SerializedName("html_url") val htmlUrl: String,
    val repository: GHCodeRepository,
    @SerializedName("text_matches") val textMatches: List<GHTextMatch> = emptyList(),
)

// ── Search: Users / Orgs ──────────────────────────────────────────────────────

/**
 * /search/users 接口返回的单条用户或组织结果
 */
data class GHSearchUser(
    val id: Long = 0,
    val login: String,
    @SerializedName("avatar_url") val avatarUrl: String?,
    @SerializedName("html_url") val htmlUrl: String,
    val type: String = "User",   // "User" | "Organization"
    val score: Double = 0.0,
)

/**
 * GitHub Compare API 响应数据模型
 * 用于比较两个分支/提交的差异
 */
data class GHCompareResult(
    val url: String,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("base_commit") val baseCommit: GHCommit,
    @SerializedName("merge_base_commit") val mergeBaseCommit: GHCommit,
    val status: String,
    @SerializedName("ahead_by") val aheadBy: Int,
    @SerializedName("behind_by") val behindBy: Int,
    @SerializedName("total_commits") val totalCommits: Int,
    val commits: List<GHCommit>,
    val files: List<GHCommitFile>?,
)
