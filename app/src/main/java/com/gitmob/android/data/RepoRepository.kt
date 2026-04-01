package com.gitmob.android.data

import android.util.Base64
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.gitmob.android.api.*
import com.gitmob.android.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class RepoRepository {

    private val api get() = ApiClient.api

    // ─── 内存缓存 ─────────────────────────────────────────────────────────────
    private var reposCache: List<GHRepo>? = null
    private var reposCacheTime: Long = 0
    private val CACHE_TTL = 5 * 60 * 1000L           // 5 分钟（列表）
    private val DETAIL_TTL = 60 * 1000L               // 60 秒（详情）

    private data class Entry<T>(val data: T, val ts: Long = System.currentTimeMillis()) {
        fun valid(ttl: Long = 60_000L) = System.currentTimeMillis() - ts < ttl
    }
    private val repoDetailCache = java.util.concurrent.ConcurrentHashMap<String, Entry<GHRepo>>()
    private val branchCache     = java.util.concurrent.ConcurrentHashMap<String, Entry<List<GHBranch>>>()
    private val commitCache     = java.util.concurrent.ConcurrentHashMap<String, Entry<List<GHCommit>>>()
    // 目录内容缓存：key = "owner/repo/ref/path"，TTL = 2 分钟
    private val contentsCache   = java.util.concurrent.ConcurrentHashMap<String, Entry<List<GHContent>>>()
    private val CONTENTS_TTL    = 2 * 60 * 1000L
    // 文件内容缓存：key = "owner/repo/ref/path"，TTL = 5 分钟（文件内容变化更少）
    private val fileContentCache = java.util.concurrent.ConcurrentHashMap<String, Entry<String>>()
    private val FILE_TTL         = 5 * 60 * 1000L
    // PR / Issue / Release 缓存：TTL = 2 分钟
    private val prCache       = java.util.concurrent.ConcurrentHashMap<String, Entry<List<GHPullRequest>>>()
    private val issueCache    = java.util.concurrent.ConcurrentHashMap<String, Entry<List<GHIssue>>>()
    private val releaseCache  = java.util.concurrent.ConcurrentHashMap<String, Entry<List<GHRelease>>>()
    private val commitDetailCache = java.util.concurrent.ConcurrentHashMap<String, Entry<GHCommitFull>>()
    private val subscriptionCache = java.util.concurrent.ConcurrentHashMap<String, Entry<GHRepoSubscription?>>()
    private val LIST_TTL      = 2 * 60 * 1000L   // 列表 2 分钟
    private val COMMIT_TTL    = 10 * 60 * 1000L  // commit detail 10 分钟（不变）

    // ─── 用户 / 仓库 ───

    suspend fun getMyRepos(forceRefresh: Boolean = false): List<GHRepo> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && reposCache != null && (now - reposCacheTime) < CACHE_TTL) {
            return@withContext reposCache!!
        }
        // 完全使用 GraphQL API 获取仓库列表
        val token = ApiClient.currentToken() ?: return@withContext emptyList()
        val data = GraphQLClient.queryUserRepos(token) ?: return@withContext emptyList()
        val nodes = data.optJSONArray("nodes") ?: return@withContext emptyList()
        val repos = (0 until nodes.length()).mapNotNull { mapGraphQLToGHRepo(nodes.getJSONObject(it)) }
        reposCache = repos
        reposCacheTime = now
        repos
    }

    fun invalidateCommitCache(owner: String, repo: String, sha: String) {
        commitCache.remove("$owner/$repo/$sha")
    }

    fun invalidateReposCache() {
        reposCache = null
        reposCacheTime = 0
    }

    suspend fun getOrgRepos(org: String): List<GHRepo> = withContext(Dispatchers.IO) {
        // 完全使用 GraphQL API 获取组织仓库列表
        val token = ApiClient.currentToken() ?: return@withContext emptyList()
        val data = GraphQLClient.queryOrgRepos(token, org) ?: return@withContext emptyList()
        val nodes = data.optJSONArray("nodes") ?: return@withContext emptyList()
        (0 until nodes.length()).mapNotNull { mapGraphQLToGHRepo(nodes.getJSONObject(it)) }
    }

    suspend fun getUserOrgs(): List<GHOrg> = withContext(Dispatchers.IO) {
        api.getUserOrgs()
    }

    suspend fun getRepo(owner: String, repo: String, forceRefresh: Boolean = false): GHRepo = withContext(Dispatchers.IO) {
        val key = "$owner/$repo"
        if (!forceRefresh) repoDetailCache[key]?.takeIf { it.valid(DETAIL_TTL) }?.data?.let { return@withContext it }

        // 直接使用 REST API，确保能获取到 parent 字段
        val result = api.getRepo(owner, repo)
        repoDetailCache[key] = Entry(result)
        result
    }

    suspend fun createRepo(body: GHCreateRepoRequest): GHRepo = withContext(Dispatchers.IO) {
        invalidateReposCache()
        api.createRepo(body)
    }

    suspend fun createOrgRepo(org: String, body: GHCreateRepoRequest): GHRepo = withContext(Dispatchers.IO) {
        invalidateReposCache()
        api.createOrgRepo(org, body)
    }

    suspend fun updateRepo(owner: String, repo: String, body: GHUpdateRepoRequest): GHRepo =
        withContext(Dispatchers.IO) {
            invalidateReposCache()
            api.updateRepo(owner, repo, body)
        }

    suspend fun deleteRepo(owner: String, repo: String): Boolean = withContext(Dispatchers.IO) {
        val resp = api.deleteRepo(owner, repo)
        invalidateReposCache()
        resp.isSuccessful
    }

    suspend fun transferRepo(owner: String, repo: String, newOwner: String, newName: String? = null): GHRepo =
        withContext(Dispatchers.IO) {
            invalidateReposCache()
            api.transferRepo(owner, repo, GHTransferRepoRequest(newOwner = newOwner, newName = newName))
        }

    suspend fun checkRepoExists(owner: String, repo: String): Boolean = withContext(Dispatchers.IO) {
        val resp = api.checkRepoExists(owner, repo)
        resp.isSuccessful
    }

    suspend fun getTopics(owner: String, repo: String): List<String> = withContext(Dispatchers.IO) {
        api.getTopics(owner, repo).names
    }

    suspend fun replaceTopics(owner: String, repo: String, topics: List<String>): List<String> = withContext(Dispatchers.IO) {
        api.replaceTopics(owner, repo, GHTopics(topics)).names
    }

    // ─── Contents / Files ───

    /**
     * 获取目录内容，带 2 分钟内存缓存。
     * 切换路径/分支时命中缓存，避免重复请求。
     * forceRefresh = true 可强制跳过缓存（下拉刷新场景）。
     */
    suspend fun getContents(
        owner: String, repo: String, path: String, ref: String,
        forceRefresh: Boolean = false,
    ): List<GHContent> = withContext(Dispatchers.IO) {
        val key = "$owner/$repo/$ref/${path.ifEmpty { "__root__" }}"
        if (!forceRefresh) {
            contentsCache[key]?.takeIf { it.valid(CONTENTS_TTL) }?.data?.let {
                return@withContext it
            }
        }
        val result = api.getContents(owner, repo, path.ifEmpty { "" }, ref)
        contentsCache[key] = Entry(result)
        result
    }

    /** 使 contentsCache 中该仓库的所有路径失效（push/commit 后调用） */
    fun invalidateContentsCache(owner: String, repo: String) {
        contentsCache.keys.removeAll { it.startsWith("$owner/$repo/") }
    }

    /**
     * 获取文件内容（Base64 解码），带 5 分钟内存缓存。
     */
    suspend fun getFileContent(
        owner: String, repo: String, path: String, ref: String,
        forceRefresh: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val key = "$owner/$repo/$ref/$path"
        if (!forceRefresh) {
            fileContentCache[key]?.takeIf { it.valid(FILE_TTL) }?.data?.let {
                return@withContext it
            }
        }
        val f = api.getFile(owner, repo, path, ref)
        val encoded = f.content ?: return@withContext ""
        val decoded = String(Base64.decode(encoded.replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
        fileContentCache[key] = Entry(decoded)
        decoded
    }

    /**
     * 获取文件信息（包含sha）
     */
    suspend fun getFileInfo(
        owner: String, repo: String, path: String, ref: String,
    ): GHContent = withContext(Dispatchers.IO) {
        api.getFile(owner, repo, path, ref)
    }

    /**
     * 创建或更新文件
     */
    suspend fun createOrUpdateFile(
        owner: String, repo: String, path: String,
        message: String, content: String, sha: String? = null,
        branch: String? = null,
    ): GHCreateFileResponse = withContext(Dispatchers.IO) {
        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val request = GHCreateFileRequest(
            message = message,
            content = encoded,
            sha = sha,
            branch = branch,
        )
        val response = api.createOrUpdateFile(owner, repo, path, request)
        invalidateContentsCache(owner, repo)
        fileContentCache.clear()
        response
    }

    /**
     * 删除文件
     */
    suspend fun deleteFile(
        owner: String, repo: String, path: String,
        message: String, sha: String,
        branch: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val request = GHDeleteFileRequest(
            message = message,
            sha = sha,
            branch = branch,
        )
        val response = api.deleteFile(owner, repo, path, request)
        invalidateContentsCache(owner, repo)
        fileContentCache.clear()
        response.isSuccessful
    }

    // ─── Commits ───

    suspend fun getCommits(owner: String, repo: String, sha: String, path: String? = null, forceRefresh: Boolean = false): List<GHCommit> =
        withContext(Dispatchers.IO) {
            val key = if (path != null) "$owner/$repo/$sha/$path" else "$owner/$repo/$sha"
            if (!forceRefresh) commitCache[key]?.takeIf { it.valid(30_000L) }?.data?.let { return@withContext it }
            val result = api.getCommits(owner, repo, sha, path)
            commitCache[key] = Entry(result)
            result
        }

    suspend fun getCommitDetail(owner: String, repo: String, sha: String, forceRefresh: Boolean = false): GHCommitFull =
        withContext(Dispatchers.IO) {
            val key = "$owner/$repo/$sha"
            if (!forceRefresh) commitDetailCache[key]?.takeIf { it.valid(COMMIT_TTL) }?.data?.let { return@withContext it }
            val result = api.getCommitFull(owner, repo, sha)
            commitDetailCache[key] = Entry(result)
            result
        }

    // ─── Branches ───

    suspend fun getBranches(owner: String, repo: String, forceRefresh: Boolean = false): List<GHBranch> =
        withContext(Dispatchers.IO) {
            val key = "$owner/$repo"
            if (!forceRefresh) branchCache[key]?.takeIf { it.valid(DETAIL_TTL) }?.data?.let { return@withContext it }
            val result = api.getBranches(owner, repo)
            branchCache[key] = Entry(result)
            result
        }

    suspend fun getBranch(owner: String, repo: String, branch: String): GHBranch = withContext(Dispatchers.IO) {
        api.getBranch(owner, repo, branch)
    }

    suspend fun createBranch(owner: String, repo: String, name: String, fromSha: String): GHRef =
        withContext(Dispatchers.IO) {
            api.createBranch(owner, repo, GHCreateBranchRequest("refs/heads/$name", fromSha))
        }

    suspend fun deleteBranch(owner: String, repo: String, branch: String): Boolean =
        withContext(Dispatchers.IO) { api.deleteBranch(owner, repo, branch).isSuccessful }

    suspend fun renameBranch(owner: String, repo: String, oldName: String, newName: String): GHBranch =
        withContext(Dispatchers.IO) {
            api.renameBranch(owner, repo, oldName, GHRenameBranchRequest(newName))
        }

    // ─── Star ───

    suspend fun isStarred(owner: String, repo: String): Boolean = withContext(Dispatchers.IO) {
        api.checkStarred(owner, repo).isSuccessful
    }

    /**
     * 获取仓库的星标用户列表
     */
    suspend fun getStargazers(owner: String, repo: String, page: Int = 1): List<GHStargazer> = withContext(Dispatchers.IO) {
        api.getStargazers(owner, repo, page = page)
    }

    /**
     * 获取仓库的复刻列表
     */
    suspend fun getForks(owner: String, repo: String, sort: String = "newest", page: Int = 1): List<GHRepo> = withContext(Dispatchers.IO) {
        api.getForks(owner, repo, sort = sort, page = page)
    }

    /**
     * 比较两个分支/提交的差异
     * @param owner 上游仓库所有者
     * @param repo 上游仓库名称
     * @param base 上游分支
     * @param head 复刻分支（格式：fork_owner:fork_branch）
     */
    suspend fun compareCommits(
        owner: String, repo: String, base: String, head: String,
    ): GHCompareResult = withContext(Dispatchers.IO) {
        api.compareCommits(owner, repo, base, head)
    }

    /**
     * 同步复刻分支与上游仓库
     */
    suspend fun syncForkBranch(
        owner: String, repo: String, branch: String,
    ): GHSyncForkResponse = withContext(Dispatchers.IO) {
        api.syncForkBranch(owner, repo, GHSyncForkRequest(branch = branch))
    }

    suspend fun createPullRequest(
        upstreamOwner: String, upstreamRepo: String,
        title: String, body: String? = null,
        head: String, base: String,
    ): GHPullRequest = withContext(Dispatchers.IO) {
        api.createPullRequest(
            upstreamOwner, upstreamRepo,
            GHCreatePullRequestRequest(title, body, head, base)
        )
    }

    suspend fun starRepo(owner: String, repo: String) = withContext(Dispatchers.IO) {
        api.starRepo(owner, repo)
    }

    suspend fun unstarRepo(owner: String, repo: String) = withContext(Dispatchers.IO) {
        api.unstarRepo(owner, repo)
    }

    /**
     * 创建仓库的复刻
     */
    suspend fun createFork(
        owner: String, repo: String,
        name: String? = null,
        organization: String? = null,
        defaultBranchOnly: Boolean = false,
    ): GHRepo = withContext(Dispatchers.IO) {
        api.createFork(
            owner, repo,
            GHCreateForkRequest(
                name = name,
                organization = organization,
                defaultBranchOnly = defaultBranchOnly
            )
        )
    }

    // ─── 服务端 Reset / Revert（通过 GitHub Git Data API，无需本地 git）──────

    /**
     * 回滚：强制将分支指针移动到目标 SHA（等同于 git reset --hard + git push -f）
     * ⚠ 会重写提交历史，之后的提交从该分支消失
     */
    suspend fun resetBranchToCommit(
        owner: String, repo: String, branch: String, sha: String,
    ) = withContext(Dispatchers.IO) {
        api.updateRef(owner, repo, branch, GHUpdateRefRequest(sha, force = true))
    }

    /**
     * 撤销（Revert）：在当前 HEAD 上创建一个新提交，将内容恢复到目标提交的父提交状态。
     * 不重写历史，保留所有提交记录，适合有保护规则的主分支。
     *
     * 步骤：
     *  1. 获取目标提交的父 SHA
     *  2. 获取父提交的 tree SHA（文件快照）
     *  3. 以当前 HEAD 为 parent，用父提交的 tree 创建新 commit
     *  4. 快进更新分支指针
     */
    suspend fun revertCommit(
        owner: String, repo: String, branch: String,
        targetSha: String, currentHeadSha: String, message: String,
    ): GHCreateCommitResponse = withContext(Dispatchers.IO) {
        val targetGit = api.getGitCommit(owner, repo, targetSha)
        val parentSha = targetGit.parents.firstOrNull()?.sha
            ?: error("该提交没有父提交（初始提交），无法 revert")
        val parentGit = api.getGitCommit(owner, repo, parentSha)
        val newCommit = api.createCommit(
            owner, repo,
            GHCreateCommitRequest(
                message = message,
                tree    = parentGit.tree.sha,
                parents = listOf(currentHeadSha),
            )
        )
        api.updateRef(owner, repo, branch, GHUpdateRefRequest(newCommit.sha, force = false))
        newCommit
    }

    // ─── PR / Issues ───

    suspend fun getPRs(owner: String, repo: String, forceRefresh: Boolean = false): List<GHPullRequest> =
        withContext(Dispatchers.IO) {
            val key = "$owner/$repo"
            if (!forceRefresh) prCache[key]?.takeIf { it.valid(LIST_TTL) }?.data?.let { return@withContext it }
            val result = api.getPullRequests(owner, repo)
            prCache[key] = Entry(result)
            result
        }

    suspend fun getIssues(
        owner: String, repo: String,
        state: String = "open",
        page: Int = 1,
        forceRefresh: Boolean = false,
    ): List<GHIssue> = withContext(Dispatchers.IO) {
        val key = "$owner/$repo/$state/$page"
        if (!forceRefresh && page == 1)
            issueCache[key]?.takeIf { it.valid(LIST_TTL) }?.data?.let { return@withContext it }
        val result = api.getIssues(owner, repo, state = state, perPage = 30, page = page)
        if (page == 1) issueCache[key] = Entry(result)
        result
    }

    suspend fun getIssue(owner: String, repo: String, issueNumber: Int): GHIssue =
        withContext(Dispatchers.IO) { api.getIssue(owner, repo, issueNumber) }

    suspend fun updateIssue(
        owner: String,
        repo: String,
        issueNumber: Int,
        request: GHUpdateIssueRequest
    ): GHIssue = withContext(Dispatchers.IO) { api.updateIssue(owner, repo, issueNumber, request) }

    suspend fun deleteIssue(owner: String, repo: String, issueNumber: Int): Boolean =
        withContext(Dispatchers.IO) { api.deleteIssue(owner, repo, issueNumber).isSuccessful }

    suspend fun createIssue(
        owner: String,
        repo: String,
        title: String,
        body: String? = null,
    ): GHIssue = withContext(Dispatchers.IO) {
        api.createIssue(owner, repo, GHCreateIssueRequest(title = title, body = body))
    }

    suspend fun lockIssue(owner: String, repo: String, issueNumber: Int): Boolean =
        withContext(Dispatchers.IO) { api.lockIssue(owner, repo, issueNumber).isSuccessful }

    suspend fun unlockIssue(owner: String, repo: String, issueNumber: Int): Boolean =
        withContext(Dispatchers.IO) { api.unlockIssue(owner, repo, issueNumber).isSuccessful }

    suspend fun getIssueSubscription(owner: String, repo: String, issueNumber: Int): GHIssueSubscription =
        withContext(Dispatchers.IO) { api.getIssueSubscription(owner, repo, issueNumber) }

    suspend fun subscribeIssue(owner: String, repo: String, issueNumber: Int): GHIssueSubscription =
        withContext(Dispatchers.IO) { api.subscribeIssue(owner, repo, issueNumber) }

    suspend fun unsubscribeIssue(owner: String, repo: String, issueNumber: Int): Boolean =
        withContext(Dispatchers.IO) { api.unsubscribeIssue(owner, repo, issueNumber).isSuccessful }

    suspend fun getIssueComments(owner: String, repo: String, issueNumber: Int): List<GHComment> =
        withContext(Dispatchers.IO) { api.getIssueComments(owner, repo, issueNumber) }

    suspend fun createIssueComment(
        owner: String,
        repo: String,
        issueNumber: Int,
        body: String
    ): GHComment = withContext(Dispatchers.IO) {
        api.createIssueComment(owner, repo, issueNumber, GHCreateCommentRequest(body))
    }

    suspend fun getIssueComment(owner: String, repo: String, commentId: Long): GHComment =
        withContext(Dispatchers.IO) { api.getIssueComment(owner, repo, commentId) }

    suspend fun updateIssueComment(
        owner: String,
        repo: String,
        commentId: Long,
        body: String
    ): GHComment = withContext(Dispatchers.IO) {
        api.updateIssueComment(owner, repo, commentId, GHUpdateCommentRequest(body))
    }

    suspend fun deleteIssueComment(owner: String, repo: String, commentId: Long): Boolean =
        withContext(Dispatchers.IO) { api.deleteIssueComment(owner, repo, commentId).isSuccessful }

    // ─── Releases ───
    suspend fun getReleases(owner: String, repo: String, forceRefresh: Boolean = false): List<GHRelease> =
        withContext(Dispatchers.IO) {
            val key = "$owner/$repo"
            if (!forceRefresh) releaseCache[key]?.takeIf { it.valid(LIST_TTL) }?.data?.let { return@withContext it }
            val result = api.getReleases(owner, repo)
            releaseCache[key] = Entry(result)
            result
        }

    // ─── GitHub Actions ───
    suspend fun getWorkflows(owner: String, repo: String): List<GHWorkflow> =
        withContext(Dispatchers.IO) { api.getWorkflows(owner, repo).workflows }

    suspend fun getWorkflowRuns(owner: String, repo: String, workflowId: Long? = null): List<GHWorkflowRun> =
        withContext(Dispatchers.IO) {
            if (workflowId != null) {
                api.getWorkflowRunsForWorkflow(owner, repo, workflowId).workflowRuns
            } else {
                api.getWorkflowRuns(owner, repo).workflowRuns
            }
        }

    suspend fun getWorkflowJobs(owner: String, repo: String, runId: Long): List<GHWorkflowJob> =
        withContext(Dispatchers.IO) { api.getWorkflowJobs(owner, repo, runId).jobs }

    suspend fun dispatchWorkflow(
        owner: String, repo: String, workflowId: Long,
        ref: String, inputs: Map<String, Any>? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        api.dispatchWorkflow(owner, repo, workflowId, GHWorkflowDispatchRequest(ref, inputs)).isSuccessful
    }

    suspend fun deleteWorkflowRun(owner: String, repo: String, runId: Long): Boolean =
        withContext(Dispatchers.IO) { api.deleteWorkflowRun(owner, repo, runId).isSuccessful }

    suspend fun rerunWorkflow(owner: String, repo: String, runId: Long): Boolean =
        withContext(Dispatchers.IO) { api.rerunWorkflow(owner, repo, runId).isSuccessful }

    suspend fun cancelWorkflow(owner: String, repo: String, runId: Long): Boolean =
        withContext(Dispatchers.IO) { api.cancelWorkflow(owner, repo, runId).isSuccessful }

    suspend fun getWorkflowLogs(owner: String, repo: String, runId: Long): Map<String, String>? =
        withContext(Dispatchers.IO) {
            val response = api.getWorkflowLogs(owner, repo, runId)
            if (response.isSuccessful) {
                val bytes = response.body()?.bytes()
                if (bytes != null) {
                    parseWorkflowLogs(bytes)
                } else {
                    null
                }
            } else {
                null
            }
        }

    suspend fun getWorkflowRunArtifacts(owner: String, repo: String, runId: Long): List<GHWorkflowArtifact> =
        withContext(Dispatchers.IO) { api.getWorkflowRunArtifacts(owner, repo, runId).artifacts }

    suspend fun deleteArtifact(owner: String, repo: String, artifactId: Long): Boolean =
        withContext(Dispatchers.IO) { api.deleteArtifact(owner, repo, artifactId).isSuccessful }

    /**
     * 解析工作流日志 zip 文件
     * 返回 Map：key 为文件名（通常是 job/step 名称），value 为日志内容
     */
    private fun parseWorkflowLogs(zipBytes: ByteArray): Map<String, String> {
        val logs = mutableMapOf<String, String>()
        val zipInput = ZipInputStream(ByteArrayInputStream(zipBytes))
        try {
            var entry = zipInput.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val content = zipInput.readBytes().toString(Charsets.UTF_8)
                    logs[entry.name] = content
                    LogManager.d("WorkflowLogs", "解析日志文件: ${entry.name}, 长度: ${content.length}")
                }
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            }
        } catch (e: Exception) {
            LogManager.e("WorkflowLogs", "解析 zip 日志失败", e)
        } finally {
            zipInput.close()
        }
        return logs
    }

    suspend fun getWorkflowInputs(
        owner: String,
        repo: String,
        workflowPath: String,
        ref: String? = null,
    ): List<WorkflowInput> = withContext(Dispatchers.IO) {
        try {
            LogManager.d("WorkflowInputs", "获取工作流输入: owner=$owner, repo=$repo, path=$workflowPath, ref=$ref")
            val file = api.getFile(owner, repo, workflowPath, ref)
            LogManager.d("WorkflowInputs", "文件信息: encoding=${file.encoding}, content长度=${file.content?.length}")
            
            if (file.encoding == "base64" && file.content != null) {
                val content = String(Base64.decode(file.content, Base64.DEFAULT), Charsets.UTF_8)
                LogManager.d("WorkflowInputs", "解码后内容长度: ${content.length}")
                parseWorkflowInputs(content)
            } else {
                LogManager.w("WorkflowInputs", "文件编码不是 base64 或内容为空")
                emptyList()
            }
        } catch (e: Exception) {
            LogManager.e("WorkflowInputs", "获取工作流输入失败", e)
            emptyList()
        }
    }


    /** 将 GraphQL repository 节点映射为 GHRepo（与 REST 返回格式对齐） */
    private fun mapGraphQLToGHRepo(node: org.json.JSONObject): GHRepo {
        val nameWithOwner = node.getString("nameWithOwner")   // "owner/repo"
        val parts = nameWithOwner.split("/")
        val ownerLogin = parts.getOrElse(0) { "" }
        val repoName   = parts.getOrElse(1) { node.getString("name") }

        val defaultBranch = node.optJSONObject("defaultBranchRef")
            ?.optString("name") ?: "main"
        val language = node.optJSONObject("primaryLanguage")?.optString("name")
        val stars    = node.optInt("stargazerCount", 0)
        val forks    = node.optInt("forkCount", 0)
        val openIssues = node.optJSONObject("openIssues")?.optInt("totalCount", 0) ?: 0

        // GitHub GraphQL node_id 是 base64 字符串，REST 用 Long；这里用 hashCode 兼容
        val idLong = node.optString("id").hashCode().toLong().let {
            if (it < 0) it + Long.MAX_VALUE else it
        }
        val ownerAvatarUrl = node.optString("openGraphImageUrl").ifBlank { null }
        val owner = GHOwner(login = ownerLogin, avatarUrl = ownerAvatarUrl)

        // 解析 parent 字段（如果存在）
        val parent = node.optJSONObject("parent")?.let { mapGraphQLToGHRepo(it) }

        return GHRepo(
            id            = idLong,
            name          = repoName,
            fullName      = nameWithOwner,
            description   = node.optString("description").ifBlank { null },
            homepage      = null,
            private       = node.optBoolean("isPrivate", false),
            htmlUrl       = node.optString("url"),
            sshUrl        = "git@github.com:$nameWithOwner.git",
            cloneUrl      = "https://github.com/$nameWithOwner.git",
            defaultBranch = defaultBranch,
            stars         = stars,
            forks         = forks,
            openIssues    = openIssues,
            updatedAt     = node.optString("updatedAt").ifBlank { null },
            pushedAt      = node.optString("pushedAt").ifBlank { null },
            language      = language,
            owner         = owner,
            fork          = node.optBoolean("isFork", false),
            parent        = parent,
        )
    }

    private fun parseWorkflowInputs(yamlContent: String): List<WorkflowInput> {
        val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        val inputs = mutableListOf<WorkflowInput>()

        try {
            // 将 'on:' 替换为 'on_trigger:' 避免被解析为布尔值（只在行首替换）
            val modifiedContent = yamlContent.replace(Regex("^on:", setOf(RegexOption.MULTILINE)), "on_trigger:")
            LogManager.d("WorkflowParser", "开始解析 YAML，内容长度: ${yamlContent.length}")
            val root = yamlMapper.readValue(modifiedContent, Map::class.java)
            LogManager.d("WorkflowParser", "根节点 keys: ${root.keys}")
            
            val onValue = root["on_trigger"]
            LogManager.d("WorkflowParser", "on_trigger 字段类型: ${onValue?.javaClass?.simpleName}, 值: $onValue")

            var workflowDispatch: Map<*, *>? = null

            when (onValue) {
                is Map<*, *> -> {
                    LogManager.d("WorkflowParser", "on 是 Map 类型")
                    workflowDispatch = onValue["workflow_dispatch"] as? Map<*, *>
                    LogManager.d("WorkflowParser", "workflow_dispatch: $workflowDispatch")
                }
                is List<*> -> {
                    LogManager.d("WorkflowParser", "on 是 List 类型，大小: ${onValue.size}")
                    onValue.forEach { item ->
                        if (item is Map<*, *> && item.containsKey("workflow_dispatch")) {
                            workflowDispatch = item["workflow_dispatch"] as? Map<*, *>
                            LogManager.d("WorkflowParser", "找到 workflow_dispatch: $workflowDispatch")
                        }
                    }
                }
                is String -> {
                    LogManager.d("WorkflowParser", "on 是 String 类型: $onValue")
                    if (onValue == "workflow_dispatch") {
                        workflowDispatch = emptyMap<String, Any>()
                    }
                }
            }

            workflowDispatch?.let { dispatch ->
                LogManager.d("WorkflowParser", "workflow_dispatch keys: ${dispatch.keys}")
                val inputsMap = dispatch["inputs"] as? Map<*, *>
                LogManager.d("WorkflowParser", "inputs: $inputsMap")

                inputsMap?.forEach { (key, value) ->
                    val name = key.toString()
                    val inputConfig = value as? Map<*, *> ?: return@forEach
                    
                    val type = inputConfig["type"]?.toString() ?: "string"
                    val description = inputConfig["description"]?.toString()
                    val required = inputConfig["required"] as? Boolean ?: false
                    val default = inputConfig["default"]
                    val options = (inputConfig["options"] as? List<*>)?.mapNotNull { it?.toString() }
                    
                    LogManager.d("WorkflowParser", "解析输入: name=$name, type=$type, description=$description, required=$required, default=$default, options=$options")

                    inputs.add(
                        WorkflowInput(
                            name = name,
                            type = type,
                            description = description,
                            required = required,
                            default = default,
                            options = options
                        )
                    )
                }
            } ?: LogManager.w("WorkflowParser", "未找到 workflow_dispatch 配置")
        } catch (e: Exception) {
            LogManager.e("WorkflowParser", "解析 YAML 失败", e)
        }

        LogManager.d("WorkflowParser", "最终解析结果: ${inputs.size} 个输入")
        return inputs
    }
    // ── 仓库订阅（Watch）──────────────────────────────────────────────────────

    /** 获取当前订阅状态，404 表示未订阅（仅参与后@提及） */
    suspend fun getRepoSubscription(owner: String, repo: String, forceRefresh: Boolean = false): GHRepoSubscription? =
        withContext(Dispatchers.IO) {
            val key = "$owner/$repo"
            if (!forceRefresh) subscriptionCache[key]?.takeIf { it.valid(60_000L) }?.data?.let { return@withContext it }
            try {
                val resp = api.getRepoSubscription(owner, repo)
                val sub = if (resp.isSuccessful) resp.body() else null
                subscriptionCache[key] = Entry(sub)
                sub
            } catch (_: Exception) { null }
        }

    /** 设为"所有活动"或"忽略" */
    suspend fun setRepoSubscription(owner: String, repo: String, subscribed: Boolean, ignored: Boolean): GHRepoSubscription =
        withContext(Dispatchers.IO) {
            api.setRepoSubscription(owner, repo, GHRepoSubscriptionRequest(subscribed, ignored))
        }

    /** 恢复为"仅参与后@提及"（删除订阅记录） */
    suspend fun unsubscribeRepo(owner: String, repo: String) =
        withContext(Dispatchers.IO) {
            api.deleteRepoSubscription(owner, repo)
        }

    // ── Issue Template 解析 ────────────────────────────────────────────────────

    /**
     * 获取仓库的 Issue 模板列表。
     * 按 GitHub 规范，模板存放于 .github/ISSUE_TEMPLATE/ 目录：
     *   .md  → Markdown 模板（YAML front matter + body）
     *   .yml → YAML Forms（GitHub Issue Forms）
     */
    suspend fun getIssueTemplates(owner: String, repo: String): List<IssueTemplate> =
        withContext(Dispatchers.IO) {
            try {
                val dirs = listOf(".github/ISSUE_TEMPLATE", ".github/issue_template", "ISSUE_TEMPLATE")
                var files: List<GHContent> = emptyList()
                for (dir in dirs) {
                    try {
                        val resp = api.getContents(owner, repo, dir, "")
                        if (resp.isNotEmpty()) { files = resp; break }
                    } catch (_: Exception) { }
                }
                files
                    .filter { it.name.endsWith(".md") || it.name.endsWith(".yml") || it.name.endsWith(".yaml") }
                    .mapNotNull { file ->
                        try {
                            val raw = getFileContent(owner, repo, file.path, "")
                            if (file.name.endsWith(".md")) parseMdTemplate(raw)
                            else parseYamlFormTemplate(raw)
                        } catch (_: Exception) { null }
                    }
                    .filter { it.name.isNotBlank() }
            } catch (_: Exception) { emptyList() }
        }

    /** 解析 Markdown 模板（YAML front matter） */
    private fun parseMdTemplate(content: String): IssueTemplate? {
        val fmRegex = Regex("""^---\s*\n(.*?)\n---\s*\n?""", RegexOption.DOT_MATCHES_ALL)
        val fmMatch = fmRegex.find(content) ?: return IssueTemplate(name = "Bug Report", body = content)
        val fm   = fmMatch.groupValues[1]
        val body = content.substring(fmMatch.range.last + 1).trim()

        fun field(key: String): String =
            Regex("""(?mi)^""" + key + """:\s*["']?(.*?)["']?\s*${'$'}""")
                .find(fm)?.groupValues?.get(1)?.trim() ?: ""

        fun list(key: String): List<String> {
            val inl = Regex("""(?m)^""" + key + """:\s*\[(.+?)\]""").find(fm)
            if (inl != null) return inl.groupValues[1].split(",").map { it.trim().trim('"', '\'') }
            return Regex("""(?m)^""" + key + """:\s*\n((?:\s+-\s+.+\n?)+)""").find(fm)
                ?.groupValues?.get(1)?.lines()
                ?.mapNotNull { Regex("""(?m)^\s+-\s+(.+)""").find(it)?.groupValues?.get(1)?.trim() }
                ?: emptyList()
        }

        val name = field("name").ifBlank { "Template" }
        return IssueTemplate(
            name   = name,
            about  = field("about"),
            title  = field("title"),
            labels = list("labels"),
            body   = body,
            isForm = false,
        )
    }


    /** 解析 YAML Forms 模板（GitHub Issue Forms .yml） */
    @Suppress("UNCHECKED_CAST")
    private fun parseYamlFormTemplate(content: String): IssueTemplate? {
        return try {
            val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
            val root = yamlMapper.readValue(content, Map::class.java) as Map<String, Any>

            val name   = root["name"]?.toString() ?: return null
            val about  = root["about"]?.toString() ?: ""
            val title  = root["title"]?.toString() ?: ""
            val labels = when (val l = root["labels"]) {
                is List<*> -> l.mapNotNull { it?.toString() }
                is String  -> l.split(",").map { it.trim() }
                else       -> emptyList()
            }

            val bodyList = root["body"] as? List<*> ?: emptyList<Any>()
            val fields = bodyList.mapNotNull { item ->
                val m = item as? Map<String, Any> ?: return@mapNotNull null
                val type  = m["type"]?.toString() ?: return@mapNotNull null
                val id    = m["id"]?.toString() ?: type + "_${bodyList.indexOf(item)}"
                val attrs = m["attributes"] as? Map<String, Any> ?: emptyMap()
                val label = attrs["label"]?.toString() ?: ""
                val desc  = attrs["description"]?.toString() ?: ""
                val req   = (m["validations"] as? Map<String, Any>)?.get("required") as? Boolean ?: false

                when (type) {
                    "input" -> IssueField.InputField(
                        id = id, label = label, description = desc, required = req,
                        placeholder = attrs["placeholder"]?.toString() ?: "",
                        value = attrs["value"]?.toString() ?: "",
                    )
                    "textarea" -> IssueField.TextareaField(
                        id = id, label = label, description = desc, required = req,
                        placeholder = attrs["placeholder"]?.toString() ?: "",
                        value = attrs["value"]?.toString() ?: "",
                        render = attrs["render"]?.toString() ?: "",
                    )
                    "dropdown" -> IssueField.DropdownField(
                        id = id, label = label, description = desc, required = req,
                        options = (attrs["options"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
                        multiple = attrs["multiple"] as? Boolean ?: false,
                    )
                    "checkboxes" -> IssueField.CheckboxesField(
                        id = id, label = label, description = desc, required = req,
                        options = (attrs["options"] as? List<*>)?.mapNotNull { opt ->
                            val om = opt as? Map<String, Any> ?: return@mapNotNull null
                            CheckboxOption(
                                label    = om["label"]?.toString() ?: "",
                                required = (om["required"] as? Boolean) ?: false,
                            )
                        } ?: emptyList(),
                    )
                    "markdown" -> IssueField.MarkdownField(
                        id = id, value = attrs["value"]?.toString() ?: "",
                    )
                    else -> null
                }
            }

            IssueTemplate(
                name   = name,
                about  = about,
                title  = title,
                labels = labels,
                fields = fields,
                isForm = true,
            )
        } catch (_: Exception) { null }
    }


}
