package com.gitmob.app.data.repository

import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.model.PagedWorkDiscussions
import com.gitmob.app.data.model.PagedWorkIssues
import com.gitmob.app.data.model.DiscussionStateReason
import com.gitmob.app.data.model.IssueState
import com.gitmob.app.data.model.IssueStateReason
import com.gitmob.app.data.model.PullRequestState
import com.gitmob.app.data.model.UserDiscussionAnswerFilter
import com.gitmob.app.data.model.UserDiscussionFilter
import com.gitmob.app.data.model.UserDiscussionRelationFilter
import com.gitmob.app.data.model.UserDiscussionSortFilter
import com.gitmob.app.data.model.UserDiscussionStateFilter
import com.gitmob.app.data.model.UserDiscussionVisibilityFilter
import com.gitmob.app.data.model.UserIssueFilter
import com.gitmob.app.data.model.UserIssueRelationFilter
import com.gitmob.app.data.model.UserIssueSortFilter
import com.gitmob.app.data.model.UserIssueStateFilter
import com.gitmob.app.data.model.UserIssueVisibilityFilter
import com.gitmob.app.data.model.UserPullRequestFilter
import com.gitmob.app.data.model.UserPullRequestRelationFilter
import com.gitmob.app.data.model.UserPullRequestSortFilter
import com.gitmob.app.data.model.UserPullRequestStateFilter
import com.gitmob.app.data.model.UserPullRequestVisibilityFilter
import com.gitmob.app.data.model.WorkDiscussionItem
import com.gitmob.app.data.model.WorkIssueItem
import com.gitmob.app.data.model.WorkSearchNode
import com.gitmob.app.data.model.WorkSearchQueryData
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

// Issue/PullRequest 共用字段；各类型独有的状态字段在对应 fragment 中显式查询。
private const val WORK_ISSUE_FIELDS = """
    __typename
    id
    number
    title
    updatedAt
    repository { name owner { login avatarUrl } }
"""

/**
 * "involves:@me" 跨仓库聚合——议题/拉取请求/讨论各自独立的入口列表，见
 * references/architecture.md 主页事务入口设计。用统一的 GraphQL search 字段，
 * type: ISSUE 同时覆盖 Issue 和 PullRequest（用 __typename 分流），
 * type: DISCUSSION 是完全独立的一次查询，没法和前者合并。
 */
@Singleton
class WorkRepository @Inject constructor(
    private val api: GHApiClient,
) {
    suspend fun getUserIssues(
        filter: UserIssueFilter = UserIssueFilter(),
        after: String? = null,
    ): ApiResult<PagedWorkIssues> = safeCall {
        val query = """
            query WorkIssues(${'$'}searchQuery: String!, ${'$'}after: String) {
                search(query: ${'$'}searchQuery, type: ISSUE, first: ${PageSize.WORK_ITEMS}, after: ${'$'}after) {
                    issueCount
                    nodes {
                        ... on Issue {
                            $WORK_ISSUE_FIELDS
                            state
                            stateReason
                            locked
                        }
                    }
                    pageInfo { hasNextPage endCursor }
                }
            }
        """.trimIndent()
        val conn = fetch(query, buildUserIssueSearchQuery(filter), after)
        PagedWorkIssues(
            totalCount = conn.issueCount,
            items = conn.nodes.map { it.toIssueDomain() },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }

    suspend fun getUserPullRequests(
        filter: UserPullRequestFilter = UserPullRequestFilter(),
        after: String? = null,
    ): ApiResult<PagedWorkIssues> = safeCall {
        val query = """
            query WorkPullRequests(${'$'}searchQuery: String!, ${'$'}after: String) {
                search(query: ${'$'}searchQuery, type: ISSUE, first: ${PageSize.WORK_ITEMS}, after: ${'$'}after) {
                    issueCount
                    nodes {
                        ... on PullRequest {
                            $WORK_ISSUE_FIELDS
                            state
                            isDraft
                            locked
                        }
                    }
                    pageInfo { hasNextPage endCursor }
                }
            }
        """.trimIndent()
        val conn = fetch(query, buildUserPullRequestSearchQuery(filter), after)
        PagedWorkIssues(
            totalCount = conn.issueCount,
            items = conn.nodes.map { it.toPullRequestDomain() },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }

    suspend fun getUserDiscussions(
        filter: UserDiscussionFilter = UserDiscussionFilter(),
        after: String? = null,
    ): ApiResult<PagedWorkDiscussions> = safeCall {
        val query = """
            query WorkDiscussions(${'$'}searchQuery: String!, ${'$'}after: String) {
                search(query: ${'$'}searchQuery, type: DISCUSSION, first: ${PageSize.WORK_ITEMS}, after: ${'$'}after) {
                    discussionCount
                    nodes {
                        ... on Discussion {
                            __typename id number title updatedAt
                            stateReason
                            isAnswered
                            locked
                            repository { name owner { login avatarUrl } }
                        }
                    }
                    pageInfo { hasNextPage endCursor }
                }
            }
        """.trimIndent()
        val conn = fetch(query, buildUserDiscussionSearchQuery(filter), after)
        PagedWorkDiscussions(
            totalCount = conn.discussionCount,
            items = conn.nodes.map {
                WorkDiscussionItem(
                    id = it.id, number = it.number, title = it.title,
                    repoOwner = it.repository.owner.login, repoName = it.repository.name,
                    stateReason = it.stateReason.toDiscussionStateReason(),
                    isAnswered = it.isAnswered,
                    locked = it.locked,
                    updatedAt = it.updatedAt,
                )
            },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }

    private suspend fun fetch(query: String, searchQuery: String, after: String?) =
        api.graphQL<WorkSearchQueryData>(
            query,
            buildMap {
                put("searchQuery", JsonPrimitive(searchQuery))
                after?.let { put("after", JsonPrimitive(it)) }
            },
        ).search

    private fun WorkSearchNode.toIssueDomain() = WorkIssueItem(
        id = id, number = number, title = title,
        repoOwner = repository.owner.login, repoName = repository.name,
        isPullRequest = false,
        issueState = state.toIssueState(),
        issueStateReason = stateReason.toIssueStateReason(),
        locked = locked,
        updatedAt = updatedAt,
    )

    private fun WorkSearchNode.toPullRequestDomain() = WorkIssueItem(
        id = id, number = number, title = title,
        repoOwner = repository.owner.login, repoName = repository.name,
        isPullRequest = true,
        pullRequestState = state.toPullRequestState(),
        isDraft = isDraft,
        locked = locked,
        updatedAt = updatedAt,
    )

    private fun String?.toIssueState(): IssueState = when (this) {
        "OPEN" -> IssueState.OPEN
        "CLOSED" -> IssueState.CLOSED
        else -> error("Unsupported Issue state: $this")
    }

    private fun String?.toIssueStateReason(): IssueStateReason? = when (this) {
        null -> null
        "REOPENED" -> IssueStateReason.REOPENED
        "NOT_PLANNED" -> IssueStateReason.NOT_PLANNED
        "COMPLETED" -> IssueStateReason.COMPLETED
        "DUPLICATE" -> IssueStateReason.DUPLICATE
        else -> error("Unsupported Issue stateReason: $this")
    }

    private fun String?.toPullRequestState(): PullRequestState = when (this) {
        "OPEN" -> PullRequestState.OPEN
        "CLOSED" -> PullRequestState.CLOSED
        "MERGED" -> PullRequestState.MERGED
        else -> error("Unsupported PullRequest state: $this")
    }

    private fun String?.toDiscussionStateReason(): DiscussionStateReason? = when (this) {
        null -> null
        "RESOLVED" -> DiscussionStateReason.RESOLVED
        "OUTDATED" -> DiscussionStateReason.OUTDATED
        "DUPLICATE" -> DiscussionStateReason.DUPLICATE
        "REOPENED" -> DiscussionStateReason.REOPENED
        else -> error("Unsupported Discussion stateReason: $this")
    }
}

internal fun buildUserIssueSearchQuery(filter: UserIssueFilter): String = buildList {
    add(filter.relation.toSearchQualifier())
    add("is:issue")
    filter.state.toSearchQualifier()?.let(::add)
    filter.visibility.toSearchQualifier()?.let(::add)
    add("sort:${filter.sort.toSearchQualifier()}")
}.joinToString(" ")

internal fun buildUserPullRequestSearchQuery(filter: UserPullRequestFilter): String = buildList {
    add(filter.relation.toSearchQualifier())
    add("is:pr")
    filter.state.toSearchQualifiers().forEach(::add)
    filter.visibility.toSearchQualifier()?.let(::add)
    add("sort:${filter.sort.toSearchQualifier()}")
}.joinToString(" ")

internal fun buildUserDiscussionSearchQuery(filter: UserDiscussionFilter): String = buildList {
    add(filter.relation.toSearchQualifier())
    filter.state.toSearchQualifier()?.let(::add)
    filter.answer.toSearchQualifier()?.let(::add)
    filter.visibility.toSearchQualifier()?.let(::add)
    add("sort:${filter.sort.toSearchQualifier()}")
}.joinToString(" ")

private fun UserIssueRelationFilter.toSearchQualifier() = when (this) {
    UserIssueRelationFilter.INVOLVED -> "involves:@me"
    UserIssueRelationFilter.AUTHORED -> "author:@me"
    UserIssueRelationFilter.ASSIGNED -> "assignee:@me"
    UserIssueRelationFilter.MENTIONED -> "mentions:@me"
    UserIssueRelationFilter.COMMENTED -> "commenter:@me"
}

private fun UserIssueStateFilter.toSearchQualifier() = when (this) {
    UserIssueStateFilter.OPEN -> "is:open"
    UserIssueStateFilter.CLOSED -> "is:closed"
    UserIssueStateFilter.ALL -> null
}

private fun UserIssueVisibilityFilter.toSearchQualifier() = when (this) {
    UserIssueVisibilityFilter.ALL -> null
    UserIssueVisibilityFilter.PUBLIC -> "is:public"
    UserIssueVisibilityFilter.PRIVATE -> "is:private"
    UserIssueVisibilityFilter.INTERNAL -> "is:internal"
}

private fun UserIssueSortFilter.toSearchQualifier() = when (this) {
    UserIssueSortFilter.CREATED_DESC -> "created-desc"
    UserIssueSortFilter.CREATED_ASC -> "created-asc"
    UserIssueSortFilter.COMMENTS_DESC -> "comments-desc"
    UserIssueSortFilter.COMMENTS_ASC -> "comments-asc"
    UserIssueSortFilter.UPDATED_DESC -> "updated-desc"
    UserIssueSortFilter.UPDATED_ASC -> "updated-asc"
}

private fun UserPullRequestRelationFilter.toSearchQualifier() = when (this) {
    UserPullRequestRelationFilter.INVOLVED -> "involves:@me"
    UserPullRequestRelationFilter.AUTHORED -> "author:@me"
    UserPullRequestRelationFilter.ASSIGNED -> "assignee:@me"
    UserPullRequestRelationFilter.REVIEW_REQUESTED -> "review-requested:@me"
    UserPullRequestRelationFilter.COMMENTED -> "commenter:@me"
}

private fun UserPullRequestStateFilter.toSearchQualifiers() = when (this) {
    UserPullRequestStateFilter.OPEN -> listOf("is:open")
    UserPullRequestStateFilter.MERGED -> listOf("is:merged")
    UserPullRequestStateFilter.CLOSED_UNMERGED -> listOf("is:closed", "is:unmerged")
    UserPullRequestStateFilter.ALL -> emptyList()
}

private fun UserPullRequestVisibilityFilter.toSearchQualifier() = when (this) {
    UserPullRequestVisibilityFilter.ALL -> null
    UserPullRequestVisibilityFilter.PUBLIC -> "is:public"
    UserPullRequestVisibilityFilter.PRIVATE -> "is:private"
    UserPullRequestVisibilityFilter.INTERNAL -> "is:internal"
}

private fun UserPullRequestSortFilter.toSearchQualifier() = when (this) {
    UserPullRequestSortFilter.CREATED_DESC -> "created-desc"
    UserPullRequestSortFilter.CREATED_ASC -> "created-asc"
    UserPullRequestSortFilter.COMMENTS_DESC -> "comments-desc"
    UserPullRequestSortFilter.COMMENTS_ASC -> "comments-asc"
    UserPullRequestSortFilter.UPDATED_DESC -> "updated-desc"
    UserPullRequestSortFilter.UPDATED_ASC -> "updated-asc"
}

private fun UserDiscussionRelationFilter.toSearchQualifier() = when (this) {
    UserDiscussionRelationFilter.INVOLVED -> "involves:@me"
    UserDiscussionRelationFilter.AUTHORED -> "author:@me"
    UserDiscussionRelationFilter.COMMENTED -> "commenter:@me"
}

private fun UserDiscussionStateFilter.toSearchQualifier() = when (this) {
    UserDiscussionStateFilter.ALL -> null
    UserDiscussionStateFilter.OPEN -> "is:open"
    UserDiscussionStateFilter.CLOSED -> "is:closed"
}

private fun UserDiscussionAnswerFilter.toSearchQualifier() = when (this) {
    UserDiscussionAnswerFilter.ALL -> null
    UserDiscussionAnswerFilter.ANSWERED -> "is:answered"
    UserDiscussionAnswerFilter.UNANSWERED -> "is:unanswered"
}

private fun UserDiscussionVisibilityFilter.toSearchQualifier() = when (this) {
    UserDiscussionVisibilityFilter.ALL -> null
    UserDiscussionVisibilityFilter.PUBLIC -> "is:public"
    UserDiscussionVisibilityFilter.PRIVATE -> "is:private"
    UserDiscussionVisibilityFilter.INTERNAL -> "is:internal"
}

private fun UserDiscussionSortFilter.toSearchQualifier() = when (this) {
    UserDiscussionSortFilter.CREATED_DESC -> "created-desc"
    UserDiscussionSortFilter.CREATED_ASC -> "created-asc"
    UserDiscussionSortFilter.UPDATED_DESC -> "updated-desc"
    UserDiscussionSortFilter.UPDATED_ASC -> "updated-asc"
}
