package com.gitmob.android.ui.repos

import com.gitmob.android.api.GHRepo
import org.json.JSONObject

// ─── UserList（GitHub 星标分类列表）────────────────────────────────────────────
data class UserList(
    val id: String,          // UL_xxxx
    val name: String,
    val description: String,
    val isPrivate: Boolean,
    val itemCount: Int,
)

// ─── 星标仓库（含所属列表 ID）──────────────────────────────────────────────────
data class StarredRepo(
    val nodeId: String,          // GraphQL node ID（R_xxx）
    val databaseId: Long,
    val nameWithOwner: String,
    val name: String,
    val description: String?,
    val homepage: String? = null,
    val topics: List<String> = emptyList(),
    val isPrivate: Boolean,
    val url: String,
    val sshUrl: String,
    val defaultBranch: String,
    val stars: Int,
    val forks: Int,
    val language: String?,
    val ownerLogin: String,
    val ownerAvatarUrl: String?,
    val pushedAt: String?,
    val createdAt: String? = null,
    val fork: Boolean = false,
    val archived: Boolean = false,
    val isTemplate: Boolean = false,
    val mirrorUrl: String? = null,
    val listIds: List<String> = emptyList(),   // 所属 UserList ID 列表
    val openIssues: Int = 0,
) {
    /** 转为 GHRepo（供现有 RepoCardContent 展示） */
    fun toGHRepo(): GHRepo {
        val parts = nameWithOwner.split("/")
        val owner = parts.getOrElse(0) { ownerLogin }
        val repo  = parts.getOrElse(1) { name }
        return GHRepo(
            id            = databaseId,
            nodeId        = nodeId,
            name          = repo,
            fullName      = nameWithOwner,
            description   = description,
            homepage      = homepage,
            private       = isPrivate,
            htmlUrl       = url,
            sshUrl        = sshUrl,
            cloneUrl      = "$url.git",
            defaultBranch = defaultBranch,
            stars         = stars,
            forks         = forks,
            openIssues    = openIssues,
            createdAt     = createdAt,
            updatedAt     = pushedAt,
            pushedAt      = pushedAt,
            language      = language,
            owner         = com.gitmob.android.api.GHOwner(login = owner, avatarUrl = ownerAvatarUrl ?: ""),
            fork          = fork,
            archived      = archived,
            isTemplate    = isTemplate,
            mirrorUrl     = mirrorUrl,
            topics        = topics,
        )
    }
}

// ─── 解析工具 ──────────────────────────────────────────────────────────────────
fun JSONObject.toUserList() = UserList(
    id          = optString("id"),
    name        = optString("name"),
    description = optString("description").takeIf { it.isNotBlank() && it != "null" } ?: "",
    isPrivate   = optBoolean("isPrivate"),
    itemCount   = optJSONObject("items")?.optInt("totalCount") ?: 0,
)

fun JSONObject.toStarredRepo(): StarredRepo? {
    val nodeId = optString("id").takeIf { it.isNotBlank() } ?: return null
    val listIds = optJSONObject("lists")?.optJSONArray("nodes")
        ?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).optString("id") } }
        ?: emptyList()
    
    // 解析 topics
    val topics = mutableListOf<String>()
    optJSONObject("repositoryTopics")?.let { rtNode ->
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
    
    return StarredRepo(
        nodeId         = nodeId,
        databaseId     = optLong("databaseId"),
        nameWithOwner  = optString("nameWithOwner"),
        name           = optString("name"),
        description    = optString("description").takeIf { it.isNotBlank() },
        homepage       = optString("homepageUrl").takeIf { it.isNotBlank() },
        topics         = topics,
        isPrivate      = optBoolean("isPrivate"),
        url            = optString("url"),
        sshUrl         = optString("sshUrl"),
        defaultBranch  = optJSONObject("defaultBranchRef")?.optString("name") ?: "main",
        stars          = optInt("stargazerCount"),
        forks          = optInt("forkCount"),
        language       = optJSONObject("primaryLanguage")?.optString("name"),
        ownerLogin     = optJSONObject("owner")?.optString("login") ?: "",
        ownerAvatarUrl = optJSONObject("owner")?.optString("avatarUrl"),
        pushedAt       = optString("pushedAt").takeIf { it.isNotBlank() },
        createdAt      = optString("createdAt").takeIf { it.isNotBlank() },
        fork           = optBoolean("isFork"),
        archived       = optBoolean("isArchived"),
        isTemplate     = optBoolean("isTemplate"),
        mirrorUrl      = optString("mirrorUrl").takeIf { it.isNotBlank() },
        listIds        = listIds,
        openIssues     = optJSONObject("openIssues")?.optInt("totalCount") ?: 0,
    )
}
