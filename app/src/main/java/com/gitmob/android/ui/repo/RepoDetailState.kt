package com.gitmob.android.ui.repo

import com.gitmob.android.api.*

data class FilePatchInfo(
    val filename: String,
    val patch: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String = "modified",          // added|removed|modified|renamed
    val parentSha: String? = null,            // 父提交 SHA（用于获取旧文件内容）
    val previousFilename: String? = null,     // renamed 时的旧文件名
    val owner: String = "",
    val repoName: String = "",
    val currentSha: String = "",              // 当前提交 SHA
    val currentBranch: String = "",
)


enum class WatchMode {
    ALL_ACTIVITY,   // 所有活动
    PARTICIPATING,  // 仅参与后@提及（GitHub 默认）
    IGNORE,         // 忽略
}


data class RepoDetailState(
    val repo: GHRepo? = null,
    val currentRepoName: String = "",  // 动态维护的仓库名
    val branches: List<GHBranch> = emptyList(),
    val commits: List<GHCommit> = emptyList(),
    val contents: List<GHContent> = emptyList(),
    val currentPath: String = "",
    val currentBranch: String = "",
    val isStarred: Boolean = false,
    val loading: Boolean = false,
    val contentsLoading: Boolean = false,
    val error: String? = null,
    /** 仓库已被删除（HTTP 404），供收藏场景显示提示 */
    val repoNotFound: Boolean = false,
    val tab: Int = 0,
    val prs: List<GHPullRequest> = emptyList(),
    val prsLoaded: Boolean = false,  // PR 列表是否已加载过
    val labels: List<GHLabel> = emptyList(),
    // PR 筛选状态
    val prFilterState: PRFilterState = PRFilterState(),
    // PR 分页
    val prsPage: Int = 1,
    val prsHasMore: Boolean = false,
    val prsLoadingMore: Boolean = false,
    // PR 详情（当前打开的 PR）
    val selectedPR: GHPullRequest? = null,
    val prReviews: List<GHReview> = emptyList(),
    val prComments: List<GHComment> = emptyList(),
    val prDetailLoading: Boolean = false,
    val prOpInProgress: Boolean = false,
    val prOpResult: String? = null,   // 成功/失败消息
    val issues: List<GHIssue> = emptyList(),
    val issuesLoaded: Boolean = false,  // Issues 列表是否已加载过
    val selectedCommit: GHCommitFull? = null,
    val commitDetailLoading: Boolean = false,
    val selectedFilePatch: FilePatchInfo? = null,
    // Reset / Revert 操作状态
    val gitOpInProgress: Boolean = false,   // 操作进行中（显示 loading）
    val gitOpResult: GitOpResult? = null,   // 操作结果（成功/失败弹窗）
    val toast: String? = null,
    // 用户 / 组织，用于仓库转移
    val userLogin: String = "",
    val userAvatar: String = "",
    val userOrgs: List<GHOrg> = emptyList(),
    // Releases
    val releases: List<GHRelease> = emptyList(),
    // GitHub Actions
    val workflows: List<GHWorkflow> = emptyList(),
    val selectedWorkflow: GHWorkflow? = null,
    val allWorkflowRuns: List<GHWorkflowRun> = emptyList(),  // 所有工作流运行记录
    val workflowRuns: List<GHWorkflowRun> = emptyList(),       // 当前显示的运行记录（可能已筛选）
    val selectedWorkflowRun: GHWorkflowRun? = null,
    val workflowJobs: List<GHWorkflowJob> = emptyList(),
    val workflowArtifacts: List<GHWorkflowArtifact> = emptyList(),
    val workflowLogs: Map<String, String>? = null,  // 解析后的日志，key 为文件名
    val workflowLogsLoading: Boolean = false,
    val workflowInputs: List<WorkflowInput> = emptyList(),
    val workflowInputsLoading: Boolean = false,
    // 工作流运行记录分页状态
    val workflowRunsPage: Int = 1,
    val workflowRunsHasMore: Boolean = false,
    val workflowRunsLoadingMore: Boolean = false,
    // 各Tab刷新状态
    val filesRefreshing: Boolean = false,
    val commitsRefreshing: Boolean = false,
    val branchesRefreshing: Boolean = false,
    val actionsRefreshing: Boolean = false,
    val releasesRefreshing: Boolean = false,
    val prsRefreshing: Boolean = false,
    val issuesRefreshing: Boolean = false,
    // Issues筛选状态
    val issueFilterState: IssueFilterState = IssueFilterState(),
    // Issues分页状态
    val issuesPage: Int = 1,
    val issuesHasMore: Boolean = false,
    val issuesLoadingMore: Boolean = false,
    // Discussions（GraphQL only）
    val discussions: List<GHDiscussion> = emptyList(),
    val discussionCategories: List<GHDiscussionCategory> = emptyList(),
    val discussionsLoading: Boolean = false,
    val discussionsLoadingMore: Boolean = false,
    val discussionsHasMore: Boolean = false,
    val discussionsCursor: String? = null,
    val discussionCategoryFilter: String? = null,  // categoryId
    val discussionAnsweredFilter: Boolean? = null,   // null=全部 true=已回答 false=未回答
    val discussionFilterState: DiscussionFilterState = DiscussionFilterState(),
    val discussionsRefreshing: Boolean = false,
    // README
    val readmeLoading: Boolean = false,
    val readmeContent: String? = null,
    // 仓库订阅
    val subscription: GHRepoSubscription? = null,
    val subscriptionLoading: Boolean = false,
    // Issue Templates
    val issueTemplates: List<IssueTemplate> = emptyList(),
    val issueTemplatesLoading: Boolean = false,
    // 下载任务 ID 表，key = asset.url 或 artifact.id（UI 查询进度用）
    val downloadTaskIds: Map<String, Int> = emptyMap(),
    // ── 上传状态 ──
    val uploadPhase: UploadPhase = UploadPhase.IDLE,
    val uploadBlobProgress: Int = 0,    // 已完成 blob 数
    val uploadBlobTotal: Int = 0,       // 总 blob 数
    val uploadCurrentFile: String = "", // 正在处理的文件名
    val uploadError: String? = null,
    // 星标用户列表
    val stargazers: List<GHStargazer> = emptyList(),
    val stargazersLoading: Boolean = false,
    val stargazersPage: Int = 1,
    val stargazersHasMore: Boolean = false,
    val stargazersLoadingMore: Boolean = false,
    val showStargazersSheet: Boolean = false,
    // 复刻仓库列表
    val forks: List<GHRepo> = emptyList(),
    val forksLoading: Boolean = false,
    val forksPage: Int = 1,
    val forksHasMore: Boolean = false,
    val forksLoadingMore: Boolean = false,
    val showForksSheet: Boolean = false,
    val forkSortBy: ForkSortBy = ForkSortBy.NEWEST,
    // 文件历史
    val showFileHistorySheet: Boolean = false,
    val fileHistoryCommits: List<GHCommit> = emptyList(),
    val fileHistoryLoading: Boolean = false,
    val selectedFileForHistory: GHContent? = null,
    val selectedCommitForFileHistory: GHCommitFull? = null,
    val fileHistoryCommitDetailLoading: Boolean = false,
    // 复刻仓库同步状态
    val forkSyncLoading: Boolean = false,
    val forkSyncStatus: String? = null,
    val forkAheadBy: Int = 0,
    val forkBehindBy: Int = 0,
    val forkSyncCompareResult: GHCompareResult? = null,
    val forkSyncReverseCompareResult: GHCompareResult? = null,
    // 同步操作状态
    val forkSyncing: Boolean = false,
    val forkSyncError: String? = null,
    val forkSyncSuccess: Boolean = false,
    val forkSyncResponse: GHSyncForkResponse? = null,
    val forkDiscardingCommits: Boolean = false,
    val forkDiscardSuccess: Boolean = false,
    val forkCreatingPR: Boolean = false,
    val forkCreatePRSuccess: Boolean = false,
    val forkCreatedPR: GHPullRequest? = null,
    // 同步提交列表显示
    val showForkSyncCommits: Boolean = false,
    val forkSyncCommitsType: ForkSyncCommitsType = ForkSyncCommitsType.AHEAD,
    // 复刻仓库
    val showCreateForkDialog: Boolean = false,
    val creatingFork: Boolean = false,
    val createForkError: String? = null,
    val createForkSuccess: Boolean = false,
)

enum class ForkSyncCommitsType {
    AHEAD,
    BEHIND,
}

/**
 * 复刻列表排序方式
 */
enum class ForkSortBy(val displayName: String, val apiValue: String) {
    NEWEST("最新", "newest"),
    OLDEST("最早", "oldest"),
    STARGAZERS("最多星标", "stargazers"),
    WATCHERS("最多观看", "watchers");
}

/**
 * 复刻仓库期限筛选
 */
enum class ForkTimeFilter(val displayName: String, val months: Int) {
    ONE_MONTH("一个月", 1),
    SIX_MONTHS("六个月", 6),
    ONE_YEAR("一年", 12),
    TWO_YEARS("两年", 24),
    FIVE_YEARS("五年", 60),
    ALL("历史", 0);
}

/**
 * 复刻仓库类型筛选
 */
enum class ForkTypeFilter(val displayName: String) {
    ACTIVE("活跃"),
    INACTIVE("已停用"),
    NETWORK("网络"),
    ARCHIVED("存档"),
    STARRED("主演");
}

/**
 * 复刻仓库排序方式
 */
enum class ForkOrderBy(val displayName: String) {
    RECENTLY_UPDATED("最近更新"),
    MOST_STARS("最多星级"),
    OPEN_ISSUES("未解决的问题"),
    OPEN_PULL_REQUESTS("开放拉取请求");
}

/**
 * Issue筛选状态
 */
data class IssueFilterState(
    val status: IssueStatusFilter = IssueStatusFilter.OPEN,
    val sortBy: IssueSortBy = IssueSortBy.CREATED_DESC,
    val selectedLabels: Set<String> = emptySet(),
    val selectedCreator: String? = null,
)

/**
 * Issue状态筛选
 */
enum class IssueStatusFilter(val displayName: String, val apiValue: String) {
    OPEN("打开", "open"),
    CLOSED("关闭", "closed"),
    ALL("所有", "all");
}

/**
 * Issue排序方式（sort + direction）
 */
enum class IssueSortBy(val displayName: String, val sort: String, val direction: String) {
    CREATED_DESC("最新", "created", "desc"),
    CREATED_ASC("最早", "created", "asc"),
    UPDATED_DESC("最近更新", "updated", "desc"),
    UPDATED_ASC("最早更新", "updated", "asc"),
    COMMENTS_DESC("最多评论", "comments", "desc"),
    COMMENTS_ASC("最少评论", "comments", "asc");
}

/**
 * Discussion筛选状态
 */
data class DiscussionFilterState(
    val sortBy: DiscussionSortBy = DiscussionSortBy.UPDATED_DESC,
    val selectedCreator: String? = null,
    val selectedLabels: Set<String> = emptySet(),
)

/**
 * Discussion排序方式（sort + direction）
 */
enum class DiscussionSortBy(val displayName: String, val apiValue: String) {
    UPDATED_DESC("最近更新", "UPDATED_AT"),
    CREATED_DESC("最新创建", "CREATED_AT");
}

/** Reset / Revert 操作结果 */
data class GitOpResult(
    val success: Boolean,
    val opName: String,       // "回滚" / "撤销"
    val detail: String,       // 成功/失败详情
    val newSha: String? = null,
)

/** 上传阶段 */
enum class UploadPhase {
    IDLE,      // 空闲
    BLOBS,     // 正在创建 blob
    TREE,      // 正在构建 tree
    COMMIT,    // 正在创建 commit
    REF,       // 正在更新分支 ref
    DONE,      // 完成
    ERROR,     // 错误
}

