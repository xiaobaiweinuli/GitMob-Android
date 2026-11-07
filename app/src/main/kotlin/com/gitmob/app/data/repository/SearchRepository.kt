package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.HttpStatusException
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.executeAsApiResult
import com.gitmob.app.data.model.*
import com.gitmob.app.graphql.SearchAccountsQuery
import com.gitmob.app.graphql.SearchRepositoriesQuery
import com.gitmob.app.graphql.WorkIssuesQuery
import com.gitmob.app.graphql.WorkPullRequestsQuery
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val apollo: ApolloClient,
    private val rest: GHApiClient,
) {
    suspend fun search(
        query: String, category: SearchCategory, preview: Boolean,
        continuation: SearchContinuation? = null,
    ): ApiResult<SearchPage> {
        val scoped = scopedSearchQuery(query, category)
            ?: return ApiResult.Failure(ApiError.UserVisible(R.string.search_scope_conflict))
        val position = continuation ?: SearchContinuation()
        val size = if (preview) PageSize.SEARCH_PREVIEW else PageSize.SEARCH_RESULTS
        return when (category) {
            SearchCategory.CODE -> code(scoped, size, position)
            SearchCategory.REPOSITORIES -> apollo.query(SearchRepositoriesQuery(
                query = scoped, first = size, after = Optional.presentIfNotNull(position.cursor),
                topicsFirst = PageSize.TOPICS_PER_REPO,
            )).executeAsApiResult().mapped { data ->
                val connection = data.search
                page(connection.nodes.orEmpty().mapNotNull { it?.onRepository?.repoListFields }
                    .map { SearchItem.Repository(it.toDomain()) }, connection.repositoryCount,
                    connection.pageInfo.hasNextPage, connection.pageInfo.endCursor, position)
            }
            SearchCategory.ISSUES -> apollo.query(WorkIssuesQuery(
                searchQuery = scoped, first = size, after = Optional.presentIfNotNull(position.cursor),
            )).executeAsApiResult().mapped { data ->
                val connection = data.search
                page(connection.nodes.orEmpty().mapNotNull { it?.onIssue }.map {
                    SearchItem.Work(WorkIssueItem(
                        id = it.id, number = it.number, title = it.title,
                        repoOwner = it.repository.owner.login, repoName = it.repository.name,
                        isPullRequest = false, issueState = IssueState.valueOf(it.state.rawValue),
                        issueStateReason = it.stateReason?.rawValue?.let(IssueStateReason::valueOf),
                        locked = it.locked, updatedAt = it.updatedAt,
                    ))
                }, connection.issueCount, connection.pageInfo.hasNextPage, connection.pageInfo.endCursor, position)
            }
            SearchCategory.PULL_REQUESTS -> apollo.query(WorkPullRequestsQuery(
                searchQuery = scoped, first = size, after = Optional.presentIfNotNull(position.cursor),
            )).executeAsApiResult().mapped { data ->
                val connection = data.search
                page(connection.nodes.orEmpty().mapNotNull { it?.onPullRequest }.map {
                    SearchItem.Work(WorkIssueItem(
                        id = it.id, number = it.number, title = it.title,
                        repoOwner = it.repository.owner.login, repoName = it.repository.name,
                        isPullRequest = true, pullRequestState = PullRequestState.valueOf(it.state.rawValue),
                        isDraft = it.isDraft, locked = it.locked, updatedAt = it.updatedAt,
                    ))
                }, connection.issueCount, connection.pageInfo.hasNextPage, connection.pageInfo.endCursor, position)
            }
            SearchCategory.USERS, SearchCategory.ORGANIZATIONS -> apollo.query(SearchAccountsQuery(
                query = scoped, first = size, after = Optional.presentIfNotNull(position.cursor),
            )).executeAsApiResult().mapped { data ->
                val connection = data.search
                val items = connection.nodes.orEmpty().mapNotNull { node ->
                    if (category == SearchCategory.USERS) node?.onUser?.let {
                        SearchItem.Account(it.id, it.login, it.name, it.avatarUrl, it.bio, it.location)
                    } else node?.onOrganization?.let {
                        SearchItem.Account(it.id, it.login, it.name, it.avatarUrl, it.description, it.location)
                    }
                }
                page(items, connection.userCount, connection.pageInfo.hasNextPage, connection.pageInfo.endCursor, position)
            }
        }
    }

    private fun page(
        items: List<SearchItem>, total: Int, hasNext: Boolean, cursor: String?, position: SearchContinuation,
    ): SearchPage {
        val received = position.received + items.size
        val limited = received >= PageSize.SEARCH_ACCESSIBLE_LIMIT && (hasNext || total > received)
        val next = if (hasNext && !cursor.isNullOrBlank() && cursor != position.cursor &&
            received < PageSize.SEARCH_ACCESSIBLE_LIMIT) SearchContinuation(cursor, received = received) else null
        return SearchPage(items, total, next, limitReached = limited)
    }

    private suspend fun code(query: String, size: Int, position: SearchContinuation): ApiResult<SearchPage> = safeCall {
        val response = try {
            rest.get<CodeSearchResponse>(
                "/search/code?q=" + URLEncoder.encode(query, "UTF-8") + "&per_page=$size&page=" + position.page,
                accept = "application/vnd.github.text-match+json",
            )
        } catch (error: HttpStatusException) {
            if (error.code == 422) throw UserVisibleException(R.string.search_invalid_query)
            throw error
        }
        val items = response.items.map { item ->
            SearchItem.Code(
                owner = item.repository.owner.login, repository = item.repository.name,
                name = item.name, path = item.path, htmlUrl = item.htmlUrl,
                target = searchFileTarget(item.htmlUrl, item.repository.owner.login, item.repository.name, item.path),
                snippets = item.toSnippets(),
            )
        }
        val received = position.received + items.size
        val more = items.size == size && (response.incomplete || received < response.totalCount)
        SearchPage(
            items = items, totalCount = response.totalCount, incomplete = response.incomplete,
            next = if (more && received < PageSize.SEARCH_ACCESSIBLE_LIMIT)
                SearchContinuation(page = position.page + 1, received = received) else null,
            limitReached = received >= PageSize.SEARCH_ACCESSIBLE_LIMIT && (more || response.totalCount > received),
        )
    }
}

private fun CodeSearchDto.toSnippets(): List<SearchSnippet> = textMatches.mapNotNull { match ->
    if (match.property != "content" || match.fragment.isBlank()) null else match.toSnippet()
}

private fun CodeSearchTextMatch.toSnippet(): SearchSnippet {
    // Code search offsets are UTF-8 bytes; Compose spans use UTF-16 positions.
    val byteToUtf16 = mutableMapOf(0 to 0)
    var byteOffset = 0
    var charOffset = 0
    while (charOffset < fragment.length) {
        val codePoint = fragment.codePointAt(charOffset)
        byteOffset += when {
            codePoint <= 0x7F -> 1
            codePoint <= 0x7FF -> 2
            codePoint <= 0xFFFF -> 3
            else -> 4
        }
        charOffset += Character.charCount(codePoint)
        byteToUtf16[byteOffset] = charOffset
    }
    val ranges = matches.mapNotNull { match ->
        val start = byteToUtf16[match.indices.getOrNull(0)] ?: return@mapNotNull null
        val end = byteToUtf16[match.indices.getOrNull(1)] ?: return@mapNotNull null
        if (end <= start || (match.text != null && fragment.substring(start, end) != match.text)) {
            return@mapNotNull null
        }
        SearchMatch(start, end)
    }
    return SearchSnippet(fragment, ranges)
}

private suspend fun <T, R> ApiResult<T>.mapped(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Failure -> this
    is ApiResult.Success -> safeCall { transform(data) }
}
