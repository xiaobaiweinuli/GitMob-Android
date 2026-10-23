package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.api.Optional
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.executeAsApiResult
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.*
import com.gitmob.app.graphql.AddPullRequestCommentMutation
import com.gitmob.app.graphql.AddPullRequestReviewMutation
import com.gitmob.app.graphql.CloseRepoPullRequestMutation
import com.gitmob.app.graphql.ConvertPullRequestToDraftMutation
import com.gitmob.app.graphql.CreateRepoPullRequestMutation
import com.gitmob.app.graphql.DeletePullRequestCommentMutation
import com.gitmob.app.graphql.DisablePullRequestAutoMergeMutation
import com.gitmob.app.graphql.EnablePullRequestAutoMergeMutation
import com.gitmob.app.graphql.MarkPullRequestReadyForReviewMutation
import com.gitmob.app.graphql.MergeRepoPullRequestMutation
import com.gitmob.app.graphql.PullRequestBranchesQuery
import com.gitmob.app.graphql.ReopenRepoPullRequestMutation
import com.gitmob.app.graphql.RepoAssignableUsersQuery
import com.gitmob.app.graphql.RepoLabelsQuery
import com.gitmob.app.graphql.RepoMentionableUsersQuery
import com.gitmob.app.graphql.RepoMilestonesQuery
import com.gitmob.app.graphql.RepoPullRequestDetailQuery
import com.gitmob.app.graphql.RepoPullRequestMetadataQuery
import com.gitmob.app.graphql.RepoPullRequestsQuery
import com.gitmob.app.graphql.ReplyPullRequestThreadMutation
import com.gitmob.app.graphql.RequestPullRequestReviewsMutation
import com.gitmob.app.graphql.ResolveReviewThreadMutation
import com.gitmob.app.graphql.UnresolveReviewThreadMutation
import com.gitmob.app.graphql.UpdatePullRequestCommentMutation
import com.gitmob.app.graphql.UpdatePullRequestSubscriptionMutation
import com.gitmob.app.graphql.UpdateRepoPullRequestBranchMutation
import com.gitmob.app.graphql.UpdateRepoPullRequestMutation
import com.gitmob.app.graphql.fragment.PullRequestCommentFields
import com.gitmob.app.graphql.fragment.PullRequestFields
import com.gitmob.app.graphql.fragment.PullRequestReviewCommentFields
import com.gitmob.app.graphql.fragment.PullRequestReviewFields
import com.gitmob.app.graphql.type.AddCommentInput
import com.gitmob.app.graphql.type.AddPullRequestReviewInput
import com.gitmob.app.graphql.type.AddPullRequestReviewThreadReplyInput
import com.gitmob.app.graphql.type.ClosePullRequestInput
import com.gitmob.app.graphql.type.ConvertPullRequestToDraftInput
import com.gitmob.app.graphql.type.CreatePullRequestInput
import com.gitmob.app.graphql.type.DeleteIssueCommentInput
import com.gitmob.app.graphql.type.DisablePullRequestAutoMergeInput
import com.gitmob.app.graphql.type.EnablePullRequestAutoMergeInput
import com.gitmob.app.graphql.type.IssueOrder
import com.gitmob.app.graphql.type.IssueOrderField
import com.gitmob.app.graphql.type.MarkPullRequestReadyForReviewInput
import com.gitmob.app.graphql.type.MergePullRequestInput
import com.gitmob.app.graphql.type.OrderDirection
import com.gitmob.app.graphql.type.PullRequestMergeMethod
import com.gitmob.app.graphql.type.ReopenPullRequestInput
import com.gitmob.app.graphql.type.RequestReviewsInput
import com.gitmob.app.graphql.type.ResolveReviewThreadInput
import com.gitmob.app.graphql.type.SubscriptionState
import com.gitmob.app.graphql.type.UnresolveReviewThreadInput
import com.gitmob.app.graphql.type.UpdateIssueCommentInput
import com.gitmob.app.graphql.type.UpdatePullRequestBranchInput
import com.gitmob.app.graphql.type.UpdatePullRequestInput
import com.gitmob.app.graphql.type.UpdateSubscriptionInput
import com.apollographql.apollo.ApolloCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Singleton
class RepoPullRequestRepository @Inject constructor(
    private val api: GHApiClient,
    private val apolloClient: ApolloClient,
) {
    suspend fun getBranches(owner: String, name: String): ApiResult<List<RepoBranch>> =
        when (val result = apolloClient.query(
            PullRequestBranchesQuery(owner, name, Optional.Absent),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val repository = result.data.repository ?: error("Repository not found")
                val defaultName = repository.defaultBranchRef?.name
                repository.refs?.nodes.orEmpty().filterNotNull()
                    .map { RepoBranch(it.id, it.name, it.name == defaultName, it.target?.oid) }
                    .sortedByDescending(RepoBranch::isDefault)
            }
        }

    /** 存在性探测：REST 实现（base/head 过滤），只需要第一条匹配的开放 PR。 */
    suspend fun findOpenPullRequest(
        baseOwner: String,
        baseRepository: String,
        baseRef: String,
        headOwner: String,
        headRef: String,
    ): ApiResult<ExistingRepoPullRequest?> = safeCall {
        val base = encode(baseRef)
        val head = encode("$headOwner:$headRef")
        api.get<List<RestOpenPullRequest>>(
            "/repos/$baseOwner/$baseRepository/pulls?state=open&base=$base&head=$head&per_page=1",
        ).firstOrNull()?.let {
            ExistingRepoPullRequest(
                number = it.number,
                title = it.title,
                url = it.htmlUrl,
                state = when (it.state.uppercase()) {
                    "CLOSED" -> RepoPullRequestState.CLOSED
                    else -> RepoPullRequestState.OPEN
                },
                isDraft = it.draft,
                author = it.user?.let { user -> SimpleUser(user.login, null, user.avatarUrl, null, user.nodeId) },
                updatedAt = it.updatedAt,
                baseRefName = it.base?.ref.orEmpty(),
                headRefName = it.head?.ref.orEmpty(),
                commentCount = it.comments,
                labels = it.labels.map { label -> IssueLabel(label.nodeId, label.name, label.color, label.description) },
            )
        }
    }

    suspend fun getPullRequests(
        owner: String,
        name: String,
        filter: RepoPullRequestFilter,
        after: String? = null,
    ): ApiResult<RepoPullRequestPage> =
        when (val result = apolloClient.query(
            RepoPullRequestsQuery(
                owner = owner,
                name = name,
                after = Optional.presentIfNotNull(after),
                states = Optional.present(states(filter.state)),
                orderBy = order(filter.sort),
                labels = Optional.presentIfNotNull(filter.labels.takeIf { it.isNotEmpty() }?.toList()),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val repository = result.data.repository ?: error("Repository not found")
                val permission = RepoIssueRepository.parsePermission(repository.viewerPermission?.rawValue)
                RepoPullRequestPage(
                    repositoryId = repository.id,
                    permission = permission,
                    capabilities = permission.toCapabilities(),
                    hasPullRequestsEnabled = repository.hasPullRequestsEnabled,
                    creationPolicy = parseCreationPolicy(repository.pullRequestCreationPolicy?.rawValue),
                    defaultBranchName = repository.defaultBranchRef?.name,
                    allowedMergeMethods = allowedMergeMethods(
                        repository.mergeCommitAllowed, repository.squashMergeAllowed, repository.rebaseMergeAllowed,
                    ),
                    totalCount = repository.pullRequests.totalCount,
                    items = repository.pullRequests.nodes.orEmpty().filterNotNull().map { toPullRequest(it.pullRequestFields) },
                    hasNextPage = repository.pullRequests.pageInfo.hasNextPage,
                    endCursor = repository.pullRequests.pageInfo.endCursor,
                )
            }
        }

    suspend fun getPullRequest(
        owner: String,
        name: String,
        number: Int,
        commentsAfter: String? = null,
    ): ApiResult<RepoPullRequestDetail> {
        val detail = when (val result = apolloClient.query(
            RepoPullRequestDetailQuery(owner, name, number, Optional.presentIfNotNull(commentsAfter)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.data
        }
        return safeCall {
            val repository = detail.repository ?: error("Repository not found")
            val node = repository.pullRequest ?: error("Pull request not found")
            // 文件列表走 REST（GraphQL 没有等价的 changedFiles 明细端点）
            val files = api.get<List<RestPullRequestFile>>("/repos/$owner/$name/pulls/$number/files?per_page=${PageSize.PULL_REQUEST_FILES}")
            val permission = RepoIssueRepository.parsePermission(repository.viewerPermission?.rawValue)
            RepoPullRequestDetail(
                repositoryId = repository.id,
                permission = permission,
                capabilities = permission.toCapabilities(),
                allowedMergeMethods = allowedMergeMethods(
                    repository.mergeCommitAllowed, repository.squashMergeAllowed, repository.rebaseMergeAllowed,
                ),
                pullRequest = toPullRequest(node.pullRequestFields),
                comments = node.comments.nodes.orEmpty().filterNotNull().map { toComment(it.pullRequestCommentFields) },
                reviews = node.reviews?.nodes.orEmpty().filterNotNull().map { toReview(it.pullRequestReviewFields) },
                reviewThreads = node.reviewThreads.nodes.orEmpty().filterNotNull().map { thread ->
                    RepoPullRequestReviewThread(
                        thread.id, thread.path, thread.line, thread.isResolved, thread.isOutdated,
                        thread.viewerCanReply, thread.viewerCanResolve, thread.viewerCanUnresolve,
                        thread.comments.nodes.orEmpty().filterNotNull().map { toReviewComment(it.pullRequestReviewCommentFields) },
                    )
                },
                commits = node.commits.nodes.orEmpty().filterNotNull().map {
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
                commentsHasNextPage = node.comments.pageInfo.hasNextPage,
                commentsEndCursor = node.comments.pageInfo.endCursor,
            )
        }
    }

    suspend fun getCreateMetadata(owner: String, name: String): ApiResult<RepoPullRequestCreateMetadata> {
        val data = when (val result = apolloClient.query(
            RepoPullRequestMetadataQuery(owner, name),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.data
        }
        return safeCall {
            val repository = data.repository ?: error("Repository not found")
            val branches = loadAllRefs(owner, name, repository.refs)
            val labels = loadAllLabels(owner, name, repository.labels?.nodes.orEmpty().filterNotNull().map {
                IssueLabel(it.id, it.name, it.color, it.description)
            }, repository.labels?.pageInfo?.hasNextPage == true, repository.labels?.pageInfo?.endCursor)
            val milestones = loadAllMilestones(owner, name, repository.milestones?.nodes.orEmpty().filterNotNull().map {
                IssueMilestone(it.id, it.number, it.title, it.state.rawValue, it.dueOn)
            }, repository.milestones?.pageInfo?.hasNextPage == true, repository.milestones?.pageInfo?.endCursor)
            val assignees = loadAllAssignable(owner, name, repository.assignableUsers.nodes.orEmpty().filterNotNull().map {
                SimpleUser(it.login, it.name, it.avatarUrl, it.bio, it.id)
            }, repository.assignableUsers.pageInfo.hasNextPage, repository.assignableUsers.pageInfo.endCursor)
            val reviewers = loadAllMentionable(owner, name, repository.mentionableUsers.nodes.orEmpty().filterNotNull().map {
                SimpleUser(it.login, it.name, it.avatarUrl, it.bio, it.id)
            }, repository.mentionableUsers.pageInfo.hasNextPage, repository.mentionableUsers.pageInfo.endCursor)
            RepoPullRequestCreateMetadata(
                repositoryId = repository.id,
                viewerLogin = data.viewer.login,
                defaultBranchName = repository.defaultBranchRef?.name,
                repositories = listOf(
                    RepoPullRequestHeadRepository(id = repository.id, owner = owner, name = name, branches = branches),
                ),
                labels = labels,
                milestones = milestones,
                assignees = assignees,
                reviewers = reviewers,
            )
        }
    }

    /** 仓库全部标签，供列表筛选的「标签」多选胶囊使用。与 Issue/Discussion 域共用 RepoLabelsQuery。 */
    suspend fun getLabels(owner: String, name: String): ApiResult<List<IssueLabel>> {
        val labels = mutableListOf<IssueLabel>()
        var after: String? = null
        while (true) {
            when (val result = apolloClient.query(
                RepoLabelsQuery(owner, name, Optional.presentIfNotNull(after)),
            ).executeAsApiResult()) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> {
                    val page = result.data.repository?.labels ?: break
                    labels += page.nodes.orEmpty().filterNotNull().map { IssueLabel(it.id, it.name, it.color, it.description) }
                    after = page.pageInfo.endCursor.takeIf { page.pageInfo.hasNextPage } ?: break
                }
            }
        }
        return ApiResult.Success(labels)
    }

    /** 元数据续页加载失败时抛出，由外层 safeCall 统一转为 Failure（与旧实现一致）。 */
    private suspend fun <D : Operation.Data> ApolloCall<D>.executeOrThrow(): D =
        when (val result = executeAsApiResult()) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> error("Metadata page load failed: ${result.error}")
        }

    private suspend fun loadAllRefs(owner: String, name: String, firstPage: RepoPullRequestMetadataQuery.Refs?): List<RepoBranch> {
        val seen = linkedMapOf<String, RepoBranch>()
        firstPage?.nodes.orEmpty().filterNotNull().forEach { seen[it.id] = RepoBranch(it.id, it.name, false, it.target?.oid) }
        var after = firstPage?.pageInfo?.endCursor.takeIf { firstPage?.pageInfo?.hasNextPage == true }
        while (after != null) {
            val refs = apolloClient.query(PullRequestBranchesQuery(owner, name, Optional.present(after)))
                .executeOrThrow().repository?.refs ?: break
            refs.nodes.orEmpty().filterNotNull().forEach { seen[it.id] = RepoBranch(it.id, it.name, false, it.target?.oid) }
            after = refs.pageInfo.endCursor.takeIf { refs.pageInfo.hasNextPage }
        }
        return seen.values.toList()
    }

    private suspend fun loadAllLabels(
        owner: String,
        name: String,
        initial: List<IssueLabel>,
        hasNext: Boolean,
        cursor: String?,
    ): List<IssueLabel> {
        val nodes = initial.toMutableList()
        var after = cursor.takeIf { hasNext }
        while (true) {
            val page = apolloClient.query(RepoLabelsQuery(owner, name, Optional.presentIfNotNull(after)))
                .executeOrThrow().repository?.labels ?: break
            nodes += page.nodes.orEmpty().filterNotNull().map { IssueLabel(it.id, it.name, it.color, it.description) }
            after = page.pageInfo.endCursor.takeIf { page.pageInfo.hasNextPage } ?: break
        }
        return nodes.distinctBy(IssueLabel::id)
    }

    private suspend fun loadAllMilestones(
        owner: String,
        name: String,
        initial: List<IssueMilestone>,
        hasNext: Boolean,
        cursor: String?,
    ): List<IssueMilestone> {
        val nodes = initial.toMutableList()
        var after = cursor.takeIf { hasNext }
        while (true) {
            val page = apolloClient.query(RepoMilestonesQuery(owner, name, Optional.presentIfNotNull(after)))
                .executeOrThrow().repository?.milestones ?: break
            nodes += page.nodes.orEmpty().filterNotNull().map { IssueMilestone(it.id, it.number, it.title, it.state.rawValue, it.dueOn) }
            after = page.pageInfo.endCursor.takeIf { page.pageInfo.hasNextPage } ?: break
        }
        return nodes.distinctBy(IssueMilestone::id)
    }

    private suspend fun loadAllAssignable(
        owner: String,
        name: String,
        initial: List<SimpleUser>,
        hasNext: Boolean,
        cursor: String?,
    ): List<SimpleUser> {
        val nodes = initial.toMutableList()
        var after = cursor.takeIf { hasNext }
        while (true) {
            val page = apolloClient.query(RepoAssignableUsersQuery(owner, name, Optional.presentIfNotNull(after)))
                .executeOrThrow().repository?.assignableUsers ?: break
            nodes += page.nodes.orEmpty().filterNotNull().map { SimpleUser(it.login, it.name, it.avatarUrl, it.bio, it.id) }
            after = page.pageInfo.endCursor.takeIf { page.pageInfo.hasNextPage } ?: break
        }
        return nodes.distinctBy(SimpleUser::id)
    }

    private suspend fun loadAllMentionable(
        owner: String,
        name: String,
        initial: List<SimpleUser>,
        hasNext: Boolean,
        cursor: String?,
    ): List<SimpleUser> {
        val nodes = initial.toMutableList()
        var after = cursor.takeIf { hasNext }
        while (true) {
            val page = apolloClient.query(RepoMentionableUsersQuery(owner, name, Optional.presentIfNotNull(after)))
                .executeOrThrow().repository?.mentionableUsers ?: break
            nodes += page.nodes.orEmpty().filterNotNull().map { SimpleUser(it.login, it.name, it.avatarUrl, it.bio, it.id) }
            after = page.pageInfo.endCursor.takeIf { page.pageInfo.hasNextPage } ?: break
        }
        return nodes.distinctBy(SimpleUser::id)
    }

    suspend fun createPullRequest(input: CreateRepoPullRequestInput): ApiResult<RepoPullRequest> =
        when (val result = apolloClient.mutation(
            CreateRepoPullRequestMutation(
                CreatePullRequestInput(
                    repositoryId = input.repositoryId,
                    baseRefName = input.baseRefName,
                    // 跨仓库 head 需要 owner:branch 前缀
                    headRefName = input.headOwner?.takeIf { input.headRepositoryId != null }
                        ?.let { "$it:${input.headRefName}" }
                        ?: input.headRefName,
                    headRepositoryId = Optional.presentIfNotNull(input.headRepositoryId),
                    title = input.title,
                    body = Optional.present(input.body),
                    draft = Optional.present(input.draft),
                ),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                toPullRequest(result.data.createPullRequest?.pullRequest?.pullRequestFields ?: error("Pull request mutation failed"))
            }
        }

    suspend fun updatePullRequest(input: UpdateRepoPullRequestInput): ApiResult<RepoPullRequest> =
        when (val result = apolloClient.mutation(
            UpdateRepoPullRequestMutation(
                UpdatePullRequestInput(
                    pullRequestId = input.id,
                    title = Optional.present(input.title),
                    body = Optional.present(input.body),
                    baseRefName = Optional.present(input.baseRefName),
                    // 与旧实现一致：labelIds/assigneeIds 永远显式发送（空数组 = 清空），milestoneId null = 清除
                    labelIds = Optional.present(input.labelIds),
                    assigneeIds = Optional.present(input.assigneeIds),
                    milestoneId = Optional.present(input.milestoneId),
                ),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                toPullRequest(result.data.updatePullRequest?.pullRequest?.pullRequestFields ?: error("Pull request mutation failed"))
            }
        }

    suspend fun closePullRequest(id: String): ApiResult<RepoPullRequest> =
        simpleMutation(CloseRepoPullRequestMutation(ClosePullRequestInput(pullRequestId = id))) { it.closePullRequest?.pullRequest?.pullRequestFields }

    suspend fun reopenPullRequest(id: String): ApiResult<RepoPullRequest> =
        simpleMutation(ReopenRepoPullRequestMutation(ReopenPullRequestInput(pullRequestId = id))) { it.reopenPullRequest?.pullRequest?.pullRequestFields }

    suspend fun convertToDraft(id: String): ApiResult<RepoPullRequest> =
        simpleMutation(ConvertPullRequestToDraftMutation(ConvertPullRequestToDraftInput(pullRequestId = id))) { it.convertPullRequestToDraft?.pullRequest?.pullRequestFields }

    suspend fun markReadyForReview(id: String): ApiResult<RepoPullRequest> =
        simpleMutation(MarkPullRequestReadyForReviewMutation(MarkPullRequestReadyForReviewInput(pullRequestId = id))) { it.markPullRequestReadyForReview?.pullRequest?.pullRequestFields }

    suspend fun updateBranch(id: String): ApiResult<RepoPullRequest> =
        simpleMutation(UpdateRepoPullRequestBranchMutation(UpdatePullRequestBranchInput(pullRequestId = id))) { it.updatePullRequestBranch?.pullRequest?.pullRequestFields }

    suspend fun mergePullRequest(
        id: String,
        method: RepoPullRequestMergeMethod,
        headline: String?,
        body: String?,
        expectedHeadOid: String? = null,
    ): ApiResult<RepoPullRequest> =
        simpleMutation(
            MergeRepoPullRequestMutation(
                MergePullRequestInput(
                    pullRequestId = id,
                    mergeMethod = Optional.present(PullRequestMergeMethod.safeValueOf(method.name)),
                    commitHeadline = Optional.presentIfNotNull(headline?.takeIf(String::isNotBlank)),
                    commitBody = Optional.presentIfNotNull(body?.takeIf(String::isNotBlank)),
                    expectedHeadOid = Optional.presentIfNotNull(expectedHeadOid),
                ),
            ),
        ) { it.mergePullRequest?.pullRequest?.pullRequestFields }

    suspend fun setAutoMerge(id: String, enabled: Boolean, method: RepoPullRequestMergeMethod): ApiResult<RepoPullRequest> =
        if (enabled) {
            simpleMutation(
                EnablePullRequestAutoMergeMutation(
                    EnablePullRequestAutoMergeInput(
                        pullRequestId = id,
                        mergeMethod = Optional.present(PullRequestMergeMethod.safeValueOf(method.name)),
                    ),
                ),
            ) { it.enablePullRequestAutoMerge?.pullRequest?.pullRequestFields }
        } else {
            simpleMutation(
                DisablePullRequestAutoMergeMutation(DisablePullRequestAutoMergeInput(pullRequestId = id)),
            ) { it.disablePullRequestAutoMerge?.pullRequest?.pullRequestFields }
        }

    private suspend fun <D : Mutation.Data, M : Mutation<D>> simpleMutation(
        mutation: M,
        selector: (D) -> PullRequestFields?,
    ): ApiResult<RepoPullRequest> =
        when (val result = apolloClient.mutation(mutation).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                toPullRequest(selector(result.data) ?: error("Pull request mutation failed"))
            }
        }

    suspend fun addComment(id: String, body: String): ApiResult<RepoPullRequestComment> =
        when (val result = apolloClient.mutation(
            AddPullRequestCommentMutation(AddCommentInput(subjectId = id, body = body)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                toComment(result.data.addComment?.commentEdge?.node?.pullRequestCommentFields ?: error("Comment was not created"))
            }
        }

    suspend fun updateComment(id: String, body: String): ApiResult<RepoPullRequestComment> =
        when (val result = apolloClient.mutation(
            UpdatePullRequestCommentMutation(UpdateIssueCommentInput(id = id, body = body)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                toComment(result.data.updateIssueComment?.issueComment?.pullRequestCommentFields ?: error("Comment was not updated"))
            }
        }

    suspend fun deleteComment(id: String): ApiResult<Unit> =
        when (val result = apolloClient.mutation(
            DeletePullRequestCommentMutation(DeleteIssueCommentInput(id = id)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall { }
        }

    suspend fun submitReview(id: String, event: RepoPullRequestReviewEvent, body: String): ApiResult<RepoPullRequestReview> =
        when (val result = apolloClient.mutation(
            AddPullRequestReviewMutation(
                AddPullRequestReviewInput(
                    pullRequestId = id,
                    event = Optional.present(com.gitmob.app.graphql.type.PullRequestReviewEvent.safeValueOf(event.name)),
                    body = Optional.present(body),
                ),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                toReview(result.data.addPullRequestReview?.pullRequestReview?.pullRequestReviewFields ?: error("Review was not created"))
            }
        }

    suspend fun requestReviews(id: String, userIds: List<String>): ApiResult<Unit> =
        when (val result = apolloClient.mutation(
            RequestPullRequestReviewsMutation(
                RequestReviewsInput(
                    pullRequestId = id,
                    userIds = Optional.present(userIds),
                    botIds = Optional.present(emptyList()),
                    teamIds = Optional.present(emptyList()),
                    union = Optional.present(false),
                ),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall { }
        }

    suspend fun replyToThread(threadId: String, body: String): ApiResult<RepoPullRequestReviewComment> =
        when (val result = apolloClient.mutation(
            ReplyPullRequestThreadMutation(
                AddPullRequestReviewThreadReplyInput(pullRequestReviewThreadId = threadId, body = body),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                toReviewComment(result.data.addPullRequestReviewThreadReply?.comment?.pullRequestReviewCommentFields ?: error("Reply was not created"))
            }
        }

    suspend fun setThreadResolved(threadId: String, resolved: Boolean): ApiResult<Unit> =
        if (resolved) {
            when (val result = apolloClient.mutation(
                ResolveReviewThreadMutation(ResolveReviewThreadInput(threadId = threadId)),
            ).executeAsApiResult()) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> safeCall { }
            }
        } else {
            when (val result = apolloClient.mutation(
                UnresolveReviewThreadMutation(UnresolveReviewThreadInput(threadId = threadId)),
            ).executeAsApiResult()) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> safeCall { }
            }
        }

    suspend fun updateSubscription(id: String, subscribed: Boolean): ApiResult<String?> =
        when (val result = apolloClient.mutation(
            UpdatePullRequestSubscriptionMutation(
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

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
    private fun parseCreationPolicy(value: String?) = value?.let { runCatching { PullRequestCreationPolicy.valueOf(it) }.getOrNull() } ?: PullRequestCreationPolicy.UNKNOWN

    private fun states(value: RepoPullRequestStateFilter) = when (value) {
        RepoPullRequestStateFilter.OPEN -> listOf(com.gitmob.app.graphql.type.PullRequestState.OPEN)
        RepoPullRequestStateFilter.CLOSED -> listOf(com.gitmob.app.graphql.type.PullRequestState.CLOSED)
        RepoPullRequestStateFilter.MERGED -> listOf(com.gitmob.app.graphql.type.PullRequestState.MERGED)
        RepoPullRequestStateFilter.ALL -> listOf(
            com.gitmob.app.graphql.type.PullRequestState.OPEN,
            com.gitmob.app.graphql.type.PullRequestState.CLOSED,
            com.gitmob.app.graphql.type.PullRequestState.MERGED,
        )
    }

    private fun order(value: RepoPullRequestSort): IssueOrder {
        val field = when (value) {
            RepoPullRequestSort.CREATED_ASC, RepoPullRequestSort.CREATED_DESC -> IssueOrderField.CREATED_AT
            RepoPullRequestSort.COMMENTS_ASC, RepoPullRequestSort.COMMENTS_DESC -> IssueOrderField.COMMENTS
            else -> IssueOrderField.UPDATED_AT
        }
        return IssueOrder(field = field, direction = if (value.name.endsWith("ASC")) OrderDirection.ASC else OrderDirection.DESC)
    }

    private fun allowedMergeMethods(mergeCommit: Boolean, squash: Boolean, rebase: Boolean) = buildSet {
        if (mergeCommit) add(RepoPullRequestMergeMethod.MERGE)
        if (squash) add(RepoPullRequestMergeMethod.SQUASH)
        if (rebase) add(RepoPullRequestMergeMethod.REBASE)
    }

    private fun PullRequestFields.Author.toDomain() = SimpleUser(login, null, avatarUrl, null)
    private fun PullRequestFields.Editor.toDomain() = SimpleUser(login, null, avatarUrl, null)
    private fun PullRequestCommentFields.Author.toDomain() = SimpleUser(login, null, avatarUrl, null)
    private fun PullRequestCommentFields.Editor.toDomain() = SimpleUser(login, null, avatarUrl, null)
    private fun PullRequestReviewFields.Author.toDomain() = SimpleUser(login, null, avatarUrl, null)
    private fun PullRequestReviewFields.Editor.toDomain() = SimpleUser(login, null, avatarUrl, null)
    private fun PullRequestReviewCommentFields.Author.toDomain() = SimpleUser(login, null, avatarUrl, null)
    private fun PullRequestReviewCommentFields.Editor.toDomain() = SimpleUser(login, null, avatarUrl, null)

    private fun association(value: com.gitmob.app.graphql.type.CommentAuthorAssociation) =
        runCatching { CommentAuthorAssociation.valueOf(value.rawValue) }.getOrDefault(CommentAuthorAssociation.NONE)

    private fun toPullRequest(node: PullRequestFields) = RepoPullRequest(
        id = node.id,
        number = node.number,
        title = node.title,
        body = node.body,
        bodyHtml = node.bodyHTML,
        state = RepoPullRequestState.valueOf(node.state.rawValue),
        isDraft = node.isDraft,
        locked = node.locked,
        author = node.author?.toDomain(),
        createdAt = node.createdAt,
        updatedAt = node.updatedAt,
        baseRefName = node.baseRefName,
        headRefName = node.headRefName,
        headRepositoryNameWithOwner = node.headRepository?.nameWithOwner,
        commentCount = node.totalCommentsCount ?: 0,
        additions = node.additions,
        deletions = node.deletions,
        changedFiles = node.changedFiles,
        labels = node.labels?.nodes.orEmpty().filterNotNull().map { IssueLabel(it.id, it.name, it.color, it.description) },
        assignees = node.assignees.nodes.orEmpty().filterNotNull().map { SimpleUser(it.login, it.name, it.avatarUrl, it.bio, it.id) },
        milestone = node.milestone?.let { IssueMilestone(it.id, it.number, it.title, it.state.rawValue, it.dueOn) },
        mergeable = node.mergeable.rawValue,
        mergeStateStatus = node.mergeStateStatus.rawValue,
        reviewDecision = node.reviewDecision?.rawValue,
        statusCheckState = node.statusCheckRollup?.state?.rawValue,
        viewerCanClose = node.viewerCanClose,
        viewerCanReopen = node.viewerCanReopen,
        viewerCanUpdate = node.viewerCanUpdate,
        viewerCanLabel = node.viewerCanLabel,
        viewerCanAssign = node.viewerCanAssign,
        viewerCanSubscribe = node.viewerCanSubscribe,
        viewerCanEnableAutoMerge = node.viewerCanEnableAutoMerge,
        viewerCanDisableAutoMerge = node.viewerCanDisableAutoMerge,
        viewerCanUpdateBranch = node.viewerCanUpdateBranch,
        viewerSubscription = node.viewerSubscription?.rawValue,
        autoMergeEnabled = node.autoMergeRequest != null,
        url = node.url,
        authorAssociation = association(node.authorAssociation),
        editSummary = ConversationEditSummary(node.includesCreatedEdit, node.lastEditedAt, node.editor?.toDomain()),
    )

    private fun toComment(node: PullRequestCommentFields) = RepoPullRequestComment(
        node.id, node.author?.toDomain(), node.body, node.bodyHTML, node.createdAt, node.updatedAt,
        node.viewerCanUpdate, node.viewerCanDelete, node.viewerCanReact, node.url, association(node.authorAssociation),
        ConversationEditSummary(node.includesCreatedEdit, node.lastEditedAt, node.editor?.toDomain()),
    )

    private fun toReview(node: PullRequestReviewFields) = RepoPullRequestReview(
        id = node.id, author = node.author?.toDomain(), body = node.body, bodyHtml = node.bodyHTML,
        state = node.state.rawValue, submittedAt = node.submittedAt, viewerCanUpdate = node.viewerCanUpdate,
        viewerCanDelete = node.viewerCanDelete, url = node.url,
        authorAssociation = association(node.authorAssociation),
        editSummary = ConversationEditSummary(node.includesCreatedEdit, node.lastEditedAt, node.editor?.toDomain()),
    )

    private fun toReviewComment(node: PullRequestReviewCommentFields) = RepoPullRequestReviewComment(
        node.id, node.author?.toDomain(), node.body, node.bodyHTML, node.path, node.line, node.originalLine,
        node.outdated, node.createdAt, node.viewerCanUpdate, node.viewerCanDelete,
        node.url, association(node.authorAssociation),
        ConversationEditSummary(node.includesCreatedEdit, node.lastEditedAt, node.editor?.toDomain()),
    )
}

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

@Serializable private data class RestOpenPullRequest(
    val number: Int,
    val title: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val state: String = "open",
    val draft: Boolean = false,
    val user: RestPullRequestUser? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val base: RestPullRequestRef? = null,
    val head: RestPullRequestRef? = null,
    val comments: Int = 0,
    val labels: List<RestPullRequestLabel> = emptyList(),
)

@Serializable private data class RestPullRequestUser(
    val login: String = "",
    @SerialName("node_id") val nodeId: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable private data class RestPullRequestRef(
    val ref: String? = null,
)

@Serializable private data class RestPullRequestLabel(
    @SerialName("node_id") val nodeId: String = "",
    val name: String = "",
    val color: String = "",
    val description: String? = null,
)
