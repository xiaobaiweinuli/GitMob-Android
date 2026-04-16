package com.gitmob.android.api

import com.gitmob.android.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub GraphQL API 客户端
 *
 * 优势：一次请求获取仓库全量概况（repo + stars + branches count + defaultBranch + topics + languages +
 * README existence + PR count + Issue count + release count），
 * 替代原来 loadAll() 中的 5-6 个并发 REST 请求。
 *
 * 端点：POST https://api.github.com/graphql
 */
object GraphQLClient {

    private const val TAG = "GraphQL"
    private const val ENDPOINT = "https://api.github.com/graphql"

    // ── 用户仓库列表 ─────────────────────────────────────────────────────
    private val USER_REPOS_QUERY = """
        query UserRepos(${'$'}cursor: String) {
          viewer {
            repositories(first: 50, after: ${'$'}cursor, ownerAffiliations: [OWNER], orderBy: {field: UPDATED_AT, direction: DESC}) {
              pageInfo { hasNextPage endCursor }
              nodes {
                id
                databaseId
                name
                nameWithOwner
                description
                homepageUrl
                isPrivate
                url
                sshUrl
                defaultBranchRef { name }
                stargazerCount
                forkCount
                pushedAt
                updatedAt
                createdAt
                isFork
                isArchived
                isTemplate
                mirrorUrl
                primaryLanguage { name }
                owner { login avatarUrl }
                openIssues: issues(states: OPEN) { totalCount }
                repositoryTopics(first: 10) { nodes { topic { name } } }
                parent {
                  id
                  databaseId
                  name
                  nameWithOwner
                  description
                  homepageUrl
                  isPrivate
                  url
                  sshUrl
                  owner { login avatarUrl }
                  isFork
                }
              }
            }
          }
        }
    """.trimIndent()

    // ── 组织仓库列表 ─────────────────────────────────────────────────────
    private val ORG_REPOS_QUERY = """
        query OrgRepos(${'$'}org: String!, ${'$'}cursor: String) {
          organization(login: ${'$'}org) {
            repositories(first: 50, after: ${'$'}cursor, orderBy: {field: UPDATED_AT, direction: DESC}) {
              pageInfo { hasNextPage endCursor }
              nodes {
                id
                databaseId
                name
                nameWithOwner
                description
                homepageUrl
                isPrivate
                url
                sshUrl
                defaultBranchRef { name }
                stargazerCount
                forkCount
                pushedAt
                updatedAt
                createdAt
                isFork
                isArchived
                isTemplate
                mirrorUrl
                primaryLanguage { name }
                owner { login avatarUrl }
                openIssues: issues(states: OPEN) { totalCount }
                repositoryTopics(first: 10) { nodes { topic { name } } }
                parent {
                  id
                  databaseId
                  name
                  nameWithOwner
                  description
                  homepageUrl
                  isPrivate
                  url
                  sshUrl
                  owner { login avatarUrl }
                  isFork
                }
              }
            }
          }
        }
    """.trimIndent()

    // ── 星标仓库列表 ──────────────────────────────────────────────────────
    // 注意：Repository 不暴露 lists 字段，listIds 在应用层通过 updateUserListsForItem 维护
    private val STARRED_REPOS_QUERY = """
        query StarredRepos(${'$'}cursor: String) {
          viewer {
            starredRepositories(first: 50, after: ${'$'}cursor, orderBy: {field: STARRED_AT, direction: DESC}) {
              pageInfo { hasNextPage endCursor }
              nodes {
                id
                databaseId
                name
                nameWithOwner
                description
                homepageUrl
                isPrivate
                url
                sshUrl
                defaultBranchRef { name }
                stargazerCount
                forkCount
                pushedAt
                updatedAt
                createdAt
                isFork
                isArchived
                isTemplate
                mirrorUrl
                primaryLanguage { name }
                owner { login avatarUrl }
                openIssues: issues(states: OPEN) { totalCount }
                repositoryTopics(first: 10) { nodes { topic { name } } }
              }
            }
          }
        }
    """.trimIndent()

    // ── 用户所有 UserList ─────────────────────────────────────────────────
    private val USER_LISTS_QUERY = """
        query UserLists {
          viewer {
            lists(first: 100) {
              nodes {
                id
                name
                description
                isPrivate
                items { totalCount }
              }
            }
          }
        }
    """.trimIndent()

    // ── 按列表筛选星标仓库 ─────────────────────────────────────────────────
    private val LIST_REPOS_QUERY = """
        query ListRepos(${'$'}listId: ID!, ${'$'}cursor: String) {
          node(id: ${'$'}listId) {
            ... on UserList {
              items(first: 50, after: ${'$'}cursor) {
                pageInfo { hasNextPage endCursor }
                nodes {
                  ... on Repository {
                    id
                    databaseId
                    name
                    nameWithOwner
                    description
                    homepageUrl
                    isPrivate
                    url
                    sshUrl
                    defaultBranchRef { name }
                    stargazerCount
                    forkCount
                    pushedAt
                    updatedAt
                    createdAt
                    isFork
                    isArchived
                    isTemplate
                    mirrorUrl
                    primaryLanguage { name }
                    owner { login avatarUrl }
                    openIssues: issues(states: OPEN) { totalCount }
                    repositoryTopics(first: 10) { nodes { topic { name } } }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    // ── Mutation: 创建 UserList ─────────────────────────────────────────────
    private val CREATE_USER_LIST_MUTATION = """
        mutation CreateUserList(${'$'}input: CreateUserListInput!) {
          createUserList(input: ${'$'}input) {
            list {
              id name description isPrivate
              items { totalCount }
            }
            clientMutationId
          }
        }
    """.trimIndent()

    // ── Mutation: 更新 UserList ─────────────────────────────────────────────
    private val UPDATE_USER_LIST_MUTATION = """
        mutation UpdateUserList(${'$'}input: UpdateUserListInput!) {
          updateUserList(input: ${'$'}input) {
            list { id name description isPrivate items { totalCount } }
            clientMutationId
          }
        }
    """.trimIndent()

    // ── Mutation: 删除 UserList（返回 clientMutationId，无 userId 字段）─────
    private val DELETE_USER_LIST_MUTATION = """
        mutation DeleteUserList(${'$'}input: DeleteUserListInput!) {
          deleteUserList(input: ${'$'}input) {
            clientMutationId
          }
        }
    """.trimIndent()

    // ── Mutation: 更新仓库所属列表（覆盖写，传空数组=移出所有列表）──────────
    private val UPDATE_REPO_LISTS_MUTATION = """
        mutation UpdateRepoLists(${'$'}itemId: ID!, ${'$'}listIds: [ID!]!) {
          updateUserListsForItem(input: { itemId: ${'$'}itemId, listIds: ${'$'}listIds }) {
            item {
              ... on Repository {
                id
                lists(first: 20) { nodes { id } }
              }
            }
          }
        }
    """.trimIndent()

    // ── Mutation: 取消星标 ─────────────────────────────────────────────────
    private val REMOVE_STAR_MUTATION = """
        mutation RemoveStar(${'$'}starrableId: ID!) {
          removeStar(input: { starrableId: ${'$'}starrableId }) {
            starrable { id }
          }
        }
    """.trimIndent()

    // ── 公开方法 ─────────────────────────────────────────────────────────────

    /** 查询用户仓库（支持分页），返回 repositories 节点 */
    suspend fun queryUserRepos(token: String, cursor: String? = null): org.json.JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val vars = JSONObject().apply { if (cursor != null) put("cursor", cursor) }
                val resp = post(token, JSONObject().put("query", USER_REPOS_QUERY).put("variables", vars).toString())
                resp?.optJSONObject("data")?.optJSONObject("viewer")?.optJSONObject("repositories")
            } catch (e: Exception) {
                LogManager.w(TAG, "queryUserRepos 失败: ${e.message}"); null
            }
        }

    /** 查询组织仓库（支持分页），返回 repositories 节点 */
    suspend fun queryOrgRepos(token: String, org: String, cursor: String? = null): org.json.JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val vars = JSONObject().put("org", org).apply { if (cursor != null) put("cursor", cursor) }
                val resp = post(token, JSONObject().put("query", ORG_REPOS_QUERY).put("variables", vars).toString())
                resp?.optJSONObject("data")?.optJSONObject("organization")?.optJSONObject("repositories")
            } catch (e: Exception) {
                LogManager.w(TAG, "queryOrgRepos 失败: ${e.message}"); null
            }
        }

    /** 查询星标仓库（支持分页），返回 nodes 数组 */
    suspend fun queryStarredRepos(token: String, cursor: String? = null): org.json.JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val vars = JSONObject().apply { if (cursor != null) put("cursor", cursor) }
                val resp = post(token, JSONObject().put("query", STARRED_REPOS_QUERY).put("variables", vars).toString())
                resp?.optJSONObject("data")?.optJSONObject("viewer")?.optJSONObject("starredRepositories")
            } catch (e: Exception) {
                LogManager.w(TAG, "queryStarredRepos 失败: ${e.message}"); null
            }
        }

    /** 查询用户所有 UserList */
    suspend fun queryUserLists(token: String): List<org.json.JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                val resp = post(token, JSONObject().put("query", USER_LISTS_QUERY).put("variables", JSONObject()).toString())
                val nodes = resp?.optJSONObject("data")?.optJSONObject("viewer")?.optJSONObject("lists")?.optJSONArray("nodes")
                    ?: return@withContext emptyList()
                (0 until nodes.length()).map { nodes.getJSONObject(it) }
            } catch (e: Exception) {
                LogManager.w(TAG, "queryUserLists 失败: ${e.message}"); emptyList()
            }
        }

    /** 按列表 ID 查询仓库 */
    suspend fun queryListRepos(token: String, listId: String, cursor: String? = null): org.json.JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val vars = JSONObject().put("listId", listId).apply { if (cursor != null) put("cursor", cursor) }
                val resp = post(token, JSONObject().put("query", LIST_REPOS_QUERY).put("variables", vars).toString())
                resp?.optJSONObject("data")?.optJSONObject("node")?.optJSONObject("items")
            } catch (e: Exception) {
                LogManager.w(TAG, "queryListRepos 失败: ${e.message}"); null
            }
        }

    /** 创建 UserList，成功返回新列表 JSONObject，失败返回 null */
    suspend fun createUserList(token: String, name: String, description: String, isPrivate: Boolean): org.json.JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val input = JSONObject().put("name", name).put("description", description).put("isPrivate", isPrivate)
                val vars = JSONObject().put("input", input)
                val resp = post(token, JSONObject().put("query", CREATE_USER_LIST_MUTATION).put("variables", vars).toString())
                resp?.optJSONObject("data")?.optJSONObject("createUserList")?.optJSONObject("list")
            } catch (e: Exception) {
                LogManager.w(TAG, "createUserList 失败: ${e.message}"); null
            }
        }

    /** 更新 UserList */
    suspend fun updateUserList(token: String, listId: String, name: String, description: String, isPrivate: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val input = JSONObject().put("listId", listId).put("name", name).put("description", description).put("isPrivate", isPrivate)
                val vars = JSONObject().put("input", input)
                val resp = post(token, JSONObject().put("query", UPDATE_USER_LIST_MUTATION).put("variables", vars).toString())
                resp?.optJSONObject("data")?.optJSONObject("updateUserList") != null
            } catch (e: Exception) {
                LogManager.w(TAG, "updateUserList 失败: ${e.message}"); false
            }
        }

    /** 删除 UserList */
    suspend fun deleteUserList(token: String, listId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val input = JSONObject().put("listId", listId)
                val vars = JSONObject().put("input", input)
                val resp = post(token, JSONObject().put("query", DELETE_USER_LIST_MUTATION).put("variables", vars).toString())
                // deleteUserList 成功时 data.deleteUserList 存在（含 clientMutationId）
                resp?.optJSONObject("data")?.has("deleteUserList") == true
            } catch (e: Exception) {
                LogManager.w(TAG, "deleteUserList 失败: ${e.message}"); false
            }
        }

    /** 更新仓库所属列表（覆盖写） */
    suspend fun updateRepoLists(token: String, repoNodeId: String, listIds: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val arr = org.json.JSONArray().also { a -> listIds.forEach { a.put(it) } }
                val input = JSONObject().put("itemId", repoNodeId).put("listIds", arr)
                val vars = JSONObject().put("input", input)
                val resp = post(token, JSONObject().put("query", UPDATE_REPO_LISTS_MUTATION).put("variables", vars).toString())
                resp?.optJSONObject("data")?.optJSONObject("updateUserListsForItem") != null
            } catch (e: Exception) {
                LogManager.w(TAG, "updateRepoLists 失败: ${e.message}"); false
            }
        }

    /** 取消星标 */
    suspend fun removeStar(token: String, starrableId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val input = JSONObject().put("starrableId", starrableId)
                val vars = JSONObject().put("input", input)
                val resp = post(token, JSONObject().put("query", REMOVE_STAR_MUTATION).put("variables", vars).toString())
                resp?.optJSONObject("data")?.optJSONObject("removeStar") != null
            } catch (e: Exception) {
                LogManager.w(TAG, "removeStar 失败: ${e.message}"); false
            }
        }

    // ── 仓库概况查询 ────────────────────────────────────────────────────────
    // 一次请求替代：getRepo + getTopics + getBranches(count) + README check
    private val REPO_OVERVIEW_QUERY = """
        query RepoOverview(${'$'}owner: String!, ${'$'}name: String!) {
          repository(owner: ${'$'}owner, name: ${'$'}name) {
            id
            databaseId
            name
            nameWithOwner
            description
            homepageUrl
            url
            isPrivate
            isFork
            isArchived
            hasIssuesEnabled
            hasDiscussionsEnabled
            stargazerCount
            forkCount
            openGraphImageUrl
            defaultBranchRef {
              name
            }
            primaryLanguage {
              name
              color
            }
            languages(first: 5, orderBy: {field: SIZE, direction: DESC}) {
              nodes { name color }
            }
            repositoryTopics(first: 10) {
              nodes { topic { name } }
            }
            refs(refPrefix: "refs/heads/", first: 0) {
              totalCount
            }
            openIssues: issues(states: OPEN) { totalCount }
            openPRs:    pullRequests(states: OPEN) { totalCount }
            releases    { totalCount }
            object(expression: "HEAD:README.md") { id }
            diskUsage
            pushedAt
            updatedAt
            licenseInfo { spdxId name }
            watchers { totalCount }
          }
        }
    """.trimIndent()

    // ── 提交历史 + 文件变更（减少 per-file REST 请求） ─────────────────────
    private val COMMIT_HISTORY_QUERY = """
        query CommitHistory(${'$'}owner: String!, ${'$'}name: String!, ${'$'}branch: String!, ${'$'}count: Int!) {
          repository(owner: ${'$'}owner, name: ${'$'}name) {
            ref(qualifiedName: ${'$'}branch) {
              target {
                ... on Commit {
                  history(first: ${'$'}count) {
                    nodes {
                      oid
                      messageHeadline
                      committedDate
                      author { name email avatarUrl }
                      additions
                      deletions
                    }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    /**
     * 查询仓库概况，返回原始 JSON 节点（由 RepoRepository 解析映射到现有数据类）。
     * @return JSONObject of repository node，或 null（失败/降级到 REST）
     */
    suspend fun queryRepoOverview(token: String, owner: String, name: String): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val vars = JSONObject().put("owner", owner).put("name", name)
                val body = JSONObject()
                    .put("query", REPO_OVERVIEW_QUERY)
                    .put("variables", vars)
                val resp = post(token, body.toString())
                resp?.optJSONObject("data")?.optJSONObject("repository")
            } catch (e: Exception) {
                LogManager.w(TAG, "GraphQL 仓库概况失败，降级 REST: ${e.message}")
                null
            }
        }

    /**
     * 查询提交历史（GraphQL 比 REST 少一半请求）。
     */
    suspend fun queryCommitHistory(
        token: String, owner: String, name: String,
        branch: String, count: Int = 30,
    ): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val vars = JSONObject()
                .put("owner", owner).put("name", name)
                .put("branch", "refs/heads/$branch").put("count", count)
            val body = JSONObject()
                .put("query", COMMIT_HISTORY_QUERY)
                .put("variables", vars)
            val resp = post(token, body.toString())
            resp?.optJSONObject("data")
                ?.optJSONObject("repository")
                ?.optJSONObject("ref")
                ?.optJSONObject("target")
                ?.optJSONObject("history")
        } catch (e: Exception) {
            LogManager.w(TAG, "GraphQL 提交历史失败，降级 REST: ${e.message}")
            null
        }
    }

    // ── 用户仓库总数（含私有）────────────────────────────────────────────────

    private val VIEWER_REPO_COUNT_QUERY = """
        query { viewer { repositories(ownerAffiliations: [OWNER]) { totalCount } } }
    """.trimIndent()

    private val USER_REPO_COUNT_QUERY = """
        query UserRepoCount(${'$'}login: String!) {
          user(login: ${'$'}login) { repositories { totalCount } }
        }
    """.trimIndent()

    suspend fun getViewerRepoCount(token: String): Int = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("query", VIEWER_REPO_COUNT_QUERY).toString()
            val json = post(token, body) ?: return@withContext 0
            json.optJSONObject("data")
                ?.optJSONObject("viewer")
                ?.optJSONObject("repositories")
                ?.optInt("totalCount") ?: 0
        } catch (e: Exception) {
            LogManager.w(TAG, "getViewerRepoCount 失败: ${e.message}")
            0
        }
    }

    suspend fun getUserRepoCount(token: String, login: String): Int = withContext(Dispatchers.IO) {
        try {
            val vars = JSONObject().put("login", login)
            val body = JSONObject()
                .put("query", USER_REPO_COUNT_QUERY)
                .put("variables", vars)
                .toString()
            val json = post(token, body) ?: return@withContext 0
            json.optJSONObject("data")
                ?.optJSONObject("user")
                ?.optJSONObject("repositories")
                ?.optInt("totalCount") ?: 0
        } catch (e: Exception) {
            LogManager.w(TAG, "getUserRepoCount 失败: ${e.message}")
            0
        }
    }

    // ── 将草稿 PR 标记为就绪（REST API 无效，必须用 GraphQL）─────────────────

    suspend fun markPrReadyForReview(token: String, prNodeId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val mutation = """
                    mutation MarkReadyForReview(${'$'}input: MarkPullRequestReadyForReviewInput!) {
                      markPullRequestReadyForReview(input: ${'$'}input) {
                        pullRequest { isDraft }
                      }
                    }
                """.trimIndent()
                val vars = JSONObject().put("input", JSONObject().put("pullRequestId", prNodeId))
                val body = JSONObject().put("query", mutation).put("variables", vars).toString()
                val json = post(token, body) ?: return@withContext false
                val pr = json.optJSONObject("data")
                    ?.optJSONObject("markPullRequestReadyForReview")
                    ?.optJSONObject("pullRequest")
                // isDraft == false 表示已成功转为就绪
                pr != null && !pr.optBoolean("isDraft", true)
            } catch (e: Exception) {
                LogManager.w(TAG, "markPrReadyForReview 失败: ${e.message}")
                false
            }
        }

    // ── 置顶仓库（主页用）────────────────────────────────────────────────────

    private val PINNED_REPOS_QUERY = """
        query PinnedRepos(${'$'}login: String!) {
          user(login: ${'$'}login) {
            pinnedItems(first: 6, types: REPOSITORY) {
              nodes {
                ... on Repository {
                  databaseId
                  name
                  nameWithOwner
                  description
                  homepageUrl
                  isPrivate
                  url
                  sshUrl
                  stargazerCount
                  forkCount
                  primaryLanguage { name }
                  defaultBranchRef { name }
                  pushedAt
                  updatedAt
                  createdAt
                  isFork
                  isArchived
                  isTemplate
                  owner { login avatarUrl }
                  repositoryTopics(first: 10) { nodes { topic { name } } }
                }
              }
            }
          }
        }
    """.trimIndent()

    suspend fun getPinnedRepos(token: String, login: String): List<com.gitmob.android.api.GHRepo> =
        withContext(Dispatchers.IO) {
            try {
                val vars = JSONObject().put("login", login)
                val body = JSONObject()
                    .put("query", PINNED_REPOS_QUERY)
                    .put("variables", vars)
                    .toString()
                val json = post(token, body) ?: return@withContext emptyList()
                val nodes = json
                    .optJSONObject("data")
                    ?.optJSONObject("user")
                    ?.optJSONObject("pinnedItems")
                    ?.optJSONArray("nodes") ?: return@withContext emptyList()

                val result = mutableListOf<com.gitmob.android.api.GHRepo>()
                for (i in 0 until nodes.length()) {
                    val n = nodes.optJSONObject(i) ?: continue
                    val ownerObj = n.optJSONObject("owner")
                    val owner = com.gitmob.android.api.GHOwner(
                        login = ownerObj?.optString("login") ?: login,
                        avatarUrl = ownerObj?.optString("avatarUrl"),
                    )
                    val fullName = n.optString("nameWithOwner").ifBlank {
                        "${owner.login}/${n.optString("name")}"
                    }
                    
                    // 解析 topics
                    val topics = mutableListOf<String>()
                    n.optJSONObject("repositoryTopics")?.let { rtNode ->
                        rtNode.optJSONArray("nodes")?.let { nodesArray ->
                            for (j in 0 until nodesArray.length()) {
                                nodesArray.optJSONObject(j)?.let { topicNode ->
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
                    
                    val repo = com.gitmob.android.api.GHRepo(
                        id = n.optLong("databaseId"),
                        name = n.optString("name"),
                        fullName = fullName,
                        description = n.optString("description").ifBlank { null },
                        homepage = n.optString("homepageUrl").ifBlank { null },
                        private = n.optBoolean("isPrivate"),
                        htmlUrl = n.optString("url"),
                        sshUrl = n.optString("sshUrl"),
                        cloneUrl = "${n.optString("url")}.git",
                        defaultBranch = n.optJSONObject("defaultBranchRef")?.optString("name") ?: "main",
                        stars = n.optInt("stargazerCount"),
                        forks = n.optInt("forkCount"),
                        openIssues = 0,
                        language = n.optJSONObject("primaryLanguage")?.optString("name"),
                        owner = owner,
                        fork = n.optBoolean("isFork"),
                        archived = n.optBoolean("isArchived"),
                        isTemplate = n.optBoolean("isTemplate"),
                        updatedAt = n.optString("updatedAt").ifBlank { null },
                        pushedAt = n.optString("pushedAt").ifBlank { null },
                        createdAt = n.optString("createdAt").ifBlank { null },
                        topics = topics,
                    )
                    result.add(repo)
                }
                result
            } catch (e: Exception) {
                LogManager.w(TAG, "getPinnedRepos 失败: ${e.message}")
                emptyList()
            }
        }

    // ── 星标仓库总数 ────────────────────────────────────────────────────────

    private val STARRED_COUNT_QUERY = """
        query { viewer { starredRepositories { totalCount } } }
    """.trimIndent()

    private val USER_STARRED_COUNT_QUERY = """
        query UserStarredCount(${'$'}login: String!) {
          user(login: ${'$'}login) { starredRepositories { totalCount } }
        }
    """.trimIndent()

    suspend fun getStarredCount(token: String): Int = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("query", STARRED_COUNT_QUERY).toString()
            val json = post(token, body) ?: return@withContext 0
            json.optJSONObject("data")
                ?.optJSONObject("viewer")
                ?.optJSONObject("starredRepositories")
                ?.optInt("totalCount") ?: 0
        } catch (e: Exception) {
            LogManager.w(TAG, "getStarredCount 失败: ${e.message}")
            0
        }
    }

    suspend fun getUserStarredCount(token: String, login: String): Int = withContext(Dispatchers.IO) {
        try {
            val vars = JSONObject().put("login", login)
            val body = JSONObject()
                .put("query", USER_STARRED_COUNT_QUERY)
                .put("variables", vars)
                .toString()
            val json = post(token, body) ?: return@withContext 0
            json.optJSONObject("data")
                ?.optJSONObject("user")
                ?.optJSONObject("starredRepositories")
                ?.optInt("totalCount") ?: 0
        } catch (e: Exception) {
            LogManager.w(TAG, "getUserStarredCount 失败: ${e.message}")
            0
        }
    }

    // ── 内部 HTTP ────────────────────────────────────────────────────────────

    private fun post(token: String, jsonBody: String): JSONObject? {
        val req = Request.Builder()
            .url(ENDPOINT)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("Accept",        "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .build()
        val resp = ApiClient.okHttpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            LogManager.w(TAG, "GraphQL HTTP ${resp.code}")
            return null
        }
        val text = resp.body?.string() ?: return null
        val json = JSONObject(text)
        // 检查 errors 字段
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            LogManager.w(TAG, "GraphQL errors: ${errors.getJSONObject(0).optString("message")}")
        }
        return json
    }

    // ── Discussions ────────────────────────────────────────────────────────────

    suspend fun getDiscussionCategories(token: String, owner: String, repo: String): List<com.gitmob.android.api.GHDiscussionCategory> =
        withContext(Dispatchers.IO) {
            try {
                val query = """
                    query { repository(owner: "$owner", name: "$repo") {
                        discussionCategories(first: 25) { nodes {
                            id name emojiHTML description
                        }}
                    }}
                """.trimIndent()
                val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext emptyList()
                val nodes = json.optJSONObject("data")?.optJSONObject("repository")
                    ?.optJSONObject("discussionCategories")?.optJSONArray("nodes") ?: return@withContext emptyList()
                (0 until nodes.length()).map { i ->
                    val n = nodes.getJSONObject(i)
                    com.gitmob.android.api.GHDiscussionCategory(
                        id = n.optString("id"),
                        name = n.optString("name"),
                        emoji = n.optString("emojiHTML").replace(Regex("<[^>]*>"), "").trim().ifBlank { "💬" },
                        description = n.optString("description").ifBlank { null },
                    )
                }
            } catch (e: Exception) { LogManager.w(TAG, "getDiscussionCategories: ${e.message}"); emptyList() }
        }

    suspend fun getDiscussions(
        token: String, owner: String, repo: String,
        first: Int = 20, after: String? = null,
        categoryId: String? = null,
        answered: Boolean? = null,
        orderField: String = "UPDATED_AT",
    ): Pair<List<com.gitmob.android.api.GHDiscussion>, String?> = withContext(Dispatchers.IO) {
        try {
            val afterArg     = if (after != null) """, after: "$after"""" else ""
            val categoryArg  = if (categoryId != null) """, categoryId: "$categoryId"""" else ""
            val answeredArg  = if (answered != null) ", answered: $answered" else ""
            val query = """
                query {
                  repository(owner: "$owner", name: "$repo") {
                    discussions(first: $first$afterArg$categoryArg$answeredArg,
                      orderBy: { field: $orderField, direction: DESC }) {
                      pageInfo { hasNextPage endCursor }
                      nodes {
                        id number title body createdAt updatedAt url
                        upvoteCount
                        isAnswered
                        author { login avatarUrl }
                        category { id name emojiHTML description isAnswerable }
                        comments { totalCount }
                        answer { author { login } }
                        labels(first: 20) {
                          nodes {
                            id name color description
                          }
                        }
                      }
                    }
                  }
                }
            """.trimIndent()
            val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext Pair(emptyList(), null)
            val discussionsObj = json.optJSONObject("data")?.optJSONObject("repository")
                ?.optJSONObject("discussions") ?: return@withContext Pair(emptyList(), null)
            val pageInfo = discussionsObj.optJSONObject("pageInfo")
            val hasNext = pageInfo?.optBoolean("hasNextPage") ?: false
            val endCursor = if (hasNext) pageInfo.optString("endCursor").ifBlank { null } else null
            val nodes = discussionsObj.optJSONArray("nodes") ?: return@withContext Pair(emptyList(), null)
            val list = (0 until nodes.length()).map { i ->
                val n = nodes.getJSONObject(i)
                val authorObj = n.optJSONObject("author")
                val catObj    = n.optJSONObject("category")
                val answerObj = n.optJSONObject("answer")
                val labelsObj = n.optJSONObject("labels")
                val labelsList = mutableListOf<com.gitmob.android.api.GHLabel>()
                labelsObj?.optJSONArray("nodes")?.let { labelsNodes ->
                    for (j in 0 until labelsNodes.length()) {
                        val labelNode = labelsNodes.getJSONObject(j)
                        labelsList.add(
                            com.gitmob.android.api.GHLabel(
                                id = labelNode.optLong("id", 0),
                                name = labelNode.optString("name"),
                                color = labelNode.optString("color"),
                                description = labelNode.optString("description").ifBlank { null }
                            )
                        )
                    }
                }
                com.gitmob.android.api.GHDiscussion(
                    id        = n.optString("id"),
                    number    = n.optInt("number"),
                    title     = n.optString("title"),
                    body      = n.optString("body").ifBlank { null },
                    createdAt = n.optString("createdAt"),
                    updatedAt = n.optString("updatedAt"),
                    url       = n.optString("url"),
                    author    = if (authorObj != null) com.gitmob.android.api.GHOwner(
                        authorObj.optString("login"), authorObj.optString("avatarUrl")) else null,
                    category  = if (catObj != null) com.gitmob.android.api.GHDiscussionCategory(
                        id = catObj.optString("id"), name = catObj.optString("name"),
                        emoji = catObj.optString("emojiHTML").replace(Regex("<[^>]*>"), "").trim().ifBlank { "💬" },
                        description = catObj.optString("description").ifBlank { null },
                        isAnswerable = catObj.optBoolean("isAnswerable", false),
                    ) else null,
                    comments    = n.optJSONObject("comments")?.optInt("totalCount") ?: 0,
                    upvoteCount = n.optInt("upvoteCount"),
                    isAnswered  = n.optBoolean("isAnswered", false),
                    answeredBy  = answerObj?.optJSONObject("author")?.let {
                        com.gitmob.android.api.GHOwner(it.optString("login"), it.optString("avatarUrl"))
                    },
                    labels = labelsList,
                )
            }
            Pair(list, endCursor)
        } catch (e: Exception) {
            LogManager.w(TAG, "getDiscussions: ${e.message}")
            Pair(emptyList(), null)
        }
    }

    /**
     * 获取单个讨论详情
     */
    suspend fun getDiscussionDetail(
        token: String, owner: String, repo: String, number: Int
    ): Pair<com.gitmob.android.api.GHDiscussion?, List<com.gitmob.android.api.GHDiscussionComment>> = withContext(Dispatchers.IO) {
        try {
            val query = """
                query {
                  repository(owner: "$owner", name: "$repo") {
                    discussion(number: $number) {
                      id number title body createdAt updatedAt lastEditedAt url
                      upvoteCount isAnswered
                      author { login avatarUrl }
                      category { id name emojiHTML description isAnswerable }
                      comments(first: 100) {
                        totalCount
                        nodes {
                          id body createdAt updatedAt lastEditedAt url
                          author { login avatarUrl }
                          viewerCanUpdate
                          viewerCanDelete
                        }
                      }
                      answer { author { login } }
                      viewerSubscription
                      viewerCanUpdate
                      viewerCanDelete
                    }
                  }
                }
            """.trimIndent()
            val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext Pair(null, emptyList())
            val n = json.optJSONObject("data")?.optJSONObject("repository")
                ?.optJSONObject("discussion") ?: return@withContext Pair(null, emptyList())
            val authorObj = n.optJSONObject("author")
            val catObj    = n.optJSONObject("category")
            val answerObj = n.optJSONObject("answer")
            
            val comments = n.optJSONObject("comments")?.optJSONArray("nodes")
            val commentList = if (comments != null) {
                (0 until comments.length()).map { i ->
                    val c = comments.getJSONObject(i)
                    val cAuthorObj = c.optJSONObject("author")
                    com.gitmob.android.api.GHDiscussionComment(
                        id = c.optString("id"),
                        body = c.optString("body"),
                        author = if (cAuthorObj != null) com.gitmob.android.api.GHOwner(
                            cAuthorObj.optString("login"), cAuthorObj.optString("avatarUrl")) else null,
                        createdAt = c.optString("createdAt"),
                        updatedAt = c.optString("updatedAt"),
                        lastEditedAt = if (c.isNull("lastEditedAt")) null else c.optString("lastEditedAt"),
                        url = c.optString("url"),
                        viewerCanUpdate = c.optBoolean("viewerCanUpdate"),
                        viewerCanDelete = c.optBoolean("viewerCanDelete"),
                        nodeId = c.optString("id"),
                    )
                }
            } else emptyList()
            
            val discussion = com.gitmob.android.api.GHDiscussion(
                id        = n.optString("id"),
                number    = n.optInt("number"),
                title     = n.optString("title"),
                body      = n.optString("body").ifBlank { null },
                createdAt = n.optString("createdAt"),
                updatedAt = n.optString("updatedAt"),
                lastEditedAt = if (n.isNull("lastEditedAt")) null else n.optString("lastEditedAt"),
                url       = n.optString("url"),
                author    = if (authorObj != null) com.gitmob.android.api.GHOwner(
                    authorObj.optString("login"), authorObj.optString("avatarUrl")) else null,
                category  = if (catObj != null) com.gitmob.android.api.GHDiscussionCategory(
                    id = catObj.optString("id"), name = catObj.optString("name"),
                    emoji = catObj.optString("emojiHTML").replace(Regex("<[^>]*>"), "").trim().ifBlank { "💬" },
                    description = catObj.optString("description").ifBlank { null },
                    isAnswerable = catObj.optBoolean("isAnswerable", false),
                ) else null,
                comments    = n.optJSONObject("comments")?.optInt("totalCount") ?: 0,
                upvoteCount = n.optInt("upvoteCount"),
                isAnswered  = n.optBoolean("isAnswered", false),
                answeredBy  = answerObj?.optJSONObject("author")?.let {
                    com.gitmob.android.api.GHOwner(it.optString("login"), it.optString("avatarUrl"))
                },
                viewerSubscription = n.optString("viewerSubscription"),
                viewerCanUpdate = n.optBoolean("viewerCanUpdate"),
                viewerCanDelete = n.optBoolean("viewerCanDelete"),
            )
            Pair(discussion, commentList)
        } catch (e: Exception) {
            LogManager.w(TAG, "getDiscussionDetail: ${e.message}")
            Pair(null, emptyList())
        }
    }

    /**
     * 获取单个Issue详情
     */
    suspend fun getIssueDetail(
        token: String, owner: String, repo: String, number: Int
    ): Pair<com.gitmob.android.api.GHIssue?, List<com.gitmob.android.api.GHComment>> = withContext(Dispatchers.IO) {
        try {
            val query = """
                query {
                  repository(owner: "$owner", name: "$repo") {
                    issue(number: $number) {
                      id
                      number
                      title
                      body
                      state
                      createdAt
                      updatedAt
                      lastEditedAt
                      url
                      author {
                        login
                        avatarUrl
                      }
                      labels(first: 20) {
                        nodes {
                          id
                          name
                          color
                          description
                        }
                      }
                      comments(first: 100) {
                        totalCount
                        nodes {
                          id
                          body
                          createdAt
                          updatedAt
                          lastEditedAt
                          url
                          author {
                            login
                            avatarUrl
                          }
                          authorAssociation
                        }
                      }
                      viewerSubscription
                    }
                  }
                }
            """.trimIndent()
            val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext Pair(null, emptyList())
            val n = json.optJSONObject("data")?.optJSONObject("repository")
                ?.optJSONObject("issue") ?: return@withContext Pair(null, emptyList())
            val authorObj = n.optJSONObject("author")
            
            val commentsJson = n.optJSONObject("comments")?.optJSONArray("nodes")
            val commentList = if (commentsJson != null) {
                (0 until commentsJson.length()).map { i ->
                    val c = commentsJson.getJSONObject(i)
                    val cAuthorObj = c.optJSONObject("author")
                    com.gitmob.android.api.GHComment(
                        id = c.optString("id").hashCode().toLong(),
                        body = c.optString("body"),
                        user = if (cAuthorObj != null) com.gitmob.android.api.GHOwner(
                            cAuthorObj.optString("login"), cAuthorObj.optString("avatarUrl")) else com.gitmob.android.api.GHOwner("", ""),
                        createdAt = c.optString("createdAt"),
                        updatedAt = c.optString("updatedAt"),
                        lastEditedAt = if (c.isNull("lastEditedAt")) null else c.optString("lastEditedAt"),
                        htmlUrl = c.optString("url"),
                        authorAssociation = c.optString("authorAssociation"),
                        nodeId = c.optString("id"),
                    )
                }
            } else emptyList()
            
            val labelsJson = n.optJSONObject("labels")?.optJSONArray("nodes")
            val labelList = if (labelsJson != null) {
                (0 until labelsJson.length()).map { i ->
                    val l = labelsJson.getJSONObject(i)
                    com.gitmob.android.api.GHLabel(
                        id = l.optString("id").hashCode().toLong(),
                        name = l.optString("name"),
                        color = l.optString("color"),
                        description = l.optString("description").ifBlank { null },
                    )
                }
            } else emptyList()
            
            val issue = com.gitmob.android.api.GHIssue(
                number = n.optInt("number"),
                title = n.optString("title"),
                state = n.optString("state").lowercase(),
                body = n.optString("body").ifBlank { null },
                htmlUrl = n.optString("url"),
                createdAt = n.optString("createdAt"),
                updatedAt = n.optString("updatedAt"),
                lastEditedAt = if (n.isNull("lastEditedAt")) null else n.optString("lastEditedAt"),
                user = if (authorObj != null) com.gitmob.android.api.GHOwner(
                    authorObj.optString("login"), authorObj.optString("avatarUrl")) else com.gitmob.android.api.GHOwner("", ""),
                labels = labelList,
                comments = n.optJSONObject("comments")?.optInt("totalCount"),
                nodeId = n.optString("id"),
                viewerSubscription = n.optString("viewerSubscription"),
            )
            Pair(issue, commentList)
        } catch (e: Exception) {
            LogManager.w(TAG, "getIssueDetail: ${e.message}")
            Pair(null, emptyList())
        }
    }

    /**
     * 获取单个PR详情
     */
    suspend fun getPRDetail(
        token: String, owner: String, repo: String, number: Int
    ): Triple<com.gitmob.android.api.GHPullRequest?, List<com.gitmob.android.api.GHComment>, List<com.gitmob.android.api.GHReview>> = withContext(Dispatchers.IO) {
        try {
            val query = """
                query {
                  repository(owner: "$owner", name: "$repo") {
                    pullRequest(number: $number) {
                      id
                      number
                      title
                      body
                      state
                      createdAt
                      updatedAt
                      lastEditedAt
                      url
                      isDraft
                      merged
                      mergedAt
                      mergeable
                      author {
                        login
                        avatarUrl
                      }
                      baseRefName
                      headRefName
                      labels(first: 20) {
                        nodes {
                          id
                          name
                          color
                          description
                        }
                      }
                      comments(first: 100) {
                        totalCount
                        nodes {
                          id
                          body
                          createdAt
                          updatedAt
                          lastEditedAt
                          url
                          author {
                            login
                            avatarUrl
                          }
                          authorAssociation
                        }
                      }
                      reviews(first: 100) {
                        nodes {
                          id
                          state
                          body
                          submittedAt
                          url
                          author {
                            login
                            avatarUrl
                          }
                        }
                      }
                      viewerSubscription
                    }
                  }
                }
            """.trimIndent()
            val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext Triple(null, emptyList(), emptyList())
            val n = json.optJSONObject("data")?.optJSONObject("repository")
                ?.optJSONObject("pullRequest") ?: return@withContext Triple(null, emptyList(), emptyList())
            val authorObj = n.optJSONObject("author")
            
            val commentsJson = n.optJSONObject("comments")?.optJSONArray("nodes")
            val commentList = if (commentsJson != null) {
                (0 until commentsJson.length()).map { i ->
                    val c = commentsJson.getJSONObject(i)
                    val cAuthorObj = c.optJSONObject("author")
                    com.gitmob.android.api.GHComment(
                        id = c.optString("id").hashCode().toLong(),
                        body = c.optString("body"),
                        user = if (cAuthorObj != null) com.gitmob.android.api.GHOwner(
                            cAuthorObj.optString("login"), cAuthorObj.optString("avatarUrl")) else com.gitmob.android.api.GHOwner("", ""),
                        createdAt = c.optString("createdAt"),
                        updatedAt = c.optString("updatedAt"),
                        lastEditedAt = if (c.isNull("lastEditedAt")) null else c.optString("lastEditedAt"),
                        htmlUrl = c.optString("url"),
                        authorAssociation = c.optString("authorAssociation"),
                        nodeId = c.optString("id"),
                    )
                }
            } else emptyList()
            
            val reviewsJson = n.optJSONObject("reviews")?.optJSONArray("nodes")
            val reviewList = if (reviewsJson != null) {
                (0 until reviewsJson.length()).map { i ->
                    val r = reviewsJson.getJSONObject(i)
                    val rAuthorObj = r.optJSONObject("author")
                    com.gitmob.android.api.GHReview(
                        id = r.optString("id").hashCode().toLong(),
                        user = if (rAuthorObj != null) com.gitmob.android.api.GHOwner(
                            rAuthorObj.optString("login"), rAuthorObj.optString("avatarUrl")) else com.gitmob.android.api.GHOwner("", ""),
                        body = r.optString("body").ifBlank { null },
                        state = r.optString("state"),
                        submittedAt = r.optString("submittedAt"),
                        htmlUrl = r.optString("url"),
                    )
                }
            } else emptyList()
            
            val labelsJson = n.optJSONObject("labels")?.optJSONArray("nodes")
            val labelList = if (labelsJson != null) {
                (0 until labelsJson.length()).map { i ->
                    val l = labelsJson.getJSONObject(i)
                    com.gitmob.android.api.GHLabel(
                        id = l.optString("id").hashCode().toLong(),
                        name = l.optString("name"),
                        color = l.optString("color"),
                        description = l.optString("description").ifBlank { null },
                    )
                }
            } else emptyList()
            
            val pr = com.gitmob.android.api.GHPullRequest(
                number = n.optInt("number"),
                title = n.optString("title"),
                body = n.optString("body").ifBlank { null },
                state = n.optString("state").lowercase(),
                user = if (authorObj != null) com.gitmob.android.api.GHOwner(
                    authorObj.optString("login"), authorObj.optString("avatarUrl")) else com.gitmob.android.api.GHOwner("", ""),
                createdAt = n.optString("createdAt"),
                updatedAt = n.optString("updatedAt"),
                lastEditedAt = if (n.isNull("lastEditedAt")) null else n.optString("lastEditedAt"),
                htmlUrl = n.optString("url"),
                draft = n.optBoolean("isDraft", false),
                merged = n.optBoolean("merged", false),
                mergedAt = if (n.isNull("mergedAt")) null else n.optString("mergedAt"),
                mergeableState = when (n.optString("mergeable")) {
                    "MERGEABLE" -> "clean"
                    "CONFLICTING" -> "dirty"
                    else -> null
                },
                head = com.gitmob.android.api.GHPRBranch(
                    label = "",
                    ref = n.optString("headRefName"),
                    sha = "",
                    repo = null,
                ),
                base = com.gitmob.android.api.GHPRBranch(
                    label = "",
                    ref = n.optString("baseRefName"),
                    sha = "",
                    repo = null,
                ),
                labels = labelList,
                comments = n.optJSONObject("comments")?.optInt("totalCount") ?: 0,
                nodeId = n.optString("id"),
                viewerSubscription = n.optString("viewerSubscription"),
            )
            Triple(pr, commentList, reviewList)
        } catch (e: Exception) {
            LogManager.w(TAG, "getPRDetail: ${e.message}")
            Triple(null, emptyList(), emptyList())
        }
    }

    /**
     * 更新讨论的订阅状态
     */
    suspend fun updateDiscussionSubscription(
        token: String, discussionId: String, subscribed: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val state = if (subscribed) "SUBSCRIBED" else "UNSUBSCRIBED"
            val query = """
                mutation {
                  updateSubscription(input: {
                    subscribableId: "$discussionId",
                    state: $state
                  }) {
                    clientMutationId
                  }
                }
            """.trimIndent()
            post(token, JSONObject().put("query", query).toString()) != null
        } catch (e: Exception) {
            LogManager.w(TAG, "updateDiscussionSubscription: ${e.message}")
            false
        }
    }

    /**
     * 更新讨论（标题、内容）
     */
    suspend fun updateDiscussion(
        token: String, discussionId: String, title: String? = null, body: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val titleArg = if (title != null) """, title: "$title"""" else ""
            val bodyArg = if (body != null) """, body: "$body"""" else ""
            val query = """
                mutation {
                  updateDiscussion(input: {
                    discussionId: "$discussionId"
                    $titleArg $bodyArg
                  }) {
                    clientMutationId
                  }
                }
            """.trimIndent()
            post(token, JSONObject().put("query", query).toString()) != null
        } catch (e: Exception) {
            LogManager.w(TAG, "updateDiscussion: ${e.message}")
            false
        }
    }

    /**
     * 删除讨论
     */
    suspend fun deleteDiscussion(
        token: String, discussionId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val query = """
                mutation {
                  deleteDiscussion(input: { id: "$discussionId" }) {
                    clientMutationId
                  }
                }
            """.trimIndent()
            post(token, JSONObject().put("query", query).toString()) != null
        } catch (e: Exception) {
            LogManager.w(TAG, "deleteDiscussion: ${e.message}")
            false
        }
    }



    /**
     * 转义字符串用于 GraphQL 查询
     */
    private fun escapeGraphQLString(value: String): String =
        value.replace("\"", "\\\"").replace("\n", "\\n")

    /**
     * 从 GraphQL 响应中提取嵌套数据
     */
    private inline fun <T> extractFromResponse(
        json: JSONObject?,
        vararg path: String,
        extractor: (JSONObject) -> T
    ): T? {
        var current: JSONObject? = json
        for (key in path) {
            current = current?.optJSONObject(key) ?: return null
        }
        return current?.let { extractor(it) }
    }

    /**
     * 解析 GHDiscussionComment 从 JSON
     */
    private fun parseDiscussionComment(c: JSONObject): com.gitmob.android.api.GHDiscussionComment {
        val cAuthorObj = c.optJSONObject("author")
        return com.gitmob.android.api.GHDiscussionComment(
            id = c.optString("id"),
            body = c.optString("body"),
            author = if (cAuthorObj != null) com.gitmob.android.api.GHOwner(
                cAuthorObj.optString("login"), cAuthorObj.optString("avatarUrl")) else null,
            createdAt = c.optString("createdAt"),
            updatedAt = c.optString("updatedAt"),
            lastEditedAt = if (c.isNull("lastEditedAt")) null else c.optString("lastEditedAt"),
            url = c.optString("url"),
            viewerCanUpdate = c.optBoolean("viewerCanUpdate"),
            viewerCanDelete = c.optBoolean("viewerCanDelete"),
            nodeId = c.optString("id"),
        )
    }

    /**
     * 执行 GraphQL mutation 并返回响应中的数据
     */
    private suspend fun executeMutation(
        token: String,
        query: String,
    ): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val json = post(token, JSONObject().put("query", query).toString())
            json?.optJSONObject("data")
        } catch (e: Exception) {
            LogManager.w(TAG, "executeMutation: ${e.message}")
            null
        }
    }

    /**
     * 添加讨论评论
     */
    suspend fun addDiscussionComment(
        token: String,
        discussionId: String,
        body: String,
        replyToId: String? = null,
    ): com.gitmob.android.api.GHDiscussionComment? = withContext(Dispatchers.IO) {
        try {
            val replyToArg = if (replyToId != null) """, replyToId: "$replyToId"""" else ""
            val escapedBody = escapeGraphQLString(body)
            val query = """
                mutation {
                  addDiscussionComment(input: {
                    discussionId: "$discussionId"
                    body: "$escapedBody"
                    $replyToArg
                  }) {
                    comment {
                      id
                      body
                      author { login avatarUrl }
                      createdAt
                      updatedAt
                      url
                      viewerCanUpdate
                      viewerCanDelete
                    }
                  }
                }
            """.trimIndent()
            val data = executeMutation(token, query)
            extractFromResponse(data, "addDiscussionComment", "comment") { parseDiscussionComment(it) }
        } catch (e: Exception) {
            LogManager.w(TAG, "addDiscussionComment: ${e.message}")
            null
        }
    }

    /**
     * 更新讨论评论
     */
    suspend fun updateDiscussionComment(
        token: String,
        commentId: String,
        body: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val escapedBody = escapeGraphQLString(body)
            val query = """
                mutation {
                  updateDiscussionComment(input: {
                    commentId: "$commentId"
                    body: "$escapedBody"
                  }) {
                    comment {
                      id
                    }
                  }
                }
            """.trimIndent()
            executeMutation(token, query) != null
        } catch (e: Exception) {
            LogManager.w(TAG, "updateDiscussionComment: ${e.message}")
            false
        }
    }

    /**
     * 删除讨论评论
     */
    suspend fun deleteDiscussionComment(
        token: String,
        commentId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val query = """
                mutation {
                  deleteDiscussionComment(input: { id: "$commentId" }) {
                    comment {
                      id
                    }
                  }
                }
            """.trimIndent()
            executeMutation(token, query) != null
        } catch (e: Exception) {
            LogManager.w(TAG, "deleteDiscussionComment: ${e.message}")
            false
        }
    }

    /**
     * 获取 Issue 订阅状态
     */
    suspend fun getIssueSubscription(
        token: String, owner: String, repo: String, number: Int
    ): String? = withContext(Dispatchers.IO) {
        try {
            val query = """
                query {
                  repository(owner: "$owner", name: "$repo") {
                    issue(number: $number) {
                      viewerSubscription
                    }
                  }
                }
            """.trimIndent()
            val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext null
            json.optJSONObject("data")?.optJSONObject("repository")
                ?.optJSONObject("issue")?.optString("viewerSubscription")
        } catch (e: Exception) {
            LogManager.w(TAG, "getIssueSubscription: ${e.message}")
            null
        }
    }

    /**
     * 获取 PR 订阅状态
     */
    suspend fun getPullRequestSubscription(
        token: String, owner: String, repo: String, number: Int
    ): String? = withContext(Dispatchers.IO) {
        try {
            val query = """
                query {
                  repository(owner: "$owner", name: "$repo") {
                    pullRequest(number: $number) {
                      viewerSubscription
                    }
                  }
                }
            """.trimIndent()
            val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext null
            json.optJSONObject("data")?.optJSONObject("repository")
                ?.optJSONObject("pullRequest")?.optString("viewerSubscription")
        } catch (e: Exception) {
            LogManager.w(TAG, "getPullRequestSubscription: ${e.message}")
            null
        }
    }

    /**
     * 更新 Issue 或 PR 订阅状态
     */
    suspend fun updateSubscribableSubscription(
        token: String, subscribableId: String, subscribed: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val state = if (subscribed) "SUBSCRIBED" else "UNSUBSCRIBED"
            val query = """
                mutation {
                  updateSubscription(input: {
                    subscribableId: "$subscribableId",
                    state: $state
                  }) {
                    clientMutationId
                  }
                }
            """.trimIndent()
            post(token, JSONObject().put("query", query).toString()) != null
        } catch (e: Exception) {
            LogManager.w(TAG, "updateSubscribableSubscription: ${e.message}")
            false
        }
    }

    /**
     * 获取 PR 列表，使用 GraphQL API，确保评论数量正确
     */
    suspend fun getPullRequests(
        token: String,
        owner: String,
        repo: String,
        state: String = "open",
        first: Int = 30,
        after: String? = null
    ): Pair<List<com.gitmob.android.api.GHPullRequest>, String?> = withContext(Dispatchers.IO) {
        try {
            val states = when (state) {
                "open" -> "[OPEN]"
                "closed" -> "[CLOSED, MERGED]"
                "all" -> "[OPEN, CLOSED, MERGED]"
                else -> "[OPEN]"
            }
            val afterArg = if (after != null) """, after: "$after"""" else ""
            val query = """
                query {
                  repository(owner: "$owner", name: "$repo") {
                    pullRequests(first: $first$afterArg, states: $states, orderBy: {field: CREATED_AT, direction: DESC}) {
                      pageInfo { hasNextPage endCursor }
                      nodes {
                        id
                        number
                        title
                        body
                        state
                        createdAt
                        updatedAt
                        mergedAt
                        closedAt
                        isDraft
                        merged
                        url
                        author { login avatarUrl }
                        headRefName
                        headRepository { name owner { login } }
                        baseRefName
                        baseRepository { name owner { login } }
                        comments { totalCount }
                        reviews { totalCount }
                        labels(first: 10) {
                          nodes { id name color description }
                        }
                        reviewRequests(first: 10) {
                          nodes {
                            requestedReviewer {
                              ... on User { login avatarUrl }
                            }
                          }
                        }
                        assignees(first: 10) {
                          nodes { login avatarUrl }
                        }
                        locked
                        viewerSubscription
                      }
                    }
                  }
                }
            """.trimIndent()
            
            val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext Pair(emptyList(), null)
            val prsObj = json.optJSONObject("data")?.optJSONObject("repository")
                ?.optJSONObject("pullRequests") ?: return@withContext Pair(emptyList(), null)
            
            val pageInfo = prsObj.optJSONObject("pageInfo")
            val hasNext = pageInfo?.optBoolean("hasNextPage") ?: false
            val endCursor = if (hasNext) pageInfo.optString("endCursor").ifBlank { null } else null
            val nodes = prsObj.optJSONArray("nodes") ?: return@withContext Pair(emptyList(), endCursor)
            
            val list = mutableListOf<com.gitmob.android.api.GHPullRequest>()
            for (i in 0 until nodes.length()) {
                val n = nodes.optJSONObject(i) ?: continue
                val authorObj = n.optJSONObject("author")
                val headRepoObj = n.optJSONObject("headRepository")
                val baseRepoObj = n.optJSONObject("baseRepository")
                
                val labelsList = mutableListOf<com.gitmob.android.api.GHLabel>()
                n.optJSONObject("labels")?.optJSONArray("nodes")?.let { labelsNodes ->
                    for (j in 0 until labelsNodes.length()) {
                        val labelNode = labelsNodes.optJSONObject(j)
                        labelsList.add(
                            com.gitmob.android.api.GHLabel(
                                id = labelNode.optLong("id", 0),
                                name = labelNode.optString("name"),
                                color = labelNode.optString("color"),
                                description = labelNode.optString("description").ifBlank { null }
                            )
                        )
                    }
                }
                
                val requestedReviewersList = mutableListOf<com.gitmob.android.api.GHOwner>()
                n.optJSONObject("reviewRequests")?.optJSONArray("nodes")?.let { reviewRequestsNodes ->
                    for (j in 0 until reviewRequestsNodes.length()) {
                        val reviewRequestNode = reviewRequestsNodes.optJSONObject(j)
                        val reviewerNode = reviewRequestNode.optJSONObject("requestedReviewer")
                        if (reviewerNode != null) {
                            requestedReviewersList.add(
                                com.gitmob.android.api.GHOwner(
                                    login = reviewerNode.optString("login"),
                                    avatarUrl = reviewerNode.optString("avatarUrl")
                                )
                            )
                        }
                    }
                }
                
                val assigneesList = mutableListOf<com.gitmob.android.api.GHOwner>()
                n.optJSONObject("assignees")?.optJSONArray("nodes")?.let { assigneesNodes ->
                    for (j in 0 until assigneesNodes.length()) {
                        val assigneeNode = assigneesNodes.optJSONObject(j)
                        assigneesList.add(
                            com.gitmob.android.api.GHOwner(
                                login = assigneeNode.optString("login"),
                                avatarUrl = assigneeNode.optString("avatarUrl")
                            )
                        )
                    }
                }
                
                val pr = com.gitmob.android.api.GHPullRequest(
                    number = n.optInt("number"),
                    title = n.optString("title"),
                    state = n.optString("state").lowercase(),
                    body = n.optString("body").ifBlank { null },
                    htmlUrl = n.optString("url"),
                    createdAt = n.optString("createdAt"),
                    updatedAt = n.optString("updatedAt").ifBlank { null },
                    mergedAt = n.optString("mergedAt").ifBlank { null },
                    closedAt = n.optString("closedAt").ifBlank { null },
                    user = com.gitmob.android.api.GHOwner(
                        login = authorObj?.optString("login") ?: "",
                        avatarUrl = authorObj?.optString("avatarUrl")
                    ),
                    head = com.gitmob.android.api.GHPRBranch(
                        label = "${headRepoObj?.optJSONObject("owner")?.optString("login") ?: ""}:${n.optString("headRefName")}",
                        ref = n.optString("headRefName"),
                        sha = "",
                        repo = headRepoObj?.let {
                            com.gitmob.android.api.GHRepo(
                                id = 0,
                                name = it.optString("name"),
                                fullName = "${it.optJSONObject("owner")?.optString("login") ?: ""}/${it.optString("name")}",
                                description = null,
                                homepage = null,
                                private = false,
                                htmlUrl = "",
                                sshUrl = "",
                                cloneUrl = "",
                                defaultBranch = "",
                                updatedAt = null,
                                pushedAt = null,
                                language = null,
                                owner = com.gitmob.android.api.GHOwner(
                                    login = it.optJSONObject("owner")?.optString("login") ?: "",
                                    avatarUrl = null
                                )
                            )
                        }
                    ),
                    base = com.gitmob.android.api.GHPRBranch(
                        label = "${baseRepoObj?.optJSONObject("owner")?.optString("login") ?: ""}:${n.optString("baseRefName")}",
                        ref = n.optString("baseRefName"),
                        sha = "",
                        repo = baseRepoObj?.let {
                            com.gitmob.android.api.GHRepo(
                                id = 0,
                                name = it.optString("name"),
                                fullName = "${it.optJSONObject("owner")?.optString("login") ?: ""}/${it.optString("name")}",
                                description = null,
                                homepage = null,
                                private = false,
                                htmlUrl = "",
                                sshUrl = "",
                                cloneUrl = "",
                                defaultBranch = "",
                                updatedAt = null,
                                pushedAt = null,
                                language = null,
                                owner = com.gitmob.android.api.GHOwner(
                                    login = it.optJSONObject("owner")?.optString("login") ?: "",
                                    avatarUrl = null
                                )
                            )
                        }
                    ),
                    draft = n.optBoolean("isDraft", false),
                    merged = n.optBoolean("merged", false),
                    comments = n.optJSONObject("comments")?.optInt("totalCount") ?: 0,
                    reviewComments = n.optJSONObject("reviews")?.optInt("totalCount") ?: 0,
                    labels = labelsList,
                    requestedReviewers = requestedReviewersList,
                    assignees = assigneesList,
                    nodeId = n.optString("id"),
                    locked = n.optBoolean("locked", false),
                    viewerSubscription = n.optString("viewerSubscription").ifBlank { null }
                )
                list.add(pr)
            }
            Pair(list, endCursor)
        } catch (e: Exception) {
            LogManager.w(TAG, "getPullRequests (GraphQL): ${e.message}")
            Pair(emptyList(), null)
        }
    }

    /**
     * 使用GraphQL API获取Issues列表
     */
    suspend fun getIssues(
        token: String,
        owner: String,
        repo: String,
        state: String = "open",
        first: Int = 30,
        after: String? = null,
        labels: String? = null,
        creator: String? = null
    ): Pair<List<com.gitmob.android.api.GHIssue>, String?> = withContext(Dispatchers.IO) {
        try {
            val states = when (state) {
                "open" -> "[OPEN]"
                "closed" -> "[CLOSED]"
                "all" -> "[OPEN, CLOSED]"
                else -> "[OPEN]"
            }
            val afterArg = if (after != null) """, after: "$after"""" else ""
            val labelFilter = if (labels != null && labels.isNotEmpty()) """, labels: [$labels]""" else ""
            val creatorFilter = if (creator != null && creator.isNotEmpty()) """, creator: "$creator"""" else ""
            
            val query = """
                query {
                  repository(owner: "$owner", name: "$repo") {
                    issues(first: $first$afterArg, states: $states$labelFilter$creatorFilter, orderBy: {field: CREATED_AT, direction: DESC}) {
                      pageInfo { hasNextPage endCursor }
                      nodes {
                        id
                        number
                        title
                        body
                        state
                        createdAt
                        updatedAt
                        closedAt
                        url
                        author { login avatarUrl }
                        comments { totalCount }
                        labels(first: 10) {
                          nodes { id name color description }
                        }
                        assignees(first: 10) {
                          nodes { login avatarUrl }
                        }
                        locked
                        viewerSubscription
                      }
                    }
                  }
                }
            """.trimIndent()
            
            val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext Pair(emptyList(), null)
            val issuesObj = json.optJSONObject("data")?.optJSONObject("repository")
                ?.optJSONObject("issues") ?: return@withContext Pair(emptyList(), null)
            
            val pageInfo = issuesObj.optJSONObject("pageInfo")
            val hasNext = pageInfo?.optBoolean("hasNextPage") ?: false
            val endCursor = if (hasNext) pageInfo.optString("endCursor").ifBlank { null } else null
            val nodes = issuesObj.optJSONArray("nodes") ?: return@withContext Pair(emptyList(), endCursor)
            
            val list = mutableListOf<com.gitmob.android.api.GHIssue>()
            for (i in 0 until nodes.length()) {
                val n = nodes.optJSONObject(i) ?: continue
                val authorObj = n.optJSONObject("author")
                
                val labelsList = mutableListOf<com.gitmob.android.api.GHLabel>()
                n.optJSONObject("labels")?.optJSONArray("nodes")?.let { labelsNodes ->
                    for (j in 0 until labelsNodes.length()) {
                        val labelNode = labelsNodes.optJSONObject(j)
                        labelsList.add(
                            com.gitmob.android.api.GHLabel(
                                id = labelNode.optLong("id", 0),
                                name = labelNode.optString("name"),
                                color = labelNode.optString("color"),
                                description = labelNode.optString("description").ifBlank { null }
                            )
                        )
                    }
                }
                
                val assigneesList = mutableListOf<com.gitmob.android.api.GHOwner>()
                n.optJSONObject("assignees")?.optJSONArray("nodes")?.let { assigneesNodes ->
                    for (j in 0 until assigneesNodes.length()) {
                        val assigneeNode = assigneesNodes.optJSONObject(j)
                        assigneesList.add(
                            com.gitmob.android.api.GHOwner(
                                login = assigneeNode.optString("login"),
                                avatarUrl = assigneeNode.optString("avatarUrl")
                            )
                        )
                    }
                }
                
                val issue = com.gitmob.android.api.GHIssue(
                    number = n.optInt("number"),
                    title = n.optString("title"),
                    state = n.optString("state").lowercase(),
                    body = n.optString("body").ifBlank { null },
                    htmlUrl = n.optString("url"),
                    createdAt = n.optString("createdAt"),
                    updatedAt = n.optString("updatedAt").ifBlank { null },
                    closedAt = n.optString("closedAt").ifBlank { null },
                    user = com.gitmob.android.api.GHOwner(
                        login = authorObj?.optString("login") ?: "",
                        avatarUrl = authorObj?.optString("avatarUrl")
                    ),
                    comments = n.optJSONObject("comments")?.optInt("totalCount") ?: 0,
                    labels = labelsList,
                    assignees = assigneesList,
                    nodeId = n.optString("id"),
                    locked = n.optBoolean("locked", false),
                    viewerSubscription = n.optString("viewerSubscription").ifBlank { null }
                )
                list.add(issue)
            }
            Pair(list, endCursor)
        } catch (e: Exception) {
            LogManager.w(TAG, "getIssues (GraphQL): ${e.message}")
            Pair(emptyList(), null)
        }
    }

    // ── 编辑历史查询 ─────────────────────────────────────────────────────────

    /**
     * 获取 Issue 主体的编辑历史
     */
    suspend fun getIssueBodyEditHistory(
        token: String,
        owner: String,
        repo: String,
        number: Int,
        first: Int = 20
    ): List<GHUserContentEdit> = withContext(Dispatchers.IO) {
        try {
            val query = """
                query {
                    repository(owner: "$owner", name: "$repo") {
                        issue(number: $number) {
                            userContentEdits(first: $first) {
                                nodes {
                                    id
                                    createdAt
                                    diff
                                    editor { login avatarUrl }
                                    deletedBy { login avatarUrl }
                                }
                            }
                        }
                    }
                }
            """.trimIndent()
            val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext emptyList()
            val nodes = json.optJSONObject("data")?.optJSONObject("repository")
                ?.optJSONObject("issue")?.optJSONObject("userContentEdits")
                ?.optJSONArray("nodes") ?: return@withContext emptyList()
            (0 until nodes.length()).map { parseUserContentEdit(nodes.getJSONObject(it)) }
        } catch (e: Exception) {
            LogManager.w(TAG, "getIssueBodyEditHistory: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取 Issue 评论的编辑历史
     */
    suspend fun getIssueCommentEditHistory(
        token: String,
        commentId: String,
        first: Int = 20
    ): List<GHUserContentEdit> = withContext(Dispatchers.IO) {
        try {
            val query = """
                query {
                    node(id: "$commentId") {
                        ... on IssueComment {
                            userContentEdits(first: $first) {
                                nodes {
                                    id
                                    createdAt
                                    diff
                                    editor { login avatarUrl }
                                    deletedBy { login avatarUrl }
                                }
                            }
                        }
                    }
                }
            """.trimIndent()
            val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext emptyList()
            val nodes = json.optJSONObject("data")?.optJSONObject("node")
                ?.optJSONObject("userContentEdits")
                ?.optJSONArray("nodes") ?: return@withContext emptyList()
            (0 until nodes.length()).map { parseUserContentEdit(nodes.getJSONObject(it)) }
        } catch (e: Exception) {
            LogManager.w(TAG, "getIssueCommentEditHistory: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取 PR 主体的编辑历史
     */
    suspend fun getPRBodyEditHistory(
        token: String,
        owner: String,
        repo: String,
        number: Int,
        first: Int = 20
    ): List<GHUserContentEdit> = withContext(Dispatchers.IO) {
        try {
            val query = """
                query {
                    repository(owner: "$owner", name: "$repo") {
                        pullRequest(number: $number) {
                            userContentEdits(first: $first) {
                                nodes {
                                    id
                                    createdAt
                                    diff
                                    editor { login avatarUrl }
                                    deletedBy { login avatarUrl }
                                }
                            }
                        }
                    }
                }
            """.trimIndent()
            val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext emptyList()
            val nodes = json.optJSONObject("data")?.optJSONObject("repository")
                ?.optJSONObject("pullRequest")?.optJSONObject("userContentEdits")
                ?.optJSONArray("nodes") ?: return@withContext emptyList()
            (0 until nodes.length()).map { parseUserContentEdit(nodes.getJSONObject(it)) }
        } catch (e: Exception) {
            LogManager.w(TAG, "getPRBodyEditHistory: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取 PR 评论的编辑历史
     */
    suspend fun getPRCommentEditHistory(
        token: String,
        commentId: String,
        first: Int = 20
    ): List<GHUserContentEdit> = withContext(Dispatchers.IO) {
        try {
            val query = """
                query {
                    node(id: "$commentId") {
                        ... on IssueComment {
                            userContentEdits(first: $first) {
                                nodes {
                                    id
                                    createdAt
                                    diff
                                    editor { login avatarUrl }
                                    deletedBy { login avatarUrl }
                                }
                            }
                        }
                        ... on PullRequestReviewComment {
                            userContentEdits(first: $first) {
                                nodes {
                                    id
                                    createdAt
                                    diff
                                    editor { login avatarUrl }
                                    deletedBy { login avatarUrl }
                                }
                            }
                        }
                    }
                }
            """.trimIndent()
            val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext emptyList()
            val nodeObj = json.optJSONObject("data")?.optJSONObject("node") ?: return@withContext emptyList()
            val nodes = nodeObj.optJSONObject("userContentEdits")
                ?.optJSONArray("nodes") ?: return@withContext emptyList()
            (0 until nodes.length()).map { parseUserContentEdit(nodes.getJSONObject(it)) }
        } catch (e: Exception) {
            LogManager.w(TAG, "getPRCommentEditHistory: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取 Discussion 主体的编辑历史
     */
    suspend fun getDiscussionBodyEditHistory(
        token: String,
        owner: String,
        repo: String,
        number: Int,
        first: Int = 20
    ): List<GHUserContentEdit> = withContext(Dispatchers.IO) {
        try {
            val query = """
                query {
                    repository(owner: "$owner", name: "$repo") {
                        discussion(number: $number) {
                            userContentEdits(first: $first) {
                                nodes {
                                    id
                                    createdAt
                                    diff
                                    editor { login avatarUrl }
                                    deletedBy { login avatarUrl }
                                }
                            }
                        }
                    }
                }
            """.trimIndent()
            val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext emptyList()
            val nodes = json.optJSONObject("data")?.optJSONObject("repository")
                ?.optJSONObject("discussion")?.optJSONObject("userContentEdits")
                ?.optJSONArray("nodes") ?: return@withContext emptyList()
            (0 until nodes.length()).map { parseUserContentEdit(nodes.getJSONObject(it)) }
        } catch (e: Exception) {
            LogManager.w(TAG, "getDiscussionBodyEditHistory: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取 Discussion 评论的编辑历史
     */
    suspend fun getDiscussionCommentEditHistory(
        token: String,
        commentId: String,
        first: Int = 20
    ): List<GHUserContentEdit> = withContext(Dispatchers.IO) {
        try {
            LogManager.d(TAG, "getDiscussionCommentEditHistory: commentId = $commentId")
            val query = """
                query {
                    node(id: "$commentId") {
                        ... on DiscussionComment {
                            userContentEdits(first: $first) {
                                nodes {
                                    id
                                    createdAt
                                    diff
                                    editor { login avatarUrl }
                                    deletedBy { login avatarUrl }
                                }
                            }
                        }
                    }
                }
            """.trimIndent()
            val json = post(token, JSONObject().put("query", query).toString()) ?: return@withContext emptyList()
            LogManager.d(TAG, "getDiscussionCommentEditHistory response: $json")
            val nodes = json.optJSONObject("data")?.optJSONObject("node")
                ?.optJSONObject("userContentEdits")
                ?.optJSONArray("nodes") ?: return@withContext emptyList()
            (0 until nodes.length()).map { parseUserContentEdit(nodes.getJSONObject(it)) }
        } catch (e: Exception) {
            LogManager.w(TAG, "getDiscussionCommentEditHistory: ${e.message}")
            emptyList()
        }
    }

    /**
     * 解析 GHUserContentEdit 从 JSON
     */
    private fun parseUserContentEdit(json: JSONObject): GHUserContentEdit {
        val editorObj = json.optJSONObject("editor")
        val deletedByObj = json.optJSONObject("deletedBy")
        return GHUserContentEdit(
            id = json.optString("id"),
            createdAt = json.optString("createdAt"),
            diff = json.optString("diff").ifBlank { null },
            editor = editorObj?.let {
                GHOwner(
                    login = it.optString("login"),
                    avatarUrl = it.optString("avatarUrl")
                )
            },
            deletedBy = deletedByObj?.let {
                GHOwner(
                    login = it.optString("login"),
                    avatarUrl = it.optString("avatarUrl")
                )
            }
        )
    }

    /**
     * 获取指定分支的最新commit OID
     */
    suspend fun getBranchHeadOid(token: String, owner: String, repo: String, branch: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val query = """
                    query {
                      repository(owner: "$owner", name: "$repo") {
                        ref(qualifiedName: "refs/heads/$branch") {
                          target {
                            ... on Commit {
                              oid
                            }
                          }
                        }
                      }
                    }
                """.trimIndent()
                val json = post(token, JSONObject().put("query", query).toString())
                json?.optJSONObject("data")
                    ?.optJSONObject("repository")
                    ?.optJSONObject("ref")
                    ?.optJSONObject("target")
                    ?.optString("oid")
            } catch (e: Exception) {
                LogManager.w(TAG, "getBranchHeadOid 失败: ${e.message}")
                null
            }
        }

    /**
     * 使用 createCommitOnBranch mutation 重命名文件（单次 commit）
     */
    suspend fun renameFileWithGraphQL(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        oldPath: String,
        newPath: String,
        contentBase64: String,
        message: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val mutation = """
                mutation CreateCommitOnBranch(${'$'}input: CreateCommitOnBranchInput!) {
                  createCommitOnBranch(input: ${'$'}input) {
                    commit {
                      oid
                      url
                    }
                  }
                }
            """.trimIndent()

            val expectedHeadOid = getBranchHeadOid(token, owner, repo, branch)
                ?: return@withContext false

            val cleanContentBase64 = contentBase64.replace("\n", "").replace("\r", "")

            val input = JSONObject().apply {
                put("branch", JSONObject().apply {
                    put("repositoryNameWithOwner", "$owner/$repo")
                    put("branchName", branch)
                })
                put("message", JSONObject().apply {
                    put("headline", message)
                })
                put("fileChanges", JSONObject().apply {
                    put("deletions", org.json.JSONArray().apply {
                        put(JSONObject().put("path", oldPath))
                    })
                    put("additions", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("path", newPath)
                            put("contents", cleanContentBase64)
                        })
                    })
                })
                put("expectedHeadOid", expectedHeadOid)
            }

            val vars = JSONObject().put("input", input)
            val json = post(token, JSONObject().put("query", mutation).put("variables", vars).toString())
            json?.optJSONObject("data")?.has("createCommitOnBranch") == true
        } catch (e: Exception) {
            LogManager.w(TAG, "renameFileWithGraphQL 失败: ${e.message}")
            false
        }
    }

    /**
     * 使用 createCommitOnBranch mutation 删除文件（单次 commit）
     */
    suspend fun deleteFileWithGraphQL(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        path: String,
        message: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val mutation = """
                mutation CreateCommitOnBranch(${'$'}input: CreateCommitOnBranchInput!) {
                  createCommitOnBranch(input: ${'$'}input) {
                    commit {
                      oid
                      url
                    }
                  }
                }
            """.trimIndent()

            val expectedHeadOid = getBranchHeadOid(token, owner, repo, branch)
                ?: return@withContext false

            val input = JSONObject().apply {
                put("branch", JSONObject().apply {
                    put("repositoryNameWithOwner", "$owner/$repo")
                    put("branchName", branch)
                })
                put("message", JSONObject().apply {
                    put("headline", message)
                })
                put("fileChanges", JSONObject().apply {
                    put("deletions", org.json.JSONArray().apply {
                        put(JSONObject().put("path", path))
                    })
                })
                put("expectedHeadOid", expectedHeadOid)
            }

            val vars = JSONObject().put("input", input)
            val json = post(token, JSONObject().put("query", mutation).put("variables", vars).toString())
            json?.optJSONObject("data")?.has("createCommitOnBranch") == true
        } catch (e: Exception) {
            LogManager.w(TAG, "deleteFileWithGraphQL 失败: ${e.message}")
            false
        }
    }
}
