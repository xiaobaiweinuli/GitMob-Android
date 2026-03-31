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

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

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
            name
            nameWithOwner
            description
            url
            isPrivate
            isFork
            isArchived
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

    // ── 内部 HTTP ────────────────────────────────────────────────────────────

    private fun post(token: String, jsonBody: String): JSONObject? {
        val req = Request.Builder()
            .url(ENDPOINT)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("Accept",        "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val resp = client.newCall(req).execute()
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
}
