package com.gitmob.android.api

import retrofit2.Response
import retrofit2.http.*

interface GitHubApi {

    // ── User ──
    @GET("user")
    suspend fun getCurrentUser(): GHUser

    @GET("user")
    suspend fun getCurrentUserWithResponse(): retrofit2.Response<GHUser>

    @GET("users/{login}")
    suspend fun getUser(@Path("login") login: String): GHUser

    @GET("users/{login}/followers")
    suspend fun getFollowers(@Path("login") login: String, @Query("per_page") perPage: Int = 100): List<GHUser>

    @GET("users/{login}/following")
    suspend fun getFollowing(@Path("login") login: String, @Query("per_page") perPage: Int = 100): List<GHUser>

    @GET("user/followers")
    suspend fun getMyFollowers(@Query("per_page") perPage: Int = 100): List<GHUser>

    @GET("user/following")
    suspend fun getMyFollowing(@Query("per_page") perPage: Int = 100): List<GHUser>

    // ── Repos ──
    @GET("user/repos")
    suspend fun getMyRepos(
        @Query("type") type: String = "owner",
        @Query("sort") sort: String = "updated",
        @Query("per_page") perPage: Int = 50,
        @Query("page") page: Int = 1,
    ): List<GHRepo>

    @GET("users/{login}/repos")
    suspend fun getUserRepos(
        @Path("login") login: String,
        @Query("type") type: String = "owner",
        @Query("sort") sort: String = "updated",
        @Query("per_page") perPage: Int = 50,
        @Query("page") page: Int = 1,
    ): List<GHRepo>

    @GET("users/{login}/starred")
    suspend fun getUserStarred(
        @Path("login") login: String,
        @Query("sort") sort: String = "created",
        @Query("direction") direction: String = "desc",
        @Query("per_page") perPage: Int = 50,
        @Query("page") page: Int = 1,
    ): List<GHRepo>

    @GET("repos/{owner}/{repo}")
    suspend fun getRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): GHRepo

    @POST("user/repos")
    suspend fun createRepo(@Body body: GHCreateRepoRequest): GHRepo

    @POST("orgs/{org}/repos")
    suspend fun createOrgRepo(
        @Path("org") org: String,
        @Body body: GHCreateRepoRequest,
    ): GHRepo

    @DELETE("repos/{owner}/{repo}")
    suspend fun deleteRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): Response<Unit>

    @POST("repos/{owner}/{repo}/forks")
    suspend fun createFork(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: GHCreateForkRequest,
    ): GHRepo

    // ── Contents ──
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getContents(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String = "",
        @Query("ref") ref: String? = null,
    ): List<GHContent>

    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String,
        @Query("ref") ref: String? = null,
    ): GHContent

    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun createOrUpdateFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String,
        @Body body: GHCreateFileRequest,
    ): GHCreateFileResponse

    @HTTP(method = "DELETE", path = "repos/{owner}/{repo}/contents/{path}", hasBody = true)
    suspend fun deleteFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String,
        @Body body: GHDeleteFileRequest,
    ): Response<Unit>

    // ── Commits ──
    @GET("repos/{owner}/{repo}/commits")
    suspend fun getCommits(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("sha") sha: String? = null,
        @Query("path") path: String? = null,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1,
    ): List<GHCommit>

    @GET("repos/{owner}/{repo}/commits/{ref}")
    suspend fun getCommit(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("ref") ref: String,
    ): GHCommit

    // ── Branches ──
    @GET("repos/{owner}/{repo}/branches")
    suspend fun getBranches(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 100,
    ): List<GHBranch>

    @GET("repos/{owner}/{repo}/branches/{branch}")
    suspend fun getBranch(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String,
    ): GHBranch

    @POST("repos/{owner}/{repo}/git/refs")
    suspend fun createBranch(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: GHCreateBranchRequest,
    ): GHRef

    @DELETE("repos/{owner}/{repo}/git/refs/heads/{branch}")
    suspend fun deleteBranch(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String,
    ): Response<Unit>

    /**
     * 同步复刻分支与上游仓库
     */
    @POST("repos/{owner}/{repo}/merge-upstream")
    suspend fun syncForkBranch(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: GHSyncForkRequest,
    ): GHSyncForkResponse

    // ── Pull Requests ──
    @GET("repos/{owner}/{repo}/pulls")
    suspend fun getPullRequests(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("state")     state:     String  = "open",
        @Query("sort")      sort:      String  = "created",
        @Query("direction") direction: String  = "desc",
        @Query("per_page")  perPage:   Int     = 30,
        @Query("page")      page:      Int     = 1,
        @Query("labels")    labels:    String? = null,
    ): List<GHPullRequest>

    /** Search Issues API — 支持 author/review-requested 等高级 PR 筛选 */
    @GET("search/issues")
    suspend fun searchPullRequests(
        @Query("q")        query:   String,
        @Query("sort")     sort:    String = "created",
        @Query("order")    order:   String = "desc",
        @Query("per_page") perPage: Int    = 30,
        @Query("page")     page:    Int    = 1,
    ): GHSearchResult<GHIssue>

    @GET("repos/{owner}/{repo}/pulls/{pull_number}")
    suspend fun getPullRequest(
        @Path("owner")       owner:      String,
        @Path("repo")        repo:       String,
        @Path("pull_number") pullNumber: Int,
    ): GHPullRequest

    @POST("repos/{owner}/{repo}/pulls")
    suspend fun createPullRequest(
        @Path("owner") owner: String,
        @Path("repo")  repo:  String,
        @Body body: GHCreatePullRequestRequest,
    ): GHPullRequest

    @PATCH("repos/{owner}/{repo}/pulls/{pull_number}")
    suspend fun updatePullRequest(
        @Path("owner")       owner:      String,
        @Path("repo")        repo:       String,
        @Path("pull_number") pullNumber: Int,
        @Body body: GHUpdatePullRequestRequest,
    ): GHPullRequest

    @PUT("repos/{owner}/{repo}/pulls/{pull_number}/merge")
    suspend fun mergePullRequest(
        @Path("owner")       owner:      String,
        @Path("repo")        repo:       String,
        @Path("pull_number") pullNumber: Int,
        @Body body: GHMergePRRequest,
    ): GHMergePRResponse

    @PUT("repos/{owner}/{repo}/pulls/{pull_number}/update-branch")
    suspend fun updatePRBranch(
        @Path("owner")       owner:      String,
        @Path("repo")        repo:       String,
        @Path("pull_number") pullNumber: Int,
        @Body body: GHUpdateBranchRequest = GHUpdateBranchRequest(),
    ): GHUpdateBranchResponse

    @GET("repos/{owner}/{repo}/pulls/{pull_number}/reviews")
    suspend fun getPRReviews(
        @Path("owner")       owner:      String,
        @Path("repo")        repo:       String,
        @Path("pull_number") pullNumber: Int,
    ): List<GHReview>

    @POST("repos/{owner}/{repo}/pulls/{pull_number}/reviews")
    suspend fun createPRReview(
        @Path("owner")       owner:      String,
        @Path("repo")        repo:       String,
        @Path("pull_number") pullNumber: Int,
        @Body body: GHCreateReviewRequest,
    ): GHReview

    @POST("repos/{owner}/{repo}/pulls/{pull_number}/requested_reviewers")
    suspend fun requestReviewers(
        @Path("owner")       owner:      String,
        @Path("repo")        repo:       String,
        @Path("pull_number") pullNumber: Int,
        @Body body: GHReviewersRequest,
    ): GHPullRequest

    @DELETE("repos/{owner}/{repo}/pulls/{pull_number}/requested_reviewers")
    suspend fun removeReviewers(
        @Path("owner")       owner:      String,
        @Path("repo")        repo:       String,
        @Path("pull_number") pullNumber: Int,
        @Body body: GHReviewersRequest,
    ): Response<Unit>

    @GET("repos/{owner}/{repo}/issues/{issue_number}/comments")
    suspend fun getPRComments(
        @Path("owner")        owner:       String,
        @Path("repo")         repo:        String,
        @Path("issue_number") issueNumber: Int,
        @Query("per_page") perPage: Int = 50,
    ): List<GHComment>

    @POST("repos/{owner}/{repo}/issues/{issue_number}/comments")
    suspend fun addPRComment(
        @Path("owner")        owner:       String,
        @Path("repo")         repo:        String,
        @Path("issue_number") issueNumber: Int,
        @Body body: GHCreateCommentRequest,
    ): GHComment

    // ── Labels ──
    @GET("repos/{owner}/{repo}/labels")
    suspend fun getLabels(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 100,
        @Query("page") page: Int = 1,
    ): List<GHLabel>

    // ── Issues ──
    @GET("repos/{owner}/{repo}/issues")
    suspend fun getIssues(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("state") state: String = "open",
        @Query("labels") labels: String? = null,
        @Query("creator") creator: String? = null,
        @Query("sort") sort: String = "created",
        @Query("direction") direction: String = "desc",
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1,
    ): List<GHIssue>

    @POST("repos/{owner}/{repo}/issues")
    suspend fun createIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: GHCreateIssueRequest,
    ): GHIssue

    @GET("repos/{owner}/{repo}/issues/{issueNumber}")
    suspend fun getIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("issueNumber") issueNumber: Int,
    ): GHIssue

    @PATCH("repos/{owner}/{repo}/issues/{issueNumber}")
    suspend fun updateIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("issueNumber") issueNumber: Int,
        @Body body: GHUpdateIssueRequest,
    ): GHIssue

    @DELETE("repos/{owner}/{repo}/issues/{issueNumber}")
    suspend fun deleteIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("issueNumber") issueNumber: Int,
    ): Response<Unit>

    @PUT("repos/{owner}/{repo}/issues/{issueNumber}/lock")
    suspend fun lockIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("issueNumber") issueNumber: Int,
    ): Response<Unit>

    @DELETE("repos/{owner}/{repo}/issues/{issueNumber}/lock")
    suspend fun unlockIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("issueNumber") issueNumber: Int,
    ): Response<Unit>

    @GET("repos/{owner}/{repo}/issues/{issueNumber}/subscription")
    suspend fun getIssueSubscription(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("issueNumber") issueNumber: Int,
    ): GHIssueSubscription

    @PUT("repos/{owner}/{repo}/issues/{issueNumber}/subscription")
    suspend fun subscribeIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("issueNumber") issueNumber: Int,
    ): GHIssueSubscription

    @DELETE("repos/{owner}/{repo}/issues/{issueNumber}/subscription")
    suspend fun unsubscribeIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("issueNumber") issueNumber: Int,
    ): Response<Unit>

    // ── Issue Comments ──
    @GET("repos/{owner}/{repo}/issues/{issueNumber}/comments")
    suspend fun getIssueComments(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("issueNumber") issueNumber: Int,
        @Query("per_page") perPage: Int = 100,
    ): List<GHComment>

    @POST("repos/{owner}/{repo}/issues/{issueNumber}/comments")
    suspend fun createIssueComment(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("issueNumber") issueNumber: Int,
        @Body body: GHCreateCommentRequest,
    ): GHComment

    @GET("repos/{owner}/{repo}/issues/comments/{commentId}")
    suspend fun getIssueComment(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("commentId") commentId: Long,
    ): GHComment

    @PATCH("repos/{owner}/{repo}/issues/comments/{commentId}")
    suspend fun updateIssueComment(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("commentId") commentId: Long,
        @Body body: GHUpdateCommentRequest,
    ): GHComment

    @DELETE("repos/{owner}/{repo}/issues/comments/{commentId}")
    suspend fun deleteIssueComment(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("commentId") commentId: Long,
    ): Response<Unit>

    // ── Releases ──
    @GET("repos/{owner}/{repo}/releases")
    suspend fun getReleases(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 20,
    ): List<GHRelease>

    @PATCH("repos/{owner}/{repo}/releases/{releaseId}")
    suspend fun updateRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("releaseId") releaseId: Long,
        @Body body: UpdateReleaseRequest,
    ): GHRelease

    @DELETE("repos/{owner}/{repo}/releases/{releaseId}")
    suspend fun deleteRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("releaseId") releaseId: Long,
    ): retrofit2.Response<Unit>

    // ── Git Data API (multi-file commit) ──────────────────────────────────────
    /** 获取分支最新 commit SHA：GET /repos/{owner}/{repo}/git/refs/heads/{branch} */
    @GET("repos/{owner}/{repo}/git/refs/heads/{branch}")
    suspend fun getRef(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String,
    ): GHRef

    /** 创建 blob（单个文件内容）：POST /repos/{owner}/{repo}/git/blobs */
    @POST("repos/{owner}/{repo}/git/blobs")
    suspend fun createBlob(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: GHCreateBlobRequest,
    ): GHBlobResponse

    /** 创建 tree（包含多个 blob）：POST /repos/{owner}/{repo}/git/trees */
    @POST("repos/{owner}/{repo}/git/trees")
    suspend fun createTree(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: GHCreateTreeRequest,
    ): GHTreeResponse

    // ── Search ──
    @GET("search/repositories")
    suspend fun searchRepos(
        @Query("q") query: String,
        @Query("sort") sort: String = "stars",
        @Query("per_page") perPage: Int = 20,
        @Query("page") page: Int = 1,
    ): GHSearchResult<GHRepo>

    // ── Star ──
    @PUT("user/starred/{owner}/{repo}")
    suspend fun starRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): Response<Unit>

    @DELETE("user/starred/{owner}/{repo}")
    suspend fun unstarRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): Response<Unit>

    @GET("user/starred/{owner}/{repo}")
    suspend fun checkStarred(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): Response<Unit>
    // ── Orgs ──
    @GET("user/orgs")
    suspend fun getUserOrgs(@Query("per_page") perPage: Int = 50): List<GHOrg>

    @GET("users/{login}/orgs")
    suspend fun getUserOrgs(@Path("login") login: String, @Query("per_page") perPage: Int = 50): List<GHOrg>

    @GET("orgs/{org}/repos")
    suspend fun getOrgRepos(
        @Path("org") org: String,
        @Query("sort") sort: String = "updated",
        @Query("per_page") perPage: Int = 50,
    ): List<GHRepo>

    // ── Repo PATCH (rename / edit) ──
    @PATCH("repos/{owner}/{repo}")
    suspend fun updateRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: GHUpdateRepoRequest,
    ): GHRepo

    // ── Repo transfer ──
    @POST("repos/{owner}/{repo}/transfer")
    suspend fun transferRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: GHTransferRepoRequest,
    ): GHRepo

    // ── Topics ──
    @Headers("Accept: application/vnd.github.mercy-preview+json")
    @GET("repos/{owner}/{repo}/topics")
    suspend fun getTopics(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): GHTopics

    @Headers("Accept: application/vnd.github.mercy-preview+json")
    @PUT("repos/{owner}/{repo}/topics")
    suspend fun replaceTopics(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: GHTopics,
    ): GHTopics

    // ── Branch rename ──
    @POST("repos/{owner}/{repo}/branches/{branch}/rename")
    suspend fun renameBranch(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String,
        @Body body: GHRenameBranchRequest,
    ): GHBranch

    // ── Commit detail (full with files) ──
    @GET("repos/{owner}/{repo}/commits/{sha}")
    suspend fun getCommitFull(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("sha") sha: String,
    ): GHCommitFull

    // ── Git Data API (Reset / Revert) ──────────────────────────────────────

    /** 获取 Git 提交对象（含 tree SHA，用于构造 revert commit） */
    @GET("repos/{owner}/{repo}/git/commits/{sha}")
    suspend fun getGitCommit(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("sha") sha: String,
    ): GHGitCommit

    /** 创建新提交（revert commit 的核心步骤） */
    @POST("repos/{owner}/{repo}/git/commits")
    suspend fun createCommit(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: GHCreateCommitRequest,
    ): GHCreateCommitResponse

    /** 强制更新分支指针（回滚 = force push to target SHA） */
    @PATCH("repos/{owner}/{repo}/git/refs/heads/{branch}")
    suspend fun updateRef(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String,
        @Body body: GHUpdateRefRequest,
    ): GHRef

    // ── Check name availability ──
    @GET("repos/{owner}/{repo}")
    suspend fun checkRepoExists(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): retrofit2.Response<GHRepo>

    // ── GitHub Actions ──
    @GET("repos/{owner}/{repo}/actions/workflows")
    suspend fun getWorkflows(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 30,
    ): GHWorkflowsResponse

    @GET("repos/{owner}/{repo}/actions/runs")
    suspend fun getWorkflowRuns(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1,
    ): GHWorkflowRunsResponse

    @GET("repos/{owner}/{repo}/actions/workflows/{workflowId}/runs")
    suspend fun getWorkflowRunsForWorkflow(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("workflowId") workflowId: Long,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1,
    ): GHWorkflowRunsResponse

    @GET("repos/{owner}/{repo}/actions/runs/{runId}/jobs")
    suspend fun getWorkflowJobs(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("runId") runId: Long,
    ): GHWorkflowJobsResponse

    @POST("repos/{owner}/{repo}/actions/workflows/{workflowId}/dispatches")
    suspend fun dispatchWorkflow(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("workflowId") workflowId: Long,
        @Body body: GHWorkflowDispatchRequest,
    ): Response<Unit>

    @DELETE("repos/{owner}/{repo}/actions/runs/{runId}")
    suspend fun deleteWorkflowRun(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("runId") runId: Long,
    ): Response<Unit>

    @POST("repos/{owner}/{repo}/actions/runs/{runId}/rerun")
    suspend fun rerunWorkflow(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("runId") runId: Long,
    ): Response<Unit>

    @POST("repos/{owner}/{repo}/actions/runs/{runId}/cancel")
    suspend fun cancelWorkflow(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("runId") runId: Long,
    ): Response<Unit>

    @GET("repos/{owner}/{repo}/actions/runs/{runId}/logs")
    suspend fun getWorkflowLogs(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("runId") runId: Long,
    ): Response<okhttp3.ResponseBody>

    @GET("repos/{owner}/{repo}/actions/runs/{runId}/artifacts")
    suspend fun getWorkflowRunArtifacts(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("runId") runId: Long,
    ): GHWorkflowArtifactsResponse

    @DELETE("repos/{owner}/{repo}/actions/artifacts/{artifactId}")
    suspend fun deleteArtifact(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("artifactId") artifactId: Long,
    ): Response<Unit>

    // ── 仓库订阅（Watch）──────────────────────────────────────────
    @GET("repos/{owner}/{repo}/subscription")
    suspend fun getRepoSubscription(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): retrofit2.Response<GHRepoSubscription>

    @PUT("repos/{owner}/{repo}/subscription")
    suspend fun setRepoSubscription(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: GHRepoSubscriptionRequest,
    ): GHRepoSubscription

    @DELETE("repos/{owner}/{repo}/subscription")
    suspend fun deleteRepoSubscription(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): retrofit2.Response<Unit>

    // ── Release Asset 元数据 ──────────────────────────────────────
    @GET("repos/{owner}/{repo}/releases/assets/{assetId}")
    suspend fun getReleaseAsset(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("assetId") assetId: Long,
    ): GHAsset

    // ── Stargazers ──
    @GET("repos/{owner}/{repo}/stargazers")
    suspend fun getStargazers(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 50,
        @Query("page") page: Int = 1,
    ): List<GHStargazer>

    // ── Forks ──
    @GET("repos/{owner}/{repo}/forks")
    suspend fun getForks(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("sort") sort: String = "newest", // newest, oldest, stargazers
        @Query("per_page") perPage: Int = 50,
        @Query("page") page: Int = 1,
    ): List<GHRepo>
    
    // ── Compare ──
    @GET("repos/{owner}/{repo}/compare/{base}...{head}")
    suspend fun compareCommits(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("base") base: String,
        @Path("head") head: String,
    ): GHCompareResult

    // ── Global Search ─────────────────────────────────────────────────────────

    /** 搜索代码 — GET /search/code */
    @Headers("Accept: application/vnd.github.text-match+json")
    @GET("search/code")
    suspend fun searchCode(
        @Query("q") query: String,
        @Query("per_page") perPage: Int = 20,
        @Query("page") page: Int = 1,
    ): GHSearchResult<GHCodeResult>

    /** 搜索 Issues 或 PR — GET /search/issues（q 中追加 type:issue / type:pr）*/
    @GET("search/issues")
    suspend fun searchIssues(
        @Query("q") query: String,
        @Query("sort") sort: String = "updated",
        @Query("per_page") perPage: Int = 20,
        @Query("page") page: Int = 1,
    ): GHSearchResult<GHIssue>

    /** 搜索用户或组织 — GET /search/users（q 中追加 type:user / type:org）*/
    @GET("search/users")
    suspend fun searchUsers(
        @Query("q") query: String,
        @Query("per_page") perPage: Int = 20,
        @Query("page") page: Int = 1,
    ): GHSearchResult<GHSearchUser>
}
