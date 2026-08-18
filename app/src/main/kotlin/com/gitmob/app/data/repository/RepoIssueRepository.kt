package com.gitmob.app.data.repository

import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepoIssueRepository @Inject constructor(private val api: GHApiClient) {
    private val labelsCache = MemoryCache<String, List<IssueLabel>>(5 * 60_000L)
    private val milestonesCache = MemoryCache<String, List<IssueMilestone>>(5 * 60_000L)
    private val assigneesCache = MemoryCache<String, List<SimpleUser>>(5 * 60_000L)

    fun invalidateAllCaches() {
        labelsCache.invalidateAll()
        milestonesCache.invalidateAll()
        assigneesCache.invalidateAll()
    }

    suspend fun getIssues(owner: String, name: String, filter: RepoIssueFilter, after: String? = null): ApiResult<RepoIssuePage> = safeCall {
        val query = """
            query RepoIssues(${'$'}owner: String!, ${'$'}name: String!, ${'$'}after: String, ${'$'}states: [IssueState!], ${'$'}orderBy: IssueOrder!, ${'$'}filterBy: IssueFilters) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    id viewerPermission viewerCanCreateIssues hasIssuesEnabled
                    issues(first: ${PageSize.REPO_ISSUES}, after: ${'$'}after, states: ${'$'}states, orderBy: ${'$'}orderBy, filterBy: ${'$'}filterBy) {
                        totalCount nodes { ${issueFields(commentsFirst = 0)} }
                        pageInfo { hasNextPage endCursor }
                    }
                }
            }
        """.trimIndent()
        val data = api.graphQL<RepoIssuesQueryData>(query, buildMap {
            put("owner", JsonPrimitive(owner)); put("name", JsonPrimitive(name))
            after?.let { put("after", JsonPrimitive(it)) }
            put("states", JsonArray(states(filter.state).map(::JsonPrimitive)))
            put("orderBy", issueOrder(filter.sort))
            issueFilters(filter)?.let { put("filterBy", it) }
        }).repository ?: error("Repository not found")
        val permission = parsePermission(data.viewerPermission)
        RepoIssuePage(
            repositoryId = data.id,
            permission = permission,
            capabilities = permission.toCapabilities(),
            viewerCanCreateIssues = data.viewerCanCreateIssues,
            hasIssuesEnabled = data.hasIssuesEnabled,
            totalCount = data.issues.totalCount,
            items = data.issues.nodes.map(::toIssue),
            hasNextPage = data.issues.pageInfo.hasNextPage,
            endCursor = data.issues.pageInfo.endCursor,
        )
    }

    suspend fun getIssue(owner: String, name: String, number: Int, commentsAfter: String? = null): ApiResult<RepoIssueDetail> = safeCall {
        val query = """
            query RepoIssueDetail(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}after: String) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    id viewerPermission
                    issue(number: ${'$'}number) { ${issueFields(PageSize.ISSUE_COMMENTS, "${'$'}after")} }
                }
            }
        """.trimIndent()
        val data = api.graphQL<RepoIssueDetailQueryData>(query, buildMap {
            put("owner", JsonPrimitive(owner)); put("name", JsonPrimitive(name)); put("number", JsonPrimitive(number))
            commentsAfter?.let { put("after", JsonPrimitive(it)) }
        }).repository ?: error("Repository not found")
        val node = data.issue ?: error("Issue not found")
        val permission = parsePermission(data.viewerPermission)
        RepoIssueDetail(
            repositoryId = data.id,
            permission = permission,
            capabilities = permission.toCapabilities(),
            issue = toIssue(node),
            comments = toCommentPage(node.comments),
        )
    }

    suspend fun getLabels(owner: String, name: String): ApiResult<List<IssueLabel>> = cached(labelsCache, "$owner/$name") {
        val query = "query RepoLabels(${'$'}owner: String!, ${'$'}name: String!) { repository(owner: ${'$'}owner, name: ${'$'}name) { labels(first: 100) { nodes { id name color description } } } }"
        api.graphQL<RepoLabelsQueryData>(query, repoVariables(owner, name)).repository?.labels?.nodes.orEmpty().map(::toLabel)
    }

    suspend fun getMilestones(owner: String, name: String): ApiResult<List<IssueMilestone>> = cached(milestonesCache, "$owner/$name") {
        val query = "query RepoMilestones(${'$'}owner: String!, ${'$'}name: String!) { repository(owner: ${'$'}owner, name: ${'$'}name) { milestones(first: 100, states: [OPEN]) { nodes { id number title state dueOn } } } }"
        api.graphQL<RepoMilestonesQueryData>(query, repoVariables(owner, name)).repository?.milestones?.nodes.orEmpty().map(::toMilestone)
    }

    suspend fun getAssignableUsers(owner: String, name: String): ApiResult<List<SimpleUser>> = cached(assigneesCache, "$owner/$name") {
        val query = "query RepoAssignableUsers(${'$'}owner: String!, ${'$'}name: String!) { repository(owner: ${'$'}owner, name: ${'$'}name) { assignableUsers(first: 100) { nodes { id login name avatarUrl bio } } } }"
        api.graphQL<RepoAssignableUsersQueryData>(query, repoVariables(owner, name)).repository?.assignableUsers?.nodes.orEmpty().map(::toUser)
    }

    suspend fun getIssueTemplates(owner: String, name: String): ApiResult<IssueTemplateLoadResult> = safeCall {
        val contextQuery = """
            query RepoIssueFormContext(${'$'}owner: String!, ${'$'}name: String!) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    id viewerCanCreateIssues isBlankIssuesEnabled defaultBranchRef { name }
                }
            }
        """.trimIndent()
        val context = api.graphQL<RepoIssueFormContextQueryData>(contextQuery, repoVariables(owner, name)).repository
            ?: error("Repository not found")
        val defaultBranch = context.defaultBranchRef?.name
            ?: return@safeCall IssueTemplateLoadResult(context.isBlankIssuesEnabled, emptyList())
        val formsQuery = """
            query RepoIssueForms(${'$'}owner: String!, ${'$'}name: String!, ${'$'}expression: String!) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    object(expression: ${'$'}expression) {
                        ... on Tree {
                            entries {
                                name path type
                                object { ... on Blob { text isBinary isTruncated byteSize } }
                            }
                        }
                    }
                }
            }
        """.trimIndent()
        val variables = repoVariables(owner, name) + ("expression" to JsonPrimitive("$defaultBranch:.github/ISSUE_TEMPLATE"))
        val entries = api.graphQL<RepoIssueFormsQueryData>(formsQuery, variables).repository?.objectNode?.entries.orEmpty()
        val config = entries.firstOrNull { it.type.equals("blob", true) && it.name.equals("config.yml", true) }
        val blankIssuesEnabled = config?.objectNode?.usableText()?.let { source ->
            try { IssueFormYamlParser.parseBlankIssuesEnabled(source) } catch (_: Exception) { context.isBlankIssuesEnabled }
        } ?: context.isBlankIssuesEnabled
        var invalidTemplateCount = 0
        val templates = entries.mapNotNull { entry ->
            if (!entry.type.equals("blob", true) || !entry.name.endsWith(".yml", true) || entry.name.equals("config.yml", true)) return@mapNotNull null
            val source = entry.objectNode?.usableText()
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

    suspend fun createIssue(input: CreateRepoIssueInput): ApiResult<RepoIssue> = safeCall {
        val mutation = """
            mutation CreateRepoIssue(${'$'}input: CreateIssueInput!) {
                createIssue(input: ${'$'}input) { issue { ${issueFields(0)} } }
            }
        """.trimIndent()
        val payload = api.graphQL<CreateIssueMutationData>(mutation, mapOf("input" to JsonObject(buildMap {
            put("repositoryId", JsonPrimitive(input.repositoryId)); put("title", JsonPrimitive(input.title)); put("body", JsonPrimitive(input.body))
            if (input.labelIds.isNotEmpty()) put("labelIds", JsonArray(input.labelIds.map(::JsonPrimitive)))
            if (input.assigneeIds.isNotEmpty()) put("assigneeIds", JsonArray(input.assigneeIds.map(::JsonPrimitive)))
            input.milestoneId?.let { put("milestoneId", JsonPrimitive(it)) }
        }))).createIssue?.issue ?: error("Issue was not created")
        toIssue(payload)
    }

    suspend fun updateIssue(input: UpdateRepoIssueInput): ApiResult<RepoIssue> = mutateIssue<UpdateIssueMutationData>(
        mutationName = "updateIssue", inputType = "UpdateIssueInput", response = { it.updateIssue?.issue },
        input = JsonObject(buildMap {
            put("id", JsonPrimitive(input.id)); put("title", JsonPrimitive(input.title)); put("body", JsonPrimitive(input.body))
            put("labelIds", JsonArray(input.labelIds.map(::JsonPrimitive))); put("assigneeIds", JsonArray(input.assigneeIds.map(::JsonPrimitive)))
            put("milestoneId", input.milestoneId?.let(::JsonPrimitive) ?: JsonNull)
        }),
    )

    suspend fun closeIssue(issueId: String, reason: IssueStateReason): ApiResult<RepoIssue> = mutateIssue<CloseIssueMutationData>(
        "closeIssue", "CloseIssueInput", { it.closeIssue?.issue }, JsonObject(mapOf("issueId" to JsonPrimitive(issueId), "stateReason" to JsonPrimitive(reason.name)))
    )

    suspend fun reopenIssue(issueId: String): ApiResult<RepoIssue> = mutateIssue<ReopenIssueMutationData>(
        "reopenIssue", "ReopenIssueInput", { it.reopenIssue?.issue }, JsonObject(mapOf("issueId" to JsonPrimitive(issueId)))
    )

    suspend fun deleteIssue(issueId: String): ApiResult<Unit> = simpleMutation<DeleteIssueMutationData>("deleteIssue", "DeleteIssueInput", JsonObject(mapOf("issueId" to JsonPrimitive(issueId))))

    suspend fun addComment(issueId: String, body: String): ApiResult<IssueComment> = safeCall {
        val mutation = "mutation AddIssueComment(${'$'}input: AddCommentInput!) { addComment(input: ${'$'}input) { commentEdge { node { ${commentFields()} } } } }"
        val node = api.graphQL<AddIssueCommentMutationData>(mutation, mapOf("input" to JsonObject(mapOf("subjectId" to JsonPrimitive(issueId), "body" to JsonPrimitive(body))))).addComment?.commentEdge?.node ?: error("Comment was not created")
        toComment(node)
    }

    suspend fun updateComment(commentId: String, body: String): ApiResult<IssueComment> = safeCall {
        val mutation = "mutation UpdateIssueComment(${'$'}input: UpdateIssueCommentInput!) { updateIssueComment(input: ${'$'}input) { issueComment { ${commentFields()} } } }"
        val node = api.graphQL<UpdateIssueCommentMutationData>(mutation, mapOf("input" to JsonObject(mapOf("id" to JsonPrimitive(commentId), "body" to JsonPrimitive(body))))).updateIssueComment?.issueComment ?: error("Comment was not updated")
        toComment(node)
    }

    suspend fun deleteComment(commentId: String): ApiResult<Unit> = simpleMutation<DeleteIssueCommentMutationData>("deleteIssueComment", "DeleteIssueCommentInput", JsonObject(mapOf("id" to JsonPrimitive(commentId))))

    suspend fun updateSubscription(issueId: String, subscribed: Boolean): ApiResult<String?> = safeCall {
        val mutation = "mutation UpdateIssueSubscription(${'$'}input: UpdateSubscriptionInput!) { updateSubscription(input: ${'$'}input) { subscribable { viewerSubscription } } }"
        api.graphQL<UpdateIssueSubscriptionMutationData>(mutation, mapOf("input" to JsonObject(mapOf(
            "subscribableId" to JsonPrimitive(issueId), "state" to JsonPrimitive(if (subscribed) "SUBSCRIBED" else "UNSUBSCRIBED")
        )))).updateSubscription?.subscribable?.viewerSubscription
    }

    private suspend inline fun <reified D> mutateIssue(mutationName: String, inputType: String, crossinline response: (D) -> RepoIssueNode?, input: JsonObject): ApiResult<RepoIssue> = safeCall {
        val mutation = "mutation RepoIssueMutation(${'$'}input: $inputType!) { $mutationName(input: ${'$'}input) { issue { ${issueFields(0)} } } }"
        toIssue(response(api.graphQL<D>(mutation, mapOf("input" to input))) ?: error("Issue mutation failed"))
    }

    private suspend inline fun <reified D> simpleMutation(name: String, inputType: String, input: JsonObject): ApiResult<Unit> = safeCall {
        val mutation = "mutation RepoIssueMutation(${'$'}input: $inputType!) { $name(input: ${'$'}input) { clientMutationId } }"
        with(api.graphQL<D>(mutation, mapOf("input" to input))) { }
    }

    private suspend fun <T : Any> cached(cache: MemoryCache<String, T>, key: String, loader: suspend () -> T): ApiResult<T> {
        cache.get(key)?.let { return ApiResult.Success(it) }
        return safeCall { loader().also { cache.set(key, it) } }
    }

    private fun repoVariables(owner: String, name: String) = mapOf("owner" to JsonPrimitive(owner), "name" to JsonPrimitive(name))

    private fun RepoIssueFormBlobNode.usableText(): String? = text?.takeIf {
        !isBinary && !isTruncated && byteSize in 1..MAX_ISSUE_FORM_BYTES
    }

    private fun states(value: RepoIssueStateFilter) = when (value) { RepoIssueStateFilter.OPEN -> listOf("OPEN"); RepoIssueStateFilter.CLOSED -> listOf("CLOSED"); RepoIssueStateFilter.ALL -> listOf("OPEN", "CLOSED") }

    private fun issueOrder(value: RepoIssueSort): JsonObject {
        val field = when (value) { RepoIssueSort.CREATED_ASC, RepoIssueSort.CREATED_DESC -> "CREATED_AT"; RepoIssueSort.COMMENTS_ASC, RepoIssueSort.COMMENTS_DESC -> "COMMENTS"; else -> "UPDATED_AT" }
        val direction = if (value.name.endsWith("ASC")) "ASC" else "DESC"
        return JsonObject(mapOf("field" to JsonPrimitive(field), "direction" to JsonPrimitive(direction)))
    }

    private fun issueFilters(filter: RepoIssueFilter): JsonObject? {
        val values = buildMap<String, kotlinx.serialization.json.JsonElement> {
            if (filter.labels.isNotEmpty()) put("labels", JsonArray(filter.labels.map(::JsonPrimitive)))
            when (val value = filter.milestone) { RepoMilestoneFilter.ALL -> Unit; RepoMilestoneFilter.NONE -> put("milestone", JsonNull); is RepoMilestoneFilter.Number -> put("milestoneNumber", JsonPrimitive(value.value.toString())) }
            when (val value = filter.assignee) { RepoAssigneeFilter.ALL -> Unit; RepoAssigneeFilter.ANY -> put("assignee", JsonPrimitive("*")); RepoAssigneeFilter.NONE -> put("assignee", JsonNull); is RepoAssigneeFilter.Login -> put("assignee", JsonPrimitive(value.value)) }
            when (val value = filter.author) { RepoAuthorFilter.ALL -> Unit; is RepoAuthorFilter.Login -> put("createdBy", JsonPrimitive(value.value)) }
            if (filter.mentioned) put("mentioned", JsonPrimitive("*"))
            if (filter.subscribed) put("viewerSubscribed", JsonPrimitive(true))
            filter.updatedSince?.let { put("since", JsonPrimitive(it.toString())) }
        }
        return values.takeIf { it.isNotEmpty() }?.let(::JsonObject)
    }

    companion object {
        private const val MAX_ISSUE_FORM_BYTES = 256 * 1024
        private fun issueFields(commentsFirst: Int, commentsAfter: String? = null): String = """
            id url number title body bodyHTML state stateReason author { login avatarUrl } authorAssociation createdAt updatedAt locked
            comments(first: $commentsFirst${commentsAfter?.let { ", after: $it" }.orEmpty()}) { totalCount nodes { ${commentFields()} } pageInfo { hasNextPage endCursor } }
            labels(first: 20) { nodes { id name color description } }
            assignees(first: 20) { nodes { id login name avatarUrl bio } }
            milestone { id number title state dueOn }
            viewerCanClose viewerCanDelete viewerCanLabel viewerCanSetMilestone viewerCanUpdate viewerCanSubscribe viewerCanReopen viewerSubscription
        """.trimIndent()

        private fun commentFields() = "id url author { login avatarUrl } authorAssociation body bodyHTML createdAt updatedAt viewerDidAuthor viewerCanUpdate viewerCanDelete viewerCanReact"
        private fun parsePermission(value: String?) = value?.let { runCatching { RepoPermission.valueOf(it) }.getOrDefault(RepoPermission.NONE) } ?: RepoPermission.NONE
        private fun toUser(node: SimpleUserNode) = SimpleUser(node.login, node.name, node.avatarUrl, node.bio, node.id)
        private fun toLabel(node: RepoIssueLabelNode) = IssueLabel(node.id, node.name, node.color, node.description)
        private fun toMilestone(node: RepoIssueMilestoneNode) = IssueMilestone(node.id, node.number, node.title, node.state, node.dueOn)
        private fun association(value: String) = runCatching { CommentAuthorAssociation.valueOf(value) }.getOrDefault(CommentAuthorAssociation.NONE)
        private fun toComment(node: RepoIssueCommentNode) = IssueComment(node.id, node.author?.let(::toUser), node.body, node.bodyHTML, node.createdAt, node.updatedAt, node.viewerDidAuthor, node.viewerCanUpdate, node.viewerCanDelete, node.viewerCanReact, node.url, association(node.authorAssociation))
        private fun toCommentPage(node: RepoIssueCommentConnectionNode) = IssueCommentPage(node.nodes.map(::toComment), node.pageInfo?.hasNextPage ?: false, node.pageInfo?.endCursor)
        private fun toIssue(node: RepoIssueNode) = RepoIssue(
            id = node.id, number = node.number, title = node.title, body = node.body, bodyHtml = node.bodyHTML,
            state = IssueState.valueOf(node.state), stateReason = node.stateReason?.let { runCatching { IssueStateReason.valueOf(it) }.getOrNull() },
            author = node.author?.let(::toUser), createdAt = node.createdAt, updatedAt = node.updatedAt, commentCount = node.comments.totalCount,
            labels = node.labels?.nodes.orEmpty().map(::toLabel), assignees = node.assignees?.nodes.orEmpty().map(::toUser), milestone = node.milestone?.let(::toMilestone),
            locked = node.locked, viewerCanClose = node.viewerCanClose, viewerCanDelete = node.viewerCanDelete, viewerCanLabel = node.viewerCanLabel,
            viewerCanSetMilestone = node.viewerCanSetMilestone, viewerCanUpdate = node.viewerCanUpdate, viewerCanSubscribe = node.viewerCanSubscribe,
            viewerCanReopen = node.viewerCanReopen, viewerSubscription = node.viewerSubscription, url = node.url,
            authorAssociation = association(node.authorAssociation),
        )
    }
}
