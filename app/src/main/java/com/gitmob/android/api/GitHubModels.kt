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
    val type: String,   // file | dir | symlink
    val name: String,
    val path: String,
    val sha: String,
    val size: Long = 0,
    val content: String? = null,   // base64, only when type==file
    val encoding: String? = null,
    @SerializedName("download_url") val downloadUrl: String?,
    @SerializedName("html_url") val htmlUrl: String?,
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
    val state: String,
    val body: String?,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("created_at") val createdAt: String,
    val user: GHOwner,
    val head: GHPRBranch,
    val base: GHPRBranch,
)

data class GHPRBranch(val label: String, val ref: String, val sha: String)

data class GHIssue(
    val number: Int,
    val title: String,
    val state: String,
    val body: String?,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String?,
    val user: GHOwner,
    val labels: List<GHLabel> = emptyList(),
    val comments: Int? = null,
    @SerializedName("pull_request") val pullRequest: Any? = null,
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
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("author_association") val authorAssociation: String?,
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

data class GHSSHKey(
    val id: Long,
    val title: String,
    val key: String,
    @SerializedName("created_at") val createdAt: String,
    val verified: Boolean,
)

data class GHCreateSSHKeyRequest(val title: String, val key: String)

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
    val sha: String,
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
