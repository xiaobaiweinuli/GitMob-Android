package com.gitmob.app.data.repository

import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepoPullRequestRepository @Inject constructor(
    private val api: GHApiClient,
) {
    suspend fun getPullRequests(
        owner: String,
        name: String,
        filter: RepoPullRequestFilter,
        after: String? = null,
    ): ApiResult<RepoPullRequestPage> = safeCall {
        val query = """
            query RepoPullRequests(${'$'}owner: String!, ${'$'}name: String!, ${'$'}after: String, ${'$'}states: [PullRequestState!], ${'$'}orderBy: IssueOrder!, ${'$'}labels: [String!], ${'$'}baseRefName: String, ${'$'}headRefName: String) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    id viewerPermission hasPullRequestsEnabled pullRequestCreationPolicy
                    defaultBranchRef { name }
                    mergeCommitAllowed squashMergeAllowed rebaseMergeAllowed
                    pullRequests(
                        first: ${PageSize.REPO_ISSUES}, after: ${'$'}after,
                        states: ${'$'}states, labels: ${'$'}labels,
                        baseRefName: ${'$'}baseRefName, headRefName: ${'$'}headRefName,
                        orderBy: ${'$'}orderBy
                    ) {
                        totalCount nodes { ${pullRequestFields()} }
                        pageInfo { hasNextPage endCursor }
                    }
                }
            }
        """.trimIndent()
        val repository = api.graphQL<PullRequestsQueryData>(query, buildMap {
            put("owner", JsonPrimitive(owner))
            put("name", JsonPrimitive(name))
            after?.let { put("after", JsonPrimitive(it)) }
            put("states", JsonArray(states(filter.state).map(::JsonPrimitive)))
            put("orderBy", order(filter.sort))
            if (filter.labels.isNotEmpty()) put("labels", JsonArray(filter.labels.map(::JsonPrimitive)))
            filter.baseRefName?.takeIf(String::isNotBlank)?.let { put("baseRefName", JsonPrimitive(it)) }
            filter.headRefName?.takeIf(String::isNotBlank)?.let { put("headRefName", JsonPrimitive(it)) }
        }).repository ?: error("Repository not found")
        val permission = parsePermission(repository.viewerPermission)
        RepoPullRequestPage(
            repositoryId = repository.id,
            permission = permission,
            capabilities = permission.toCapabilities(),
            hasPullRequestsEnabled = repository.hasPullRequestsEnabled,
            creationPolicy = parseCreationPolicy(repository.pullRequestCreationPolicy),
            defaultBranchName = repository.defaultBranchRef?.name,
            allowedMergeMethods = repository.allowedMergeMethods(),
            totalCount = repository.pullRequests.totalCount,
            items = repository.pullRequests.nodes.map(::toPullRequest),
            hasNextPage = repository.pullRequests.pageInfo.hasNextPage,
            endCursor = repository.pullRequests.pageInfo.endCursor,
        )
    }

    suspend fun getPullRequest(
        owner: String,
        name: String,
        number: Int,
        commentsAfter: String? = null,
    ): ApiResult<RepoPullRequestDetail> = safeCall {
        val query = """
            query RepoPullRequestDetail(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}after: String) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    id viewerPermission mergeCommitAllowed squashMergeAllowed rebaseMergeAllowed
                    pullRequest(number: ${'$'}number) {
                        ${pullRequestFields()}
                        comments(first: ${PageSize.ISSUE_COMMENTS}, after: ${'$'}after) {
                            nodes { ${commentFields()} }
                            pageInfo { hasNextPage endCursor }
                        }
                        reviews(first: 30) { nodes { id url author { login avatarUrl } authorAssociation body bodyHTML state submittedAt viewerCanUpdate viewerCanDelete } }
                        reviewThreads(first: 50) {
                            nodes {
                                id path line isResolved isOutdated viewerCanReply viewerCanResolve viewerCanUnresolve
                                comments(first: 50) { nodes { ${reviewCommentFields()} } }
                            }
                        }
                        commits(first: 50) {
                            nodes { commit { oid messageHeadline committedDate author { user { login avatarUrl } } } }
                        }
                    }
                }
            }
        """.trimIndent()
        val repository = api.graphQL<PullRequestDetailQueryData>(query, buildMap {
            put("owner", JsonPrimitive(owner))
            put("name", JsonPrimitive(name))
            put("number", JsonPrimitive(number))
            commentsAfter?.let { put("after", JsonPrimitive(it)) }
        }).repository ?: error("Repository not found")
        val node = repository.pullRequest ?: error("Pull request not found")
        val files = api.get<List<RestPullRequestFile>>("/repos/$owner/$name/pulls/$number/files?per_page=100")
        val permission = parsePermission(repository.viewerPermission)
        RepoPullRequestDetail(
            repositoryId = repository.id,
            permission = permission,
            capabilities = permission.toCapabilities(),
            allowedMergeMethods = repository.allowedMergeMethods(),
            pullRequest = toPullRequest(node),
            comments = node.comments?.nodes.orEmpty().map(::toComment),
            reviews = node.reviews?.nodes.orEmpty().map(::toReview),
            reviewThreads = node.reviewThreads?.nodes.orEmpty().map(::toThread),
            commits = node.commits?.nodes.orEmpty().map {
                RepoPullRequestCommit(
                    oid = it.commit.oid,
                    headline = it.commit.messageHeadline,
                    committedAt = it.commit.committedDate,
                    authorLogin = it.commit.author?.user?.login,
                    authorAvatarUrl = it.commit.author?.user?.avatarUrl,
                )
            },
            files = files.map {
                RepoPullRequestFile(it.filename, it.previousFilename, it.status, it.additions, it.deletions, it.changes, it.patch, it.blobUrl)
            },
            commentsHasNextPage = node.comments?.pageInfo?.hasNextPage ?: false,
            commentsEndCursor = node.comments?.pageInfo?.endCursor,
        )
    }

    suspend fun getCreateMetadata(owner: String, name: String): ApiResult<RepoPullRequestCreateMetadata> = safeCall {
        val query = """
            query RepoPullRequestMetadata(${'$'}owner: String!, ${'$'}name: String!) {
                viewer { login }
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    id defaultBranchRef { name }
                    refs(refPrefix: "refs/heads/", first: 100) { nodes { id name target { oid } } }
                    forks(first: 20, ownerAffiliations: [OWNER]) {
                        nodes { id name owner { login } refs(refPrefix: "refs/heads/", first: 100) { nodes { id name target { oid } } } }
                    }
                    labels(first: 100) { nodes { id name color description } }
                    milestones(first: 100, states: [OPEN]) { nodes { id number title state dueOn } }
                    assignableUsers(first: 100) { nodes { id login name avatarUrl bio } }
                    mentionableUsers(first: 100) { nodes { id login name avatarUrl bio } }
                }
            }
        """.trimIndent()
        val data = api.graphQL<PullRequestMetadataQueryData>(query, repoVariables(owner, name))
        val repository = data.repository ?: error("Repository not found")
        val repositories = buildList {
            add(repository.toHeadRepository(owner, name))
            repository.forks?.nodes.orEmpty().forEach { fork ->
                add(
                    RepoPullRequestHeadRepository(
                        id = fork.id,
                        owner = fork.owner.login,
                        name = fork.name,
                        branches = fork.refs?.nodes.orEmpty().map(::toBranch),
                    ),
                )
            }
        }
        RepoPullRequestCreateMetadata(
            repositoryId = repository.id,
            viewerLogin = data.viewer.login,
            defaultBranchName = repository.defaultBranchRef?.name,
            repositories = repositories,
            labels = repository.labels?.nodes.orEmpty().map(::toLabel),
            milestones = repository.milestones?.nodes.orEmpty().map(::toMilestone),
            assignees = repository.assignableUsers?.nodes.orEmpty().map(::toUser),
            reviewers = repository.mentionableUsers?.nodes.orEmpty().map(::toUser),
        )
    }

    suspend fun createPullRequest(input: CreateRepoPullRequestInput): ApiResult<RepoPullRequest> = mutatePullRequest(
        "createPullRequest",
        "CreatePullRequestInput",
        JsonObject(buildMap {
            put("repositoryId", JsonPrimitive(input.repositoryId))
            put("baseRefName", JsonPrimitive(input.baseRefName))
            put("headRefName", JsonPrimitive(input.headRefName))
            input.headRepositoryId?.let { put("headRepositoryId", JsonPrimitive(it)) }
            put("title", JsonPrimitive(input.title))
            put("body", JsonPrimitive(input.body))
            put("draft", JsonPrimitive(input.draft))
        }),
    ) { it.createPullRequest?.pullRequest }

    suspend fun updatePullRequest(input: UpdateRepoPullRequestInput): ApiResult<RepoPullRequest> = mutatePullRequest(
        "updatePullRequest",
        "UpdatePullRequestInput",
        JsonObject(buildMap {
            put("pullRequestId", JsonPrimitive(input.id))
            put("title", JsonPrimitive(input.title))
            put("body", JsonPrimitive(input.body))
            put("baseRefName", JsonPrimitive(input.baseRefName))
            put("labelIds", JsonArray(input.labelIds.map(::JsonPrimitive)))
            put("assigneeIds", JsonArray(input.assigneeIds.map(::JsonPrimitive)))
            put("milestoneId", input.milestoneId?.let(::JsonPrimitive) ?: JsonNull)
        }),
    ) { it.updatePullRequest?.pullRequest }

    suspend fun closePullRequest(id: String) = simplePullRequestMutation(
        "closePullRequest", "ClosePullRequestInput", "pullRequestId", id,
    ) { it.closePullRequest?.pullRequest }

    suspend fun reopenPullRequest(id: String) = simplePullRequestMutation(
        "reopenPullRequest", "ReopenPullRequestInput", "pullRequestId", id,
    ) { it.reopenPullRequest?.pullRequest }

    suspend fun convertToDraft(id: String) = simplePullRequestMutation(
        "convertPullRequestToDraft", "ConvertPullRequestToDraftInput", "pullRequestId", id,
    ) { it.convertPullRequestToDraft?.pullRequest }

    suspend fun markReadyForReview(id: String) = simplePullRequestMutation(
        "markPullRequestReadyForReview", "MarkPullRequestReadyForReviewInput", "pullRequestId", id,
    ) { it.markPullRequestReadyForReview?.pullRequest }

    suspend fun updateBranch(id: String): ApiResult<RepoPullRequest> = mutatePullRequest(
        "updatePullRequestBranch", "UpdatePullRequestBranchInput",
        JsonObject(mapOf("pullRequestId" to JsonPrimitive(id))),
    ) { it.updatePullRequestBranch?.pullRequest }

    suspend fun mergePullRequest(
        id: String,
        method: RepoPullRequestMergeMethod,
        headline: String?,
        body: String?,
        expectedHeadOid: String? = null,
    ): ApiResult<RepoPullRequest> = mutatePullRequest(
        "mergePullRequest", "MergePullRequestInput",
        JsonObject(buildMap {
            put("pullRequestId", JsonPrimitive(id))
            put("mergeMethod", JsonPrimitive(method.name))
            headline?.takeIf(String::isNotBlank)?.let { put("commitHeadline", JsonPrimitive(it)) }
            body?.takeIf(String::isNotBlank)?.let { put("commitBody", JsonPrimitive(it)) }
            expectedHeadOid?.let { put("expectedHeadOid", JsonPrimitive(it)) }
        }),
    ) { it.mergePullRequest?.pullRequest }

    suspend fun setAutoMerge(id: String, enabled: Boolean, method: RepoPullRequestMergeMethod): ApiResult<RepoPullRequest> {
        val mutation = if (enabled) "enablePullRequestAutoMerge" else "disablePullRequestAutoMerge"
        val inputType = if (enabled) "EnablePullRequestAutoMergeInput" else "DisablePullRequestAutoMergeInput"
        return mutatePullRequest(
            mutation,
            inputType,
            JsonObject(buildMap {
                put("pullRequestId", JsonPrimitive(id))
                if (enabled) put("mergeMethod", JsonPrimitive(method.name))
            }),
        ) { if (enabled) it.enablePullRequestAutoMerge?.pullRequest else it.disablePullRequestAutoMerge?.pullRequest }
    }

    suspend fun addComment(id: String, body: String): ApiResult<RepoPullRequestComment> = safeCall {
        val mutation = "mutation AddPullRequestComment(${'$'}input: AddCommentInput!) { addComment(input: ${'$'}input) { commentEdge { node { ${commentFields()} } } } }"
        val node = api.graphQL<AddPullRequestCommentData>(mutation, mapOf("input" to JsonObject(mapOf(
            "subjectId" to JsonPrimitive(id), "body" to JsonPrimitive(body),
        )))).addComment?.commentEdge?.node ?: error("Comment was not created")
        toComment(node)
    }

    suspend fun updateComment(id: String, body: String): ApiResult<RepoPullRequestComment> = safeCall {
        val mutation = "mutation UpdatePullRequestComment(${'$'}input: UpdateIssueCommentInput!) { updateIssueComment(input: ${'$'}input) { issueComment { ${commentFields()} } } }"
        val node = api.graphQL<UpdatePullRequestCommentData>(mutation, mapOf("input" to JsonObject(mapOf(
            "id" to JsonPrimitive(id), "body" to JsonPrimitive(body),
        )))).updateIssueComment?.issueComment ?: error("Comment was not updated")
        toComment(node)
    }

    suspend fun deleteComment(id: String): ApiResult<Unit> = safeCall {
        val mutation = "mutation DeletePullRequestComment(${'$'}input: DeleteIssueCommentInput!) { deleteIssueComment(input: ${'$'}input) { clientMutationId } }"
        with(api.graphQL<DeletePullRequestCommentData>(mutation, mapOf("input" to JsonObject(mapOf("id" to JsonPrimitive(id)))))) { }
    }

    suspend fun submitReview(id: String, event: RepoPullRequestReviewEvent, body: String): ApiResult<RepoPullRequestReview> = safeCall {
        val mutation = "mutation AddPullRequestReview(${'$'}input: AddPullRequestReviewInput!) { addPullRequestReview(input: ${'$'}input) { pullRequestReview { id author { login avatarUrl } bodyHTML state submittedAt viewerCanUpdate viewerCanDelete } } }"
        val review = api.graphQL<AddPullRequestReviewData>(mutation, mapOf("input" to JsonObject(mapOf(
            "pullRequestId" to JsonPrimitive(id), "event" to JsonPrimitive(event.name), "body" to JsonPrimitive(body),
        )))).addPullRequestReview?.pullRequestReview ?: error("Review was not created")
        toReview(review)
    }

    suspend fun requestReviews(id: String, logins: List<String>): ApiResult<Unit> = safeCall {
        val mutation = "mutation RequestPullRequestReviews(${'$'}input: RequestReviewsByLoginInput!) { requestReviewsByLogin(input: ${'$'}input) { clientMutationId } }"
        with(api.graphQL<RequestReviewsData>(mutation, mapOf("input" to JsonObject(mapOf(
            "pullRequestId" to JsonPrimitive(id), "userLogins" to JsonArray(logins.map(::JsonPrimitive)),
        ))))) { }
    }

    suspend fun replyToThread(threadId: String, body: String): ApiResult<RepoPullRequestReviewComment> = safeCall {
        val mutation = "mutation ReplyPullRequestThread(${'$'}input: AddPullRequestReviewThreadReplyInput!) { addPullRequestReviewThreadReply(input: ${'$'}input) { comment { ${reviewCommentFields()} } } }"
        val comment = api.graphQL<ReplyThreadData>(mutation, mapOf("input" to JsonObject(mapOf(
            "pullRequestReviewThreadId" to JsonPrimitive(threadId), "body" to JsonPrimitive(body),
        )))).addPullRequestReviewThreadReply?.comment ?: error("Reply was not created")
        toReviewComment(comment)
    }

    suspend fun setThreadResolved(threadId: String, resolved: Boolean): ApiResult<Unit> = safeCall {
        val name = if (resolved) "resolveReviewThread" else "unresolveReviewThread"
        val type = if (resolved) "ResolveReviewThreadInput" else "UnresolveReviewThreadInput"
        val mutation = "mutation UpdateReviewThread(${'$'}input: $type!) { $name(input: ${'$'}input) { thread { id isResolved } } }"
        with(api.graphQL<ReviewThreadMutationData>(mutation, mapOf("input" to JsonObject(mapOf("threadId" to JsonPrimitive(threadId)))))) { }
    }

    suspend fun updateSubscription(id: String, subscribed: Boolean): ApiResult<String?> = safeCall {
        val mutation = "mutation UpdatePullRequestSubscription(${'$'}input: UpdateSubscriptionInput!) { updateSubscription(input: ${'$'}input) { subscribable { viewerSubscription } } }"
        api.graphQL<UpdatePullRequestSubscriptionData>(mutation, mapOf("input" to JsonObject(mapOf(
            "subscribableId" to JsonPrimitive(id),
            "state" to JsonPrimitive(if (subscribed) "SUBSCRIBED" else "UNSUBSCRIBED"),
        )))).updateSubscription?.subscribable?.viewerSubscription
    }

    private suspend fun simplePullRequestMutation(
        mutationName: String,
        inputType: String,
        key: String,
        id: String,
        selector: (PullRequestMutationsData) -> PullRequestNode?,
    ): ApiResult<RepoPullRequest> = mutatePullRequest(
        mutationName, inputType, JsonObject(mapOf(key to JsonPrimitive(id))), selector,
    )

    private suspend fun mutatePullRequest(
        mutationName: String,
        inputType: String,
        input: JsonObject,
        selector: (PullRequestMutationsData) -> PullRequestNode?,
    ): ApiResult<RepoPullRequest> = safeCall {
        val mutation = "mutation RepoPullRequestMutation(${'$'}input: $inputType!) { $mutationName(input: ${'$'}input) { pullRequest { ${pullRequestFields()} } } }"
        val data = api.graphQL<PullRequestMutationsData>(mutation, mapOf("input" to input))
        toPullRequest(selector(data) ?: error("Pull request mutation failed"))
    }

    private fun repoVariables(owner: String, name: String) = mapOf("owner" to JsonPrimitive(owner), "name" to JsonPrimitive(name))

    private fun parsePermission(value: String?) = value?.let { runCatching { RepoPermission.valueOf(it) }.getOrNull() } ?: RepoPermission.NONE
    private fun parseCreationPolicy(value: String?) = value?.let { runCatching { PullRequestCreationPolicy.valueOf(it) }.getOrNull() } ?: PullRequestCreationPolicy.UNKNOWN

    private fun states(value: RepoPullRequestStateFilter) = when (value) {
        RepoPullRequestStateFilter.OPEN -> listOf("OPEN")
        RepoPullRequestStateFilter.CLOSED -> listOf("CLOSED")
        RepoPullRequestStateFilter.MERGED -> listOf("MERGED")
        RepoPullRequestStateFilter.ALL -> listOf("OPEN", "CLOSED", "MERGED")
    }

    private fun order(value: RepoPullRequestSort): JsonObject {
        val field = when (value) {
            RepoPullRequestSort.CREATED_ASC, RepoPullRequestSort.CREATED_DESC -> "CREATED_AT"
            RepoPullRequestSort.COMMENTS_ASC, RepoPullRequestSort.COMMENTS_DESC -> "COMMENTS"
            else -> "UPDATED_AT"
        }
        return JsonObject(mapOf(
            "field" to JsonPrimitive(field),
            "direction" to JsonPrimitive(if (value.name.endsWith("ASC")) "ASC" else "DESC"),
        ))
    }

    private fun toPullRequest(node: PullRequestNode) = RepoPullRequest(
        id = node.id,
        number = node.number,
        title = node.title,
        body = node.body,
        bodyHtml = node.bodyHTML,
        state = RepoPullRequestState.valueOf(node.state),
        isDraft = node.isDraft,
        locked = node.locked,
        author = node.author?.toDomain(),
        createdAt = node.createdAt,
        updatedAt = node.updatedAt,
        baseRefName = node.baseRefName,
        headRefName = node.headRefName,
        headRepositoryNameWithOwner = node.headRepository?.nameWithOwner,
        commentCount = node.comments?.totalCount ?: node.totalCommentsCount ?: 0,
        additions = node.additions,
        deletions = node.deletions,
        changedFiles = node.changedFiles,
        labels = node.labels?.nodes.orEmpty().map(::toLabel),
        assignees = node.assignees?.nodes.orEmpty().map(::toUser),
        milestone = node.milestone?.let(::toMilestone),
        mergeable = node.mergeable,
        mergeStateStatus = node.mergeStateStatus,
        reviewDecision = node.reviewDecision,
        statusCheckState = node.statusCheckRollup?.state,
        viewerCanClose = node.viewerCanClose,
        viewerCanReopen = node.viewerCanReopen,
        viewerCanUpdate = node.viewerCanUpdate,
        viewerCanLabel = node.viewerCanLabel,
        viewerCanAssign = node.viewerCanAssign,
        viewerCanSubscribe = node.viewerCanSubscribe,
        viewerCanEnableAutoMerge = node.viewerCanEnableAutoMerge,
        viewerCanDisableAutoMerge = node.viewerCanDisableAutoMerge,
        viewerCanUpdateBranch = node.viewerCanUpdateBranch,
        viewerSubscription = node.viewerSubscription,
        autoMergeEnabled = node.autoMergeRequest != null,
        url = node.url,
        authorAssociation = association(node.authorAssociation),
    )

    private fun toComment(node: PullRequestCommentNode) = RepoPullRequestComment(
        node.id, node.author?.toDomain(), node.body, node.bodyHTML, node.createdAt, node.updatedAt,
        node.viewerCanUpdate, node.viewerCanDelete, node.viewerCanReact, node.url, association(node.authorAssociation),
    )

    private fun toReview(node: PullRequestReviewNode) = RepoPullRequestReview(
        id = node.id, author = node.author?.toDomain(), body = node.body, bodyHtml = node.bodyHTML,
        state = node.state, submittedAt = node.submittedAt, viewerCanUpdate = node.viewerCanUpdate,
        viewerCanDelete = node.viewerCanDelete, url = node.url,
        authorAssociation = association(node.authorAssociation),
    )

    private fun toReviewComment(node: PullRequestReviewCommentNode) = RepoPullRequestReviewComment(
        node.id, node.author?.toDomain(), node.body, node.bodyHTML, node.path, node.line, node.originalLine,
        node.outdated, node.createdAt, node.viewerCanUpdate, node.viewerCanDelete,
        node.url, association(node.authorAssociation),
    )

    private fun toThread(node: PullRequestReviewThreadNode) = RepoPullRequestReviewThread(
        node.id, node.path, node.line, node.isResolved, node.isOutdated, node.viewerCanReply,
        node.viewerCanResolve, node.viewerCanUnresolve, node.comments.nodes.map(::toReviewComment),
    )

    private fun ActorNode.toDomain() = SimpleUser(login, null, avatarUrl, null)
    private fun association(value: String) = runCatching { CommentAuthorAssociation.valueOf(value) }.getOrDefault(CommentAuthorAssociation.NONE)
    private fun toUser(node: UserNode) = SimpleUser(node.login, node.name, node.avatarUrl, node.bio, node.id)
    private fun toLabel(node: LabelNode) = IssueLabel(node.id, node.name, node.color, node.description)
    private fun toMilestone(node: MilestoneNode) = IssueMilestone(node.id, node.number, node.title, node.state, node.dueOn)
    private fun toBranch(node: RefNode) = RepoBranch(node.id, node.name, false, node.target?.oid)

    private fun PullRequestMetadataRepositoryNode.toHeadRepository(owner: String, name: String) = RepoPullRequestHeadRepository(
        id = id, owner = owner, name = name, branches = refs?.nodes.orEmpty().map(::toBranch),
    )

    private fun PullRequestsRepositoryNode.allowedMergeMethods() = buildSet {
        if (mergeCommitAllowed) add(RepoPullRequestMergeMethod.MERGE)
        if (squashMergeAllowed) add(RepoPullRequestMergeMethod.SQUASH)
        if (rebaseMergeAllowed) add(RepoPullRequestMergeMethod.REBASE)
    }

    private fun PullRequestDetailRepositoryNode.allowedMergeMethods() = buildSet {
        if (mergeCommitAllowed) add(RepoPullRequestMergeMethod.MERGE)
        if (squashMergeAllowed) add(RepoPullRequestMergeMethod.SQUASH)
        if (rebaseMergeAllowed) add(RepoPullRequestMergeMethod.REBASE)
    }

    companion object {
        private fun pullRequestFields() = """
            id url number title body bodyHTML state isDraft locked createdAt updatedAt
            author { login avatarUrl } authorAssociation
            baseRefName headRefName headRepository { nameWithOwner }
            totalCommentsCount additions deletions changedFiles mergeable mergeStateStatus reviewDecision
            labels(first: 20) { nodes { id name color description } }
            assignees(first: 20) { nodes { id login name avatarUrl bio } }
            milestone { id number title state dueOn }
            statusCheckRollup { state }
            autoMergeRequest { enabledAt }
            viewerCanClose viewerCanReopen viewerCanUpdate viewerCanLabel viewerCanAssign
            viewerCanSubscribe viewerCanEnableAutoMerge viewerCanDisableAutoMerge viewerCanUpdateBranch viewerSubscription
        """.trimIndent()

        private fun commentFields() = """
            id url author { login avatarUrl } authorAssociation body bodyHTML createdAt updatedAt
            viewerCanUpdate viewerCanDelete viewerCanReact
        """.trimIndent()

        private fun reviewCommentFields() = """
            id url author { login avatarUrl } authorAssociation body bodyHTML path line originalLine outdated createdAt
            viewerCanUpdate viewerCanDelete
        """.trimIndent()
    }
}

@Serializable private data class PageInfoNode(val hasNextPage: Boolean = false, val endCursor: String? = null)
@Serializable private data class ActorNode(val login: String, val avatarUrl: String? = null)
@Serializable private data class UserNode(val id: String, val login: String, val name: String? = null, val avatarUrl: String? = null, val bio: String? = null)
@Serializable private data class LabelNode(val id: String, val name: String, val color: String, val description: String? = null)
@Serializable private data class MilestoneNode(val id: String, val number: Int, val title: String, val state: String, val dueOn: String? = null)
@Serializable private data class RefTargetNode(val oid: String? = null)
@Serializable private data class RefNode(val id: String, val name: String, val target: RefTargetNode? = null)
@Serializable private data class RefConnectionNode(val nodes: List<RefNode> = emptyList())
@Serializable private data class DefaultBranchNode(val name: String)
@Serializable private data class LabelConnectionNode(val nodes: List<LabelNode> = emptyList())
@Serializable private data class UserConnectionNode(val nodes: List<UserNode> = emptyList())
@Serializable private data class MilestoneConnectionNode(val nodes: List<MilestoneNode> = emptyList())
@Serializable private data class HeadRepositoryNode(val nameWithOwner: String? = null)
@Serializable private data class StatusCheckRollupNode(val state: String? = null)
@Serializable private data class AutoMergeRequestNode(val enabledAt: String? = null)

@Serializable private data class PullRequestCommentNode(
    val id: String,
    val url: String = "",
    val author: ActorNode? = null,
    val authorAssociation: String = "NONE",
    val body: String = "",
    val bodyHTML: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val viewerCanUpdate: Boolean = false,
    val viewerCanDelete: Boolean = false,
    val viewerCanReact: Boolean = false,
)
@Serializable private data class PullRequestCommentConnectionNode(
    val totalCount: Int = 0,
    val nodes: List<PullRequestCommentNode> = emptyList(),
    val pageInfo: PageInfoNode? = null,
)
@Serializable private data class PullRequestReviewNode(
    val id: String,
    val url: String = "",
    val author: ActorNode? = null,
    val authorAssociation: String = "NONE",
    val body: String = "",
    val bodyHTML: String = "",
    val state: String = "COMMENTED",
    val submittedAt: String? = null,
    val viewerCanUpdate: Boolean = false,
    val viewerCanDelete: Boolean = false,
)
@Serializable private data class PullRequestReviewConnectionNode(val nodes: List<PullRequestReviewNode> = emptyList())
@Serializable private data class PullRequestReviewCommentNode(
    val id: String,
    val url: String = "",
    val author: ActorNode? = null,
    val authorAssociation: String = "NONE",
    val body: String = "",
    val bodyHTML: String = "",
    val path: String = "",
    val line: Int? = null,
    val originalLine: Int? = null,
    val outdated: Boolean = false,
    val createdAt: String = "",
    val viewerCanUpdate: Boolean = false,
    val viewerCanDelete: Boolean = false,
)
@Serializable private data class PullRequestReviewCommentConnectionNode(val nodes: List<PullRequestReviewCommentNode> = emptyList())
@Serializable private data class PullRequestReviewThreadNode(
    val id: String,
    val path: String,
    val line: Int? = null,
    val isResolved: Boolean = false,
    val isOutdated: Boolean = false,
    val viewerCanReply: Boolean = false,
    val viewerCanResolve: Boolean = false,
    val viewerCanUnresolve: Boolean = false,
    val comments: PullRequestReviewCommentConnectionNode = PullRequestReviewCommentConnectionNode(),
)
@Serializable private data class PullRequestReviewThreadConnectionNode(val nodes: List<PullRequestReviewThreadNode> = emptyList())
@Serializable private data class CommitAuthorNode(val user: ActorNode? = null)
@Serializable private data class CommitNode(val oid: String, val messageHeadline: String, val committedDate: String, val author: CommitAuthorNode? = null)
@Serializable private data class PullRequestCommitNode(val commit: CommitNode)
@Serializable private data class PullRequestCommitConnectionNode(val nodes: List<PullRequestCommitNode> = emptyList())

@Serializable private data class PullRequestNode(
    val id: String,
    val url: String = "",
    val number: Int,
    val title: String,
    val body: String = "",
    val bodyHTML: String = "",
    val state: String,
    val isDraft: Boolean = false,
    val locked: Boolean = false,
    val author: ActorNode? = null,
    val authorAssociation: String = "NONE",
    val createdAt: String,
    val updatedAt: String,
    val baseRefName: String,
    val headRefName: String,
    val headRepository: HeadRepositoryNode? = null,
    val totalCommentsCount: Int? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changedFiles: Int = 0,
    val labels: LabelConnectionNode? = null,
    val assignees: UserConnectionNode? = null,
    val milestone: MilestoneNode? = null,
    val mergeable: String = "UNKNOWN",
    val mergeStateStatus: String = "UNKNOWN",
    val reviewDecision: String? = null,
    val statusCheckRollup: StatusCheckRollupNode? = null,
    val autoMergeRequest: AutoMergeRequestNode? = null,
    val viewerCanClose: Boolean = false,
    val viewerCanReopen: Boolean = false,
    val viewerCanUpdate: Boolean = false,
    val viewerCanLabel: Boolean = false,
    val viewerCanAssign: Boolean = false,
    val viewerCanSubscribe: Boolean = false,
    val viewerCanEnableAutoMerge: Boolean = false,
    val viewerCanDisableAutoMerge: Boolean = false,
    val viewerCanUpdateBranch: Boolean = false,
    val viewerSubscription: String? = null,
    val comments: PullRequestCommentConnectionNode? = null,
    val reviews: PullRequestReviewConnectionNode? = null,
    val reviewThreads: PullRequestReviewThreadConnectionNode? = null,
    val commits: PullRequestCommitConnectionNode? = null,
)

@Serializable private data class PullRequestConnectionNode(val totalCount: Int = 0, val nodes: List<PullRequestNode> = emptyList(), val pageInfo: PageInfoNode)
@Serializable private data class PullRequestsQueryData(val repository: PullRequestsRepositoryNode? = null)
@Serializable private data class PullRequestsRepositoryNode(
    val id: String,
    val viewerPermission: String? = null,
    val hasPullRequestsEnabled: Boolean = false,
    val pullRequestCreationPolicy: String? = null,
    val defaultBranchRef: DefaultBranchNode? = null,
    val mergeCommitAllowed: Boolean = false,
    val squashMergeAllowed: Boolean = false,
    val rebaseMergeAllowed: Boolean = false,
    val pullRequests: PullRequestConnectionNode,
)

@Serializable private data class PullRequestDetailQueryData(val repository: PullRequestDetailRepositoryNode? = null)
@Serializable private data class PullRequestDetailRepositoryNode(
    val id: String,
    val viewerPermission: String? = null,
    val mergeCommitAllowed: Boolean = false,
    val squashMergeAllowed: Boolean = false,
    val rebaseMergeAllowed: Boolean = false,
    val pullRequest: PullRequestNode? = null,
)

@Serializable private data class ViewerNode(val login: String)
@Serializable private data class OwnerNode(val login: String)
@Serializable private data class ForkNode(val id: String, val name: String, val owner: OwnerNode, val refs: RefConnectionNode? = null)
@Serializable private data class ForkConnectionNode(val nodes: List<ForkNode> = emptyList())
@Serializable private data class PullRequestMetadataQueryData(val viewer: ViewerNode, val repository: PullRequestMetadataRepositoryNode? = null)
@Serializable private data class PullRequestMetadataRepositoryNode(
    val id: String,
    val defaultBranchRef: DefaultBranchNode? = null,
    val refs: RefConnectionNode? = null,
    val forks: ForkConnectionNode? = null,
    val labels: LabelConnectionNode? = null,
    val milestones: MilestoneConnectionNode? = null,
    val assignableUsers: UserConnectionNode? = null,
    val mentionableUsers: UserConnectionNode? = null,
)

@Serializable private data class PullRequestPayload(val pullRequest: PullRequestNode? = null)
@Serializable private data class PullRequestMutationsData(
    val createPullRequest: PullRequestPayload? = null,
    val updatePullRequest: PullRequestPayload? = null,
    val closePullRequest: PullRequestPayload? = null,
    val reopenPullRequest: PullRequestPayload? = null,
    val convertPullRequestToDraft: PullRequestPayload? = null,
    val markPullRequestReadyForReview: PullRequestPayload? = null,
    val updatePullRequestBranch: PullRequestPayload? = null,
    val mergePullRequest: PullRequestPayload? = null,
    val enablePullRequestAutoMerge: PullRequestPayload? = null,
    val disablePullRequestAutoMerge: PullRequestPayload? = null,
)

@Serializable private data class CommentEdgeNode(val node: PullRequestCommentNode? = null)
@Serializable private data class AddCommentPayload(val commentEdge: CommentEdgeNode? = null)
@Serializable private data class AddPullRequestCommentData(val addComment: AddCommentPayload? = null)
@Serializable private data class UpdateCommentPayload(val issueComment: PullRequestCommentNode? = null)
@Serializable private data class UpdatePullRequestCommentData(val updateIssueComment: UpdateCommentPayload? = null)
@Serializable private data class DeletePullRequestCommentData(val deleteIssueComment: ClientMutationPayload? = null)
@Serializable private data class ClientMutationPayload(val clientMutationId: String? = null)
@Serializable private data class AddReviewPayload(val pullRequestReview: PullRequestReviewNode? = null)
@Serializable private data class AddPullRequestReviewData(val addPullRequestReview: AddReviewPayload? = null)
@Serializable private data class RequestReviewsData(val requestReviewsByLogin: ClientMutationPayload? = null)
@Serializable private data class ReplyThreadPayload(val comment: PullRequestReviewCommentNode? = null)
@Serializable private data class ReplyThreadData(val addPullRequestReviewThreadReply: ReplyThreadPayload? = null)
@Serializable private data class ReviewThreadMutationData(val resolveReviewThread: ReviewThreadPayload? = null, val unresolveReviewThread: ReviewThreadPayload? = null)
@Serializable private data class ReviewThreadPayload(val thread: ReviewThreadStateNode? = null)
@Serializable private data class ReviewThreadStateNode(val id: String, val isResolved: Boolean)
@Serializable private data class SubscriptionNode(val viewerSubscription: String? = null)
@Serializable private data class SubscriptionPayload(val subscribable: SubscriptionNode? = null)
@Serializable private data class UpdatePullRequestSubscriptionData(val updateSubscription: SubscriptionPayload? = null)

@Serializable private data class RestPullRequestFile(
    val filename: String,
    val status: String,
    @SerialName("previous_filename") val previousFilename: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    val patch: String? = null,
    @SerialName("blob_url") val blobUrl: String? = null,
)
