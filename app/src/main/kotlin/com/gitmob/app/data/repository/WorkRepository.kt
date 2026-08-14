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
    suspend fun getInvolvedIssues(after: String? = null): ApiResult<PagedWorkIssues> = safeCall {
        val query = """
            query WorkIssues(${'$'}after: String) {
                search(query: "involves:@me is:issue is:open", type: ISSUE, first: ${PageSize.WORK_ITEMS}, after: ${'$'}after) {
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
        val conn = fetch(query, after)
        PagedWorkIssues(
            totalCount = conn.issueCount,
            items = conn.nodes.map { it.toIssueDomain() },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }

    suspend fun getInvolvedPullRequests(after: String? = null): ApiResult<PagedWorkIssues> = safeCall {
        val query = """
            query WorkPullRequests(${'$'}after: String) {
                search(query: "involves:@me is:pr is:open", type: ISSUE, first: ${PageSize.WORK_ITEMS}, after: ${'$'}after) {
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
        val conn = fetch(query, after)
        PagedWorkIssues(
            totalCount = conn.issueCount,
            items = conn.nodes.map { it.toPullRequestDomain() },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }

    suspend fun getInvolvedDiscussions(after: String? = null): ApiResult<PagedWorkDiscussions> = safeCall {
        val query = """
            query WorkDiscussions(${'$'}after: String) {
                search(query: "involves:@me", type: DISCUSSION, first: ${PageSize.WORK_ITEMS}, after: ${'$'}after) {
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
        val conn = fetch(query, after)
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

    private suspend fun fetch(query: String, after: String?) =
        api.graphQL<WorkSearchQueryData>(
            query,
            after?.let { mapOf("after" to JsonPrimitive(it)) } ?: emptyMap(),
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
