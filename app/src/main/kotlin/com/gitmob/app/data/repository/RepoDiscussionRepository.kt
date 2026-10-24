package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.executeAsApiResult
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.CommentAuthorAssociation
import com.gitmob.app.data.model.ConversationEditSummary
import com.gitmob.app.data.model.DiscussionStateReason
import com.gitmob.app.data.model.IssueLabel
import com.gitmob.app.data.model.RepoDiscussion
import com.gitmob.app.data.model.RepoDiscussionCategory
import com.gitmob.app.data.model.RepoDiscussionComment
import com.gitmob.app.data.model.RepoDiscussionDetail
import com.gitmob.app.data.model.RepoDiscussionFilter
import com.gitmob.app.data.model.RepoDiscussionPage
import com.gitmob.app.data.model.RepoDiscussionSort
import com.gitmob.app.data.model.RepoDiscussionState
import com.gitmob.app.data.model.RepoDiscussionStateFilter
import com.gitmob.app.data.model.SimpleUser
import com.gitmob.app.graphql.AddDiscussionCommentMutation
import com.gitmob.app.graphql.AddDiscussionLabelsMutation
import com.gitmob.app.graphql.CloseRepoDiscussionMutation
import com.gitmob.app.graphql.CreateRepoDiscussionMutation
import com.gitmob.app.graphql.DeleteDiscussionCommentMutation
import com.gitmob.app.graphql.DeleteRepoDiscussionMutation
import com.gitmob.app.graphql.MarkCommentAsAnswerMutation
import com.gitmob.app.graphql.RemoveDiscussionLabelsMutation
import com.gitmob.app.graphql.ReopenRepoDiscussionMutation
import com.gitmob.app.graphql.RepoDiscussionDetailQuery
import com.gitmob.app.graphql.RepoDiscussionsQuery
import com.gitmob.app.graphql.RepoLabelsQuery
import com.gitmob.app.graphql.UnmarkCommentAsAnswerMutation
import com.gitmob.app.graphql.UpdateDiscussionCommentMutation
import com.gitmob.app.graphql.UpdateDiscussionSubscriptionMutation
import com.gitmob.app.graphql.UpdateRepoDiscussionMutation
import com.gitmob.app.graphql.fragment.DiscussionCategoryFields
import com.gitmob.app.graphql.fragment.DiscussionCommentFields
import com.gitmob.app.graphql.fragment.DiscussionFields
import com.gitmob.app.graphql.type.AddDiscussionCommentInput
import com.gitmob.app.graphql.type.AddLabelsToLabelableInput
import com.gitmob.app.graphql.type.CloseDiscussionInput
import com.gitmob.app.graphql.type.CreateDiscussionInput
import com.gitmob.app.graphql.type.DeleteDiscussionCommentInput
import com.gitmob.app.graphql.type.DeleteDiscussionInput
import com.gitmob.app.graphql.type.DiscussionOrder
import com.gitmob.app.graphql.type.DiscussionOrderField
import com.gitmob.app.graphql.type.MarkDiscussionCommentAsAnswerInput
import com.gitmob.app.graphql.type.OrderDirection
import com.gitmob.app.graphql.type.RemoveLabelsFromLabelableInput
import com.gitmob.app.graphql.type.ReopenDiscussionInput
import com.gitmob.app.graphql.type.SubscriptionState
import com.gitmob.app.graphql.type.UnmarkDiscussionCommentAsAnswerInput
import com.gitmob.app.graphql.type.UpdateDiscussionCommentInput
import com.gitmob.app.graphql.type.UpdateDiscussionInput
import com.gitmob.app.graphql.type.UpdateSubscriptionInput
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepoDiscussionRepository @Inject constructor(private val apolloClient: ApolloClient) {

    suspend fun getDiscussions(owner: String, name: String, filter: RepoDiscussionFilter, after: String? = null): ApiResult<RepoDiscussionPage> =
        when (val result = apolloClient.query(
            RepoDiscussionsQuery(
                owner = owner,
                name = name,
                after = Optional.presentIfNotNull(after),
                states = Optional.present(states(filter.state)),
                orderBy = Optional.present(order(filter.sort)),
                categoryId = Optional.presentIfNotNull(filter.categoryId),
                answered = Optional.presentIfNotNull(filter.answered),
                first = PageSize.REPO_ISSUES,
                categoriesFirst = PageSize.DISCUSSION_CATEGORIES,
                metadataFirst = PageSize.METADATA,
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val repo = result.data.repository ?: error("Repository not found")
                val permission = RepoIssueRepository.parsePermission(repo.viewerPermission?.rawValue)
                RepoDiscussionPage(
                    repo.id,
                    permission,
                    permission.toCapabilities(),
                    repo.hasDiscussionsEnabled,
                    repo.categories.nodes.orEmpty().filterNotNull().map { toCategory(it.discussionCategoryFields) },
                    repo.discussions.totalCount,
                    repo.discussions.nodes.orEmpty().filterNotNull()
                        .map { toDiscussion(it.discussionFields, it.comments.totalCount) },
                    repo.discussions.pageInfo.hasNextPage,
                    repo.discussions.pageInfo.endCursor,
                )
            }
        }

    suspend fun getDiscussion(owner: String, name: String, number: Int, after: String? = null): ApiResult<RepoDiscussionDetail> =
        when (val result = apolloClient.query(
            RepoDiscussionDetailQuery(
                owner = owner,
                name = name,
                number = number,
                after = Optional.presentIfNotNull(after),
                commentsFirst = PageSize.ISSUE_COMMENTS,
                categoriesFirst = PageSize.DISCUSSION_CATEGORIES,
                metadataFirst = PageSize.METADATA,
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val repo = result.data.repository ?: error("Repository not found")
                val discussion = repo.discussion ?: error("Discussion not found")
                val permission = RepoIssueRepository.parsePermission(repo.viewerPermission?.rawValue)
                RepoDiscussionDetail(
                    repo.id,
                    permission,
                    permission.toCapabilities(),
                    repo.categories.nodes.orEmpty().filterNotNull().map { toCategory(it.discussionCategoryFields) },
                    toDiscussion(discussion.discussionFields, discussion.comments.totalCount),
                    discussion.comments.nodes.orEmpty().filterNotNull().map { toComment(it.discussionCommentFields) },
                    discussion.comments.pageInfo.hasNextPage,
                    discussion.comments.pageInfo.endCursor,
                )
            }
        }

    suspend fun createDiscussion(repositoryId: String, title: String, body: String, categoryId: String): ApiResult<RepoDiscussion> =
        when (val result = apolloClient.mutation(
            CreateRepoDiscussionMutation(
                CreateDiscussionInput(repositoryId = repositoryId, title = title, body = body, categoryId = categoryId),
                metadataFirst = PageSize.METADATA,
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val d = result.data.createDiscussion?.discussion ?: error("Discussion mutation failed")
                toDiscussion(d.discussionFields, d.comments.totalCount)
            }
        }

    suspend fun updateDiscussion(id: String, title: String, body: String, categoryId: String?): ApiResult<RepoDiscussion> =
        when (val result = apolloClient.mutation(
            UpdateRepoDiscussionMutation(
                UpdateDiscussionInput(
                    discussionId = id,
                    title = Optional.present(title),
                    body = Optional.present(body),
                    categoryId = Optional.presentIfNotNull(categoryId),
                ),
                metadataFirst = PageSize.METADATA,
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val d = result.data.updateDiscussion?.discussion ?: error("Discussion mutation failed")
                toDiscussion(d.discussionFields, d.comments.totalCount)
            }
        }

    suspend fun closeDiscussion(id: String): ApiResult<RepoDiscussion> =
        when (val result = apolloClient.mutation(
            CloseRepoDiscussionMutation(CloseDiscussionInput(discussionId = id), PageSize.METADATA),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val d = result.data.closeDiscussion?.discussion ?: error("Discussion mutation failed")
                toDiscussion(d.discussionFields, d.comments.totalCount)
            }
        }

    suspend fun reopenDiscussion(id: String): ApiResult<RepoDiscussion> =
        when (val result = apolloClient.mutation(
            ReopenRepoDiscussionMutation(ReopenDiscussionInput(discussionId = id), PageSize.METADATA),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val d = result.data.reopenDiscussion?.discussion ?: error("Discussion mutation failed")
                toDiscussion(d.discussionFields, d.comments.totalCount)
            }
        }

    suspend fun addLabelsToDiscussion(discussionId: String, labelIds: List<String>): ApiResult<Unit> {
        if (labelIds.isEmpty()) return ApiResult.Success(Unit)
        return when (val result = apolloClient.mutation(
            AddDiscussionLabelsMutation(AddLabelsToLabelableInput(labelableId = discussionId, labelIds = Optional.present(labelIds))),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall { }
        }
    }

    suspend fun removeLabelsFromDiscussion(discussionId: String, labelIds: List<String>): ApiResult<Unit> {
        if (labelIds.isEmpty()) return ApiResult.Success(Unit)
        return when (val result = apolloClient.mutation(
            RemoveDiscussionLabelsMutation(RemoveLabelsFromLabelableInput(labelableId = discussionId, labelIds = labelIds)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall { }
        }
    }

    suspend fun deleteDiscussion(id: String): ApiResult<Unit> =
        when (val result = apolloClient.mutation(
            DeleteRepoDiscussionMutation(DeleteDiscussionInput(id = id)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall { }
        }

    /** 标签分页查询与 Issue 域完全同构，直接复用 issue 包的 RepoLabelsQuery。 */
    suspend fun getLabels(owner: String, name: String): ApiResult<List<IssueLabel>> {
        val all = mutableListOf<IssueLabel>()
        var cursor: String? = null
        while (true) {
            when (val result = apolloClient.query(
                RepoLabelsQuery(owner, name, Optional.presentIfNotNull(cursor), PageSize.METADATA),
            ).executeAsApiResult()) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> {
                    val conn = result.data.repository?.labels ?: break
                    all += conn.nodes.orEmpty().filterNotNull()
                        .map { IssueLabel(it.id, it.name, it.color, it.description) }
                    cursor = conn.pageInfo.endCursor
                    if (!conn.pageInfo.hasNextPage || cursor == null) break
                }
            }
        }
        return ApiResult.Success(all)
    }

    suspend fun addComment(discussionId: String, body: String, replyToId: String? = null): ApiResult<RepoDiscussionComment> =
        when (val result = apolloClient.mutation(
            AddDiscussionCommentMutation(
                AddDiscussionCommentInput(
                    discussionId = discussionId,
                    body = body,
                    replyToId = Optional.presentIfNotNull(replyToId),
                ),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                toComment(result.data.addDiscussionComment?.comment?.discussionCommentFields ?: error("Comment was not created"))
            }
        }

    suspend fun updateComment(id: String, body: String): ApiResult<RepoDiscussionComment> =
        when (val result = apolloClient.mutation(
            UpdateDiscussionCommentMutation(
                UpdateDiscussionCommentInput(commentId = id, body = body),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                toComment(result.data.updateDiscussionComment?.comment?.discussionCommentFields ?: error("Comment was not updated"))
            }
        }

    suspend fun deleteComment(id: String): ApiResult<Unit> =
        when (val result = apolloClient.mutation(
            DeleteDiscussionCommentMutation(DeleteDiscussionCommentInput(id = id)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall { }
        }

    suspend fun markAnswer(id: String, answer: Boolean): ApiResult<Unit> =
        if (answer) {
            when (val result = apolloClient.mutation(
                MarkCommentAsAnswerMutation(MarkDiscussionCommentAsAnswerInput(id = id)),
            ).executeAsApiResult()) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> safeCall { }
            }
        } else {
            when (val result = apolloClient.mutation(
                UnmarkCommentAsAnswerMutation(UnmarkDiscussionCommentAsAnswerInput(id = id)),
            ).executeAsApiResult()) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> safeCall { }
            }
        }

    suspend fun updateSubscription(id: String, subscribed: Boolean): ApiResult<String?> =
        when (val result = apolloClient.mutation(
            UpdateDiscussionSubscriptionMutation(
                UpdateSubscriptionInput(
                    subscribableId = id,
                    state = if (subscribed) SubscriptionState.SUBSCRIBED else SubscriptionState.UNSUBSCRIBED,
                ),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                result.data.updateSubscription?.subscribable?.viewerSubscription?.rawValue
            }
        }

    private fun states(filter: RepoDiscussionStateFilter) = when (filter) {
        RepoDiscussionStateFilter.OPEN -> listOf(com.gitmob.app.graphql.type.DiscussionState.OPEN)
        RepoDiscussionStateFilter.CLOSED -> listOf(com.gitmob.app.graphql.type.DiscussionState.CLOSED)
        RepoDiscussionStateFilter.ALL -> listOf(
            com.gitmob.app.graphql.type.DiscussionState.OPEN,
            com.gitmob.app.graphql.type.DiscussionState.CLOSED,
        )
    }

    private fun order(filter: RepoDiscussionSort) = DiscussionOrder(
        field = if (filter.name.startsWith("CREATED")) DiscussionOrderField.CREATED_AT else DiscussionOrderField.UPDATED_AT,
        direction = if (filter.name.endsWith("ASC")) OrderDirection.ASC else OrderDirection.DESC,
    )

    private fun toCategory(node: DiscussionCategoryFields) =
        RepoDiscussionCategory(node.id, node.name, node.emoji, node.description, node.isAnswerable)

    private fun association(value: com.gitmob.app.graphql.type.CommentAuthorAssociation) =
        runCatching { CommentAuthorAssociation.valueOf(value.rawValue) }.getOrDefault(CommentAuthorAssociation.NONE)

    private fun toDiscussion(node: DiscussionFields, commentCount: Int) = RepoDiscussion(
        node.id,
        node.number,
        node.title,
        node.body,
        node.bodyHTML,
        if (node.closed) RepoDiscussionState.CLOSED else RepoDiscussionState.OPEN,
        node.stateReason.toDiscussionStateReason(),
        toCategory(node.category.discussionCategoryFields),
        node.author?.let { SimpleUser(it.login, null, it.avatarUrl, null) },
        node.createdAt,
        node.updatedAt,
        commentCount,
        node.labels?.nodes.orEmpty().filterNotNull().map { IssueLabel(it.id, it.name, it.color, it.description) },
        node.locked,
        node.answerChosenAt,
        node.viewerCanUpdate,
        node.viewerCanDelete,
        node.viewerCanClose,
        node.viewerCanReopen,
        node.viewerCanSubscribe,
        node.viewerSubscription?.rawValue,
        node.url,
        association(node.authorAssociation),
        ConversationEditSummary(node.includesCreatedEdit, node.lastEditedAt, node.editor?.let { SimpleUser(it.login, null, it.avatarUrl, null) }),
    )

    private fun com.gitmob.app.graphql.type.DiscussionStateReason?.toDiscussionStateReason(): DiscussionStateReason? = when (this) {
        null -> null
        com.gitmob.app.graphql.type.DiscussionStateReason.RESOLVED -> DiscussionStateReason.RESOLVED
        com.gitmob.app.graphql.type.DiscussionStateReason.OUTDATED -> DiscussionStateReason.OUTDATED
        com.gitmob.app.graphql.type.DiscussionStateReason.DUPLICATE -> DiscussionStateReason.DUPLICATE
        com.gitmob.app.graphql.type.DiscussionStateReason.REOPENED -> DiscussionStateReason.REOPENED
        else -> error("Unsupported Discussion stateReason: ${this.rawValue}")
    }

    private fun toComment(node: DiscussionCommentFields) = RepoDiscussionComment(
        node.id,
        node.body,
        node.bodyHTML,
        node.author?.let { SimpleUser(it.login, null, it.avatarUrl, null) },
        node.createdAt,
        node.updatedAt,
        node.isAnswer,
        node.replyTo?.id,
        node.viewerCanUpdate,
        node.viewerCanDelete,
        node.viewerCanReact,
        node.viewerCanMarkAsAnswer,
        node.viewerCanUnmarkAsAnswer,
        node.url,
        association(node.authorAssociation),
        ConversationEditSummary(node.includesCreatedEdit, node.lastEditedAt, node.editor?.let { SimpleUser(it.login, null, it.avatarUrl, null) }),
    )
}
