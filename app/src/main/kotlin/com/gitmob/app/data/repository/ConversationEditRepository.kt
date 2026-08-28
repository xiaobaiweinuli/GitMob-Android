package com.gitmob.app.data.repository

import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.model.ConversationEdit
import com.gitmob.app.data.model.ConversationEditPage
import com.gitmob.app.data.model.SimpleUser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationEditRepository @Inject constructor(
    private val api: GHApiClient,
) {
    suspend fun getEdits(nodeId: String, after: String? = null): ApiResult<ConversationEditPage> = safeCall {
        require(nodeId.isNotBlank()) { "Content node id must not be blank" }
        val query = """
            query ContentEdits(${"$"}id: ID!, ${"$"}after: String) {
                node(id: ${"$"}id) {
                    ... on Comment {
                        userContentEdits(first: ${PageSize.CONTENT_EDITS}, after: ${"$"}after) {
                            totalCount
                            nodes {
                                id
                                editedAt
                                editor { login avatarUrl }
                                diff
                                deletedAt
                                deletedBy { login avatarUrl }
                            }
                            pageInfo { hasNextPage endCursor }
                        }
                    }
                }
            }
        """.trimIndent()
        val variables = buildMap {
            put("id", JsonPrimitive(nodeId))
            after?.let { put("after", JsonPrimitive(it)) }
        }
        val connection = api.graphQL<ContentEditsQueryData>(query, variables).node?.userContentEdits
        ConversationEditPage(
            items = connection?.nodes.orEmpty().map { it.toDomain() },
            hasNextPage = connection?.pageInfo?.hasNextPage ?: false,
            endCursor = connection?.pageInfo?.endCursor,
            totalCount = connection?.totalCount ?: 0,
        )
    }
}

private fun ContentEditNode.toDomain() = ConversationEdit(
    id = id,
    editedAt = editedAt,
    editor = editor?.toDomain(),
    diff = diff,
    deletedAt = deletedAt,
    deletedBy = deletedBy?.toDomain(),
)

private fun EditActorNode.toDomain() = SimpleUser(
    login = login,
    name = null,
    avatarUrl = avatarUrl,
    bio = null,
)

@Serializable
private data class ContentEditsQueryData(
    val node: ContentNode? = null,
)

@Serializable
private data class ContentNode(
    val userContentEdits: ContentEditConnection? = null,
)

@Serializable
private data class ContentEditConnection(
    val totalCount: Int = 0,
    val nodes: List<ContentEditNode> = emptyList(),
    val pageInfo: ContentEditPageInfo = ContentEditPageInfo(),
)

@Serializable
private data class ContentEditPageInfo(
    val hasNextPage: Boolean = false,
    val endCursor: String? = null,
)

@Serializable
private data class ContentEditNode(
    val id: String,
    val editedAt: String = "",
    val editor: EditActorNode? = null,
    val diff: String? = null,
    val deletedAt: String? = null,
    val deletedBy: EditActorNode? = null,
)

@Serializable
private data class EditActorNode(
    val login: String = "",
    val avatarUrl: String? = null,
)
