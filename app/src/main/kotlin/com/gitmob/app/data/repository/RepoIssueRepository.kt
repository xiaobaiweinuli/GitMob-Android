package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.graphql.executeAsApiResult
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.CommentAuthorAssociation
import com.gitmob.app.data.model.ConversationEditSummary
import com.gitmob.app.data.model.CreateRepoIssueInput
import com.gitmob.app.data.model.IssueComment
import com.gitmob.app.data.model.IssueCommentPage
import com.gitmob.app.data.model.IssueLabel
import com.gitmob.app.data.model.IssueMilestone
import com.gitmob.app.data.model.IssueState
import com.gitmob.app.data.model.IssueStateReason
import com.gitmob.app.data.model.IssueTemplateLoadResult
import com.gitmob.app.data.model.RepoAssigneeFilter
import com.gitmob.app.data.model.RepoAuthorFilter
import com.gitmob.app.data.model.RepoIssue
import com.gitmob.app.data.model.RepoIssueDetail
import com.gitmob.app.data.model.RepoIssueFilter
import com.gitmob.app.data.model.RepoIssuePage
import com.gitmob.app.data.model.RepoIssueSort
import com.gitmob.app.data.model.RepoIssueStateFilter
import com.gitmob.app.data.model.RepoMilestoneFilter
import com.gitmob.app.data.model.SimpleUser
import com.gitmob.app.data.model.UpdateRepoIssueInput
import com.gitmob.app.graphql.AddIssueCommentMutation
import com.gitmob.app.graphql.CloseRepoIssueMutation
import com.gitmob.app.graphql.CreateRepoIssueMutation
import com.gitmob.app.graphql.DeleteIssueCommentMutation
import com.gitmob.app.graphql.DeleteRepoIssueMutation
import com.gitmob.app.graphql.ReopenRepoIssueMutation
import com.gitmob.app.graphql.RepoAssignableUsersQuery
import com.gitmob.app.graphql.RepoIssueDetailQuery
import com.gitmob.app.graphql.RepoIssueFormContextQuery
import com.gitmob.app.graphql.RepoIssueFormsQuery
import com.gitmob.app.graphql.RepoIssuesQuery
import com.gitmob.app.graphql.RepoLabelsQuery
import com.gitmob.app.graphql.RepoMilestonesQuery
import com.gitmob.app.graphql.UpdateIssueCommentMutation
import com.gitmob.app.graphql.UpdateIssueSubscriptionMutation
import com.gitmob.app.graphql.UpdateRepoIssueMutation
import com.gitmob.app.graphql.fragment.IssueCommentFields
import com.gitmob.app.graphql.fragment.IssueFields
import com.gitmob.app.graphql.type.CloseIssueInput
import com.gitmob.app.graphql.type.CreateIssueInput
import com.gitmob.app.graphql.type.DeleteIssueCommentInput
import com.gitmob.app.graphql.type.DeleteIssueInput
import com.gitmob.app.graphql.type.IssueClosedStateReason
import com.gitmob.app.graphql.type.IssueFilters
import com.gitmob.app.graphql.type.IssueOrder
import com.gitmob.app.graphql.type.IssueOrderField
import com.gitmob.app.graphql.type.OrderDirection
import com.gitmob.app.graphql.type.ReopenIssueInput
import com.gitmob.app.graphql.type.SubscriptionState
import com.gitmob.app.graphql.type.UpdateIssueCommentInput
import com.gitmob.app.graphql.type.UpdateIssueInput
import com.gitmob.app.graphql.type.UpdateSubscriptionInput
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepoIssueRepository @Inject constructor(private val apolloClient: ApolloClient) {
    private val labelsCache = MemoryCache<String, List<IssueLabel>>(5 * 60_000L)
    private val milestonesCache = MemoryCache<String, List<IssueMilestone>>(5 * 60_000L)
    private val assigneesCache = MemoryCache<String, List<SimpleUser>>(5 * 60_000L)

    fun invalidateAllCaches() {
        labelsCache.invalidateAll()
        milestonesCache.invalidateAll()
        assigneesCache.invalidateAll()
    }

    suspend fun getIssues(owner: String, name: String, filter: RepoIssueFilter, after: String? = null): ApiResult<RepoIssuePage> =
        when (val result = apolloClient.query(
            RepoIssuesQuery(
                owner = owner,
                name = name,
                after = Optional.presentIfNotNull(after),
                states = Optional.present(states(filter.state)),
                orderBy = issueOrder(filter.sort),
                filterBy = Optional.presentIfNotNull(issueFilters(filter)),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val data = result.data.repository ?: error("Repository not found")
                val permission = parsePermission(data.viewerPermission?.rawValue)
                RepoIssuePage(
                    repositoryId = data.id,
                    permission = permission,
                    capabilities = permission.toCapabilities(),
                    viewerCanCreateIssues = data.viewerCanCreateIssues,
                    hasIssuesEnabled = data.hasIssuesEnabled,
                    totalCount = data.issues.totalCount,
                    items = data.issues.nodes.orEmpty().filterNotNull()
                        .map { toIssue(it.issueFields, it.comments.totalCount) },
                    hasNextPage = data.issues.pageInfo.hasNextPage,
                    endCursor = data.issues.pageInfo.endCursor,
                )
            }
        }

    suspend fun getIssue(owner: String, name: String, number: Int, commentsAfter: String? = null): ApiResult<RepoIssueDetail> =
        when (val result = apolloClient.query(
            RepoIssueDetailQuery(owner, name, number, Optional.presentIfNotNull(commentsAfter)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val data = result.data.repository ?: error("Repository not found")
                val issue = data.issue ?: error("Issue not found")
                val permission = parsePermission(data.viewerPermission?.rawValue)
                RepoIssueDetail(
                    repositoryId = data.id,
                    permission = permission,
                    capabilities = permission.toCapabilities(),
                    issue = toIssue(issue.issueFields, issue.comments.totalCount),
                    comments = IssueCommentPage(
                        items = issue.comments.nodes.orEmpty().filterNotNull().map { toComment(it.issueCommentFields) },
                        hasNextPage = issue.comments.pageInfo.hasNextPage,
                        endCursor = issue.comments.pageInfo.endCursor,
                    ),
                )
            }
        }

    suspend fun getLabels(owner: String, name: String): ApiResult<List<IssueLabel>> {
        val key = "$owner/$name"
        labelsCache.get(key)?.let { return ApiResult.Success(it) }
        val all = mutableListOf<IssueLabel>()
        var cursor: String? = null
        while (true) {
            when (val result = apolloClient.query(
                RepoLabelsQuery(owner, name, Optional.presentIfNotNull(cursor)),
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
        labelsCache.set(key, all)
        return ApiResult.Success(all)
    }

    suspend fun getMilestones(owner: String, name: String): ApiResult<List<IssueMilestone>> {
        val key = "$owner/$name"
        milestonesCache.get(key)?.let { return ApiResult.Success(it) }
        val all = mutableListOf<IssueMilestone>()
        var cursor: String? = null
        while (true) {
            when (val result = apolloClient.query(
                RepoMilestonesQuery(owner, name, Optional.presentIfNotNull(cursor)),
            ).executeAsApiResult()) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> {
                    val conn = result.data.repository?.milestones ?: break
                    all += conn.nodes.orEmpty().filterNotNull()
                        .map { IssueMilestone(it.id, it.number, it.title, it.state.rawValue, it.dueOn) }
                    cursor = conn.pageInfo.endCursor
                    if (!conn.pageInfo.hasNextPage || cursor == null) break
                }
            }
        }
        milestonesCache.set(key, all)
        return ApiResult.Success(all)
    }

    suspend fun getAssignableUsers(owner: String, name: String): ApiResult<List<SimpleUser>> {
        val key = "$owner/$name"
        assigneesCache.get(key)?.let { return ApiResult.Success(it) }
        val all = mutableListOf<SimpleUser>()
        var cursor: String? = null
        while (true) {
            when (val result = apolloClient.query(
                RepoAssignableUsersQuery(owner, name, Optional.presentIfNotNull(cursor)),
            ).executeAsApiResult()) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> {
                    val conn = result.data.repository?.assignableUsers ?: break
                    all += conn.nodes.orEmpty().filterNotNull()
                        .map { SimpleUser(it.login, it.name, it.avatarUrl, it.bio, it.id) }
                    cursor = conn.pageInfo.endCursor
                    if (!conn.pageInfo.hasNextPage || cursor == null) break
                }
            }
        }
        assigneesCache.set(key, all)
        return ApiResult.Success(all)
    }

    suspend fun getIssueTemplates(owner: String, name: String): ApiResult<IssueTemplateLoadResult> {
        val context = when (val result = apolloClient.query(
            RepoIssueFormContextQuery(owner, name),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.data.repository ?: return safeCall { error("Repository not found") }
        }
        val defaultBranch = context.defaultBranchRef?.name
            ?: return ApiResult.Success(IssueTemplateLoadResult(context.isBlankIssuesEnabled, emptyList()))
        return when (val result = apolloClient.query(
            RepoIssueFormsQuery(owner, name, "$defaultBranch:.github/ISSUE_TEMPLATE"),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val entries = result.data.repository?.`object`?.onTree?.entries.orEmpty()
                val config = entries.firstOrNull { it.type.equals("blob", true) && it.name.equals("config.yml", true) }
                val blankIssuesEnabled = config?.`object`?.onBlob?.usableText()?.let { source ->
                    try { IssueFormYamlParser.parseBlankIssuesEnabled(source) } catch (_: Exception) { context.isBlankIssuesEnabled }
                } ?: context.isBlankIssuesEnabled
                var invalidTemplateCount = 0
                val templates = entries.mapNotNull { entry ->
                    if (!entry.type.equals("blob", true) || !entry.name.endsWith(".yml", true) || entry.name.equals("config.yml", true)) return@mapNotNull null
                    val source = entry.`object`?.onBlob?.usableText()
                    if (source == null) {
                        invalidTemplateCount++
                        return@mapNotNull null
                    }
                    try {
                        IssueFormYamlParser.parse(entry.name, source)
                    } catch (_: Exception) {
                        invalidTemplateCount++
                        null
                    }
                }.sortedBy { it.name.lowercase() }
                IssueTemplateLoadResult(blankIssuesEnabled, templates, invalidTemplateCount)
            }
        }
    }

    suspend fun createIssue(input: CreateRepoIssueInput): ApiResult<RepoIssue> =
        when (val result = apolloClient.mutation(
            CreateRepoIssueMutation(
                CreateIssueInput(
                    repositoryId = input.repositoryId,
                    title = input.title,
                    body = Optional.present(input.body),
                    labelIds = if (input.labelIds.isNotEmpty()) Optional.present(input.labelIds) else Optional.Absent,
                    assigneeIds = if (input.assigneeIds.isNotEmpty()) Optional.present(input.assigneeIds) else Optional.Absent,
                    milestoneId = Optional.presentIfNotNull(input.milestoneId),
                ),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val issue = result.data.createIssue?.issue ?: error("Issue was not created")
                toIssue(issue.issueFields, issue.comments.totalCount)
            }
        }

    suspend fun updateIssue(input: UpdateRepoIssueInput): ApiResult<RepoIssue> =
        when (val result = apolloClient.mutation(
            UpdateRepoIssueMutation(
                UpdateIssueInput(
                    id = input.id,
                    title = Optional.present(input.title),
                    body = Optional.present(input.body),
                    // 与旧实现一致：labelIds/assigneeIds 永远显式发送（空数组 = 清空），
                    // milestoneId 显式发送（null = 清除里程碑）。
                    labelIds = Optional.present(input.labelIds),
                    assigneeIds = Optional.present(input.assigneeIds),
                    milestoneId = Optional.present(input.milestoneId),
                ),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val issue = result.data.updateIssue?.issue ?: error("Issue mutation failed")
                toIssue(issue.issueFields, issue.comments.totalCount)
            }
        }

    suspend fun closeIssue(issueId: String, reason: IssueStateReason): ApiResult<RepoIssue> =
        when (val result = apolloClient.mutation(
            CloseRepoIssueMutation(
                CloseIssueInput(
                    issueId = issueId,
                    stateReason = Optional.present(IssueClosedStateReason.safeValueOf(reason.name)),
                ),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val issue = result.data.closeIssue?.issue ?: error("Issue mutation failed")
                toIssue(issue.issueFields, issue.comments.totalCount)
            }
        }

    suspend fun reopenIssue(issueId: String): ApiResult<RepoIssue> =
        when (val result = apolloClient.mutation(
            ReopenRepoIssueMutation(ReopenIssueInput(issueId = issueId)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val issue = result.data.reopenIssue?.issue ?: error("Issue mutation failed")
                toIssue(issue.issueFields, issue.comments.totalCount)
            }
        }

    suspend fun deleteIssue(issueId: String): ApiResult<Unit> =
        when (val result = apolloClient.mutation(
            DeleteRepoIssueMutation(DeleteIssueInput(issueId = issueId)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall { }
        }

    suspend fun addComment(issueId: String, body: String): ApiResult<IssueComment> =
        when (val result = apolloClient.mutation(
            AddIssueCommentMutation(
                com.gitmob.app.graphql.type.AddCommentInput(
                    subjectId = issueId,
                    body = body,
                ),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val node = result.data.addComment?.commentEdge?.node ?: error("Comment was not created")
                toComment(node.issueCommentFields)
            }
        }

    suspend fun updateComment(commentId: String, body: String): ApiResult<IssueComment> =
        when (val result = apolloClient.mutation(
            UpdateIssueCommentMutation(
                UpdateIssueCommentInput(id = commentId, body = body),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val node = result.data.updateIssueComment?.issueComment ?: error("Comment was not updated")
                toComment(node.issueCommentFields)
            }
        }

    suspend fun deleteComment(commentId: String): ApiResult<Unit> =
        when (val result = apolloClient.mutation(
            DeleteIssueCommentMutation(DeleteIssueCommentInput(id = commentId)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall { }
        }

    suspend fun updateSubscription(issueId: String, subscribed: Boolean): ApiResult<String?> =
        when (val result = apolloClient.mutation(
            UpdateIssueSubscriptionMutation(
                UpdateSubscriptionInput(
                    subscribableId = issueId,
                    state = if (subscribed) SubscriptionState.SUBSCRIBED else SubscriptionState.UNSUBSCRIBED,
                ),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                result.data.updateSubscription?.subscribable?.viewerSubscription?.rawValue
            }
        }

    private fun RepoIssueFormsQuery.OnBlob.usableText(): String? = text?.takeIf {
        isBinary != true && !isTruncated && byteSize in 1..MAX_ISSUE_FORM_BYTES
    }

    private fun states(value: RepoIssueStateFilter) = when (value) {
        RepoIssueStateFilter.OPEN -> listOf(com.gitmob.app.graphql.type.IssueState.OPEN)
        RepoIssueStateFilter.CLOSED -> listOf(com.gitmob.app.graphql.type.IssueState.CLOSED)
        RepoIssueStateFilter.ALL -> listOf(com.gitmob.app.graphql.type.IssueState.OPEN, com.gitmob.app.graphql.type.IssueState.CLOSED)
    }

    private fun issueOrder(value: RepoIssueSort): IssueOrder {
        val field = when (value) {
            RepoIssueSort.CREATED_ASC, RepoIssueSort.CREATED_DESC -> IssueOrderField.CREATED_AT
            RepoIssueSort.COMMENTS_ASC, RepoIssueSort.COMMENTS_DESC -> IssueOrderField.COMMENTS
            else -> IssueOrderField.UPDATED_AT
        }
        val direction = if (value.name.endsWith("ASC")) OrderDirection.ASC else OrderDirection.DESC
        return IssueOrder(field = field, direction = direction)
    }

    private fun issueFilters(filter: RepoIssueFilter): IssueFilters? {
        var hasAny = false
        fun <T> present(value: T): Optional<T> { hasAny = true; return Optional.present(value) }
        val filters = IssueFilters(
            labels = if (filter.labels.isNotEmpty()) present(filter.labels.toList()) else Optional.Absent,
            milestone = when (val value = filter.milestone) {
                RepoMilestoneFilter.ALL -> Optional.Absent
                RepoMilestoneFilter.NONE -> present(null)
                is RepoMilestoneFilter.Number -> Optional.Absent
            },
            milestoneNumber = when (val value = filter.milestone) {
                is RepoMilestoneFilter.Number -> present(value.value.toString())
                else -> Optional.Absent
            },
            assignee = when (val value = filter.assignee) {
                RepoAssigneeFilter.ALL -> Optional.Absent
                RepoAssigneeFilter.ANY -> present("*")
                RepoAssigneeFilter.NONE -> present(null)
                is RepoAssigneeFilter.Login -> present(value.value)
            },
            createdBy = when (val value = filter.author) {
                RepoAuthorFilter.ALL -> Optional.Absent
                is RepoAuthorFilter.Login -> present(value.value)
            },
            mentioned = if (filter.mentioned) present("*") else Optional.Absent,
            viewerSubscribed = if (filter.subscribed) present(true) else Optional.Absent,
            since = filter.updatedSince?.let { present(it.toString()) } ?: Optional.Absent,
        )
        return filters.takeIf { hasAny }
    }

    companion object {
        private const val MAX_ISSUE_FORM_BYTES = 256 * 1024

        internal fun parsePermission(value: String?) =
            value?.let { runCatching { RepoPermission.valueOf(it) }.getOrDefault(RepoPermission.NONE) } ?: RepoPermission.NONE

        private fun association(value: com.gitmob.app.graphql.type.CommentAuthorAssociation) =
            runCatching { CommentAuthorAssociation.valueOf(value.rawValue) }.getOrDefault(CommentAuthorAssociation.NONE)

        private fun actorToUser(author: IssueFields.Author?) =
            author?.let { SimpleUser(it.login, null, it.avatarUrl, null) }

        private fun actorToUser(author: IssueCommentFields.Author?) =
            author?.let { SimpleUser(it.login, null, it.avatarUrl, null) }

        private fun editorToUser(editor: IssueFields.Editor?) =
            editor?.let { SimpleUser(it.login, null, it.avatarUrl, null) }

        private fun editorToUser(editor: IssueCommentFields.Editor?) =
            editor?.let { SimpleUser(it.login, null, it.avatarUrl, null) }

        internal fun toComment(node: IssueCommentFields) = IssueComment(
            id = node.id,
            author = actorToUser(node.author),
            body = node.body,
            bodyHtml = node.bodyHTML,
            createdAt = node.createdAt,
            updatedAt = node.updatedAt,
            viewerDidAuthor = node.viewerDidAuthor,
            viewerCanUpdate = node.viewerCanUpdate,
            viewerCanDelete = node.viewerCanDelete,
            viewerCanReact = node.viewerCanReact,
            url = node.url,
            authorAssociation = association(node.authorAssociation),
            editSummary = ConversationEditSummary(node.includesCreatedEdit, node.lastEditedAt, editorToUser(node.editor)),
        )

        internal fun toIssue(node: IssueFields, commentCount: Int) = RepoIssue(
            id = node.id,
            number = node.number,
            title = node.title,
            body = node.body,
            bodyHtml = node.bodyHTML,
            state = IssueState.valueOf(node.state.rawValue),
            stateReason = node.stateReason?.let { runCatching { IssueStateReason.valueOf(it.rawValue) }.getOrNull() },
            author = actorToUser(node.author),
            createdAt = node.createdAt,
            updatedAt = node.updatedAt,
            commentCount = commentCount,
            labels = node.labels?.nodes.orEmpty().filterNotNull()
                .map { IssueLabel(it.id, it.name, it.color, it.description) },
            assignees = node.assignees?.nodes.orEmpty().filterNotNull()
                .map { SimpleUser(it.login, it.name, it.avatarUrl, it.bio, it.id) },
            milestone = node.milestone?.let { IssueMilestone(it.id, it.number, it.title, it.state.rawValue, it.dueOn) },
            locked = node.locked,
            viewerCanClose = node.viewerCanClose,
            viewerCanDelete = node.viewerCanDelete,
            viewerCanLabel = node.viewerCanLabel,
            viewerCanSetMilestone = node.viewerCanSetMilestone,
            viewerCanUpdate = node.viewerCanUpdate,
            viewerCanSubscribe = node.viewerCanSubscribe,
            viewerCanReopen = node.viewerCanReopen,
            viewerSubscription = node.viewerSubscription?.rawValue,
            url = node.url,
            authorAssociation = association(node.authorAssociation),
            editSummary = ConversationEditSummary(node.includesCreatedEdit, node.lastEditedAt, editorToUser(node.editor)),
        )
    }
}
