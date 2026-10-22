package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.executeAsApiResult
import com.gitmob.app.data.model.ConversationEdit
import com.gitmob.app.data.model.ConversationEditPage
import com.gitmob.app.data.model.SimpleUser
import com.gitmob.app.graphql.ContentEditsQuery
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationEditRepository @Inject constructor(
    private val apolloClient: ApolloClient,
) {
    suspend fun getEdits(nodeId: String, after: String? = null): ApiResult<ConversationEditPage> {
        require(nodeId.isNotBlank()) { "Content node id must not be blank" }
        return when (val result = apolloClient.query(ContentEditsQuery(
            id = nodeId,
            after = Optional.presentIfNotNull(after),
            first = PageSize.CONTENT_EDITS,
        )).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val connection = result.data.node?.onComment?.userContentEdits
                ConversationEditPage(
                    items = connection?.nodes.orEmpty().filterNotNull().map { it.toDomain() },
                    hasNextPage = connection?.pageInfo?.hasNextPage ?: false,
                    endCursor = connection?.pageInfo?.endCursor,
                    totalCount = connection?.totalCount ?: 0,
                )
            }
        }
    }
}

private fun ContentEditsQuery.Node1.toDomain() = ConversationEdit(
    id = id,
    editedAt = editedAt,
    editor = editor?.toDomain(),
    diff = diff,
    deletedAt = deletedAt,
    deletedBy = deletedBy?.toDomain(),
)

private fun ContentEditsQuery.Editor.toDomain() = SimpleUser(
    login = login,
    name = null,
    avatarUrl = avatarUrl,
    bio = null,
)

private fun ContentEditsQuery.DeletedBy.toDomain() = SimpleUser(
    login = login,
    name = null,
    avatarUrl = avatarUrl,
    bio = null,
)
