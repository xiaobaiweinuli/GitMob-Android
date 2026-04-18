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
    private data class ReposCacheEntry(
        val repos: List<GHRepo>,
        val hasNextPage: Boolean,
        val endCursor: String?,
        val ts: Long = System.currentTimeMillis()
    ) {
        fun valid(ttl: Long = 60_000L) = System.currentTimeMillis() - ts < ttl
    }
    private val reposCache = java.util.concurrent.ConcurrentHashMap<String, ReposCacheEntry>()
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
    private val labelsCache   = java.util.concurrent.ConcurrentHashMap<String, Entry<List<GHLabel>>>()
    private val LIST_TTL      = 2 * 60 * 1000L   // 列表 2 分钟
    private val COMMIT_TTL    = 10 * 60 * 1000L  // commit detail 10 分钟（不变）

    // ─── 用户 / 仓库 ───

    data class ReposPageResult(
        val repos: List<GHRepo>,
        val hasNextPage: Boolean,
        val endCursor: String?
    )

    suspend fun getMyRepos(forceRefresh: Boolean = false, cursor: String? = null): ReposPageResult = withContext(Dispatchers.IO) {
        val cacheKey = "user"
        
        if (cursor == null && !forceRefresh) {
            reposCache[cacheKey]?.takeIf { it.valid(CACHE_TTL) }?.let { 
                return@withContext ReposPageResult(it.repos, it.hasNextPage, it.endCursor) 
            }
        }
        
        val token = ApiClient.currentToken() ?: return@withContext ReposPageResult(emptyList(), false, null)
        val data = GraphQLClient.queryUserRepos(token, cursor) ?: return@withContext ReposPageResult(emptyList(), false, null)
        val nodes = data.optJSONArray("nodes") ?: return@withContext ReposPageResult(emptyList(), false, null)
        val pageInfo = data.optJSONObject("pageInfo")
        val hasNextPage = pageInfo?.optBoolean("hasNextPage") ?: false
        val endCursor = pageInfo?.optString("endCursor")?.takeIf { it.isNotEmpty() }
        
        val repos = (0 until nodes.length()).mapNotNull { mapGraphQLToGHRepo(nodes.getJSONObject(it)) }
        
        if (cursor == null) {
            reposCache[cacheKey] = ReposCacheEntry(repos, hasNextPage, endCursor)
        }
        
        ReposPageResult(repos, hasNextPage, endCursor)
    }

    fun invalidateCommitCache(owner: String, repo: String, sha: String) {
        commitCache.remove("$owner/$repo/$sha")
    }

    fun invalidateReposCache() {
        reposCache.clear()
    }

    // ─── 增量刷新（Incremental Refresh）──────────────────────────────────────
    // 统一策略：只拉第一页新数据，按唯一 key 去重后与旧缓存合并（新在前），
    // 不清空已展示列表，避免 UI 闪烁和重复请求。

    /** 仓库列表增量刷新：拉第一页，按 id 合并，旧的不在新页的也保留 */
    suspend fun refreshMyReposIncremental(): ReposPageResult = withContext(Dispatchers.IO) {
        val token = ApiClient.currentToken() ?: return@withContext ReposPageResult(emptyList(), false, null)
        val data = GraphQLClient.queryUserRepos(token, cursor = null)
            ?: return@withContext ReposPageResult(emptyList(), false, null)
        val nodes = data.optJSONArray("nodes") ?: return@withContext ReposPageResult(emptyList(), false, null)
        val pageInfo   = data.optJSONObject("pageInfo")
        val hasNext    = pageInfo?.optBoolean("hasNextPage") ?: false
        val endCursor  = pageInfo?.optString("endCursor")?.takeIf { it.isNotEmpty() }
        val fresh      = (0 until nodes.length()).mapNotNull { mapGraphQLToGHRepo(nodes.getJSONObject(it)) }
        val cached     = reposCache["user"]?.repos ?: emptyList()
        val merged     = (fresh + cached).distinctBy { it.id }
        reposCache["user"] = ReposCacheEntry(merged, hasNext, endCursor)
        ReposPageResult(merged, hasNext, endCursor)
    }

    /** 组织仓库列表增量刷新 */
    suspend fun refreshOrgReposIncremental(org: String): ReposPageResult = withContext(Dispatchers.IO) {
        val fresh   = api.getOrgRepos(org)
        val key     = "org:$org"
        val cached  = reposCache[key]?.repos ?: emptyList()
        val merged  = (fresh + cached).distinctBy { it.id }
        reposCache[key] = ReposCacheEntry(merged, false, null)
        ReposPageResult(merged, false, null)
    }

    /** Commits 增量刷新：拉第一页，按 sha 合并 */
    suspend fun refreshCommitsIncremental(owner: String, repo: String, sha: String): List<GHCommit> =
        withContext(Dispatchers.IO) {
            val key    = "$owner/$repo/$sha"
            val fresh  = api.getCommits(owner, repo, sha)
            val cached = commitCache[key]?.data ?: emptyList()
            val merged = (fresh + cached).distinctBy { it.sha }
            commitCache[key] = Entry(merged)
            merged
        }

    /** Branches 增量刷新：按 name 合并（分支量小，全量拉然后合并） */
    suspend fun refreshBranchesIncremental(owner: String, repo: String): List<GHBranch> =
        withContext(Dispatchers.IO) {
            val fresh  = api.getBranches(owner, repo)
            val key    = "$owner/$repo"
            // 分支以 fresh 为主（分支可能被删除），直接替换并更新缓存时间
            branchCache[key] = Entry(fresh)
            fresh
        }

    /** Issues 增量刷新：拉第一页，按 number 合并 */
    suspend fun refreshIssuesIncremental(
        owner: String, repo: String,
        state: String = "open",
        labels: String? = null,
        creator: String? = null,
        sort: String = "created",
        direction: String = "desc",
    ): List<GHIssue> = withContext(Dispatchers.IO) {
        val key    = "$owner/$repo/$state/$labels/$creator/$sort/$direction/1"
        val fresh  = api.getIssues(owner, repo, state, labels, creator, sort, direction, 30, 1)
        val cached = issueCache[key]?.data ?: emptyList()
        val merged = (fresh + cached).distinctBy { it.number }
        issueCache[key] = Entry(merged)
        merged
    }

    /** Releases 增量刷新：按 id 合并 */
    suspend fun refreshReleasesIncremental(owner: String, repo: String): List<GHRelease> =
        withContext(Dispatchers.IO) {
            val key    = "$owner/$repo"
            val fresh  = api.getReleases(owner, repo)
            val cached = releaseCache[key]?.data ?: emptyList()
            val merged = (fresh + cached).distinctBy { it.id }
            releaseCache[key] = Entry(merged)
            merged
        }

    suspend fun getOrgRepos(org: String, cursor: String? = null): ReposPageResult = withContext(Dispatchers.IO) {
        val cacheKey = "org:$org"
        
        if (cursor == null) {
            reposCache[cacheKey]?.takeIf { it.valid(CACHE_TTL) }?.let { 
                return@withContext ReposPageResult(it.repos, it.hasNextPage, it.endCursor) 
            }
        }
        
        val token = ApiClient.currentToken() ?: return@withContext ReposPageResult(emptyList(), false, null)
        val data = GraphQLClient.queryOrgRepos(token, org, cursor) ?: return@withContext ReposPageResult(emptyList(), false, null)
        val nodes = data.optJSONArray("nodes") ?: return@withContext ReposPageResult(emptyList(), false, null)
        val pageInfo = data.optJSONObject("pageInfo")
        val hasNextPage = pageInfo?.optBoolean("hasNextPage") ?: false
        val endCursor = pageInfo?.optString("endCursor")?.takeIf { it.isNotEmpty() }
        val repos = (0 until nodes.length()).mapNotNull { mapGraphQLToGHRepo(nodes.getJSONObject(it)) }
        
        if (cursor == null) {
            reposCache[cacheKey] = ReposCacheEntry(repos, hasNextPage, endCursor)
        }
        
        ReposPageResult(repos, hasNextPage, endCursor)
    }

    private val orgsCache = java.util.concurrent.ConcurrentHashMap<String, Entry<List<GHOrg>>>()

    suspend fun getUserOrgs(forceRefresh: Boolean = false): List<GHOrg> = withContext(Dispatchers.IO) {
        val key = "user_orgs"
        if (!forceRefresh) {
            orgsCache[key]?.takeIf { it.valid(FILE_TTL) }?.data?.let {
                return@withContext it
            }
        }
        val orgs = api.getUserOrgs(perPage = 100)
        orgsCache[key] = Entry(orgs)
        orgs
    }

    /**
     * 获取指定用户的组织列表
     */
    suspend fun getUserOrgs(login: String, forceRefresh: Boolean = false): List<GHOrg> = withContext(Dispatchers.IO) {
        val key = "user_orgs:$login"
        if (!forceRefresh) {
            orgsCache[key]?.takeIf { it.valid(FILE_TTL) }?.data?.let {
                return@withContext it
            }
        }
        val orgs = api.getUserOrgs(login, perPage = 100)
        orgsCache[key] = Entry(orgs)
        orgs
    }

    /**
     * 获取指定用户的仓库列表（REST API）
     */
    suspend fun getUserRepos(login: String, forceRefresh: Boolean = false, page: Int = 1): ReposPageResult = withContext(Dispatchers.IO) {
        val cacheKey = "user:$login"
        
        if (page == 1 && !forceRefresh) {
            reposCache[cacheKey]?.takeIf { it.valid(CACHE_TTL) }?.let { 
                return@withContext ReposPageResult(it.repos, it.hasNextPage, it.endCursor) 
            }
        }
        
        val repos = api.getUserRepos(login, sort = "updated", perPage = 50, page = page)
        
        if (page == 1) {
            reposCache[cacheKey] = ReposCacheEntry(repos, repos.size >= 50, null)
        }
        
        ReposPageResult(repos, repos.size >= 50, null)
    }

    /**
     * 获取指定用户的星标仓库列表（REST API）
     */
    suspend fun getUserStarred(login: String, forceRefresh: Boolean = false, page: Int = 1): ReposPageResult = withContext(Dispatchers.IO) {
        val cacheKey = "starred:$login"
        
        if (page == 1 && !forceRefresh) {
            reposCache[cacheKey]?.takeIf { it.valid(CACHE_TTL) }?.let { 
                return@withContext ReposPageResult(it.repos, it.hasNextPage, it.endCursor) 
            }
        }
        
        val repos = api.getUserStarred(login, sort = "created", direction = "desc", perPage = 50, page = page)
        
        if (page == 1) {
            reposCache[cacheKey] = ReposCacheEntry(repos, repos.size >= 50, null)
        }
        
        ReposPageResult(repos, repos.size >= 50, null)
    }

    suspend fun getRepo(owner: String, repo: String, forceRefresh: Boolean = false): GHRepo = withContext(Dispatchers.IO) {
        val key = "$owner/$repo"
        if (!forceRefresh) repoDetailCache[key]?.takeIf { it.valid(DETAIL_TTL) }?.data?.let { return@withContext it }

        // 使用 GraphQL 获取，确保 openIssues 数量正确（只包含 Issues，不含 PRs）
        val token = ApiClient.currentToken()!!
        val graphQLNode = GraphQLClient.queryRepoOverview(token, owner, repo)!!
        val result = mapGraphQLToGHRepo(graphQLNode)

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
            val result = api.updateRepo(owner, repo, body)
            RepoUpdateEventBus.send(RepoUpdateEvent.RepoUpdated(owner, repo))
            result
        }

    suspend fun archiveRepo(owner: String, repo: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                invalidateReposCache()
                repoDetailCache.remove("$owner/$repo")
                val response = api.updateRepo(owner, repo, GHUpdateRepoRequest(archived = true))
                LogManager.d("RepoArchive", "归档仓库 $owner/$repo 成功")
                RepoUpdateEventBus.send(RepoUpdateEvent.RepoUpdated(owner, repo))
                true
            } catch (e: Exception) {
                LogManager.e("RepoArchive", "归档仓库 $owner/$repo 时发生异常", e)
                false
            }
        }

    suspend fun unarchiveRepo(owner: String, repo: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                invalidateReposCache()
                repoDetailCache.remove("$owner/$repo")
                val response = api.updateRepo(owner, repo, GHUpdateRepoRequest(archived = false))
                LogManager.d("RepoArchive", "取消归档仓库 $owner/$repo 成功")
                RepoUpdateEventBus.send(RepoUpdateEvent.RepoUpdated(owner, repo))
                true
            } catch (e: Exception) {
                LogManager.e("RepoArchive", "取消归档仓库 $owner/$repo 时发生异常", e)
                false
            }
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

    suspend fun getLabels(owner: String, repo: String, forceRefresh: Boolean = false): List<GHLabel> = withContext(Dispatchers.IO) {
        val key = "$owner/$repo"
        if (!forceRefresh) labelsCache[key]?.takeIf { it.valid(LIST_TTL) }?.data?.let { return@withContext it }
        
        val labels = api.getLabels(owner, repo, 100, 1)
        labelsCache[key] = Entry(labels)
        labels
    }

    suspend fun replaceTopics(owner: String, repo: String, topics: List<String>): List<String> = withContext(Dispatchers.IO) {
        invalidateReposCache()
        repoDetailCache.remove("$owner/$repo")
        val result = api.replaceTopics(owner, repo, GHTopics(topics)).names
        RepoUpdateEventBus.send(RepoUpdateEvent.RepoUpdated(owner, repo))
        result
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
     * 获取文件信息和内容（Base64 解码），带 5 分钟内存缓存。
     * 一次 API 调用同时获取文件信息（含 sha）和内容，避免重复请求。
     */
    data class FileWithInfo(
        val info: GHContent,
        val content: String
    )
    
    private val fileWithInfoCache = java.util.concurrent.ConcurrentHashMap<String, Entry<FileWithInfo>>()
    
    suspend fun getFileWithInfo(
        owner: String, repo: String, path: String, ref: String,
        forceRefresh: Boolean = false,
    ): FileWithInfo = withContext(Dispatchers.IO) {
        val key = "$owner/$repo/$ref/$path"
        if (!forceRefresh) {
            fileWithInfoCache[key]?.takeIf { it.valid(FILE_TTL) }?.data?.let {
                return@withContext it
            }
        }
        val f = api.getFile(owner, repo, path, ref)
        val encoded = f.content ?: ""
        val decoded = String(Base64.decode(encoded.replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
        val result = FileWithInfo(f, decoded)
        fileWithInfoCache[key] = Entry(result)
        result
    }

    /**
     * 获取文件内容（Base64 解码），带 5 分钟内存缓存。
     * 保留此方法用于向后兼容。
     */
    suspend fun getFileContent(
        owner: String, repo: String, path: String, ref: String,
        forceRefresh: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        getFileWithInfo(owner, repo, path, ref, forceRefresh).content
    }

    /**
     * 获取文件信息（包含sha）
     * 保留此方法用于向后兼容。
     */
    suspend fun getFileInfo(
        owner: String, repo: String, path: String, ref: String,
    ): GHContent = withContext(Dispatchers.IO) {
        getFileWithInfo(owner, repo, path, ref).info
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
        fileWithInfoCache.clear()
        response
    }

    /**
     * 删除文件（使用 GraphQL API）
     */
    suspend fun deleteFile(
        owner: String, repo: String, path: String,
        message: String, sha: String,
        branch: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val token = ApiClient.currentToken() ?: return@withContext false
        val success = GraphQLClient.deleteFileWithGraphQL(
            token = token,
            owner = owner,
            repo = repo,
            branch = branch ?: "main",
            path = path,
            message = message
        )
        if (success) {
            invalidateContentsCache(owner, repo)
            fileContentCache.clear()
            fileWithInfoCache.clear()
        }
        success
    }

    /**
     * 使用 GraphQL API 重命名文件（单次 commit）
     */
    suspend fun renameFileGraphQL(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        oldPath: String,
        newPath: String,
        contentBase64: String,
        message: String
    ): Boolean = withContext(Dispatchers.IO) {
        val success = GraphQLClient.renameFileWithGraphQL(
            token = token,
            owner = owner,
            repo = repo,
            branch = branch,
            oldPath = oldPath,
            newPath = newPath,
            contentBase64 = contentBase64,
            message = message
        )
        if (success) {
            invalidateContentsCache(owner, repo)
            fileContentCache.clear()
            fileWithInfoCache.clear()
        }
        success
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

    /**
     * 加载 PR 列表，使用 REST API。
     */
    suspend fun getPRs(
        owner: String,
        repo: String,
        state: String = "open",
        sort: String = "created",
        direction: String = "desc",
        page: Int = 1,
        forceRefresh: Boolean = false,
        labelFilter: String? = null,
        authorFilter: String? = null,
        reviewerFilter: String? = null,
        isMergedFilter: Boolean = false,
    ): List<GHPullRequest> = withContext(Dispatchers.IO) {
        val key = "$owner/$repo/pr/$state/$sort/$direction/$page/$labelFilter/$authorFilter/$reviewerFilter"
        if (!forceRefresh && page == 1)
            prCache[key]?.takeIf { it.valid(LIST_TTL) }?.data?.let { return@withContext it }

        val token = ApiClient.currentToken() ?: return@withContext emptyList()
        val apiState = when {
            isMergedFilter -> "closed"
            else -> state
        }
        
        val (prs, _) = GraphQLClient.getPullRequests(
            token = token,
            owner = owner,
            repo = repo,
            state = apiState,
            first = 30
        )
        
        val result = if (isMergedFilter) prs.filter { it.merged == true } else prs

        if (page == 1) prCache[key] = Entry(result)
        result
    }

    /** 增量刷新：只拉第一页，与缓存合并（按 number 去重，新条目在前） */
    suspend fun refreshPRsIncremental(
        owner: String, repo: String,
        state: String = "open",
        sort: String = "created",
        direction: String = "desc",
        labelFilter: String? = null,
        authorFilter: String? = null,
        reviewerFilter: String? = null,
        isMergedFilter: Boolean = false,
    ): List<GHPullRequest> = withContext(Dispatchers.IO) {
        val key = "$owner/$repo/pr/$state/$sort/$direction/1/$labelFilter/$authorFilter/$reviewerFilter"
        val fresh = getPRs(owner, repo, state, sort, direction, 1, true,
            labelFilter, authorFilter, reviewerFilter, isMergedFilter)
        val cached = prCache[key]?.data ?: emptyList()
        val merged = (fresh + cached).distinctBy { it.number }
        prCache[key] = Entry(merged)
        merged
    }

    suspend fun getPRDetail(owner: String, repo: String, number: Int): GHPullRequest =
        withContext(Dispatchers.IO) {
            api.getPullRequest(owner, repo, number)
        }

    suspend fun getPRReviews(owner: String, repo: String, number: Int): List<GHReview> =
        withContext(Dispatchers.IO) { api.getPRReviews(owner, repo, number) }

    suspend fun getPRComments(owner: String, repo: String, number: Int): List<GHComment> =
        withContext(Dispatchers.IO) { api.getPRComments(owner, repo, number) }

    suspend fun mergePR(
        owner: String, repo: String, number: Int, method: String, title: String?, message: String?,
    ): GHMergePRResponse = withContext(Dispatchers.IO) {
        api.mergePullRequest(owner, repo, number, GHMergePRRequest(title, message, method))
    }

    suspend fun updatePRBranch(owner: String, repo: String, number: Int): GHUpdateBranchResponse =
        withContext(Dispatchers.IO) { api.updatePRBranch(owner, repo, number) }

    suspend fun createPRReview(
        owner: String, repo: String, number: Int, event: String, body: String?,
    ): GHReview = withContext(Dispatchers.IO) {
        api.createPRReview(owner, repo, number, GHCreateReviewRequest(body, event))
    }

    suspend fun requestReviewers(
        owner: String, repo: String, number: Int, reviewers: List<String>,
    ): GHPullRequest = withContext(Dispatchers.IO) {
        api.requestReviewers(owner, repo, number, GHReviewersRequest(reviewers))
    }

    suspend fun addPRComment(owner: String, repo: String, number: Int, body: String): GHComment =
        withContext(Dispatchers.IO) {
            api.addPRComment(owner, repo, number, GHCreateCommentRequest(body))
        }

    suspend fun closePR(owner: String, repo: String, number: Int): GHPullRequest =
        withContext(Dispatchers.IO) {
            api.updatePullRequest(owner, repo, number, GHUpdatePullRequestRequest(state = "closed"))
        }

    fun invalidatePRCache(owner: String, repo: String) {
        prCache.keys.filter { it.startsWith("$owner/$repo/pr/") }.forEach { prCache.remove(it) }
    }

    suspend fun getIssues(
        owner: String, repo: String,
        state: String = "open",
        labels: String? = null,
        creator: String? = null,
        sort: String = "created",
        direction: String = "desc",
        page: Int = 1,
        forceRefresh: Boolean = false,
    ): List<GHIssue> = withContext(Dispatchers.IO) {
        val key = "$owner/$repo/$state/$labels/$creator/$sort/$direction/$page"
        if (!forceRefresh && page == 1)
            issueCache[key]?.takeIf { it.valid(LIST_TTL) }?.data?.let { return@withContext it }

        val token = ApiClient.currentToken() ?: return@withContext emptyList()
        val (issues, _) = GraphQLClient.getIssues(
            token = token,
            owner = owner,
            repo = repo,
            state = state,
            first = 30,
            labels = labels,
            creator = creator
        )

        if (page == 1) issueCache[key] = Entry(issues)
        issues
    }

    suspend fun getIssue(owner: String, repo: String, issueNumber: Int): GHIssue =
        withContext(Dispatchers.IO) { api.getIssue(owner, repo, issueNumber) }

    /**
     * 使用GraphQL API获取Issue详情和评论（包含lastEditedAt字段）
     */
    suspend fun getIssueDetailGraphQL(owner: String, repo: String, issueNumber: Int): Pair<GHIssue?, List<GHComment>> =
        withContext(Dispatchers.IO) {
            val token = ApiClient.currentToken() ?: return@withContext Pair(null, emptyList())
            GraphQLClient.getIssueDetail(token, owner, repo, issueNumber)
        }

    /**
     * 使用GraphQL API获取PR详情、评论和审查（包含lastEditedAt字段）
     */
    suspend fun getPRDetailGraphQL(owner: String, repo: String, prNumber: Int): Triple<GHPullRequest?, List<GHComment>, List<GHReview>> =
        withContext(Dispatchers.IO) {
            val token = ApiClient.currentToken() ?: return@withContext Triple(null, emptyList(), emptyList())
            GraphQLClient.getPRDetail(token, owner, repo, prNumber)
        }

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

    // ─── Discussions (GraphQL) ───
    
    /**
     * 获取Discussion分类列表
     */
    suspend fun getDiscussionCategories(owner: String, repo: String): List<com.gitmob.android.api.GHDiscussionCategory> =
        withContext(Dispatchers.IO) {
            val token = ApiClient.currentToken() ?: return@withContext emptyList()
            GraphQLClient.getDiscussionCategories(token, owner, repo)
        }

    /**
     * 获取Discussion列表（带分页）
     */
    suspend fun getDiscussions(
        owner: String, repo: String,
        first: Int = 20, after: String? = null,
        categoryId: String? = null,
    ): Pair<List<com.gitmob.android.api.GHDiscussion>, String?> =
        withContext(Dispatchers.IO) {
            val token = ApiClient.currentToken() ?: return@withContext Pair(emptyList(), null)
            GraphQLClient.getDiscussions(token, owner, repo, first, after, categoryId)
        }

    /**
     * 使用GraphQL API获取Discussion详情和评论（包含lastEditedAt字段）
     */
    suspend fun getDiscussionDetailGraphQL(
        owner: String, repo: String, number: Int
    ): Pair<com.gitmob.android.api.GHDiscussion?, List<com.gitmob.android.api.GHDiscussionComment>> =
        withContext(Dispatchers.IO) {
            val token = ApiClient.currentToken() ?: return@withContext Pair(null, emptyList())
            GraphQLClient.getDiscussionDetail(token, owner, repo, number)
        }

    /**
     * 更新Discussion订阅状态
     */
    suspend fun updateDiscussionSubscription(discussionId: String, subscribed: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val token = ApiClient.currentToken() ?: return@withContext false
            GraphQLClient.updateDiscussionSubscription(token, discussionId, subscribed)
        }

    /**
     * 更新Discussion
     */
    suspend fun updateDiscussion(discussionId: String, title: String? = null, body: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val token = ApiClient.currentToken() ?: return@withContext false
            GraphQLClient.updateDiscussion(token, discussionId, title, body)
        }

    /**
     * 删除Discussion
     */
    suspend fun deleteDiscussion(discussionId: String): Boolean =
        withContext(Dispatchers.IO) {
            val token = ApiClient.currentToken() ?: return@withContext false
            GraphQLClient.deleteDiscussion(token, discussionId)
        }

    /**
     * 添加Discussion评论
     */
    suspend fun addDiscussionComment(
        discussionId: String, body: String, replyToId: String? = null
    ): com.gitmob.android.api.GHDiscussionComment? =
        withContext(Dispatchers.IO) {
            val token = ApiClient.currentToken() ?: return@withContext null
            GraphQLClient.addDiscussionComment(token, discussionId, body, replyToId)
        }

    /**
     * 更新Discussion评论
     */
    suspend fun updateDiscussionComment(commentId: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            val token = ApiClient.currentToken() ?: return@withContext false
            GraphQLClient.updateDiscussionComment(token, commentId, body)
        }

    /**
     * 删除Discussion评论
     */
    suspend fun deleteDiscussionComment(commentId: String): Boolean =
        withContext(Dispatchers.IO) {
            val token = ApiClient.currentToken() ?: return@withContext false
            GraphQLClient.deleteDiscussionComment(token, commentId)
        }

    /**
     * 获取Discussion主体的编辑历史
     */
    suspend fun getDiscussionBodyEditHistory(
        owner: String, repo: String, number: Int
    ): List<com.gitmob.android.api.GHUserContentEdit> =
        withContext(Dispatchers.IO) {
            val token = ApiClient.currentToken() ?: return@withContext emptyList()
            GraphQLClient.getDiscussionBodyEditHistory(token, owner, repo, number)
        }

    /**
     * 获取Discussion评论的编辑历史
     */
    suspend fun getDiscussionCommentEditHistory(commentId: String): List<com.gitmob.android.api.GHUserContentEdit> =
        withContext(Dispatchers.IO) {
            val token = ApiClient.currentToken() ?: return@withContext emptyList()
            GraphQLClient.getDiscussionCommentEditHistory(token, commentId)
        }

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

    suspend fun getWorkflowRuns(
        owner: String,
        repo: String,
        workflowId: Long? = null,
        page: Int = 1
    ): List<GHWorkflowRun> =
        withContext(Dispatchers.IO) {
            if (workflowId != null) {
                api.getWorkflowRunsForWorkflow(owner, repo, workflowId, page = page).workflowRuns
            } else {
                api.getWorkflowRuns(owner, repo, page = page).workflowRuns
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
        withContext(Dispatchers.IO) {
            try {
                val response = api.deleteWorkflowRun(owner, repo, runId)
                val isSuccess = response.isSuccessful || response.code() in listOf(200, 201, 202, 204)

                if (isSuccess) {
                    LogManager.d("WorkflowDelete", "删除 Workflow Run #$runId 成功（状态码: ${response.code()}）")
                    true
                } else {
                    val errorBody = response.errorBody()?.string() ?: "未知错误"
                    LogManager.e("WorkflowDelete",
                        "删除 Workflow Run #$runId 失败，状态码: ${response.code()}, 错误: $errorBody")
                    false
                }
            } catch (e: Exception) {
                LogManager.e("WorkflowDelete", "删除 Workflow Run #$runId 时发生异常", e)
                false
            }
        }

    suspend fun rerunWorkflow(owner: String, repo: String, runId: Long): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val response = api.rerunWorkflow(owner, repo, runId)
                val isSuccess = response.isSuccessful || response.code() in listOf(200, 201, 202, 204)

                if (isSuccess) {
                    LogManager.d("WorkflowRerun", "重新运行 Workflow Run #$runId 成功（状态码: ${response.code()}）")
                    true
                } else {
                    val errorBody = response.errorBody()?.string() ?: "未知错误"
                    LogManager.e("WorkflowRerun",
                        "重新运行 Workflow Run #$runId 失败，状态码: ${response.code()}, 错误: $errorBody")
                    false
                }
            } catch (e: Exception) {
                LogManager.e("WorkflowRerun", "重新运行 Workflow Run #$runId 时发生异常", e)
                false
            }
        }

    suspend fun cancelWorkflow(owner: String, repo: String, runId: Long): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val response = api.cancelWorkflow(owner, repo, runId)

                // GitHub 官方推荐的成功状态码
                val isSuccess = when (response.code()) {
                    202,  // Accepted（异步取消请求已接受）← 最常见
                    200,  // 极少数情况下返回 200
                    204 -> true   // No Content
                    else -> false
                }

                if (isSuccess) {
                    LogManager.d("WorkflowCancel", "取消 Workflow Run #$runId 成功（状态码: ${response.code()}）")
                    true
                } else {
                    val errorBody = response.errorBody()?.string() ?: "未知错误"
                    LogManager.e("WorkflowCancel",
                        "取消 Workflow Run #$runId 失败，状态码: ${response.code()}, 错误: $errorBody")
                    false
                }
            } catch (e: Exception) {
                LogManager.e("WorkflowCancel", "取消 Workflow Run #$runId 时发生异常", e)
                false
            }
        }

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
        withContext(Dispatchers.IO) {
            try {
                val response = api.deleteArtifact(owner, repo, artifactId)
                val isSuccess = response.isSuccessful || response.code() in listOf(200, 201, 202, 204)

                if (isSuccess) {
                    LogManager.d("ArtifactDelete", "删除 Artifact #$artifactId 成功（状态码: ${response.code()}）")
                    true
                } else {
                    val errorBody = response.errorBody()?.string() ?: "未知错误"
                    LogManager.e("ArtifactDelete",
                        "删除 Artifact #$artifactId 失败，状态码: ${response.code()}, 错误: $errorBody")
                    false
                }
            } catch (e: Exception) {
                LogManager.e("ArtifactDelete", "删除 Artifact #$artifactId 时发生异常", e)
                false
            }
        }

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
        val hasIssues = node.optBoolean("hasIssuesEnabled", true)
        val hasDiscussions = node.optBoolean("hasDiscussionsEnabled", false)
        val archived = node.optBoolean("isArchived", false)
        val homepage = node.optString("homepageUrl").ifBlank { null }

        // 解析 topics
        val topics = mutableListOf<String>()
        node.optJSONObject("repositoryTopics")?.let { rtNode ->
            rtNode.optJSONArray("nodes")?.let { nodesArray ->
                for (i in 0 until nodesArray.length()) {
                    nodesArray.optJSONObject(i)?.let { topicNode ->
                    topicNode.optJSONObject("topic")?.let { innerTopic ->
                        val topicName = innerTopic.optString("name")
                        if (topicName.isNotBlank()) {
                            topics.add(topicName)
                        }
                    }
                }
                }
            }
        }

        val idLong = node.optLong("databaseId", 0L)
        val ownerAvatarUrl = node.optString("openGraphImageUrl").ifBlank { null }
        val owner = GHOwner(login = ownerLogin, avatarUrl = ownerAvatarUrl)

        // 解析 parent 字段（（（（（
        val parent = node.optJSONObject("parent")?.let { mapGraphQLToGHRepo(it) }

        return GHRepo(
            id            = idLong,
            name          = repoName,
            fullName      = nameWithOwner,
            description   = node.optString("description").ifBlank { null },
            homepage      = homepage,
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
            hasIssues     = hasIssues,
            hasDiscussions = hasDiscussions,
            archived      = archived,
            topics        = topics,
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

    // ─── Git Tree 操作（用于创建子模块、符号链接等复杂操作）───────────────────────────────────────────

    /**
     * 使用 Git Tree API 进行文件操作（支持符号链接、子模块等）
     *
     * @param owner 仓库所有者
     * @param repo 仓库名
     * @param branch 分支名
     * @param message 提交信息
     * @param treeItems 要操作的文件列表（TreeItem）
     */
    suspend fun createCommitWithGitTree(
        owner: String,
        repo: String,
        branch: String,
        message: String,
        treeItems: List<com.gitmob.android.api.GHTreeItem>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            LogManager.d("GitTree", "开始 Git Tree 操作: $owner/$repo@$branch")

            // 第 1 步：获取分支最新 commit SHA
            val ref = api.getRef(owner, repo, branch)
            val currentCommitSha = ref.obj.sha
            LogManager.d("GitTree", "当前 commit SHA: $currentCommitSha")

            // 第 2 步：获取该 commit 的 tree SHA 作为 base_tree
            val currentCommit = api.getGitCommit(owner, repo, currentCommitSha)
            val baseTreeSha = currentCommit.tree.sha
            LogManager.d("GitTree", "当前 tree SHA: $baseTreeSha")

            // 第 3 步：创建新 tree
            val newTree = api.createTree(
                owner, repo,
                com.gitmob.android.api.GHCreateTreeRequest(
                    tree = treeItems,
                    baseTree = baseTreeSha
                )
            )
            LogManager.d("GitTree", "新 tree SHA: ${newTree.sha}")

            // 第 4 步：创建 commit
            val newCommit = api.createCommit(
                owner, repo,
                com.gitmob.android.api.GHCreateCommitRequest(
                    message = message,
                    tree = newTree.sha,
                    parents = listOf(currentCommitSha)
                )
            )
            LogManager.d("GitTree", "新 commit SHA: ${newCommit.sha}")

            // 第 5 步：更新分支 ref
            api.updateRef(
                owner, repo, branch,
                com.gitmob.android.api.GHUpdateRefRequest(newCommit.sha, force = false)
            )
            LogManager.d("GitTree", "分支 $branch 更新成功")

            invalidateContentsCache(owner, repo)
            fileContentCache.clear()
            fileWithInfoCache.clear()

            true
        } catch (e: Exception) {
            LogManager.e("GitTree", "Git Tree 操作失败", e)
            false
        }
    }

    /**
     * 辅助函数：创建符号链接
     */
    suspend fun createSymlink(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        targetPath: String,
        message: String
    ): Boolean = withContext(Dispatchers.IO) {
        val treeItem = com.gitmob.android.api.GHTreeItem(
            path = path,
            mode = "120000",
            type = "blob",
            content = targetPath
        )
        createCommitWithGitTree(owner, repo, branch, message, listOf(treeItem))
    }

    /**
     * 辅助函数：创建子模块（同时创建/更新 .gitmodules）
     */
    suspend fun createSubmodule(
        owner: String,
        repo: String,
        branch: String,
        submodulePath: String,
        submoduleUrl: String,
        submoduleCommitSha: String,
        message: String
    ): Boolean = withContext(Dispatchers.IO) {
        val treeItems = mutableListOf<com.gitmob.android.api.GHTreeItem>()

        // 1. 添加子模块条目
        treeItems.add(
            com.gitmob.android.api.GHTreeItem(
                path = submodulePath,
                mode = "160000",
                type = "commit",
                sha = submoduleCommitSha
            )
        )

        // 2. 处理 .gitmodules 文件
        try {
            val existingGitmodules = try {
                getFileContent(owner, repo, ".gitmodules", branch, forceRefresh = true)
            } catch (e: Exception) {
                null
            }

            val newGitmodulesContent = if (existingGitmodules != null) {
                // .gitmodules 已存在，检查是否已有该子模块的配置
                val submoduleSection = """\[submodule "$submodulePath"\]"""
                if (existingGitmodules.contains(submoduleSection)) {
                    // 已存在，更新（保持原有内容，只修改需要的部分，这里简化处理为完全重写）
                    buildGitmodulesContent(existingGitmodules, submodulePath, submoduleUrl)
                } else {
                    // 不存在，追加新配置
                    existingGitmodules + "\n\n" + buildSingleSubmoduleConfig(submodulePath, submoduleUrl)
                }
            } else {
                // .gitmodules 不存在，创建新的
                buildSingleSubmoduleConfig(submodulePath, submoduleUrl)
            }

            treeItems.add(
                com.gitmob.android.api.GHTreeItem(
                    path = ".gitmodules",
                    mode = "100644",
                    type = "blob",
                    content = newGitmodulesContent
                )
            )
        } catch (e: Exception) {
            LogManager.e("GitTree", "处理 .gitmodules 时出错", e)
            // 即使出错也继续，至少尝试添加子模块条目
        }

        createCommitWithGitTree(owner, repo, branch, message, treeItems)
    }

    /**
     * 辅助函数：更新已有子模块（只更新 commit SHA，不修改 .gitmodules）
     */
    suspend fun updateSubmoduleCommit(
        owner: String,
        repo: String,
        branch: String,
        submodulePath: String,
        newCommitSha: String,
        message: String
    ): Boolean = withContext(Dispatchers.IO) {
        val treeItem = com.gitmob.android.api.GHTreeItem(
            path = submodulePath,
            mode = "160000",
            type = "commit",
            sha = newCommitSha
        )
        createCommitWithGitTree(owner, repo, branch, message, listOf(treeItem))
    }

    /**
     * 构建 .gitmodules 配置内容（单个子模块）
     */
    private fun buildSingleSubmoduleConfig(path: String, url: String): String {
        return """[submodule "$path"]
	path = $path
	url = $url"""
    }

    /**
     * 更新 .gitmodules 内容（保留其他子模块配置）
     */
    private fun buildGitmodulesContent(existing: String, targetPath: String, targetUrl: String): String {
        val lines = existing.lines().toMutableList()
        val result = mutableListOf<String>()
        var i = 0
        var inTargetSection = false
        var replaced = false

        while (i < lines.size) {
            val line = lines[i]
            if (line.trim().startsWith("[submodule")) {
                val sectionPath = line.substringAfter("[submodule \"").substringBefore("\"]")
                if (sectionPath == targetPath) {
                    // 找到目标子模块，替换配置
                    inTargetSection = true
                    replaced = true
                    result.addAll(buildSingleSubmoduleConfig(targetPath, targetUrl).lines())
                    // 跳过原有的配置行，直到下一个 section 或结束
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("[")) {
                        i++
                    }
                    continue
                } else {
                    inTargetSection = false
                }
            }
            result.add(line)
            i++
        }

        // 如果没找到目标子模块，追加到末尾
        if (!replaced) {
            if (result.isNotEmpty() && result.last().isNotBlank()) {
                result.add("")
            }
            result.addAll(buildSingleSubmoduleConfig(targetPath, targetUrl).lines())
        }

        return result.joinToString("\n")
    }

    // ─── 子模块辅助工具 ──────────────────────────────────────────────────────────

    /**
     * 解析 GitHub 仓库 URL，提取 owner 和 repo
     *
     * 支持的 URL 格式：
     * - https://github.com/owner/repo.git
     * - https://github.com/owner/repo
     * - git@github.com:owner/repo.git
     * - owner/repo
     */
    fun parseRepoUrl(url: String): Pair<String, String>? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        // 尝试各种格式
        val patterns = listOf(
            // HTTPS 格式: https://github.com/owner/repo.git 或 https://github.com/owner/repo
            Regex("""^https?://github\.com/([^/]+)/([^/.]+)(\.git)?$"""),
            // SSH 格式: git@github.com:owner/repo.git
            Regex("""^git@github\.com:([^/]+)/([^/.]+)(\.git)?$"""),
            // 简单格式: owner/repo
            Regex("""^([^/]+)/([^/.]+)$""")
        )

        for (pattern in patterns) {
            val match = pattern.matchEntire(trimmed)
            if (match != null) {
                val owner = match.groupValues[1]
                val repo = match.groupValues[2]
                if (owner.isNotEmpty() && repo.isNotEmpty()) {
                    return Pair(owner, repo)
                }
            }
        }

        return null
    }

    /**
     * 获取指定仓库的最新 commit SHA（使用默认分支）
     */
    suspend fun getLatestCommitSha(repoOwner: String, repoName: String): String? = withContext(Dispatchers.IO) {
        try {
            // 1. 首先获取仓库信息，找到默认分支
            val repo = api.getRepo(repoOwner, repoName)
            val defaultBranch = repo.defaultBranch

            // 2. 获取该分支的 ref，找到最新 commit SHA
            val ref = api.getRef(repoOwner, repoName, defaultBranch)
            ref.obj.sha
        } catch (e: Exception) {
            LogManager.e("Submodule", "获取子模块最新 commit SHA 失败: $repoOwner/$repoName", e)
            null
        }
    }

    /**
     * 快捷方法：通过仓库 URL 获取最新 commit SHA
     */
    suspend fun getLatestCommitShaFromUrl(repoUrl: String): String? {
        val parsed = parseRepoUrl(repoUrl) ?: return null
        return getLatestCommitSha(parsed.first, parsed.second)
    }
}
