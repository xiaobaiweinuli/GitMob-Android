package com.gitmob.app.data.repository

import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepoDiscussionRepository @Inject constructor(private val api: GHApiClient) {
    suspend fun getDiscussions(owner: String, name: String, filter: RepoDiscussionFilter, after: String? = null): ApiResult<RepoDiscussionPage> = safeCall {
        val query = """
            query RepoDiscussions(${'$'}owner: String!, ${'$'}name: String!, ${'$'}after: String, ${'$'}states: [DiscussionState!], ${'$'}orderBy: DiscussionOrder, ${'$'}categoryId: ID, ${'$'}answered: Boolean) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    id viewerPermission hasDiscussionsEnabled
                    categories: discussionCategories(first: ${PageSize.DISCUSSION_CATEGORIES}) { nodes { ${categoryFields()} } }
                    discussions(first: ${PageSize.REPO_ISSUES}, after: ${'$'}after, states: ${'$'}states, orderBy: ${'$'}orderBy, categoryId: ${'$'}categoryId, answered: ${'$'}answered) {
                        totalCount nodes { ${discussionFields(includeCommentCount = true)} }
                        pageInfo { hasNextPage endCursor }
                    }
                }
            }
        """.trimIndent()
        val repo = api.graphQL<DiscussionListData>(query, buildMap {
            put("owner", JsonPrimitive(owner)); put("name", JsonPrimitive(name))
            after?.let { put("after", JsonPrimitive(it)) }
            put("states", JsonArray(states(filter.state).map(::JsonPrimitive)))
            put("orderBy", order(filter.sort))
            filter.categoryId?.let { put("categoryId", JsonPrimitive(it)) }
            filter.answered?.let { put("answered", JsonPrimitive(it)) }
        }).repository ?: error("Repository not found")
        val permission = parsePermission(repo.viewerPermission)
        RepoDiscussionPage(repo.id, permission, permission.toCapabilities(), repo.hasDiscussionsEnabled, repo.categories.nodes.map(::toCategory), repo.discussions.totalCount, repo.discussions.nodes.map(::toDiscussion), repo.discussions.pageInfo.hasNextPage, repo.discussions.pageInfo.endCursor)
    }

    suspend fun getDiscussion(owner: String, name: String, number: Int, after: String? = null): ApiResult<RepoDiscussionDetail> = safeCall {
        val query = """
            query RepoDiscussionDetail(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}after: String) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    id viewerPermission categories: discussionCategories(first: ${PageSize.DISCUSSION_CATEGORIES}) { nodes { ${categoryFields()} } }
                    discussion(number: ${'$'}number) {
                        ${discussionFields()}
                        comments(first: ${PageSize.ISSUE_COMMENTS}, after: ${'$'}after) { totalCount nodes { ${commentFields()} } pageInfo { hasNextPage endCursor } }
                    }
                }
            }
        """.trimIndent()
        val repo = api.graphQL<DiscussionDetailData>(query, buildMap { put("owner", JsonPrimitive(owner)); put("name", JsonPrimitive(name)); put("number", JsonPrimitive(number)); after?.let { put("after", JsonPrimitive(it)) } }).repository ?: error("Repository not found")
        val permission = parsePermission(repo.viewerPermission)
        val discussion = repo.discussion ?: error("Discussion not found")
        RepoDiscussionDetail(repo.id, permission, permission.toCapabilities(), repo.categories.nodes.map(::toCategory), toDiscussion(discussion), discussion.comments?.nodes.orEmpty().map(::toComment), discussion.comments?.pageInfo?.hasNextPage ?: false, discussion.comments?.pageInfo?.endCursor)
    }

    suspend fun createDiscussion(repositoryId: String, title: String, body: String, categoryId: String): ApiResult<RepoDiscussion> = mutate("createDiscussion", "CreateDiscussionInput", JsonObject(mapOf("repositoryId" to JsonPrimitive(repositoryId), "title" to JsonPrimitive(title), "body" to JsonPrimitive(body), "categoryId" to JsonPrimitive(categoryId)))) { it.createDiscussion?.discussion }
    suspend fun updateDiscussion(id: String, title: String, body: String, categoryId: String?): ApiResult<RepoDiscussion> = mutate("updateDiscussion", "UpdateDiscussionInput", JsonObject(buildMap { put("discussionId", JsonPrimitive(id)); put("title", JsonPrimitive(title)); put("body", JsonPrimitive(body)); categoryId?.let { put("categoryId", JsonPrimitive(it)) } })) { it.updateDiscussion?.discussion }
    suspend fun addLabelsToDiscussion(discussionId: String, labelIds: List<String>): ApiResult<Unit> = safeCall {
        if (labelIds.isEmpty()) return@safeCall Unit
        api.graphQL<ClientMutationData>("mutation AddDiscussionLabels(\$input: AddLabelsToLabelableInput!) { addLabelsToLabelable(input: \$input) { clientMutationId } }", mapOf("input" to JsonObject(mapOf("labelableId" to JsonPrimitive(discussionId), "labelIds" to JsonArray(labelIds.map(::JsonPrimitive))))))
    }

    suspend fun getLabels(owner: String, name: String): ApiResult<List<IssueLabel>> = safeCall {
        val labels = mutableListOf<DiscussionLabelNode>()
        var cursor: String? = null
        do {
            val query = "query DiscussionLabels(\$owner: String!, \$name: String!, \$after: String) { repository(owner: \$owner, name: \$name) { labels(first: ${PageSize.METADATA}, after: \$after) { nodes { id name color description } pageInfo { hasNextPage endCursor } } } }"
            val connection = api.graphQL<DiscussionLabelsData>(query, mapOf("owner" to JsonPrimitive(owner), "name" to JsonPrimitive(name), "after" to (cursor?.let(::JsonPrimitive) ?: JsonNull))).repository?.labels ?: break
            labels += connection.nodes
            cursor = connection.pageInfo.endCursor
        } while (connection.pageInfo.hasNextPage && cursor != null)
        labels.map { IssueLabel(it.id, it.name, it.color, it.description) }
    }
    suspend fun removeLabelsFromDiscussion(discussionId: String, labelIds: List<String>): ApiResult<Unit> = safeCall {
        if (labelIds.isEmpty()) return@safeCall Unit
        api.graphQL<ClientMutationData>("mutation RemoveDiscussionLabels(\$input: RemoveLabelsFromLabelableInput!) { removeLabelsFromLabelable(input: \$input) { clientMutationId } }", mapOf("input" to JsonObject(mapOf("labelableId" to JsonPrimitive(discussionId), "labelIds" to JsonArray(labelIds.map(::JsonPrimitive))))))
    }
    suspend fun closeDiscussion(id: String): ApiResult<RepoDiscussion> = mutate("closeDiscussion", "CloseDiscussionInput", JsonObject(mapOf("discussionId" to JsonPrimitive(id)))) { it.closeDiscussion?.discussion }
    suspend fun reopenDiscussion(id: String): ApiResult<RepoDiscussion> = mutate("reopenDiscussion", "ReopenDiscussionInput", JsonObject(mapOf("discussionId" to JsonPrimitive(id)))) { it.reopenDiscussion?.discussion }
    suspend fun deleteDiscussion(id: String): ApiResult<Unit> = safeCall {
        api.graphQL<ClientMutationData>(
            "mutation DeleteDiscussion(${'$'}input: DeleteDiscussionInput!) { deleteDiscussion(input: ${'$'}input) { clientMutationId } }",
            mapOf("input" to JsonObject(mapOf("id" to JsonPrimitive(id)))),
        )
    }
    suspend fun addComment(discussionId: String, body: String, replyToId: String? = null): ApiResult<RepoDiscussionComment> = safeCall { val data = api.graphQL<AddDiscussionCommentData>("mutation AddDiscussionComment(${'$'}input: AddDiscussionCommentInput!) { addDiscussionComment(input: ${'$'}input) { comment { ${commentFields()} } } }", mapOf("input" to JsonObject(buildMap { put("discussionId", JsonPrimitive(discussionId)); put("body", JsonPrimitive(body)); replyToId?.let { put("replyToId", JsonPrimitive(it)) } }))); toComment(data.addDiscussionComment?.comment ?: error("Comment was not created")) }
    suspend fun updateComment(id: String, body: String): ApiResult<RepoDiscussionComment> = safeCall { val data = api.graphQL<UpdateDiscussionCommentData>("mutation UpdateDiscussionComment(${'$'}input: UpdateDiscussionCommentInput!) { updateDiscussionComment(input: ${'$'}input) { comment { ${commentFields()} } } }", mapOf("input" to JsonObject(mapOf("commentId" to JsonPrimitive(id), "body" to JsonPrimitive(body))))); toComment(data.updateDiscussionComment?.comment ?: error("Comment was not updated")) }
    suspend fun deleteComment(id: String): ApiResult<Unit> = safeCall {
        api.graphQL<ClientMutationData>(
            "mutation DeleteDiscussionComment(${'$'}input: DeleteDiscussionCommentInput!) { deleteDiscussionComment(input: ${'$'}input) { clientMutationId } }",
            mapOf("input" to JsonObject(mapOf("id" to JsonPrimitive(id)))),
        )
    }
    suspend fun markAnswer(id: String, answer: Boolean): ApiResult<Unit> = safeCall {
        val name = if (answer) "markDiscussionCommentAsAnswer" else "unmarkDiscussionCommentAsAnswer"
        val type = if (answer) "MarkDiscussionCommentAsAnswerInput" else "UnmarkDiscussionCommentAsAnswerInput"
        api.graphQL<ClientMutationData>(
            "mutation MarkAnswer(${'$'}input: $type!) { $name(input: ${'$'}input) { clientMutationId } }",
            mapOf("input" to JsonObject(mapOf("id" to JsonPrimitive(id)))),
        )
    }
    suspend fun updateSubscription(id: String, subscribed: Boolean): ApiResult<String?> = safeCall { val data = api.graphQL<SubscriptionData>("mutation UpdateDiscussionSubscription(${'$'}input: UpdateSubscriptionInput!) { updateSubscription(input: ${'$'}input) { subscribable { viewerSubscription } } }", mapOf("input" to JsonObject(mapOf("subscribableId" to JsonPrimitive(id), "state" to JsonPrimitive(if (subscribed) "SUBSCRIBED" else "UNSUBSCRIBED"))))); data.updateSubscription?.subscribable?.viewerSubscription }

    private suspend fun mutate(name: String, type: String, input: JsonObject, selector: (DiscussionMutationData) -> DiscussionNode?): ApiResult<RepoDiscussion> = safeCall { val data = api.graphQL<DiscussionMutationData>("mutation DiscussionMutation(${'$'}input: $type!) { $name(input: ${'$'}input) { discussion { ${discussionFields(includeCommentCount = true)} } } }", mapOf("input" to input)); toDiscussion(selector(data) ?: error("Discussion mutation failed")) }
    private fun parsePermission(value: String?) = value?.let { runCatching { RepoPermission.valueOf(it) }.getOrNull() } ?: RepoPermission.NONE
    private fun states(filter: RepoDiscussionStateFilter) = when (filter) { RepoDiscussionStateFilter.OPEN -> listOf("OPEN"); RepoDiscussionStateFilter.CLOSED -> listOf("CLOSED"); RepoDiscussionStateFilter.ALL -> listOf("OPEN", "CLOSED") }
    private fun order(filter: RepoDiscussionSort) = JsonObject(mapOf("field" to JsonPrimitive(if (filter.name.startsWith("CREATED")) "CREATED_AT" else "UPDATED_AT"), "direction" to JsonPrimitive(if (filter.name.endsWith("ASC")) "ASC" else "DESC")))
    private fun toCategory(node: CategoryNode) = RepoDiscussionCategory(node.id, node.name, node.emoji, node.description, node.isAnswerable)
    private fun association(value: String) = runCatching { CommentAuthorAssociation.valueOf(value) }.getOrDefault(CommentAuthorAssociation.NONE)
    private fun toDiscussion(node: DiscussionNode) = RepoDiscussion(node.id, node.number, node.title, node.body, node.bodyHTML, if (node.closed) RepoDiscussionState.CLOSED else RepoDiscussionState.OPEN, node.stateReason.toDiscussionStateReason(), toCategory(node.category), node.author?.let { SimpleUser(it.login, null, it.avatarUrl, null) }, node.createdAt, node.updatedAt, node.comments?.totalCount ?: 0, node.labels?.nodes.orEmpty().map { IssueLabel(it.id, it.name, it.color, it.description) }, node.locked, node.answerChosenAt, node.viewerCanUpdate, node.viewerCanDelete, node.viewerCanClose, node.viewerCanReopen, node.viewerCanSubscribe, node.viewerSubscription, node.url, association(node.authorAssociation), ConversationEditSummary(node.includesCreatedEdit, node.lastEditedAt, node.editor?.let { SimpleUser(it.login, null, it.avatarUrl, null) }))
    private fun String?.toDiscussionStateReason(): DiscussionStateReason? = when (this) {
        null -> null
        "RESOLVED" -> DiscussionStateReason.RESOLVED
        "OUTDATED" -> DiscussionStateReason.OUTDATED
        "DUPLICATE" -> DiscussionStateReason.DUPLICATE
        "REOPENED" -> DiscussionStateReason.REOPENED
        else -> error("Unsupported Discussion stateReason: $this")
    }
    private fun toComment(node: CommentNode) = RepoDiscussionComment(node.id, node.body, node.bodyHTML, node.author?.let { SimpleUser(it.login, null, it.avatarUrl, null) }, node.createdAt, node.updatedAt, node.isAnswer, node.replyTo?.id, node.viewerCanUpdate, node.viewerCanDelete, node.viewerCanReact, node.viewerCanMarkAsAnswer, node.viewerCanUnmarkAsAnswer, node.url, association(node.authorAssociation), ConversationEditSummary(node.includesCreatedEdit, node.lastEditedAt, node.editor?.let { SimpleUser(it.login, null, it.avatarUrl, null) }))
    companion object {
        private fun categoryFields() = "id name emoji description isAnswerable"
        private fun discussionFields(includeCommentCount: Boolean = false) = buildString {
            append("id url number title body bodyHTML closed stateReason category { ${categoryFields()} } author { login avatarUrl } authorAssociation createdAt updatedAt includesCreatedEdit lastEditedAt editor { login avatarUrl } ")
            if (includeCommentCount) append("comments { totalCount } ")
            append("labels(first: ${PageSize.METADATA}) { nodes { id name color description } } locked answerChosenAt viewerCanUpdate viewerCanDelete viewerCanClose viewerCanReopen viewerCanSubscribe viewerSubscription")
        }
        private fun commentFields() = "id url body bodyHTML author { login avatarUrl } authorAssociation createdAt updatedAt includesCreatedEdit lastEditedAt editor { login avatarUrl } isAnswer replyTo { id } viewerCanUpdate viewerCanDelete viewerCanReact viewerCanMarkAsAnswer viewerCanUnmarkAsAnswer"
    }
}

@Serializable private data class DiscussionPageInfoNode(val hasNextPage: Boolean = false, val endCursor: String? = null)
@Serializable private data class DiscussionActorNode(val login: String, val avatarUrl: String? = null)
@Serializable private data class CategoryNode(val id: String, val name: String, val emoji: String, val description: String? = null, val isAnswerable: Boolean = false)
@Serializable private data class DiscussionLabelNode(val id: String, val name: String, val color: String, val description: String? = null)
@Serializable private data class CategoryConnection(val nodes: List<CategoryNode> = emptyList())
@Serializable private data class LabelConnection(val nodes: List<DiscussionLabelNode> = emptyList(), val pageInfo: DiscussionPageInfoNode = DiscussionPageInfoNode())
@Serializable private data class CommentConnection(val totalCount: Int = 0, val nodes: List<CommentNode> = emptyList(), val pageInfo: DiscussionPageInfoNode? = null)
@Serializable private data class CommentNode(val id: String, val url: String = "", val body: String = "", val bodyHTML: String = "", val author: DiscussionActorNode? = null, val authorAssociation: String = "NONE", val createdAt: String = "", val updatedAt: String = "", val includesCreatedEdit: Boolean = false, val lastEditedAt: String? = null, val editor: DiscussionActorNode? = null, val isAnswer: Boolean = false, val replyTo: ReplyToNode? = null, val viewerCanUpdate: Boolean = false, val viewerCanDelete: Boolean = false, val viewerCanReact: Boolean = false, val viewerCanMarkAsAnswer: Boolean = false, val viewerCanUnmarkAsAnswer: Boolean = false)
@Serializable private data class ReplyToNode(val id: String)
@Serializable private data class DiscussionNode(val id: String, val url: String = "", val number: Int, val title: String, val body: String = "", val bodyHTML: String = "", val closed: Boolean = false, val stateReason: String? = null, val category: CategoryNode, val author: DiscussionActorNode? = null, val authorAssociation: String = "NONE", val createdAt: String = "", val updatedAt: String = "", val includesCreatedEdit: Boolean = false, val lastEditedAt: String? = null, val editor: DiscussionActorNode? = null, val comments: CommentConnection? = null, val labels: LabelConnection? = null, val locked: Boolean = false, val answerChosenAt: String? = null, val viewerCanUpdate: Boolean = false, val viewerCanDelete: Boolean = false, val viewerCanClose: Boolean = false, val viewerCanReopen: Boolean = false, val viewerCanSubscribe: Boolean = false, val viewerSubscription: String? = null)
@Serializable private data class DiscussionConnection(val totalCount: Int = 0, val nodes: List<DiscussionNode> = emptyList(), val pageInfo: DiscussionPageInfoNode)
@Serializable private data class DiscussionListRepository(val id: String, val viewerPermission: String? = null, val hasDiscussionsEnabled: Boolean = false, val categories: CategoryConnection = CategoryConnection(), val discussions: DiscussionConnection)
@Serializable private data class DiscussionListData(val repository: DiscussionListRepository? = null)
@Serializable private data class DiscussionDetailRepository(val id: String, val viewerPermission: String? = null, val categories: CategoryConnection = CategoryConnection(), val discussion: DiscussionNode? = null)
@Serializable private data class DiscussionDetailData(val repository: DiscussionDetailRepository? = null)
@Serializable private data class DiscussionLabelsRepository(val labels: LabelConnection = LabelConnection())
@Serializable private data class DiscussionLabelsData(val repository: DiscussionLabelsRepository? = null)
@Serializable private data class DiscussionPayload(val discussion: DiscussionNode? = null)
@Serializable private data class DiscussionMutationData(val createDiscussion: DiscussionPayload? = null, val updateDiscussion: DiscussionPayload? = null, val closeDiscussion: DiscussionPayload? = null, val reopenDiscussion: DiscussionPayload? = null)
@Serializable private data class CommentPayload(val comment: CommentNode? = null)
@Serializable private data class AddDiscussionCommentData(val addDiscussionComment: CommentPayload? = null)
@Serializable private data class UpdateDiscussionCommentData(val updateDiscussionComment: CommentPayload? = null)
@Serializable private data class ClientMutationData(val clientMutationId: String? = null)
@Serializable private data class DiscussionSubscriptionNode(val viewerSubscription: String? = null)
@Serializable private data class DiscussionSubscriptionPayload(val subscribable: DiscussionSubscriptionNode? = null)
@Serializable private data class SubscriptionData(val updateSubscription: DiscussionSubscriptionPayload? = null)
